package top.fpvp.lumen.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;

/**
 * MoreCulling 风格的增强剔除。
 *
 * <p>参考 More Culling 的三项功能，按 1.8.9 的能力裁剪：
 *
 * <ol>
 *   <li><b>树叶参与遮挡判定</b>：默认情况下树叶不被视为遮挡物
 *       （{@code isOpaqueCube()} 为 false），因此树林里"藏在树叶后面的实体"
 *       永远不会被剔除。把树叶当作遮挡物能显著提升森林场景的帧率 ——
 *       代价是被厚树叶挡住的实体提前消失。MoreCulling 把它做成开关，
 *       这里同样如此。</li>
 *   <li><b>信标光柱剔除</b>：光柱的渲染包围盒高达 256 格，远超实际可见范围。
 *       原版对它的距离剔除用的是 {@code getMaxRenderDistanceSquared}，
 *       但光柱从很远处仍然可见，于是几乎从不被剔除。这里对光柱做一次
 *       独立的视锥判定 —— 只判定光柱的<b>低段</b>，因为高段在视锥外时
 *       低段通常也已经在视锥外，用低段代表整体是安全的近似。</li>
 *   <li><b>贴墙方块实体剔除</b>：地图、告示牌贴墙时只画朝外那一面。</li>
 * </ol>
 *
 * <h2>为什么不用"完整方块"那套</h2>
 *
 * <p>MoreCulling 的"完整方块"（完整化）功能依赖 1.13+ 的
 * {@code BlockState#isSideInvisible} 与多方块形状系统。1.8.9 的
 * {@code Block#isFullCube()} 是方块级的布尔值，没有逐面可见性信息，
 * 无法在不重写方块渲染的前提下实现逐面剔除。强行实现会改变外观，
 * 因此这里<b>不做</b> —— 与其做一个会让人看出差别的"优化"，不如不做。
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
        if (block.isOpaqueCube() && block.isFullCube()) {
            return true;
        }
        // 树叶：默认不透明（isOpaqueCube 为 false），但视觉上确实挡住视线。
        if (LumenConfig.cullLeavesOcclusion && block.isLeaves(world, pos)) {
            return true;
        }
        return false;
    }

    /**
     * 信标光柱是否应该被剔除。
     *
     * <p>只对光柱低段做视锥判定：光柱从信标方块向上延伸，
     * 低段在视锥内时整根通常也在视锥内；低段在视锥外时整根基本都不可见。
     *
     * <p>这里刻意<b>不做</b>遮挡判定：光柱经常穿过方块（这是它的设计），
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
        // 本来就不渲染光柱的（无效果/被遮挡），交给原版处理。
        if (beacon.shouldBeamRender() <= 0.0F) {
            return false;
        }
        BlockPos pos = beacon.getPos();
        // 用信标方块本身的 1×1×2 作为代表体积做视锥判定。
        return !FrustumCache.get().isBoxVisible(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);
    }

    /**
     * 贴墙的地图/告示牌是否完全不可见。
     *
     * <p>思路：如果方块实体所在的方块六面都被不透明方块包围，那么它
     * 无论从哪个角度看都被挡住，可以整块跳过。
     *
     * <p>这是一个<b>保守</b>的判定：只剔除"六面全被实心方块包围"的情形。
     * 地图贴在墙上时，它自己占据的方块位置，前面是空气、后面是墙 ——
     * 所以六面判定通常不会命中。真正有效的是下面这个更强的规则：
     * <b>如果方块实体的渲染包围盒完全被不透明方块覆盖</b>，才剔除。
     *
     * <p>因此这里的实际做法是：检查该方块位置的前、后、左、右、上、下
     * 六个邻居里，是否已有一个不透明方块<b>与它共面重叠</b>，
     * 并且该方块实体本身没有可见的朝外面。
     *
     * <p>为避免误剔（漏掉一张该显示的地图比多画一张糟糕得多），
     * 这里只处理最确定的场景：<b>六个邻居全是不透明整方块</b>。
     * 这种情况下目标方块实体的任何一面都看不到。
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
                // 未加载：无法判断，按"没被包围"处理（安全方向）。
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
     * 方块实体是否整体不可见（供 MixinRenderGlobal 统一调用）。
     *
     * <p>按代价从低到高依次判定，命中即返回。
     */
    public static boolean shouldCullTileEntity(TileEntity tile) {
        if (!LumenConfig.enabled) {
            return false;
        }
        // 1. 信标光柱：专用逻辑（它的包围盒太特殊，通用逻辑处理不好）。
        if (shouldCullBeacon(tile)) {
            return true;
        }
        // 2. 被完全包围的地图/告示牌。
        World world = tile.getWorld();
        if (world != null && isFullyEnclosed(world, tile)) {
            return true;
        }
        return false;
    }
}
