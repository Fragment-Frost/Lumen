package top.fpvp.lumen.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

/**
 * 视锥剔除 + 遮挡剔除。
 *
 * <p><b>视锥剔除</b>：原版 1.8.9 对实体已经做了视锥检测，但对 {@link TileEntity}
 * 只检查距离（{@code getMaxRenderDistanceSquared}），从不检查视野 ——
 * 你身后的箱子、告示牌、熔炉依然在逐帧提交 TESR 渲染。在大型仓库或红石机器里
 * 这个数字很容易上千。粒子同样完全没有剔除。
 *
 * <p>视锥来自 {@link FrustumCache}，它用世界空间平面判定，因此没有原版
 * “减去摄像机位置”那套近似带来的偏差（见 {@link FrustumCache} 的注释）。
 *
 * <p><b>遮挡剔除</b>：沿“眼睛 → 目标”的线段<b>等距采样</b>判断是否被实心方块挡住。
 * 这里刻意不用 {@code World#rayTraceBlocks}：它做逐格 DDA，每条射线要遍历几十个
 * 方块，256 条射线就是上万次方块查询 —— 剔除本身会比它省下的渲染更贵，
 * 这正是“优化反噬”的典型。采样法固定 6 次方块查询，复杂度与距离无关。
 *
 * <p>采样法的两个错误方向：
 * <ul>
 *   <li><b>漏判</b>（薄墙没采到）→ 物体照常渲染，画面永远正确，只是没省下这份；</li>
 *   <li><b>误判</b>（目标其实从墙边露出一点）→ 由迟滞计数与“只在整块不透明方块处
 *       中止”这两个措施压住。</li>
 * </ul>
 * 也就是说：本类倾向于<b>少剔</b>。少剔只是少省一点性能，误剔是画面错误，
 * 两者的代价完全不对称。
 *
 * <p>所有状态都由渲染线程独占，不需要同步；热路径零分配。
 */
public final class OcclusionCuller {

    /** 每条射线沿线段采样的点数（不含两端）。与射线长度无关的固定成本。 */
    private static final int SAMPLES = 6;

    /** 迟滞表容量，必须是 2 的幂。用 entityId 直接寻址，零分配、无哈希。 */
    private static final int TABLE_SIZE = 4096;
    private static final int TABLE_MASK = TABLE_SIZE - 1;

    /** 连续遮挡帧数，达到阈值才真正隐藏。 */
    private final int[] blockedStreak = new int[TABLE_SIZE];

    /** 复用的可变坐标，避免每次查询都 new BlockPos。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private World world;

    private double eyeX;
    private double eyeY;
    private double eyeZ;

    private boolean eyeValid;

    private static final OcclusionCuller INSTANCE = new OcclusionCuller();

    private OcclusionCuller() {
    }

    public static OcclusionCuller get() {
        return INSTANCE;
    }

    /** 供 MoreCulling 的激进判定复用眼睛位置，避免重复计算。 */
    public static double getEyeX() {
        return INSTANCE.eyeX;
    }

    public static double getEyeY() {
        return INSTANCE.eyeY;
    }

    public static double getEyeZ() {
        return INSTANCE.eyeZ;
    }

    public static boolean isEyeValid() {
        return INSTANCE.eyeValid;
    }

    /** 每帧在世界渲染开始时调用一次，重置计数并缓存眼睛位置。 */
    public void beginFrame(World world, Entity viewEntity, float partialTicks) {
        this.world = world;
        LumenProfiler.resetFrameCounters();

        if (viewEntity != null) {
            this.eyeX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
            this.eyeY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks
                    + viewEntity.getEyeHeight();
            this.eyeZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
            this.eyeValid = true;
        } else {
            this.eyeValid = false;
        }
    }

    /**
     * 世界卸载时清空。不清空的话，迟滞表会残留上一个世界的实体 id，
     * 新世界复用同一 id 的实体可能被误判成“已经遮挡了很久”。
     */
    public void reset() {
        java.util.Arrays.fill(blockedStreak, 0);
        this.world = null;
        this.eyeValid = false;
    }

    /**
     * 方块实体是否被遮挡。
     *
     * <p><b>不含视锥判定。</b>原版在 {@code RenderGlobal#renderEntities} 的方块实体
     * 循环里已经做过 {@code ICamera#isBoundingBoxInFrustum} 和距离检查，
     * 这里再测一次只是白费 CPU，还会和重写过视锥逻辑的 mod 产生分歧。
     * 本方法只负责补上原版缺失的那一半：遮挡。
     *
     * @return {@code true} 表示可以跳过它的渲染
     */
    public boolean shouldCull(TileEntity tile) {
        if (!LumenConfig.enabled || !LumenConfig.tileEntityCulling || world == null || !eyeValid) {
            return false;
        }

        AxisAlignedBB box = BlockEntityOptimizer.getRenderBoundingBox(tile);
        if (box == null) {
            BlockPos pos = tile.getPos();
            // 用可变坐标构造，而不是每次 new 一个 AxisAlignedBB。
            // 这里无法完全避免分配（AxisAlignedBB 不可变），
            // 但只在包围盒为 null 这种罕见路径上发生。
            box = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }

        // 用包围盒中心代表可见性。对信标光柱这类超长包围盒不够精确，
        // 但漏判只会让它继续渲染，属于安全方向。
        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = (box.minY + box.maxY) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;
        if (isOccluded(cx, cy, cz)) {
            LumenProfiler.culledTileEntities++;
            return true;
        }

        LumenProfiler.visibleTileEntities++;
        return false;
    }

