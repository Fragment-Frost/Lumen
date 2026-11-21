package top.fpvp.lumen.lighting;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.util.LongHashSet;

import java.util.Arrays;

/**
 * Starlight 风格的光照引擎（1.8.9 移植）。
 *
 * <h2>为什么不能逐行照搬 Starlight</h2>
 *
 * <p>Starlight 官方明确不支持 1.8.9，它的实现依赖 1.13+ 才有的区块存储接口
 * （{@code PalettedContainer}、{@code SectionStorage}、16 位区段索引）。
 * 1.8.9 只有 {@code ExtendedBlockStorage} + {@code NibbleArray}，
 * 没有对应物。因此这里移植的是它的<b>算法骨架</b>，存储层用 1.8.9 的 NibbleArray。
 *
 * <h2>核心：延迟批量 + 增/减分离</h2>
 *
 * <p>原版 {@code World#checkLightFor} 是"单点触发、整片重算"：
 * 每次调用都从触发点重新洪泛。爆炸改 1000 个方块就是 1000 次整片洪泛。
 *
 * <p>本类的做法分两层：
 *
 * <ol>
 *   <li><b>延迟合并</b>（{@link #onBlockChange}）：方块变化时<b>只登记坐标</b>，
 *       不立即算光。同一 tick 内同一位置登记多次只算一次。
 *       这一层是<b>正确性的前提</b>，不只是性能优化 —— 见下面「为什么必须延迟」。</li>
 *   <li><b>tick 边界批量处理</b>（{@link #flush}）：把所有登记的位置按坐标排序后
 *       依次做增量更新，让相邻坐标连续处理以命中缓存。单次 flush 有工作量上限，
 *       超出就交还原版 —— 宁可慢，不能卡住一帧。</li>
 * </ol>
 *
 * <h2>为什么必须延迟（这是上一版"阴影渲染错误"的根因）</h2>
 *
 * <p>上一版在 {@code setBlockState} 的当下就对该位置跑完整 BFS。
 * 大批量方块变更（爆炸、结构生成、大面积放置）时，每次变更都会读到
 * <b>尚未完成更新的邻居光值</b>，于是：
 * <ul>
 *   <li>第一次变更把光灌进一片区域；</li>
 *   <li>第二次变更基于那片"刚被灌进去、但即将失效"的光再算一遍；</li>
 *   <li>一千次变更后，结果与"等所有方块稳定后再算一次"完全不同，
 *       表现为残留的亮斑或暗洞 —— 也就是玩家看到的阴影错误。</li>
 * </ul>
 *
 * <p>延迟到 tick 末尾，所有方块状态都已稳定，增量算法拿到的输入才是自洽的。
 * 这正是 Starlight 在真实实现里做的事（它也是攒到 tick 末再 propagate）。
 *
 * <h2>减光语义（上一版的第二个 bug）</h2>
 *
 * <p>上一版的判断条件里有一句：
 * <pre>
 *   if (neighborLevel &lt; oldLevel || (oldLevel == MAX_LIGHT &amp;&amp; type == SKY))
 * </pre>
 * 那个 {@code ||} 后半段是错的：天空光为 15 时它会<b>无条件清零所有邻居</b>，
 * 包括那些自己就能看到天空、本该保持 15 的方块。清零后光值 15 的方块
 * 又被压进减光队列，于是"清零"沿着整个天空照亮区级联扩散，
 * 把整片室外区域清成 0。这是"大批量方块出现后阴影错误"的直接原因。
 *
 * <p>正确语义只有一条：<b>邻居光值严格小于被移除的光值，才说明它原本靠这个光源照亮</b>，
 * 才清零并继续扩散；否则（{@code >=}）它是被别的光源照亮的，作为边界点稍后重灌。
 *
 * <h2>零分配</h2>
 *
 * <p>原版洪泛循环里每处理一个方块都要 {@code new BlockPos(..)} 和
 * {@code new MutableBlockPos()}（见 {@code World#checkLightFor} 的字节码）。
 * 本类全程使用 {@link BlockPos.MutableBlockPos} 单例 + {@code int} 数组队列，
 * 热路径零对象分配。
 *
 * <h2>非线程安全</h2>
 *
 * <p>所有状态由调用线程（客户端/服务端主线程）独占。光照更新必须串行，
 * 这是原版的语义，也是正确性的前提。
 */
public final class LightEngine {

    /** 光值上限。 */
    private static final int MAX_LIGHT = 15;

    /** BFS 队列初始容量。 */
    private static final int INITIAL_QUEUE = 512;

