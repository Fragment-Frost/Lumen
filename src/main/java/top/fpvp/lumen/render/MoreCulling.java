package top.fpvp.lumen.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;

/**
 * MoreCulling 风格的增强剔除。
 *
 * <h2>四项功能</h2>
 *
 * <ol>
 *   <li><b>树叶参与遮挡判定</b>：默认树叶不算遮挡物（{@code isOpaqueCube()}
 *       为 false），于是树林里"藏在树叶后面的实体"永远不会被剔除。
 *       把树叶当作遮挡物能显著提升森林场景的帧率 ——
 *       代价是被厚树叶挡住的实体提前消失。默认关闭，需要显式开启。</li>
 *   <li><b>信标光柱剔除</b>：光柱渲染包围盒高达 256 格，远超实际可见范围，
 *       原版几乎从不剔除它。这里对光柱本体做独立视锥判定。</li>
 *   <li><b>贴墙方块实体剔除</b>：方块的六个邻居全是不透明整方块时，
 *       该方块实体无论从哪个角度都看不到，整块跳过。</li>
 *   <li><b>激进模式</b>（{@link #isAggressivelyCullable}）：进一步把
 *       "一个不透明面挡住"也算作剔除依据。见下面的警告。</li>
 * </ol>
 *
 * <h2>激进模式的取舍（必读）</h2>
 *
 * <p>激进模式会把判定从"六面全包围"放宽到"沿视线有遮挡面"，
 * 能多剔掉一批边缘对象。但它<b>确实会在某些角度下让物体提前消失</b> ——
 * 这不是 bug，是设计上的取舍。因此：
 * <ul>
 *   <li>它<b>默认关闭</b>，必须显式开启 {@code culling.aggressiveCulling}；</li>
 *   <li>玩家自己、以及玩家骑乘的实体<b>永远不参与</b>激进剔除；</li>
 *   <li>只有方块实体走激进路径，实体走保守路径（实体会动，
 *       误剔的观感代价远高于方块实体）。</li>
 * </ul>
 *
 * <h2>为什么不做「完整化」（逐面剔除）</h2>
 *
 * <p>MoreCulling 的"完整方块"功能依赖 1.13+ 的 {@code BlockState#isSideInvisible}
 * 与多方块形状系统。1.8.9 的 {@code Block#isFullCube()} 是方块级布尔值，
 * 没有逐面可见性信息。强行实现必须重写方块渲染，且会改变外观 ——
 * 与其做一个让人看出差别的"优化"，不如不做。
 */
public final class MoreCulling {

    private MoreCulling() {
    }

    /**
     * 该方块对遮挡剔除而言是否"挡光"。
     *
     * <p>在原版 {@code isOpaqueCube() && isFullCube()} 的基础上，
     * 可选地把树叶也算作遮挡物。
     */
    public static boolean isOccluding(World world, BlockPos pos, IBlockState state) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();

