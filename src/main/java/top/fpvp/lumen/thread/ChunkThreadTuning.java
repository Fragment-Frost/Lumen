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

    /**
     * 区块构建模式。
     *
     * <p>这三个模式回答的是同一个问题的三种答案：<b>主线程要不要等区块构建</b>。
     *
     * <ul>
     *   <li>{@link #THREADED}（线程化）：完全不等。主线程继续渲染，
     *       区块构建在 worker 上完成。极端情况下会看到"白色空缺区块"
     *       短暂存在，但帧率最平滑。</li>
     *   <li>{@link #SEMI_BLOCKING}（半堵塞）：主线程在每帧的构建阶段
     *       只等一个<b>很短的时间预算</b>，超时就走。折中方案。</li>
     *   <li>{@link #FULL_BLOCKING}（全堵塞）：主线程等到所有排队构建完成
     *       才继续。绝不会看到白色空缺，但跑图时帧率会有明显顿挫。
     *       适合录屏/截图而不是日常游玩。</li>
     * </ul>
     *
     * <p>注意这三个模式<b>不改变构建的并行度</b> —— 那由线程数控制。
     * 它们改变的是"主线程愿意为等待构建结果付出多少时间"。
     */
    public enum Mode {
        /** 完全异步，主线程从不等待。 */
        THREADED,
        /** 主线程每帧只等一小段时间。 */
        SEMI_BLOCKING,
        /** 主线程等到全部构建完成。 */
        FULL_BLOCKING
    }

    /**
     * 把配置值解析成模式。
     *
     * <p>配置用字符串而不是枚举：Forge 的 {@code getString} 允许填任意值，
     * 填错时应当<b>退回默认</b>而不是崩溃。这里对大小写和常见别名都做了兼容。
     */
    public static Mode mode() {
        if (!LumenConfig.enabled) {
            return Mode.THREADED;
        }
        String raw = LumenConfig.chunkBuildMode;
        if (raw == null) {
            return Mode.SEMI_BLOCKING;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("threaded") || v.equals("async") || v.equals("线程化")) {
            return Mode.THREADED;
        }
        if (v.equals("full") || v.equals("full_blocking") || v.equals("blocking")
                || v.equals("全堵塞")) {
            return Mode.FULL_BLOCKING;
        }
        if (v.equals("semi") || v.equals("semi_blocking") || v.equals("半堵塞")) {
            return Mode.SEMI_BLOCKING;
        }
        return Mode.SEMI_BLOCKING;
    }

    /**
     * 主线程每帧愿意等待区块构建的时间预算（毫秒）。
     *
     * <p>三档的取值理由：
     * <ul>
     *   <li>线程化：0（不等）；</li>
     *   <li>半堵塞：用一个很小的值（默认 2ms）。60fps 的帧预算是 16.6ms，
     *       2ms 是它的 12% —— 低到不会造成可感知的顿挫，
     *       又足以让"几乎已完成"的构建在本帧就绪；</li>
     *   <li>全堵塞：用一个足够大的值（默认 50ms）。它本来就不追求帧率，
     *       而是要保证没有白色空缺。</li>
     * </ul>
     */
    public static long waitBudgetMs() {
        Mode mode = mode();
        if (mode == Mode.THREADED) {
            return 0L;
        }
        if (mode == Mode.FULL_BLOCKING) {
            return Math.max(1L, LumenConfig.chunkBuildWaitMsFull);
        }
        return Math.max(0L, LumenConfig.chunkBuildWaitMsSemi);
    }

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
        int auto = Math.max(1, Math.min(byCpu, byMemory));

        // 全堵塞模式下主线程要等 worker 完成，worker 太少会让等待时间
        // 直接翻倍。此时不占用"留两个核"的那部分余量 ——
        // 因为等待期间主线程本来就是空闲的，不存在"被 worker 挤占"的问题。
        if (mode() == Mode.FULL_BLOCKING) {
            auto = Math.max(auto, Math.min(cores - 1, byMemory));
        }
        return auto;
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
