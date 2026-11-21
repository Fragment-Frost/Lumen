package top.fpvp.lumen.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;
import top.fpvp.lumen.thread.AsyncTask;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 异步遮挡剔除（参考 EntityCulling 的 async path-tracing）。
 *
 * <h2>与同步方案的区别</h2>
 *
 * <p>已有的 {@link OcclusionCuller} 是<b>同步</b>的：在渲染线程上当场打射线，
 * 因此必须受 {@code occlusionRayBudget} 严格限制 —— 每条射线要查 6 个方块，
 * 256 条就是 1536 次方块查询，再多就会把省下来的渲染时间赔进去。
 *
 * <p>EntityCulling 的思路是把射线追踪<b>挪到后台线程</b>：主线程只提交
 * "这些候选对象需要判定"，worker 算完后把结果放回队列，主线程下一帧取用。
 * 这样主线程的每帧成本只剩下"提交 + 读取结果"，射线数量可以远超同步方案。
 *
 * <h2>三个必须处理正确的点</h2>
 *
 * <ol>
 *   <li><b>后台线程不能碰 {@code World}</b>。1.8.9 的 World 完全不是线程安全的，
 *       后台读方块会与主线程的区块加载竞争。解决方式：在主线程上
 *       <b>快照</b>射线沿途需要查询的方块坐标与是否透明，只把这点数据交给 worker。
 *       这样 worker 只处理纯数组，完全不接触游戏对象。</li>
 *   <li><b>结果必须能过期</b>。实体在几帧内会移动、世界会切换。
 *       每条请求带一个序号，结果回来时若序号已过期就丢弃。</li>
 *   <li><b>延迟必须被容忍</b>。异步结果有 1~2 帧延迟，期间按"可见"处理
 *       （安全方向），因此不会出现实体先消失再出现以外的异常。</li>
 * </ol>
 *
 * <h2>为什么快照反而更快</h2>
 *
 * <p>看起来"快照方块数据"比"直接查方块"更贵，但实际相反：
 * <ul>
 *   <li>主线程查方块是有成本的（区块查找、{@code IBlockState} 分派），
 *       但它是 <b>6 次连续查询</b>，缓存友好；</li>
 *   <li>真正贵的是<b>等待</b>：同步方案必须当场算完才能继续渲染，
 *       这 6 次查询的延迟无法与其它工作重叠；异步方案把它移出关键路径。</li>
 * </ul>
 *
 * <p>因此本类在主线程上只做"按需快照 + 提交"，实际判断在 worker 上完成。
 */
public final class AsyncOcclusionCuller {

    /** 每条射线的采样点数。与同步方案保持一致，便于对比。 */
    private static final int SAMPLES = 6;

    /** 结果槽位数（2 的幂）。按实体 id 直接寻址。 */
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;

    /** 每个槽位当前是否被判定为遮挡。 */
    private static final boolean[] OCCLUDED = new boolean[SLOTS];

    /** 每个槽位的连续遮挡计数（迟滞）。 */
    private static final int[] STREAK = new int[SLOTS];

    /** 该槽位的结果是否属于"当前这一代"，用于丢弃过期结果。 */
    private static final int[] RESULT_GENERATION = new int[SLOTS];

    /** 该槽位是否已有一条在途请求（避免同一实体重复提交）。 */
    private static final boolean[] IN_FLIGHT = new boolean[SLOTS];

    /** 每帧推进的代次。 */
    private static int generation;

    /** 眼睛位置快照（只在主线程写）。 */
    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;
    private static boolean eyeValid;

    private static World world;

    private AsyncOcclusionCuller() {
    }

    public static void beginFrame(World w, Entity viewEntity, float partialTicks) {
        world = w;
        generation++;

        if (viewEntity == null) {
            eyeValid = false;
            return;
        }
        eyeX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        eyeY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks
                + viewEntity.getEyeHeight();
        eyeZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
        eyeValid = true;
    }

    /** 世界切换时清空。 */
    public static void reset() {
        java.util.Arrays.fill(OCCLUDED, false);
        java.util.Arrays.fill(STREAK, 0);
        java.util.Arrays.fill(RESULT_GENERATION, 0);
        java.util.Arrays.fill(IN_FLIGHT, false);
        eyeValid = false;
        world = null;
    }

