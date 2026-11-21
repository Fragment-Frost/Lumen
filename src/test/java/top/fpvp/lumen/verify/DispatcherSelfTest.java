package top.fpvp.lumen.verify;

import top.fpvp.lumen.thread.AsyncTask;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步调度器的并发行为自测。
 *
 * <p>调度器是本项目里唯一真正涉及多线程的部分，也是最容易出现
 * “不崩溃但悄悄卡住”的地方。这些用例都不需要 Minecraft 运行时，
 * 因此可以在普通 JVM 里跑，作为回归测试的一部分。
 *
 * <p>覆盖的行为：
 * <ol>
 *   <li>队列满时提交<b>不阻塞</b>（背压不能变成主线程停顿）；</li>
 *   <li>所有已提交任务最终都会被交付或明确丢弃，不会静默消失；</li>
 *   <li>后台任务抛异常不会拖垮 worker；</li>
 *   <li>代次切换后旧结果被丢弃且回调被触发；</li>
 *   <li>后台任务<b>不会</b>被前台任务饿死。</li>
 * </ol>
 */
public final class DispatcherSelfTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        testWorkerSurvivesIdle();
        testQueueFullDoesNotBlock();
        testAllTasksAccountedFor();
        testExceptionDoesNotKillWorker();
        testStaleGenerationDiscarded();
        testBackgroundNotStarved();

        System.out.println();
        if (failures == 0) {
            System.out.println("All dispatcher self-tests passed.");
        } else {
            System.out.println(failures + " dispatcher self-test(s) FAILED.");
            System.exit(1);
        }
    }

    /**
     * 回归测试：worker 在空闲一段时间后必须仍然存活。
     *
     * <p>这条用例针对一个曾经存在、且极其隐蔽的活性 bug：
     * {@code Worker#run()} 把 {@code nextTask()} 返回 {@code null}
     * （两个队列都空、等待超时）误判为「该退出了」并 {@code return}，
     * 于是 worker 在启动 50ms 后若没等到任务就永久退出。
     *
     * <p>后果是<b>所有异步功能静默失效</b>：任务照样入队、队列长度在涨，
     * 但永远没人执行；不崩溃、不报错、日志干净。
     *
     * <p>之所以难被测出来：只要测试在启动后<b>立刻</b>提交任务，
     * 就能抢在 50ms 超时之前完成。因此这条用例刻意<b>先空等</b>一段时间，
     * 再提交任务。
     */
    private static void testWorkerSurvivesIdle() throws Exception {
        AsyncTaskDispatcher d = newDispatcher(8);
        try {
            // 先让 worker 空转，越过多轮 poll 超时。
            Thread.sleep(400L);

            final AtomicInteger computed = new AtomicInteger();
            final String[] runner = new String[1];
            d.submit(new AsyncTask<Object>() {
                @Override
                protected Object compute() {
                    runner[0] = Thread.currentThread().getName();
                    computed.incrementAndGet();
                    return Boolean.TRUE;
                }
            }, AsyncTaskDispatcher.Priority.REALTIME);

            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline && computed.get() == 0) {
                Thread.sleep(10L);
            }

            check("worker survives idle period", computed.get() == 1,
                    "空闲 400ms 后提交的任务未被任何 worker 执行（computed="
                            + computed.get() + "，说明 worker 已退出）");
            // 顺带确认真的是 worker 线程执行的，而不是被主线程代劳。
            check("task ran on worker thread",
                    runner[0] != null && runner[0].startsWith("Lumen-Worker"),
                    "实际执行线程=" + runner[0]);
        } finally {
            d.shutdown();
        }
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("  OK   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }

    /** 队列满时必须立刻返回 false，而不是阻塞调用线程。 */
    private static void testQueueFullDoesNotBlock() {
        AsyncTaskDispatcher d = newDispatcher(32);
        try {
            // 提交远多于队列容量的任务，全部都应立刻返回。
            long start = System.nanoTime();
            int accepted = 0;
            for (int i = 0; i < 10_000; i++) {
                if (d.submit(sleepTask(50), AsyncTaskDispatcher.Priority.REALTIME)) {
                    accepted++;
                }
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            check("queue-full submit does not block", elapsedMs < 2_000L,
                    "took " + elapsedMs + "ms for 10000 submits (accepted=" + accepted + ")");
        } finally {
            d.shutdown();
        }
    }

    /** 交付 + 丢弃的总数必须等于尝试提交的总数，不能有任务神秘消失。 */
    private static void testAllTasksAccountedFor() throws Exception {
        AsyncTaskDispatcher d = newDispatcher(64);
        try {
            final AtomicInteger delivered = new AtomicInteger();
            final AtomicInteger discarded = new AtomicInteger();

            // 注意统计口径：onDiscard 既会在"提交被拒（队列满）"时同步触发，
            // 也会在"结果交付时发现代次过期"时触发。因此对账的基数必须是
            // **尝试提交的总数**，而不是被接受的数量 —— 否则被拒的任务
            // 会被算成"多出来的丢弃"。
            final int attempted = 200;
            for (int i = 0; i < attempted; i++) {
                d.submit(new CountingTask(delivered, discarded), AsyncTaskDispatcher.Priority.BACKGROUND);
            }

            for (int spin = 0; spin < 400; spin++) {
                d.drainMainThread(64);
                if (d.pendingResults() == 0 && d.queuedTasks() == 0) {
                    break;
                }
                Thread.sleep(5L);
            }

            int accounted = delivered.get() + discarded.get()
                    + d.pendingResults() + d.queuedTasks();
            check("all attempted tasks accounted for", accounted == attempted,
                    "attempted=" + attempted + " delivered=" + delivered.get()
                            + " discarded=" + discarded.get()
                            + " pendingResults=" + d.pendingResults()
                            + " queued=" + d.queuedTasks());
        } finally {
            d.shutdown();
        }
    }

    /** 后台任务抛异常后，worker 仍然能继续处理后续任务。 */
    private static void testExceptionDoesNotKillWorker() throws Exception {
        // 容量必须足够容纳全部 40 个任务：队列满时提交会被拒，
        // 那测的就不再是"异常后 worker 是否存活"，而是队列容量。
        AsyncTaskDispatcher d = newDispatcher(128);
        try {
            final AtomicInteger ok = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                d.submit(new AsyncTask<Object>() {
                    @Override
                    protected Object compute() {
                        throw new IllegalStateException("intentional");
                    }
                }, AsyncTaskDispatcher.Priority.BACKGROUND);
            }
            // 异常任务之后再提交正常任务，它们必须还能被处理。
            for (int i = 0; i < 20; i++) {
                d.submit(new AsyncTask<Object>() {
                    @Override
                    protected Object compute() {
                        ok.incrementAndGet();
                        return Boolean.TRUE;
                    }
                }, AsyncTaskDispatcher.Priority.BACKGROUND);
            }

            for (int spin = 0; spin < 400 && ok.get() < 20; spin++) {
                d.drainMainThread(64);
                Thread.sleep(5L);
            }
            check("worker survives task exception", ok.get() == 20,
                    "completed " + ok.get() + "/20 normal tasks after exceptions");
        } finally {
            d.shutdown();
        }
    }

    /** 代次切换后，旧结果必须被丢弃并触发 onDiscard。 */
    private static void testStaleGenerationDiscarded() throws Exception {
        AsyncTaskDispatcher d = newDispatcher(8);
        try {
            final AtomicInteger discarded = new AtomicInteger();
            final AtomicInteger delivered = new AtomicInteger();

            d.submit(new AsyncTask<Object>() {
                @Override
                protected Object compute() {
                    return Boolean.TRUE;
                }

                @Override
                protected void deliver(Object result) {
                    delivered.incrementAndGet();
                }

                @Override
                protected void onDiscard(DiscardReason reason) {
                    discarded.incrementAndGet();
                }
            }, AsyncTaskDispatcher.Priority.REALTIME);

            // 等任务算完、结果入队，再切代次。
            for (int spin = 0; spin < 200 && d.pendingResults() == 0; spin++) {
                Thread.sleep(5L);
            }
            d.bumpGeneration();
            d.drainMainThread(16);

            check("stale generation discarded", discarded.get() == 1 && delivered.get() == 0,
                    "discarded=" + discarded.get() + " delivered=" + delivered.get());
        } finally {
            d.shutdown();
        }
    }

    /** 后台任务持续积压时，仍然必须被处理（不能被前台饿死）。 */
    private static void testBackgroundNotStarved() throws Exception {
        AsyncTaskDispatcher d = newDispatcher(64);
        try {
            final AtomicInteger bgDone = new AtomicInteger();

            // 先塞满后台队列。
            for (int i = 0; i < 40; i++) {
                d.submit(new AsyncTask<Object>() {
                    @Override
                    protected Object compute() {
                        bgDone.incrementAndGet();
                        return null;
                    }
                }, AsyncTaskDispatcher.Priority.BACKGROUND);
            }

            // 然后持续灌前台任务，模拟跑图时区块网格占满 worker 的情况。
            final long deadline = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < deadline && bgDone.get() < 40) {
                d.submit(new AsyncTask<Object>() {
                    @Override
                    protected Object compute() {
                        return null;
                    }
                }, AsyncTaskDispatcher.Priority.REALTIME);
                d.drainMainThread(64);
                Thread.sleep(1L);
            }

            check("background tasks not starved", bgDone.get() == 40,
                    "background completed " + bgDone.get() + "/40 while realtime queue was saturated");
        } finally {
            d.shutdown();
        }
    }

    /** 构造一个不影响全局单例的独立调度器。 */
    private static AsyncTaskDispatcher newDispatcher(int capacity) {
        return AsyncTaskDispatcher.createForTesting(2, capacity);
    }

    private static AsyncTask<Object> sleepTask(final long millis) {
        return new AsyncTask<Object>() {
            @Override
            protected Object compute() throws Exception {
                Thread.sleep(millis);
                return null;
            }
        };
    }

    private static final class CountingTask extends AsyncTask<Object> {
        private final AtomicInteger delivered;
        private final AtomicInteger discarded;

        CountingTask(AtomicInteger delivered, AtomicInteger discarded) {
            this.delivered = delivered;
            this.discarded = discarded;
        }

        @Override
        protected Object compute() {
            return Boolean.TRUE;
        }

        @Override
        protected void deliver(Object result) {
            delivered.incrementAndGet();
        }

        @Override
        protected void onDiscard(DiscardReason reason) {
            discarded.incrementAndGet();
        }
    }
}