        // 树叶优先判定：它在 isOpaqueCube() 上是 false，
        // 但视觉上确实挡住视线，是森林场景最大的收益点。
        if (LumenConfig.cullLeavesOcclusion && isLeaves(block, world, pos)) {
            return true;
        }
        return block.isOpaqueCube() && block.isFullCube();
    }

    /**
     * 判断是否树叶。
     *
     * <p>除了 {@code Block#isLeaves}，还额外识别一类常被漏掉的方块：
     * 那些不透明度很低但视觉上很密的方块（如树叶类模组方块）。
     * 这里<b>不</b>用"不透明度"做通用判据 —— 玻璃、冰、水的
     * {@code getLightOpacity} 也很低，把它们当遮挡物会让窗户后的实体凭空消失。
     *
     * <p>因此只认 {@code isLeaves} 这一个可靠的语义标记。
     */
    private static boolean isLeaves(Block block, World world, BlockPos pos) {
        try {
            return block.isLeaves(world, pos);
        } catch (RuntimeException e) {
            // 某些 mod 方块在未加载区块上会抛异常：保守按"不是树叶"处理。
            return false;
        }
    }

    /**
     * 该方块是否"六面被完全包围"，从而其方块实体完全不可见。
     *
     * <p>只处理最确定的场景：六个邻居全是不透明整方块。
     * 这种情况下目标方块实体的任何一面都看不到，可以安全跳过。
     */
    public static boolean isFullyEnclosed(World world, TileEntity tile) {
        if (!LumenConfig.enabled || !LumenConfig.mapSignCulling) {
            return false;
        }
        BlockPos pos = tile.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (EnumFacing facing : EnumFacing.values()) {
            cursor.set(pos.getX() + facing.getFrontOffsetX(),
                    pos.getY() + facing.getFrontOffsetY(),
                    pos.getZ() + facing.getFrontOffsetZ());
            if (!world.isBlockLoaded(cursor)) {
                return false;
            }
            IBlockState state = world.getBlockState(cursor);
            Block block = state.getBlock();
            if (!block.isOpaqueCube() || !block.isFullCube()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 激进剔除：方块实体的朝外面被挡住时跳过。
     *
     * <p>与 {@link #isFullyEnclosed} 的区别：那个要求六面全包围，
     * 这个只要求"玩家看它的方向上有遮挡"。判定依据是从方块实体位置
     * 朝眼睛方向的反向延伸一格，若那一格是不透明整方块，说明这一面被完全挡住。
     *
     * <p>为什么只反向延伸一格而不做长距离射线：方块实体通常紧贴墙面，
     * 一格足够覆盖绝大多数场景，而长距离射线要查更多方块、
     * 且更容易因为中间隔着半透明方块而误判。
     */
    public static boolean isAggressivelyCullable(World world, TileEntity tile,
                                                 double eyeX, double eyeY, double eyeZ) {
        if (!LumenConfig.enabled || !LumenConfig.aggressiveCulling) {
            return false;
        }
        BlockPos pos = tile.getPos();
        double cx = pos.getX() + 0.5D;
        double cy = pos.getY() + 0.5D;
        double cz = pos.getZ() + 0.5D;

        double dx = eyeX - cx;
        double dy = eyeY - cy;
        double dz = eyeZ - cz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.01D) {
            return false;
        }
        // 从方块中心朝眼方向走一格，取那一格的方块。
        int bx = (int) Math.floor(cx + dx / len);
        int by = (int) Math.floor(cy + dy / len);
        int bz = (int) Math.floor(cz + dz / len);
        if (by < 0 || by > 255) {
            return false;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(bx, by, bz);
        if (!world.isBlockLoaded(cursor)) {
            return false;
        }
        IBlockState state;
        try {
            state = world.getBlockState(cursor);
        } catch (RuntimeException e) {
            return false;
        }
        Block block = state.getBlock();
        // 只认整方块：半透明方块（玻璃、楼梯）后面依然能看到，
        // 拿它们做剔除会让人明显看出"东西不见了"。
        return block.isOpaqueCube() && block.isFullCube();
    }

    /**
     * 信标光柱是否应该被剔除。
     *
     * <p>只对光柱低段做视锥判定：光柱从信标向上延伸，低段在视锥内时
     * 整根通常也在视锥内。
     *
     * <p>刻意<b>不做</b>遮挡判定：光柱经常穿过方块（这是它的设计），
     * 用遮挡逻辑会让它消失得不合理。
     */
    public static boolean shouldCullBeacon(TileEntity tile) {
        if (!LumenConfig.enabled || !LumenConfig.beaconBeamCulling) {
            return false;
        }
        if (!(tile instanceof TileEntityBeacon)) {
            return false;
        }
        TileEntityBeacon beacon = (TileEntityBeacon) tile;
        if (beacon.shouldBeamRender() <= 0.0F) {
            return false;
        }
        BlockPos pos = beacon.getPos();
        return !FrustumCache.get().isBoxVisible(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);
    }

    /**
     * 方块实体是否整体不可见（供 MixinRenderGlobal 统一调用）。
     *
     * <p>按代价从低到高依次判定，命中即返回。
     */
    public static boolean shouldCullTileEntity(TileEntity tile,
                                               double eyeX, double eyeY, double eyeZ) {
        if (!LumenConfig.enabled) {
            return false;
        }
        // 1. 信标光柱：专用逻辑（包围盒太特殊，通用逻辑处理不好）。
        if (shouldCullBeacon(tile)) {
            return true;
        }
        World world = tile.getWorld();
        if (world == null) {
            return false;
        }
        // 2. 六面全包围。
        if (isFullyEnclosed(world, tile)) {
            return true;
        }
        // 3. 激进模式：朝眼方向被一个整方块挡住。
        return isAggressivelyCullable(world, tile, eyeX, eyeY, eyeZ);
    }
}
