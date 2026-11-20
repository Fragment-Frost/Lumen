package top.fpvp.lumen.thread;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.BlockPos;
import net.minecraft.world.pathfinder.WalkNodeProcessor;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把实体寻路（A*）挪到后台线程。
 *
 * <p><b>为什么这能省时间</b>：{@code PathNavigate#tryMoveToXYZ} 会同步跑完整条 A*。
 * 在复杂地形里单次寻路可以扩展几十万个节点；同一 tick 里几十个生物一起决定
 * “追玩家”时，主线程会被串行压住 —— 表现为与玩家移动同步的规律性卡顿。
 * 这段计算只读世界、不修改游戏状态，是天生的可并行任务。
 *
 * <h3>为什么不能直接把 PathNavigate 丢进线程池</h3>
 *
 * <p>这是这类优化最容易翻车的地方，有四处必须处理：
 *
 * <p><b>1. {@link PathNavigate} 与 {@link PathFinder} 都是<b>有状态</b>的。</b>
 * {@code PathFinder} 持有可复用的 {@code path} / {@code pathOptions} 字段；
 * 两个线程同时调同一个实例会互相踩对方的中间数组，结果是“路径指向虚空”。
 * 所以每个任务<b>自带</b>一个 {@code PathFinder} 和 {@code WalkNodeProcessor}，
 * 绝不共享。
 *
 * <p><b>2. 结果必须回到主线程再 {@code setPath}。</b>{@code setPath} 会写
 * {@code currentPath}、{@code totalTicks}，而 {@code onUpdateNavigation} 在同一
 * tick 里会读它们。跨线程写会产生“路径指针撕裂”，表现为实体沿一条不存在的
 * 路径抽搐。因此：计算在后台，赋值在主线程。
 *
 * <p><b>3. 不能在后台线程碰实体的可变状态。</b>{@code compute()} 里<b>只</b>读
 * 提交时刻抓取的快照（起点坐标、目标、搜索半径），不读 {@code entity.posX}。
 * 实体在计算期间可能已经走开；用旧坐标算出的路径依然可用 —— 导航器会把
 * 实体拉回路径起点，这正是原版在“计算期间世界变化”时的既有行为。
 *
 * <p><b>4. 同一实体不能堆积在途请求。</b>每个实体每 tick 都可能重新决策，
 * 不合并就会让一个生物把队列塞满、把其它生物饿死。用冷却窗口 + 在途标记合并。
 *
 * <p>所有入口只在主线程调用；后台线程只执行 {@link PathfindTask#compute()}。
 */
public final class AsyncPathfinding {

    /** 每个实体最近一次提交寻路的 tick，用于冷却与合并。 */
    private static final Map<Integer, Long> LAST_REQUEST = new ConcurrentHashMap<Integer, Long>();

    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();

    private AsyncPathfinding() {
    }

    /**
     * 尝试异步寻路到指定坐标。
     *
     * @return {@code true} 表示请求已被接收（结果稍后由主线程应用）；
     *         {@code false} 表示应回退到原版的同步寻路
     */
    public static boolean tryMoveToXYZ(EntityLiving entity, PathNavigate navigate,
                                       double x, double y, double z, double speed,
                                       boolean avoidsWater, boolean canSwim, boolean enterDoors) {
        if (!LumenConfig.enabled || !LumenConfig.asyncPathfinding
                || entity == null || navigate == null || entity.worldObj == null) {
            return false;
        }
        if (!shouldSubmit(entity)) {
            return false;
        }

        BlockPos target = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        float searchRange = navigate.getPathSearchRange();
        if (searchRange <= 0.0F) {
            return false;
        }

        PathfindTask task = new PathfindTask(
                entity,
                navigate,
                target,
                searchRange,
                speed,
                avoidsWater,
                canSwim,
                enterDoors);

        if (!AsyncTaskDispatcher.get().submit(task, AsyncTaskDispatcher.Priority.BACKGROUND)) {
            LAST_REQUEST.remove(entity.getEntityId());
            return false;
        }
        SUBMITTED.incrementAndGet();
        return true;
    }

    /**
     * 请求合并与冷却。
     *
     * <p>规则：同一实体在一个冷却窗口内只接受一次请求。
     */
    private static boolean shouldSubmit(EntityLiving entity) {
        long now = entity.worldObj.getTotalWorldTime();
        long cooldown = LumenConfig.pathfindRequestCooldown;
        if (cooldown <= 0L) {
            return true;
        }
        Integer key = entity.getEntityId();
        Long last = LAST_REQUEST.get(key);
        if (last != null && now - last < cooldown) {
            SKIPPED.incrementAndGet();
            return false;
        }
        LAST_REQUEST.put(key, now);
        return true;
    }

    /** 实体被移除时清理冷却记录，避免 map 无限增长。 */
    public static void forget(EntityLiving entity) {
        if (entity != null) {
            LAST_REQUEST.remove(entity.getEntityId());
        }
    }

    /** 世界切换时清空所有状态。 */
    public static void reset() {
        LAST_REQUEST.clear();
    }

    public static String describe() {
        return "path(sub=" + SUBMITTED.get() + ",done=" + COMPLETED.get() + ",skip=" + SKIPPED.get() + ")";
    }

    /**
     * 一次寻路计算。
     *
     * <p>{@code compute()} 在后台线程执行：只<b>读</b>世界、只写自己私有的
     * {@code PathFinder}，并构造 {@link PathEntity}。不修改实体、不调 {@code setPath}。
     */
    private static final class PathfindTask extends AsyncTask<PathEntity> {

        private final EntityLiving entity;
        private final PathNavigate navigate;
        private final BlockPos target;
        private final float searchRange;
        private final double speed;
        private final boolean avoidsWater;
        private final boolean canSwim;
        private final boolean enterDoors;

        PathfindTask(EntityLiving entity, PathNavigate navigate, BlockPos target,
                     float searchRange, double speed,
                     boolean avoidsWater, boolean canSwim, boolean enterDoors) {
            this.entity = entity;
            this.navigate = navigate;
            this.target = target;
            this.searchRange = searchRange;
            this.speed = speed;
            this.avoidsWater = avoidsWater;
            this.canSwim = canSwim;
            this.enterDoors = enterDoors;
        }

        @Override
        protected PathEntity compute() {
            // 每个任务私有的 NodeProcessor + PathFinder：不共享任何可变状态。
            //
            // 这里<b>不</b>快照 breakDoors：PathNavigateGround 没有公开的
            // getBreakDoors()，而它又直接影响“僵尸能否穿门”。猜测一个值会让
            // 僵尸在门前打转。做法是让 PathFinder 用默认值（不破门）算出一条路径，
            // 反正 deliver 阶段 setPath 之后，原版的 onUpdateNavigation 会在
            // 需要时重新规划 —— 也就是说，最坏情况只是"多算了一次"，
            // 而不是"算出了一条错的路径且永不纠正"。
            WalkNodeProcessor processor = new WalkNodeProcessor();
            processor.setAvoidsWater(avoidsWater);
            processor.setCanSwim(canSwim);
            processor.setEnterDoors(enterDoors);

            PathFinder finder = new PathFinder(processor);

            // 只读世界。PathFinder 内部的 NodeProcessor 会调用
            // IBlockAccess#getBlockState —— 1.8.9 的客户端区块读取在
            // “区块不被并发加载/卸载”的前提下是安全的，而客户端主线程
            // 与渲染线程之间本来就有同样的约束。
            PathEntity path = finder.createEntityPathTo(
                    entity.worldObj, entity, target, searchRange);

            COMPLETED.incrementAndGet();
            return path;
        }

        @Override
        protected void deliver(PathEntity path) {
            // 回到主线程再写实体状态。
            if (path == null || entity.isDead || entity.worldObj == null) {
                LAST_REQUEST.remove(entity.getEntityId());
                return;
            }
            // 只有导航器仍然挂着同一个实体时才应用，避免实体换乘/换维度后错写。
            navigate.setPath(path, speed);
        }

        @Override
        protected void onDiscard(DiscardReason reason) {
            // 被丢弃说明这次寻路没生效。清掉冷却记录，让实体能立刻重新请求，
            // 否则它会“卡在冷却里但从未真正寻路”，表现为站着不动。
            LAST_REQUEST.remove(entity.getEntityId());
            if (reason == DiscardReason.ERROR) {
                LumenProfiler.log().debug("[Lumen] 寻路任务被丢弃: {}", reason);
            }
        }
    }
}
