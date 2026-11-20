package top.fpvp.lumen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 全局日志与轻量统计。
 *
 * <p>这里的统计全部是 {@code int}/{@code long} 自增，没有锁、没有对象分配，
 * 可以放心地在每帧、每方块的热路径里调用。
 */
public final class LumenProfiler {

    private static final Logger LOG = LogManager.getLogger("Lumen");

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

    private static long frameStartNanos;
    private static long accumulatedFrameNanos;
    private static int frames;

    private LumenProfiler() {
    }

    public static Logger log() {
        return LOG;
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

    /** 供 F3 调试行使用的格式化输出；每帧只调用一次，分配可以接受。 */
    public static String debugLine() {
        return String.format(
                "Lumen: TESR %d/%d  Entity %d/%d  Part %d/%d  Ray %d  HUD %d%%  Fluid %d%%",
                visibleTileEntities, visibleTileEntities + culledTileEntities,
                visibleEntities, visibleEntities + culledEntities,
                visibleParticles, visibleParticles + culledParticles,
                raysCast,
                hitPercent(hudCacheHits, hudCacheMisses),
                hitPercent(fluidCacheHits, fluidCacheMisses));
    }

    private static int hitPercent(int hits, int misses) {
        int total = hits + misses;
        return total == 0 ? 0 : (int) (100L * hits / total);
    }
}