    /**
     * 单次 flush 最多处理的登记位置数。
     *
     * <p>超过这个数量就<b>停止增量处理、把剩余位置交还原版</b>
     * （{@code world.checkLight}）。原版单点洪泛虽然重复做功，但它是
     * 有界的、经过验证的；而让我的 BFS 在一个 tick 里处理十万个位置，
     * 会直接把这一帧卡死 —— 这正是"时不时卡一下"的来源。
     */
    private static final int MAX_FLUSH_POSITIONS = 4096;

    /**
     * 区段内变更数超过此值时，放弃逐位置增量、改用原版逐位置校验。
     *
     * <p>理由：同一区段内改动极密时，逐位置增量 BFS 的传播范围高度重叠，
     * 总工作量反而超过"按顺序各查一遍"。而原版的单点校验在高密度下
     * 因为有 {@code lightUpdateBlockList} 的边界预筛，表现依然稳定。
     */
    private static final int DENSE_SECTION_THRESHOLD = 64;

    /** 六个方向。避免 {@code EnumFacing.values()}（它会 clone 数组）。 */
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private final World world;

    /**
     * 待处理位置（{@link BlockPos#toLong()} 编码），天然去重。
     *
     * <p>用 {@link LongHashSet} 而不是 {@code HashSet<Long>}：后者每次 add
     * 都要装箱，而方块坐标几乎全部落在 {@code LongCache}（-128..127）之外，
     * 于是每个位置都是一个新对象。一次爆炸上千个位置，每次 flush 就是上千个
     * 短命对象 —— 这正是"优化本身制造 GC 压力"的典型。
     */
    private final LongHashSet pending = new LongHashSet(1024);

    /** 每个区段的变更计数，用于判定高密度区段。 */
    private final SectionCounter sectionHits = new SectionCounter(256);

    /** 复用的排序缓冲。 */
    private long[] flushBuffer = new long[1024];

    /** 增光队列，每项四个 int（x, y, z, level）。 */
    private int[] increaseQueue = new int[INITIAL_QUEUE * 4];
    private int increaseHead;
    private int increaseTail;

    /** 减光队列，格式同上。 */
    private int[] decreaseQueue = new int[INITIAL_QUEUE * 4];
    private int decreaseHead;
    private int decreaseTail;

    /** 减光后需要重新增光的"边界点"，格式同上（不带类型，由调用方保证一致）。 */
    private int[] relightQueue = new int[INITIAL_QUEUE * 4];
    private int relightTail;

    /** 复用的可变坐标。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** 正在处理中，期间的重入请求直接交还原版（避免队列被递归搞乱）。 */
    private boolean processing;

    /** 本 tick 是否已发生溢出（溢出后本 tick 剩余请求全部交还原版）。 */
    private boolean overflowed;