    /**
     * 查询某实体本帧是否应被视为遮挡。
     *
     * <p>返回的是<b>上一次</b>异步计算的结果（1~2 帧延迟）。
     * 若还没有结果，或结果已过期，一律按"可见"处理。
     */
    public static boolean isOccluded(int entityId) {
        if (!isEnabled()) {
            return false;
        }
        int slot = entityId & MASK;
        if (!OCCLUDED[slot]) {
            return false;
        }
        // 结果过旧（超过若干帧没有更新）时不再信任它，
        // 否则实体移出遮挡区后会一直不可见。
        int age = generation - RESULT_GENERATION[slot];
        if (age > 10) {
            OCCLUDED[slot] = false;
            return false;
        }
        return true;
    }

    /**
     * 提交一次异步遮挡判定。
     *
     * <p>在主线程调用。若该实体已有在途请求，或整体预算已用尽，则跳过。
     */
    public static void requestCheck(Entity entity) {
        if (!isEnabled() || !eyeValid || world == null || entity == null) {
            return;
        }

        final int entityId = entity.getEntityId();
        final int slot = entityId & MASK;
        if (IN_FLIGHT[slot]) {
            // 上一帧的请求还在跑，不重复提交 —— 这是限流的关键，
            // 否则高实体密度场景下会瞬间把队列灌满。
            return;
        }

        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (box == null) {
            return;
        }
        final double cx = (box.minX + box.maxX) * 0.5D;
        final double cy = (box.minY + box.maxY) * 0.5D;
        final double cz = (box.minZ + box.maxZ) * 0.5D;

        double dx = cx - eyeX;
        double dy = cy - eyeY;
        double dz = cz - eyeZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 1.0D || distSq > LumenConfig.occlusionRayLength * LumenConfig.occlusionRayLength) {
            return;
        }

        // ---- 关键：在主线程上把射线沿途的方块状态快照下来 ----
        //
        // worker 绝不能碰 world：1.8.9 的 World 完全不线程安全，
        // 后台读方块会与主线程的区块加载竞争，结果是偶发的
        // ArrayIndexOutOfBounds 或者更糟的静默数据损坏。
        final boolean[] blocked = new boolean[SAMPLES + 1];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean loaded = true;
        for (int i = 1; i <= SAMPLES; i++) {
            double t = (double) i / (SAMPLES + 1);
            int bx = MathHelper.floor_double(eyeX + dx * t);
            int by = MathHelper.floor_double(eyeY + dy * t);
            int bz = MathHelper.floor_double(eyeZ + dz * t);
            if (by < 0 || by > 255) {
                continue;
            }
            cursor.set(bx, by, bz);
            if (!world.isBlockLoaded(cursor)) {
                // 有格子没加载：无法可靠判定，本次放弃（按可见处理）。
                loaded = false;
                break;
            }
            IBlockState state;
            try {
                state = world.getBlockState(cursor);
            } catch (RuntimeException e) {
                loaded = false;
                break;
            }
            blocked[i] = MoreCulling.isOccluding(world, cursor, state);
        }
        if (!loaded) {
            return;
        }

        final int submittedGeneration = generation;
        IN_FLIGHT[slot] = true;

        AsyncTaskDispatcher.get().submit(new AsyncTask<Boolean>() {
            @Override
            protected Boolean compute() {
                // 纯数组运算，零游戏对象访问，可以在任意线程安全执行。
                for (int i = 1; i <= SAMPLES; i++) {
                    if (blocked[i]) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }

            @Override
            protected void deliver(Boolean result) {
                IN_FLIGHT[slot] = false;
                if (result == null) {
                    return;
                }
                // 代次已过期：世界换过了，结果无意义。
                if (generation - submittedGeneration > 3) {
                    return;
                }
                RESULT_GENERATION[slot] = generation;
                if (result) {
                    int streak = STREAK[slot] + 1;
                    STREAK[slot] = streak;
                    if (streak >= LumenConfig.occlusionHysteresis) {
                        OCCLUDED[slot] = true;
                        LumenProfiler.culledEntities++;
                    }
                } else {
                    STREAK[slot] = 0;
                    OCCLUDED[slot] = false;
                }
            }

            @Override
            protected void onDiscard(DiscardReason reason) {
                // 必须清掉在途标记，否则该实体永远不会再被提交，
                // 也就永远不会被剔除（表现为"这个实体永远不掉帧"）。
                IN_FLIGHT[slot] = false;
            }
        }, AsyncTaskDispatcher.Priority.BACKGROUND);
    }

    private static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.entityOcclusionCulling
                && LumenConfig.asyncOcclusion;
    }

    public static String describe() {
        int occluded = 0;
        for (boolean b : OCCLUDED) {
            if (b) {
                occluded++;
            }
        }
        return "asyncOccluded=" + occluded + " gen=" + generation;
    }
}
