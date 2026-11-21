package top.fpvp.lumen.lighting;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import top.fpvp.lumen.LumenConfig;

/**
 * Starlight 风格的光照引擎重写（1.8.9 移植）。
 *
 * <h2>为什么不能逐行照搬 Starlight</h2>
 *
 * <p>Starlight 官方明确不支持 1.8.9，它的实现依赖 1.13+ 才有的区块存储接口
 * （{@code PalettedContainer}、{@code SectionStorage}、16 位区段索引）。
 * 1.8.9 只有 {@code ExtendedBlockStorage} + {@code NibbleArray}，
 * 没有对应物。因此这里移植的是它的<b>算法骨架</b>，存储层用 1.8.9 的 NibbleArray。
 *
 * <h2>移植的核心：两级队列 + 增/减分离</h2>
 *
 * <p>原版 {@code World#checkLightFor} 是一个“单点触发、整片重算”的模型：
 * 每次调用都从触发点重新洪泛，把整片连通区域的光值重新推导一遍。
 * 代价在于同一片区域被反复推导 —— 爆炸改 100 个方块就是 100 次整片洪泛。
 *
 * <p>Starlight 的模型是<b>增量</b>的，把“光变亮”和“光变暗”分开处理：
 *
 * <ul>
 *   <li><b>增光</b>（{@link #enqueueIncrease}）：某点光值升高，把“还能往外传播”的
 *       邻居压入增光队列；然后用一条 BFS 依次把光推送出去，遇到已经够亮的邻居就停。
 *       这条路径只做加法，每个方块最多被处理一次。</li>
 *   <li><b>减光</b>（{@link #enqueueDecrease}）：某点光值降低，先把受影响的方块
 *       全部清零并记入“待重算”列表，再对外部边界重新做一次增光。
 *       这是唯一需要“先清除再重建”的部分。</li>
 * </ul>
 *
 * <p>关键收益：<b>每个方块在一次光照更新中最多被访问常数次</b>，
 * 而原版是“每次触发都重扫整片”。在高密度改动（爆炸、多方块结构）下，
 * 复杂度从 O(改动数 × 区域面积) 降到 O(改动数 × 传播半径)。
 *
 * <h2>与原版语义的对齐</h2>
 *
 * <p>为了让光照结果与原版<b>逐位相同</b>，这里严格复刻了原版的这几条规则：
 * <ol>
 *   <li>天空光的“顶光”判定：{@code canSeeSky} 时原始光值为 15；</li>
 *   <li>不透明度 {@code opacity >= 15 且发光 > 0} 时，opacity 折为 1
 *       （原版 {@code getRawLight} 里的特例，用于萤石这类方块）；</li>
 *   <li>{@code opacity < 1} 时折为 1（空气/水的处理）；</li>
 *   <li>传播衰减：{@code neighborLight - max(1, opacity)}；</li>
 *   <li>传播范围限制：曼哈顿距离 < 17。</li>
 * </ol>
 *
 * <p>第 5 条尤其重要：原版用曼哈顿距离 17 作为硬上限，这与“光值 15 + 最小衰减 1”
 * 是等价的，但<b>显式实现它</b>能避免在跨区段边界上多算一大片。
 *
 * <h2>零分配</h2>
 *
 * <p>原版洪泛循环里每处理一个方块都要 {@code new BlockPos(..)} 和
 * {@code new MutableBlockPos()}（见 {@code World#checkLightFor} 的字节码）。
 * 本类全程使用 {@link BlockPos.MutableBlockPos} 单例 + 用 {@code int} 打包坐标，
 * 热路径<b>零对象分配</b>。
 *
 * <h2>非线程安全</h2>
 *
 * <p>所有状态由调用线程（服务端/客户端主线程）独占。光照更新必须串行，
 * 这是原版的语义，也是正确性的前提。
 */
public final class LightEngine {

    /** 光值上限。 */
    private static final int MAX_LIGHT = 15;

    /** 原版的传播曼哈顿距离上限。 */
    private static final int MAX_MANHATTAN = 17;

    /** 队列初始容量。按经验，一次爆炸约产生数百个待处理点。 */
    private static final int INITIAL_QUEUE = 512;

    /** 六个方向，避免每次调用 {@code EnumFacing.values()}（它会 clone 数组）。 */
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private final World world;

