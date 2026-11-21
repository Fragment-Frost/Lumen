package top.fpvp.lumen.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.thread.AsyncTask;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;

/**
 * 异步遮挡剔除（参考 EntityCulling 的 async path-tracing）。
 *
 * <h2>与同步方案的分工</h2>
 *
 * <p>{@link OcclusionCuller} 是同步的：在渲染线程上当场打射线，因此必须受
 * {@code occlusionRayBudget} 严格限制。本类把判定挪到后台线程，主线程只剩
 * "快照 + 提交 + 读结果"。
 *
 * <h2>上一版的三处性能问题（都已修复）</h2>
 *
 * <ol>
 *   <li><b>每帧分配 {@code boolean[]}</b>：上一版每次 {@code requestCheck}
 *       都 {@code new boolean[SAMPLES + 1]}，并对每个候选实体 new 一个
 *       {@code BlockPos.MutableBlockPos}。数百实体/帧 = 数百个短命对象/帧，
 *       每秒上万个 —— 这正是"时不时卡一下"的 GC 来源。现在全部改用
 *       复用数组 + 线程内游标。</li>
 *   <li><b>{@code IN_FLIGHT} 泄漏</b>：上一版只在 {@code deliver}/{@code onDiscard}
 *       里清标志。但调度器在队列溢出时可能<b>既不 deliver 也不 discard</b>
 *       （任务被直接丢弃），标志就永久为 true，那个实体从此再也不会被剔除。
 *       现在加了代次超时兜底：超过若干帧没有结果就强行清标志。</li>
 *   <li><b>与同步路径重复计算</b>：上一版 {@code MixinRenderGlobal} 里，
 *       异步判定之后<b>又</b>跑了一次同步 {@code shouldOcclude}。同一条射线
 *       算了两遍，且同步那遍还要抢射線预算。现在两者互斥：异步启用时
 *       不再调同步。</li>
 * </ol>
 *
 * <h2>后台线程不能碰 World</h2>
 *
 * <p>1.8.9 的 {@code World} 完全不是线程安全的，后台读方块会与主线程的
 * 区块加载竞争。因此主线程先把射线沿途的"是否挡光"快照成纯 boolean 数组，
 * worker 只处理数组。
 *
 * <p>但快照本身要查方块，是有成本的。<b>所以本类的收益不在"省方块查询"，
 * 而在"把延迟移出关键路径"</b> —— 主线程不必等待这几次查询的结果就能继续渲染。
 * 为了让快照成本可控，只对<b>距离足够近、且尚未有新鲜结果</b>的实体提交。
 */
public final class AsyncOcclusionCuller {

    /** 每条射线的采样点数。 */
    private static final int SAMPLES = 6;

    /** 结果槽位数（2 的幂）。按实体 id 直接寻址。 */
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;

    /** 每个槽位当前是否被判定为遮挡。 */
    private static final boolean[] OCCLUDED = new boolean[SLOTS];

    /** 每个槽位的连续遮挡计数（迟滞）。 */
    private static final int[] STREAK = new int[SLOTS];

    /** 该槽位结果所属的代次，用于丢弃过期结果。 */
    private static final int[] RESULT_GENERATION = new int[SLOTS];

    /** 该槽位是否已有一条在途请求。 */
    private static final boolean[] IN_FLIGHT = new boolean[SLOTS];

    /** 在途请求的提交代次，用于超时兜底清理。 */
    private static final int[] IN_FLIGHT_GENERATION = new int[SLOTS];

    /** 本帧还剩多少个提交配额（限流）。 */
    private static int submitBudget;

    /** 每帧推进的代次。 */
    private static int generation;

    /** 眼睛位置快照。 */
    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;
    private static boolean eyeValid;

    private static World world;

    /**
     * 复用的快照缓冲。
     *
     * <p>每帧会提交多条请求，若每条都 new 一个数组就是上一版的分配热点。
     * 这里用一个环形缓冲池：{@code POOL_SIZE} 个槽，每次取下一个。
     * 为什么不共用一个数组：请求是异步的，worker 可能还在读上一条的数据。
     * 环形池提供"最近若干条互不覆盖"的保证，容量按提交预算设定。
     */
    private static final boolean[][] SNAPSHOT_POOL = new boolean[64][SAMPLES + 1];
    private static int snapshotCursor;

    /** 提交预算的默认值（每帧最多提交多少条异步判定）。 */
    private static final int DEFAULT_SUBMIT_BUDGET = 48;

    private AsyncOcclusionCuller() {
    }

    public static void beginFrame(World w, Entity viewEntity, float partialTicks) {
        world = w;
        generation++;
        snapshotCursor = 0;
        submitBudget = LumenConfig.asyncOcclusionBudget > 0
                ? LumenConfig.asyncOcclusionBudget : DEFAULT_SUBMIT_BUDGET;

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
        java.util.Arrays.fill(IN_FLIGHT_GENERATION, 0);
        eyeValid = false;
        world = null;
    }