    /**
     * 实体是否应该被遮挡剔除。
     *
     * <p>视锥剔除原版已经做过，这里只补遮挡，所以调用方应在原版通过视锥检测
     * <b>之后</b>再问本方法。
     *
     * @return {@code true} 表示可以跳过渲染
     */
    public boolean shouldOcclude(Entity entity) {
        if (!LumenConfig.enabled || !LumenConfig.entityOcclusionCulling || !eyeValid
                || world == null || entity == null) {
            return false;
        }
        // 玩家自己永远不剔除：第三人称下把自己剔掉会非常刺眼。
        if (entity == Minecraft.getMinecraft().getRenderViewEntity()) {
            return false;
        }
        // 这些实体本身就不该做遮挡剔除（骑乘中的、或显式要求忽略视锥的）。
        if (entity.ignoreFrustumCheck || entity.riddenByEntity != null) {
            return false;
        }

        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (box == null) {
            return false;
        }
        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = (box.minY + box.maxY) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;

        int slot = entity.getEntityId() & TABLE_MASK;
        if (isOccluded(cx, cy, cz)) {
            int streak = blockedStreak[slot] + 1;
            blockedStreak[slot] = streak;
            // 迟滞：连续 N 帧都被判定遮挡才隐藏，消除边缘闪烁。
            if (streak >= LumenConfig.occlusionHysteresis) {
                LumenProfiler.culledEntities++;
                return true;
            }
            LumenProfiler.visibleEntities++;
            return false;
        }

        blockedStreak[slot] = 0;
        LumenProfiler.visibleEntities++;
        return false;
    }

    /**
     * 粒子视锥剔除。
     *
     * <p>原版对粒子完全不剔除：一个爆炸或刷怪笼就能产生上千个粒子，
     * 其中过半在背后。
     */
    public boolean shouldCullParticle(double x, double y, double z, float scale) {
        if (!LumenConfig.enabled || !LumenConfig.particleCulling) {
            return false;
        }
        double r = Math.max(0.1D, scale * 0.5D);
        if (FrustumCache.get().isBoxVisible(x - r, y - r, z - r, x + r, y + r, z + r)) {
            LumenProfiler.visibleParticles++;
            return false;
        }
        LumenProfiler.culledParticles++;
        return true;
    }

    /**
     * 沿“眼睛 → 目标点”的线段等距采样，判断是否被不透明方块挡住。
     *
     * <p>复杂度固定为 {@link #SAMPLES} 次方块查询，与距离无关。
     */
    private boolean isOccluded(double targetX, double targetY, double targetZ) {
        if (!eyeValid || LumenConfig.occlusionRayBudget <= 0) {
            return false;
        }
        if (LumenProfiler.raysCast >= LumenConfig.occlusionRayBudget) {
            // 预算用尽：本帧剩下的对象一律按“可见”处理。
            // 宁可少剔几个，也不让剔除逻辑自己变成帧率瓶颈。
            return false;
        }
        LumenProfiler.raysCast++;

        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        double maxDistance = LumenConfig.occlusionRayLength;
        if (distanceSq > maxDistance * maxDistance) {
            // 太远，遮挡判断不可靠（远处物体通常已在视锥外）。
            return false;
        }
        if (distanceSq < 1.0D) {
            // 贴着摄像机：不剔除。
            return false;
        }

        // 跳过两端：起点可能在摄像机所在方块内，终点就是目标本身。
        for (int i = 1; i <= SAMPLES; i++) {
            double t = (double) i / (SAMPLES + 1);
            if (isOpaqueAt(eyeX + dx * t, eyeY + dy * t, eyeZ + dz * t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某个浮点坐标所处的方块是否"完全挡光"。
     *
     * <p>具体的判定规则在 {@link MoreCulling#isOccluding}，那里同时处理了
     * "树叶是否算遮挡物"这个可配置项。这里只负责把浮点坐标折算成方块坐标。
     */
    private boolean isOpaqueAt(double x, double y, double z) {
        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);
        if (by < 0 || by > 255) {
            return false;
        }

        IBlockAccess access = world;
        cursor.set(bx, by, bz);
        IBlockState state;
        try {
            state = access.getBlockState(cursor);
        } catch (RuntimeException e) {
            // 区块未加载：一律当作不透明，安全方向（少剔）。
            return false;
        }
        if (state == null) {
            return false;
        }
        return MoreCulling.isOccluding(world, cursor, state);
    }
}