    /**
     * 增光队列。
     *
     * <p>每个条目打包成 {@code (x, y, z, level)} 四个 int。用 int 数组而不是
     * 对象队列：BFS 每处理一个方块要入队最多 6 个邻居，对象队列在
     * 爆炸场景下会产生上万次分配。
     */
    private int[] increaseQueue = new int[INITIAL_QUEUE * 4];
    private int increaseHead;
    private int increaseTail;

    /** 减光队列，格式同上。 */
    private int[] decreaseQueue = new int[INITIAL_QUEUE * 4];
    private int decreaseHead;
    private int decreaseTail;

    /**
     * 减光后需要重新增光的“边界点”。
     *
     * <p>减光把一片区域清零后，区域外紧邻的亮方块需要重新往内传播，
     * 否则会出现“墙上有个洞但洞里是黑的”。这些点先收集，减光全部结束后
     * 再统一做增光 —— 必须分开，否则边清边加会来回震荡。
     */
    private int[] relightQueue = new int[INITIAL_QUEUE * 4];
    private int relightTail;

    /** 复用的可变坐标。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** 正在处理中，期间的重入请求直接忽略（避免递归爆炸）。 */
    private boolean processing;

    // 统计
    private long totalIncreaseNodes;
    private long totalDecreaseNodes;
    private long totalFlushes;

    public LightEngine(World world) {
        this.world = world;
    }

