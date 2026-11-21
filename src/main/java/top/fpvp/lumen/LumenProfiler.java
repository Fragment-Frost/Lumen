package top.fpvp.lumen;

/**
 * 全局轻量统计。
 *
 * <p>这里的统计全部是 {@code int}/{@code long} 自增，没有锁、没有对象分配，
 * 可以放心地在每帧、每方块的热路径里调用。
 *
 * <p><b>本项目不使用日志。</b>所有原本通过 log4j 输出的信息已移除：
 * <ul>
 *   <li>启动配置摘要 —— 改为配置界面（{@code LumenGuiConfig}）里可查；</li>
 *   <li>mod 冲突提示 —— 改为配置项的说明文本与 {@link #debugLine()} 的
 *       状态位；</li>
 *   <li>异常堆栈 —— 见各调用点：必须隔离的异常改为"记录到计数器 + 注释说明"，</li>
 * </ul>
 *
 * <p>这样做的代价必须写清楚：<b>运行期出问题时不再有日志可查</b>。
 * 排查只能靠 {@link #debugLine()} 的计数与配置界面里的状态。
 * 这是一个明确的取舍，不是遗漏。
 */
public final class LumenProfiler {

    // 剔除统计（每帧重置）
    public static int visibleTileEntities;
    public static int culledTileEntities;
    public static int visibleEntities;
    public static int culledEntities;
    public static int visibleParticles;
    public static int culledParticles;
    public static int raysCast;

    // 渲染统计
    public static int hudCacheHits;
    public static int hudCacheMisses;

    // 世界统计
    public static int fluidCacheHits;
    public static int fluidCacheMisses;

    // 异常计数（替代原来的日志输出）
    //
    // 这些计数只在"本该被隔离的异常"发生时自增。没有日志之后，
    // 它们是判断"有没有东西在偷偷失败"的唯一手段，所以不能省。
    /** 异步任务执行抛出的异常数。 */
    public static long asyncTaskErrors;
    /** 异步结果交付时抛出的异常数。 */
    public static long asyncDeliverErrors;
    /** 异步任务丢弃回调抛出的异常数。 */
    public static long asyncDiscardErrors;
    /** 纹理降采样失败次数。 */
    public static long textureLodFailures;
    /** 配置读取失败次数。 */
    public static int configErrors;

    private static long frameStartNanos;
    private static long accumulatedFrameNanos;
    private static int frames;

    private LumenProfiler() {
    }

    public static void beginFrame() {
        frameStartNanos = System.nanoTime();
    }

    public static void endFrame() {
        if (frameStartNanos != 0L) {
            accumulatedFrameNanos += System.nanoTime() - frameStartNanos;
            frames++;
        }
    }

    /**
     * 取出并清空本帧的剔除计数。
     *
     * <p>刻意用“返回 void + 调用方读字段”的形态：HUD 每帧都要读这些数字，
     * 如果做成 volatile 或加锁，就会在渲染线程和客户端线程之间制造伪共享与
     * 内存屏障，抵消掉剔除省下来的时间。
     */
    public static void resetFrameCounters() {
        visibleTileEntities = 0;
        culledTileEntities = 0;
        visibleEntities = 0;
        culledEntities = 0;
        visibleParticles = 0;
        culledParticles = 0;
        raysCast = 0;
        hudCacheHits = 0;
        hudCacheMisses = 0;
    }

    public static double averageFrameMillis() {
        return frames == 0 ? 0.0D : (accumulatedFrameNanos / 1_000_000.0D) / frames;
    }

    public static int sampledFrames() {
        return frames;
    }

    /**
     * 供 F3 调试行使用的格式化输出；每帧只调用一次，分配可以接受。
     *
     * <p>末尾的 {@code Err} 段替代了原来的日志：没有日志之后，
     * 这是唯一能看到"有没有东西在偷偷失败"的地方。全 0 表示一切正常。
     */
    public static String debugLine() {
        long errors = asyncTaskErrors + asyncDeliverErrors + asyncDiscardErrors
                + textureLodFailures + configErrors;
        String base = String.format(
                "Lumen: TESR %d/%d  Entity %d/%d  Part %d/%d  Ray %d  HUD %d%%  Fluid %d%%",
                visibleTileEntities, visibleTileEntities + culledTileEntities,
                visibleEntities, visibleEntities + culledEntities,
                visibleParticles, visibleParticles + culledParticles,
                raysCast,
                hitPercent(hudCacheHits, hudCacheMisses),
                hitPercent(fluidCacheHits, fluidCacheMisses));
        if (errors == 0L) {
            return base;
        }
        return base + "  Err("
                + "task=" + asyncTaskErrors
                + " deliver=" + asyncDeliverErrors
                + " discard=" + asyncDiscardErrors
                + " tex=" + textureLodFailures
                + " cfg=" + configErrors + ")";
    }

    private static int hitPercent(int hits, int misses) {
        int total = hits + misses;
        return total == 0 ? 0 : (int) (100L * hits / total);
    }
}
