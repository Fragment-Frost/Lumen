package top.fpvp.lumen.thread;

import top.fpvp.lumen.LumenConfig;

/**
 * 区块网格构建线程数的自适应公式。
 *
 * <p>抽成独立类的原因：同一个公式同时被 {@code MixinChunkRenderDispatcher}
 * （真正决定开几个线程）和启动日志（打印“实际会开几个”）使用。
 * 两处各写一遍必然漂移 —— 日志说 10 个、实际开 6 个，比不打印还糟。
 *
 * <h3>公式来源</h3>
 *
 * <p>来自对 Lunar Client 1.9 的实测反汇编（其 {@code ChunkRenderDispatcher}
 * 构造函数），在 12 核 / 8G 堆上实测开出 10 个 worker、80 个构建器。
 *
 * <pre>
 *   memoryBudget = max(1, maxMemory * 0.3 / 10MB)
 *   threads      = max(1, min(cores - 2, memoryBudget / 5))
 *   builders     = max(1, min(threads * 8, memoryBudget))
 * </pre>
 *
 * <p>三个设计要点：
 * <ul>
 *   <li><b>用堆大小反向约束线程数</b>：每个构建器持有按图层切分的顶点缓冲，
 *       是实打实的内存开销。小内存机器开满线程会导致频繁 GC 甚至 OOM，
 *       净帧率反而下降。只用 {@code cores - 2} 无法避免这点。</li>
 *   <li><b>{@code cores - 2}</b>：留两个核给主线程（tick + 渲染 + GL 上传）
 *       与 GC，两者被 worker 挤占时帧时间会明显抖动。</li>
 *   <li><b>{@code builders >= threads}</b> 是硬性不变量</b>，见
 *       {@link #builderCount(int)}。</li>
 * </ul>
 */
public final class ChunkThreadTuning {

    /** 原版 worker 线程数。 */
    public static final int VANILLA_WORKERS = 2;

    /** 原版构建器池容量。 */
    public static final int VANILLA_BUILDERS = 5;

    /** 每个构建器预估占用的内存单位（10MB）。 */
    private static final long BUILDER_COST_BYTES = 10L * 1024L * 1024L;

    /** 允许用于构建器的堆比例。留 70% 给区块数据、纹理、顶点缓冲等。 */
    private static final double MEMORY_FRACTION = 0.3D;

    /** 每个 worker 需要的内存预算份额。 */
    private static final int BUDGET_PER_WORKER = 5;

    /** 构建器相对 worker 的倍数：一个 worker 的在途任务可能同时占用多个。 */
    private static final int BUILDERS_PER_WORKER = 8;

    private ChunkThreadTuning() {
    }

    /** 内存预算：把堆大小换算成“够开几个构建器”的单位数。 */
    public static int memoryBudget() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        return (int) Math.max(1L, (long) (maxMemory * MEMORY_FRACTION / BUILDER_COST_BYTES));
    }

    /** 可用处理器数。 */
    public static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * 期望的 worker 线程数。
     *
     * <p>配置语义：{@code -1} = 自适应；{@code 0} = 强制原版 2 个；
     * {@code >0} = 强制该值（受核数封顶）。
     */
    public static int workerCount() {
        if (!LumenConfig.enabled) {
            return VANILLA_WORKERS;
        }

        int configured = LumenConfig.chunkBuilderThreads;
        if (configured == 0) {
            return VANILLA_WORKERS;
        }
        int cores = cores();
        if (configured > 0) {
            return Math.min(configured, cores);
        }

        int byCpu = cores - 2;
        int byMemory = memoryBudget() / BUDGET_PER_WORKER;
        return Math.max(1, Math.min(byCpu, byMemory));
    }

    /**
     * 构建器池容量。
     *
     * <p><b>必须 >= {@link #workerCount()}</b>：
     * {@code ChunkRenderDispatcher#allocateRenderBuilder()} 是无条件的
     * {@code BlockingQueue#take()}，池空时会永久阻塞。worker 数超过构建器数 =
     * 多出来的 worker 永远卡在 {@code take()} 上，且不崩溃、不报错。
     *
     * <p>所以这里用 {@code max(computed, workers + 1)} 兜底：公式算出的值再小，
     * 也保证每个 worker 至少一个构建器，外加 1 个给主线程的同步构建路径。
     */
    public static int builderCount() {
        if (!LumenConfig.enabled || LumenConfig.chunkBuilderThreads == 0) {
            return VANILLA_BUILDERS;
        }

        int workers = workerCount();
        int byThreads = workers * BUILDERS_PER_WORKER;
        int computed = Math.max(1, Math.min(byThreads, memoryBudget()));
        return Math.max(computed, workers + 1);
    }
}