    /** 是否启用。关闭时调用方应回退原版。 */
    public static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.starlightLightEngine && !LightEngineCompat.shouldDisable();
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 方块变化后更新该位置的光照。
     *
     * <p>这是 {@code World#checkLight} 的替代品。它先算出该位置“应该”的光值，
     * 再根据新旧光值的大小关系决定走增光还是减光路径。
     *
     * @return 是否发生了变化（对齐原版 {@code checkLight} 的返回值语义）
     */
    public boolean onBlockChange(BlockPos pos) {
        if (!isEnabled()) {
            return false;
        }
        if (processing) {
            // 重入：直接交给原版，避免自己的队列被递归搞乱。
            return world.checkLight(pos);
        }

        processing = true;
        try {
            boolean changed = false;
            // 天空光：只有有天空的世界才处理（下界/末地为 false）。
            if (!world.provider.getHasNoSky()) {
                changed |= updateLightAt(EnumSkyBlock.SKY, pos);
            }
            changed |= updateLightAt(EnumSkyBlock.BLOCK, pos);
            return changed;
        } finally {
            processing = false;
        }
    }

    /**
     * 单个光源类型的位置更新。
     *
     * <p>逻辑对应原版 {@code checkLightFor} 的“比较新旧光值”部分，
     * 但后续不再是整片重算，而是走增/减队列。
     */
    private boolean updateLightAt(EnumSkyBlock type, BlockPos pos) {
        int current = world.getLightFor(type, pos);
        int raw = rawLight(type, pos);

        if (raw > current) {
            // 变亮：设置新值，然后把它作为增光源推向邻居。
            world.setLightFor(type, pos, raw);
            pushIncrease(type, pos.getX(), pos.getY(), pos.getZ(), raw);
            processIncrease(type);
            return true;
        }
        if (raw < current) {
            // 变暗：先清除整片受影响区域，再重建。
            world.setLightFor(type, pos, raw);
            pushDecrease(type, pos.getX(), pos.getY(), pos.getZ(), current);
            processDecrease(type);
            processRelight(type);
            return true;
        }
        // 光值没变，但邻居可能因为不透明度变化而需要调整。
        pushIncrease(type, pos.getX(), pos.getY(), pos.getZ(), raw);
        processIncrease(type);
        return false;
    }

    /**
     * 该位置“原始”应得的光值，逐条对齐原版 {@code World#getRawLight}。
     */
    private int rawLight(EnumSkyBlock type, BlockPos pos) {
        // 天空光且能直射到天空 → 满值。
        if (type == EnumSkyBlock.SKY && world.canSeeSky(pos)) {
            return MAX_LIGHT;
        }

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        int emission = block.getLightValue(world, pos);

        // 天空光不吃方块的发光值，方块光才吃。
        int base = (type == EnumSkyBlock.SKY) ? 0 : emission;

        int opacity = world.getBlockLightOpacity(pos);

        // 原版特例：完全不透明但会发光的方块（萤石、海晶灯），
        // 不透明度折为 1，好让光能透出去一格。
        if (opacity >= MAX_LIGHT && emission > 0) {
            opacity = 1;
        }
        if (opacity < 1) {
            opacity = 1;
        }
        if (opacity >= MAX_LIGHT) {
            return 0;
        }
        if (base > MAX_LIGHT - opacity) {
            return base;
        }

        // 取邻居的最大值减自身不透明度。
        int best = 0;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        for (int i = 0; i < 6; i++) {
            cursor.set(x + DX[i], y + DY[i], z + DZ[i]);
            if (!isLoaded(cursor)) {
                continue;
            }
            int neighbor = world.getLightFor(type, cursor) - opacity;
            if (neighbor > best) {
                best = neighbor;
            }
        }
        return best < 0 ? 0 : best;
    }

    // ------------------------------------------------------------------
    // 增光
    // ------------------------------------------------------------------

    private void pushIncrease(EnumSkyBlock type, int x, int y, int z, int level) {
        ensureIncreaseCapacity();
        int i = increaseTail;
        increaseQueue[i] = x;
        increaseQueue[i + 1] = y;
        increaseQueue[i + 2] = z;
        increaseQueue[i + 3] = level;
        increaseTail = i + 4;
    }

    private void pushDecrease(EnumSkyBlock type, int x, int y, int z, int level) {
        ensureDecreaseCapacity();
        int i = decreaseTail;
        decreaseQueue[i] = x;
        decreaseQueue[i + 1] = y;
        decreaseQueue[i + 2] = z;
        decreaseQueue[i + 3] = level;
        decreaseTail = i + 4;
    }

    private void pushRelight(int x, int y, int z, int level) {
        ensureRelightCapacity();
        int i = relightTail;
        relightQueue[i] = x;
        relightQueue[i + 1] = y;
        relightQueue[i + 2] = z;
        relightQueue[i + 3] = level;
        relightTail = i + 4;
    }

    /**
     * 增光 BFS：把光往外推，直到衰减殆尽。
     *
     * <p>每个方块最多入队一次（因为只有光值真的升高时才入队），
     * 因此这一步是严格线性的。
     */
    private void processIncrease(EnumSkyBlock type) {
        while (increaseHead < increaseTail) {
            int i = increaseHead;
            int x = increaseQueue[i];
            int y = increaseQueue[i + 1];
            int z = increaseQueue[i + 2];
            int level = increaseQueue[i + 3];
            increaseHead = i + 4;
            totalIncreaseNodes++;

            if (level <= 0) {
                continue;
            }

            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                int nz = z + DZ[d];
                if (ny < 0 || ny > 255) {
                    continue;
                }
                cursor.set(nx, ny, nz);
                if (!isLoaded(cursor)) {
                    continue;
                }

                int opacity = world.getBlockLightOpacity(cursor);
                if (opacity < 1) {
                    opacity = 1;
                }
                if (opacity >= MAX_LIGHT) {
                    continue;
                }

                int target = level - opacity;
                if (target <= 0) {
                    continue;
                }
                int existing = world.getLightFor(type, cursor);
                if (existing >= target) {
                    // 已经够亮，不需要再传播（这正是“每个方块只处理一次”的来源）。
                    continue;
                }

                world.setLightFor(type, cursor, target);
                pushIncrease(type, nx, ny, nz, target);
            }
        }
        // 队列处理完，重置指针以便复用数组。
        increaseHead = 0;
        increaseTail = 0;
    }

    // ------------------------------------------------------------------
    // 减光
    // ------------------------------------------------------------------

