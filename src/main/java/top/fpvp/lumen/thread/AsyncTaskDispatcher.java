package top.fpvp.lumen.thread;

import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界、无阻塞、带代次校验的异步任务调度器。
 *
 * <p><b>为什么不用 {@code ExecutorService} 一把梭：</b>1.8.9 的客户端主循环是
 * “一帧一次”的，而 {@code ExecutorService} 的默认队列是<b>无界</b>的
 * {@code LinkedBlockingQueue}。当提交速率超过消费速率（跑图时区块网格、
 * 大量实体寻路同时涌入），队列会无限增长：内存一路上涨，同时每个任务都在
 * 持有区块/实体的强引用，把本该被回收的区块钉死在老年代里 —— 这正是
 * “缓存穿透”式的表现：你以为在优化，实际上在制造 GC 压力。
 *
 * <p>本调度器的三条硬规则：
 * <ol>
 *   <li><b>永不阻塞主线程。</b>提交用 {@code offer()}，队列满就丢弃并计数；
 *       主线程永远不会因为后台忙而被挂起。</li>
 *   <li><b>永不阻塞等待结果。</b>没有 {@code Future}，结果由主线程每帧主动
 *       {@link #drainMainThread(int)} 拉取。主线程等 worker = 死锁的经典配方。</li>
 *   <li><b>结果必须过代次校验。</b>切换世界/卸载区块时 {@link #bumpGeneration()}，
 *       旧代次的结果一律丢弃，避免把上一个世界的网格塞进新世界。</li>
 * </ol>
 *
 * <p>优先级上分两条队列：{@link Priority#REALTIME}（区块网格、可见性相关）
 * 与 {@link Priority#BACKGROUND}（寻路、光照、流体）。worker 优先取 REALTIME，
 * 但每处理 8 个之后强制取一个 BACKGROUND —— 这是防饿死的廉价做法：
 * 纯优先队列在跑图时会让寻路任务永远排不上队，生物于是站着不动。
 */
public final class AsyncTaskDispatcher {

    /** 任务优先级。数值越小越优先。 */
    public enum Priority {
        /** 直接决定这一帧画面的任务（区块网格重建）。 */
        REALTIME,
        /** 影响玩法但不影响本帧的任务（寻路、流体、光照）。 */
        BACKGROUND
    }

    /** 每处理这么多个 REALTIME 任务，就必须让出一个给 BACKGROUND。 */
    private static final int FAIRNESS_STRIDE = 8;

    /** 已完成结果队列的容量。超过就丢结果，不丢任务——任务在跑的时候丢不掉。 */
    private static final int COMPLETED_CAPACITY = 1024;

    private static volatile AsyncTaskDispatcher instance;

    private final BlockingQueue<QueuedTask> realtime;
    private final BlockingQueue<QueuedTask> background;

    /** 待交付结果。用非阻塞队列 + 手动限流，保证 worker 永不因主线程忙而阻塞。 */
    private final Queue<Completed> completed = new ConcurrentLinkedQueue<Completed>();

    private final AtomicInteger completedSize = new AtomicInteger();

    private final AtomicInteger generation = new AtomicInteger();

    private final AtomicLong sequence = new AtomicLong();

    private volatile boolean running = true;

    // ---- 统计（全部无锁自增，可在热路径读取） ----
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    private int fairnessCounter;

    private AsyncTaskDispatcher(int workers, int queueCapacity) {
        this.realtime = new ArrayBlockingQueue<QueuedTask>(Math.max(16, queueCapacity));
        this.background = new ArrayBlockingQueue<QueuedTask>(Math.max(16, queueCapacity));
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(new Worker(), "Lumen-Worker-" + i);
            // 守护线程：绝不能阻止 JVM 退出。1.8.9 关服时不会通知我们，
            // 非守护线程会让进程永远停在“关闭中”。
            t.setDaemon(true);
            // 低于渲染线程的优先级：后台任务不该抢渲染线程的 CPU 时间片。
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }
        LumenProfiler.log().info("[Lumen] 异步调度器已启动：{} 个 worker，队列容量 {}", workers, queueCapacity);
    }

    public static void initialize() {
        if (instance != null) {
            return;
        }
        synchronized (AsyncTaskDispatcher.class) {
            if (instance != null) {
                return;
            }
            int cores = Runtime.getRuntime().availableProcessors();
            // 留一个核给渲染线程、一个给 GC，剩下的才是我们的。
            // 取 min 是为了避免在 32 核机器上开出一堆互相抢缓存的线程。
            int workers = Math.max(1, Math.min(4, cores - 2));
            instance = new AsyncTaskDispatcher(workers, LumenConfig.asyncQueueCapacity);
        }
    }

    public static AsyncTaskDispatcher get() {
        AsyncTaskDispatcher local = instance;
        if (local == null) {
            initialize();
            local = instance;
        }
        return local;
    }

    /** 当前世界代次。任务提交时打上，交付时比对。 */
    public int generation() {
        return generation.get();
    }

    /**
     * 仅供测试使用：创建一个独立于全局单例的调度器。
     *
     * <p>生产代码一律走 {@link #get()}。测试需要独立实例才能验证
     * “队列满不阻塞”“代次切换丢弃结果”这类行为，而不污染全局状态。
     */
    public static AsyncTaskDispatcher createForTesting(int workers, int queueCapacity) {
        return new AsyncTaskDispatcher(workers, queueCapacity);
    }

    /**
     * 世界切换时调用：作废所有在途结果。
     *
     * <p>注意这里<b>不</b>尝试中断正在执行的任务。中断一个正在算 A* 的线程
     * 意味着它可能停在任意一行上、留下半初始化的状态；让它自然跑完、
     * 然后把结果丢掉，是更安全也更简单的选择。
     */
    public void bumpGeneration() {
        int now = generation.incrementAndGet();
        LumenProfiler.log().debug("[Lumen] 世界代次 -> {}", now);
    }

    /**
     * 提交任务。队列满时丢弃并返回 {@code false}——绝不阻塞。
     */
    public boolean submit(AsyncTask<?> task, Priority priority) {
        if (!running || task == null) {
            return false;
        }
        task.generation = generation.get();
        task.sequence = sequence.incrementAndGet();
        submitted.incrementAndGet();

        QueuedTask queued = new QueuedTask(task);
        BlockingQueue<QueuedTask> target = priority == Priority.REALTIME ? realtime : background;
        if (target.offer(queued)) {
            return true;
        }

        // 队列满：这是背压信号，不是错误。丢弃 + 计数，主线程继续跑。
        dropped.incrementAndGet();
        task.onDiscard(AsyncTask.DiscardReason.BACKLOG);
        return false;
    }

    /**
     * 主线程每帧调用：把已完成的结果交付出去。
     *
     * @param maxDeliveries 本帧最多交付多少个结果。这是给帧时间上的保险丝：
     *                      一次交付上千个区块网格会让这一帧明显变长，
     *                      分摊到后续几帧反而更平滑。
     * @return 实际交付数量
     */
    public int drainMainThread(int maxDeliveries) {
        int budget = maxDeliveries;
        int sizeNow = completedSize.get();
        if (sizeNow < budget) {
            budget = sizeNow;
        }
        if (budget <= 0) {
            return 0;
        }

        int now = generation.get();
        int count = 0;
        while (count < budget) {
            Completed c = completed.poll();
            if (c == null) {
                break;
            }
            completedSize.decrementAndGet();
            count++;

            if (c.error != null) {
                failed.incrementAndGet();
                // 后台任务的异常绝不能静默吞掉：它会表现成“某个功能莫名不工作”。
                LumenProfiler.log().error("[Lumen] 异步任务 #" + c.task.sequence + " 执行失败", c.error);
                safeDiscard(c.task, AsyncTask.DiscardReason.ERROR);
                continue;
            }
            if (c.task.generation != now) {
                discarded.incrementAndGet();
                safeDiscard(c.task, AsyncTask.DiscardReason.STALE_GENERATION);
                continue;
            }
            if (c.task.cancelled) {
                discarded.incrementAndGet();
                safeDiscard(c.task, AsyncTask.DiscardReason.CANCELLED);
                continue;
            }
            delivered.incrementAndGet();
            safeDeliver(c.task, c.result);
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void safeDeliver(AsyncTask task, Object result) {
        try {
            if (result != null) {
                task.deliver(result);
            }
        } catch (Throwable t) {
            LumenProfiler.log().error("[Lumen] 交付异步结果时抛异常（已隔离，不影响主线程）", t);
        }
    }

    private void safeDiscard(AsyncTask<?> task, AsyncTask.DiscardReason reason) {
        try {
            task.onDiscard(reason);
        } catch (Throwable t) {
            LumenProfiler.log().error("[Lumen] 丢弃异步任务时抛异常（已隔离）", t);
        }
    }

    /** 有待交付结果的数量，可用于自适应降低本帧的提交速率。 */
    public int pendingResults() {
        return completedSize.get();
    }

    public int queuedTasks() {
        return realtime.size() + background.size();
    }

    /**
     * 关闭：让 worker 退出并丢弃剩余结果。
     *
     * <p>不调用 {@code shutdownNow()}，也不 join —— 关游戏时主线程没有时间等后台。
     * worker 是守护线程，进程退出时会被直接终止，这是可以接受的：
     * 此刻所有结果都已经没有意义。
     */
    public void shutdown() {
        running = false;
        completed.clear();
        completedSize.set(0);
        realtime.clear();
        background.clear();
    }

    private final class Worker implements Runnable {
        @Override
        public void run() {
            while (running) {
                QueuedTask queued;
                try {
                    queued = nextTask();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // queued == null 表示「两个队列都空，等待超时」，
                // 这是空闲的正常状态，必须继续循环等下一个任务。
                //
                // 这里曾经写成 return，是一个极难发现的活性 bug：
                // worker 启动后若 50ms 内没有任何任务到来，poll 超时返回 null，
                // 线程就此永久退出 —— 之后提交的所有任务都堆在队列里没人取。
                // 它不报错、不崩溃，只表现为「异步功能整体失效」，
                // 而且自测若在启动后立刻提交任务就完全测不出来。
                if (queued == null) {
                    continue;
                }
                execute(queued.task);
            }
        }

        /**
         * REALTIME 优先，但按固定步长强制让位给 BACKGROUND，避免寻路饿死。
         *
         * <p>这里有个容易写错的地方：让位计数必须<b>每轮都自增</b>，
         * 而不是只在取到前台任务时自增。否则在“前台持续有任务、后台也有任务”
         * 的长跑图过程中，计数会在两轮之间反复回落，永远到不了步长 ——
         * 后台队列（寻路、流体）就会被完全饿死，生物整场站着不动。
         */
        private QueuedTask nextTask() throws InterruptedException {
            fairnessCounter++;

            // 每到步长轮次，优先取一个后台任务。
            if (fairnessCounter % FAIRNESS_STRIDE == 0) {
                QueuedTask bg = background.poll();
                if (bg != null) {
                    return bg;
                }
            }

            QueuedTask task = realtime.poll();
            if (task != null) {
                return task;
            }

            // 前台空了：阻塞等后台。worker 阻塞是无害的，它本来就没有别的事做。
            return background.poll(50, TimeUnit.MILLISECONDS);
        }

        private void execute(AsyncTask<?> task) {
            if (!running || task.cancelled) {
                return;
            }
            task.startedAtNanos = System.nanoTime();
            Object result = null;
            Throwable error = null;
            try {
                result = task.compute();
            } catch (Throwable t) {
                error = t;
            } finally {
                long elapsed = System.nanoTime() - task.startedAtNanos;
                long budgetNanos = LumenConfig.asyncTaskBudgetMs * 1_000_000L;
                if (elapsed > budgetNanos) {
                    timedOut.incrementAndGet();
                    // 超预算通常意味着某次计算退化（例如寻路目标不可达导致的全图搜索）。
                    // 记录但不惩罚：惩罚会让系统在负载高时拒绝干活，反而更慢。
                    if (LumenConfig.verboseDebug) {
                        LumenProfiler.log().warn("[Lumen] 异步任务 #{} 耗时 {} ms，超出预算 {} ms",
                                task.sequence, elapsed / 1_000_000L, LumenConfig.asyncTaskBudgetMs);
                    }
                }
                task.startedAtNanos = 0L;
            }

            // 结果入队：无界 put 会让内存无限增长，这里用限流 + 丢弃。
            if (completedSize.get() >= COMPLETED_CAPACITY) {
                discarded.incrementAndGet();
                // 必须走 onDiscard，不能只是静默丢弃。
                //
                // 这个回调不是可选的：寻路任务靠它清掉"该实体的冷却记录"。
                // 如果这里悄悄丢掉结果而不通知，那个实体会一直留在冷却表里，
                // 于是"请求已提交、结果被丢、又因为冷却不能再提交" ——
                // 表现为生物永久站着不动。这类 bug 只在主线程卡顿时出现，
                // 平时复现不了，极难排查。
                safeDiscard(task, AsyncTask.DiscardReason.BACKLOG);
                return;
            }
            completed.offer(new Completed(task, result, error));
            completedSize.incrementAndGet();
        }
    }

    private static final class QueuedTask {
        final AsyncTask<?> task;

        QueuedTask(AsyncTask<?> task) {
            this.task = task;
        }
    }

    private static final class Completed {
        final AsyncTask<?> task;
        final Object result;
        final Throwable error;

        Completed(AsyncTask<?> task, Object result, Throwable error) {
            this.task = task;
            this.result = result;
            this.error = error;
        }
    }

    /** 供 F3 与日志使用的统计摘要。 */
    public String describe() {
        return "queued=" + queuedTasks()
                + " done=" + pendingResults()
                + " submitted=" + submitted.get()
                + " delivered=" + delivered.get()
                + " dropped=" + dropped.get()
                + " stale=" + discarded.get()
                + " failed=" + failed.get()
                + " slow=" + timedOut.get();
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long deliveredCount() {
        return delivered.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long slowTaskCount() {
        return timedOut.get();
    }

    public long failedCount() {
        return failed.get();
    }
}