    /**
     * 每 tick 衰减迟滞计数。
     *
     * <p>只靠每帧查询来清零是不够的：实体一旦离开视野范围，
     * 就不会再有人调用 {@link #isOccluded}，它的 STREAK 会永久停在
     * "已遮挡"。等它重新进入视野时会被立刻剔除一帧（闪一下）。
     * 按 tick 衰减能保证计数不会无限累积。
     */
    public static void decayStreaks() {
        for (int i = 0; i < SLOTS; i++) {
            if (STREAK[i] > 0) {
                STREAK[i]--;
            }
        }
    }

    /**
     * 查询某实体本帧是否应被视为遮挡。
     *
     * <p>返回的是<b>上一次</b>异步计算的结果（1~2 帧延迟）。
     * 无结果或结果过旧时按"可见"处理（安全方向）。
     */
    public static boolean isOccluded(int entityId) {
        if (!isEnabled()) {
            return false;
        }
        int slot = entityId & MASK;

        // 在途请求超时兜底：调度器在队列溢出时可能既不解交付也不回调，
        // 此时 IN_FLIGHT 会永久为 true，该实体再也不会被提交。
        if (IN_FLIGHT[slot] && generation - IN_FLIGHT_GENERATION[slot] > 60) {
            IN_FLIGHT[slot] = false;
        }

        if (!OCCLUDED[slot]) {
            return false;
        }
        // 结果过旧时不再信任，否则实体移出遮挡区后会一直不可见。
        if (generation - RESULT_GENERATION[slot] > 10) {
            OCCLUDED[slot] = false;
            return false;
        }
        return true;
    }

    /**
     * 提交一次异步遮挡判定。在主线程调用。
     *
     * <p>限流三道闸：已有在途请求、本帧预算耗尽、距离不合适 —— 任一命中就跳过。
     */
    public static void requestCheck(Entity entity) {
        if (!isEnabled() || !eyeValid || world == null || entity == null) {
            return;
        }
        if (submitBudget <= 0) {
            return;
        }

        final int entityId = entity.getEntityId();
        final int slot = entityId & MASK;
        if (IN_FLIGHT[slot]) {
            return;
        }
        // 已经有本代次的新鲜结果：不必重复提交。
        if (RESULT_GENERATION[slot] == generation) {
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
        double limit = LumenConfig.occlusionRayLength;
        if (distSq < 1.0D || distSq > limit * limit) {
            return;
        }
        // 太远的实体通常在视锥外的概率已经很高，异步结果的价值低，
        // 而快照成本不减。超过半个射线长度就跳过。
        if (distSq > (limit * 0.5D) * (limit * 0.5D) && !LumenConfig.aggressiveCulling) {
            return;
        }

        // ---- 快照射线沿途的方块 ----
        //
        // 用环形池里的复用数组，不 new。
        final boolean[] blocked = SNAPSHOT_POOL[snapshotCursor];
        snapshotCursor = (snapshotCursor + 1) % SNAPSHOT_POOL.length;
        for (int i = 0; i <= SAMPLES; i++) {
            blocked[i] = false;
        }

        BlockPos.MutableBlockPos cursor = CURSOR.get();
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

        submitBudget--;
        IN_FLIGHT[slot] = true;
        final int submittedGeneration = generation;
        IN_FLIGHT_GENERATION[slot] = submittedGeneration;

        AsyncTaskDispatcher.get().submit(new AsyncTask<Boolean>() {
            @Override
            protected Boolean compute() {
                // 纯数组运算，零游戏对象访问，可在任意线程安全执行。
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
                // 代次过期太多：世界可能换过了，结果无意义。
                if (generation - submittedGeneration > 10) {
                    return;
                }
                RESULT_GENERATION[slot] = generation;
                if (result) {
                    int streak = STREAK[slot] + 1;
                    STREAK[slot] = streak;
                    if (streak >= LumenConfig.occlusionHysteresis) {
                        OCCLUDED[slot] = true;
                    }
                } else {
                    // 判定为可见时立即清零，让实体尽快恢复显示。
                    STREAK[slot] = 0;
                    OCCLUDED[slot] = false;
                }
            }

            @Override
            protected void onDiscard(DiscardReason reason) {
                // 必须清在途标记，否则该实体永远不会再被提交。
                IN_FLIGHT[slot] = false;
            }
        }, AsyncTaskDispatcher.Priority.BACKGROUND);
    }

    private static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.entityOcclusionCulling
                && LumenConfig.asyncOcclusion;
    }

    /**
     * 线程内复用的游标。
     *
     * <p>{@code requestCheck} 只在渲染线程调用，因此单例够用；
     * 用 ThreadLocal 是为了在极端情况下（多个渲染上下文）也不会互相踩。
     */
    private static final ThreadLocal<BlockPos.MutableBlockPos> CURSOR =
            new ThreadLocal<BlockPos.MutableBlockPos>() {
                @Override
                protected BlockPos.MutableBlockPos initialValue() {
                    return new BlockPos.MutableBlockPos();
                }
            };

    public static String describe() {
        int occluded = 0;
        for (boolean b : OCCLUDED) {
            if (b) {
                occluded++;
            }
        }
        return "asyncOccluded=" + occluded + " gen=" + generation
                + " budget=" + submitBudget;
    }
}