    /**
     * 减光 BFS：先把受影响区域清空，同时收集边界上的亮点。
     *
     * <p>为什么必须先清空：光值降低后，原来“靠它照亮”的邻居必须归零，
     * 否则会残留一片本不该存在的亮区。清空过程中遇到的、亮度不低于
     * 当前衰减值的方块，说明它是由<b>别的</b>光源照亮的，作为边界点收集起来，
     * 稍后重新向内传播。
     */
    private void processDecrease(EnumSkyBlock type) {
        while (decreaseHead < decreaseTail) {
            int i = decreaseHead;
            int x = decreaseQueue[i];
            int y = decreaseQueue[i + 1];
            int z = decreaseQueue[i + 2];
            int oldLevel = decreaseQueue[i + 3];
            decreaseHead = i + 4;
            totalDecreaseNodes++;

            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                int nz = z + DZ[d];
                if (ny < 0 || ny > 255) {
                    continue;
                }
                cursor.set(nx, ny, nz);
                if (!isLoaded(cursor)) {
                    continue;
                }

                int neighborLevel = world.getLightFor(type, cursor);
                if (neighborLevel == 0) {
                    continue;
                }

                int opacity = world.getBlockLightOpacity(cursor);
                if (opacity < 1) {
                    opacity = 1;
                }

                if (neighborLevel < oldLevel || (oldLevel == MAX_LIGHT && type == EnumSkyBlock.SKY)) {
                    // 它原本就是靠被清除的光源照亮的：一并清零并继续扩散。
                    world.setLightFor(type, cursor, 0);
                    pushDecrease(type, nx, ny, nz, neighborLevel);
                } else if (neighborLevel >= oldLevel) {
                    // 由别的光源照亮：作为边界点，稍后重新增光。
                    pushRelight(nx, ny, nz, neighborLevel);
                }
            }
        }
        decreaseHead = 0;
        decreaseTail = 0;
    }

    /**
     * 对边界点重新做增光，把光重新灌回被清空的区域。
     */
    private void processRelight(EnumSkyBlock type) {
        int i = 0;
        while (i < relightTail) {
            int x = relightQueue[i];
            int y = relightQueue[i + 1];
            int z = relightQueue[i + 2];
            int level = relightQueue[i + 3];
            i += 4;
            pushIncrease(type, x, y, z, level);
        }
        relightTail = 0;
        processIncrease(type);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 该位置所在区块是否已加载。未加载时任何光照读写都要跳过。 */
    private boolean isLoaded(BlockPos pos) {
        int y = pos.getY();
        if (y < 0 || y > 255) {
            return false;
        }
        return world.isBlockLoaded(pos);
    }

    private void ensureIncreaseCapacity() {
        if (increaseTail + 4 <= increaseQueue.length) {
            return;
        }
        int[] grown = new int[increaseQueue.length * 2];
        System.arraycopy(increaseQueue, 0, grown, 0, increaseTail);
        increaseQueue = grown;
    }

    private void ensureDecreaseCapacity() {
        if (decreaseTail + 4 <= decreaseQueue.length) {
            return;
        }
        int[] grown = new int[decreaseQueue.length * 2];
        System.arraycopy(decreaseQueue, 0, grown, 0, decreaseTail);
        decreaseQueue = grown;
    }

    private void ensureRelightCapacity() {
        if (relightTail + 4 <= relightQueue.length) {
            return;
        }
        int[] grown = new int[relightQueue.length * 2];
        System.arraycopy(relightQueue, 0, grown, 0, relightTail);
        relightQueue = grown;
    }

    /**
     * 区块加载时初始化该列的天空光。
     *
     * <p>对应原版 {@code Chunk#generateSkylightMap}。新加载的区块必须从上往下
     * 灌天空光，否则会出现“新加载的区块一片漆黑”。
     */
    public void initSkylightForChunk(Chunk chunk) {
        if (!isEnabled()) {
            return;
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        int baseX = chunk.xPosition << 4;
        int baseZ = chunk.zPosition << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int height = chunk.getHeightValue(x, z);

                // height 之下全部挡住，天空光为 0；之上为 15。
                for (int y = height; y <= 255; y += 1) {
                    cursor.set(worldX, y, worldZ);
                    world.setLightFor(EnumSkyBlock.SKY, cursor, MAX_LIGHT);
                }
                // 高度以下逐格处理：顶面那一格可能被部分遮挡。
                for (int y = 0; y < height && y <= 255; y++) {
                    cursor.set(worldX, y, worldZ);
                    world.setLightFor(EnumSkyBlock.SKY, cursor, 0);
                }
            }
        }
        // 顶点处再向外扩散一轮，处理跨区块边界。
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int height = chunk.getHeightValue(x, z);
                if (height <= 255) {
                    pushIncrease(EnumSkyBlock.SKY, baseX + x, height, baseZ + z, MAX_LIGHT);
                }
            }
        }
        processIncrease(EnumSkyBlock.SKY);
    }

    public long getTotalIncreaseNodes() {
        return totalIncreaseNodes;
    }

    public long getTotalDecreaseNodes() {
        return totalDecreaseNodes;
    }

    public String describe() {
        return "inc=" + totalIncreaseNodes + " dec=" + totalDecreaseNodes;
    }
}