    // 统计
    private long totalIncreaseNodes;
    private long totalDecreaseNodes;
    private long totalFlushes;
    private long totalOverflowFallbacks;

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
     * 方块变化后登记该位置，<b>不立即计算光照</b>。
     *
     * <p>真正的工作发生在 {@link #flush()}（tick 边界）。这样做的原因见类注释：
     * 立即计算会在批量变更时读到尚未稳定的邻居光值，产生错误的传播结果。
     *
     * @return 恒为 {@code false}。调用点是 {@code setBlockState} 内部的
     *         {@code checkLight}，其返回值在字节码里被 {@code pop} 掉，
     *         所以返回什么不影响语义。
     */
    public boolean onBlockChange(BlockPos pos) {
        if (!isEnabled()) {
            return false;
        }
        if (processing) {
            // 重入：正在批量处理中又触发方块变化（例如光照处理本身引发方块更新）。
            // 交还原版立即算，避免把自己的队列搞乱。
            return world.checkLight(pos);
        }
        if (overflowed) {
            return world.checkLight(pos);
        }

        long key = pos.toLong();
        if (!pending.add(key)) {
            return false;
        }
        if (pending.size() > MAX_FLUSH_POSITIONS) {
            // 登记量已超单次上限：本 tick 后续请求全部走原版，
            // 并把已登记的也尽快清掉，避免队列无限膨胀。
            overflowed = true;
            totalOverflowFallbacks++;
            return false;
        }
        sectionHits.increment(sectionKeyOf(pos));
        return false;
    }

    /**
     * 批量处理所有登记的位置。在 tick 边界调用。
     *
     * <p>处理顺序刻意按坐标<b>排序</b>：相邻坐标连续处理能极大提高
     * {@code ExtendedBlockStorage} 访问的缓存命中率（Starlight 同样做法）。
     */
    public void flush() {
        if (processing || pending.isEmpty()) {
            overflowed = false;
            return;
        }

        processing = true;
        try {
            int count = pending.size();
            if (flushBuffer.length < count) {
                flushBuffer = new long[Math.max(count, flushBuffer.length * 2)];
            }
            int drained = pending.drainTo(flushBuffer);
            Arrays.sort(flushBuffer, 0, drained);

            for (int i = 0; i < drained; i++) {
                BlockPos pos = BlockPos.fromLong(flushBuffer[i]);

                // 高密度区段：逐位置增量 BFS 的传播范围高度重叠，
                // 总成本会超过"按顺序各查一遍"。
                //
                // 注意这里<b>不是</b>跳过 —— 而是改用原版的单点校验，
                // 它内部有边界预筛，行为与关闭本优化时完全一致。
                int hits = sectionHits.countOf(sectionKeyOf(pos));
                if (hits > DENSE_SECTION_THRESHOLD) {
                    world.checkLight(pos);
                    continue;
                }

                updateLightAt(EnumSkyBlock.SKY, pos);
                updateLightAt(EnumSkyBlock.BLOCK, pos);
            }

            sectionHits.clear();
            totalFlushes++;
        } finally {
            processing = false;
            overflowed = false;
            // 队列里可能还有残留（处理中新增的），清掉指针复用数组。
            increaseHead = 0;
            increaseTail = 0;
            decreaseHead = 0;
            decreaseTail = 0;
            relightTail = 0;
        }
    }

    /**
     * 单个光源类型的位置更新。
     *
     * <p>对应原版 {@code checkLightFor} 的"比较新旧光值"部分，
     * 但后续走增/减队列而不是整片重扫。
     */
    private void updateLightAt(EnumSkyBlock type, BlockPos pos) {
        if (type == EnumSkyBlock.SKY && world.provider.getHasNoSky()) {
            return;
        }

        int current = world.getLightFor(type, pos);
        int raw = rawLight(type, pos);

        if (raw > current) {
            world.setLightFor(type, pos, raw);
            pushIncrease(type, pos.getX(), pos.getY(), pos.getZ(), raw);
            processIncrease(type);
        } else if (raw < current) {
            world.setLightFor(type, pos, raw);
            pushDecrease(type, pos.getX(), pos.getY(), pos.getZ(), current);
            processDecrease(type);
            processRelight(type);
        } else {
            // 光值没变，但该位置的不透明度可能变了，
            // 需要让邻居重新评估是否应该被这里照亮。
            pushIncrease(type, pos.getX(), pos.getY(), pos.getZ(), raw);
            processIncrease(type);
        }
    }

    /**
     * 该位置"原始"应得的光值，逐条对齐原版 {@code World#getRawLight}。
     */
    private int rawLight(EnumSkyBlock type, BlockPos pos) {
        if (type == EnumSkyBlock.SKY && world.canSeeSky(pos)) {
            return MAX_LIGHT;
        }

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        int emission = block.getLightValue(world, pos);

        // 天空光不吃方块的发光值，方块光才吃。
        int base = (type == EnumSkyBlock.SKY) ? 0 : emission;

        int opacity = LightEngineQueueLogic.effectiveOpacity(
                world.getBlockLightOpacity(pos), emission);
        if (opacity >= MAX_LIGHT) {
            return 0;
        }
        if (base > MAX_LIGHT - opacity) {
            return base;
        }

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
        if (increaseTail + 4 > increaseQueue.length) {
            int[] grown = new int[increaseQueue.length * 2];
            System.arraycopy(increaseQueue, 0, grown, 0, increaseTail);
            increaseQueue = grown;
        }
        int i = increaseTail;
        increaseQueue[i] = x;
        increaseQueue[i + 1] = y;
        increaseQueue[i + 2] = z;
        increaseQueue[i + 3] = level;
        increaseTail = i + 4;
    }

    private void pushDecrease(EnumSkyBlock type, int x, int y, int z, int level) {
        if (decreaseTail + 4 > decreaseQueue.length) {
            int[] grown = new int[decreaseQueue.length * 2];
            System.arraycopy(decreaseQueue, 0, grown, 0, decreaseTail);
            decreaseQueue = grown;
        }
        int i = decreaseTail;
        decreaseQueue[i] = x;
        decreaseQueue[i + 1] = y;
        decreaseQueue[i + 2] = z;
        decreaseQueue[i + 3] = level;
        decreaseTail = i + 4;
    }

    private void pushRelight(int x, int y, int z, int level) {
        if (relightTail + 4 > relightQueue.length) {
            int[] grown = new int[relightQueue.length * 2];
            System.arraycopy(relightQueue, 0, grown, 0, relightTail);
            relightQueue = grown;
        }
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
     * <p>每个方块最多入队一次（因为只有光值真的升高时才入队），因此严格线性。
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

                int target = LightEngineQueueLogic.propagatedLevel(level, opacity);
                if (target <= 0) {
                    continue;
                }
                if (world.getLightFor(type, cursor) >= target) {
                    // 已经够亮，不必再传播（这正是"每个方块只处理一次"的来源）。
                    continue;
                }

                world.setLightFor(type, cursor, target);
                pushIncrease(type, nx, ny, nz, target);
            }
        }
        increaseHead = 0;
        increaseTail = 0;
    }

    // ------------------------------------------------------------------
    // 减光
    // ------------------------------------------------------------------

    /**
     * 减光 BFS：清理"原本靠被移除光源照亮"的区域，并收集边界亮点。
     *
     * <p>判定只有一条：<b>邻居光值严格小于被移除的光值</b>才清零。
     * 相等或更大的邻居说明它有自己的（或另一路的）光源，必须保留并
     * 作为边界点稍后重新向内灌光。
     *
     * <p><b>上一版在这里多加了一个条件</b>
     * （{@code oldLevel == MAX_LIGHT && type == SKY} 就无条件清零），
     * 那是错的：它会把整片天空照亮区级联清成 0，表现为大片阴影异常。
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

                if (LightEngineQueueLogic.shouldClearOnDecrease(neighborLevel, oldLevel)) {
                    // 它原本就是靠被清除的光源照亮的：一并清零并继续扩散。
                    world.setLightFor(type, cursor, 0);
                    pushDecrease(type, nx, ny, nz, neighborLevel);
                } else {
                    // 由别的光源（或自身天空可见性）照亮：保留，并作为边界点。
                    pushRelight(nx, ny, nz, neighborLevel);
                }
            }
        }
        decreaseHead = 0;
        decreaseTail = 0;
    }

    /**
     * 对边界点重新做增光，把光灌回被清空的区域。
     */
    private void processRelight(EnumSkyBlock type) {
        int i = 0;
        while (i < relightTail) {
            pushIncrease(type, relightQueue[i], relightQueue[i + 1],
                    relightQueue[i + 2], relightQueue[i + 3]);
            i += 4;
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

    /**
     * 把方块坐标折叠成区段坐标。
     *
     * <p>区段是 16³，x/z 各取 4 位、y 取 8 位。用位运算而不是除法。
     */
    private static long sectionKeyOf(BlockPos pos) {
        int sx = pos.getX() >> 4;
        int sy = pos.getY() >> 4;
        int sz = pos.getZ() >> 4;
        return ((long) (sy & 0xFFFF) << 42)
                | ((long) (sz & 0x1FFFFF) << 21)
                | (sx & 0x1FFFFF);
    }

    /** 世界卸载时清理。 */
    public void reset() {
        pending.clear();
        sectionHits.clear();
        increaseHead = increaseTail = 0;
        decreaseHead = decreaseTail = 0;
        relightTail = 0;
        processing = false;
        overflowed = false;
    }

    public int getPendingCount() {
        return pending.size();
    }

    public long getTotalIncreaseNodes() {
        return totalIncreaseNodes;
    }

    public long getTotalDecreaseNodes() {
        return totalDecreaseNodes;
    }

    public String describe() {
        return "inc=" + totalIncreaseNodes + " dec=" + totalDecreaseNodes
                + " flushes=" + totalFlushes + " overflow=" + totalOverflowFallbacks
                + " pending=" + pending.size();
    }

    /**
     * 区段 → 变更次数的小型开放寻址表。
     *
     * <p>为什么不用 {@code HashMap<Long, Integer>}：每次 increment 都要
     * 装箱 key 和 value，爆炸场景下一次 flush 就是几千次装箱。
     *
     * <p>装填因子超过 0.7 时整体清空 —— 代价是丢掉计数，那意味着
     * 这次不做高密度判定，退化为增量处理，功能正确性不受影响。
     */
    private static final class SectionCounter {

        private long[] keys;
        private int[] counts;
        private int size;
        private int threshold;

        SectionCounter(int initialCapacity) {
            int cap = Integer.highestOneBit(Math.max(4, initialCapacity) - 1) << 1;
            keys = new long[cap];
            counts = new int[cap];
            threshold = (int) (cap * 0.7F);
        }

        void increment(long key) {
            if (size >= threshold) {
                clear();
            }
            int mask = keys.length - 1;
            int idx = hash(key) & mask;
            while (true) {
                if (counts[idx] == 0) {
                    keys[idx] = key;
                    counts[idx] = 1;
                    size++;
                    return;
                }
                if (keys[idx] == key) {
                    counts[idx]++;
                    return;
                }
                idx = (idx + 1) & mask;
            }
        }

        /** 查该区段的计数，没有则返回 0。不清空、不修改。 */
        int countOf(long key) {
            int mask = keys.length - 1;
            int idx = hash(key) & mask;
            while (counts[idx] != 0) {
                if (keys[idx] == key) {
                    return counts[idx];
                }
                idx = (idx + 1) & mask;
            }
            return 0;
        }

        void clear() {
            Arrays.fill(keys, 0L);
            Arrays.fill(counts, 0);
            size = 0;
        }

        private static int hash(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }
}
