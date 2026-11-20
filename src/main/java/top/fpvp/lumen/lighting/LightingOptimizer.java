package top.fpvp.lumen.lighting;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.util.LongHashSet;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Starlight 风格的光照更新批处理器（1.8.9 版）。
 *
 * <p>原版 1.8.9 的 {@code World#setBlockState} 每改一个方块就立刻调用
 * {@code checkLight}，而 {@code checkLight} 会对天空光和方块光各做一次
 * 6 邻域洪泛（flood fill）。当同一 tick 内连续改动同一片区域（爆炸、
 * 多方块结构生成、跑图时的区块填充）时，同一批位置会被反复重算，
 * 这是 1.8.9 光照卡顿的主要来源。
 *
 * <p>本类的做法与 Starlight 一致：把这些「即将发生的光照校验」收集到
 * 队列里，按 {@link BlockPos#toLong()} 去重，在 tick 内统一冲刷一次。
 * 去重后同一位置只算一次，排序后又让相邻位置在内存上连续处理，
 * 从而显著减少洪泛次数。
 *
 * <p>安全性：冲刷点在 {@code updateBlocks} 结束与 {@code tick} 结束时，
 * 都发生在渲染之前，所以不会出现「渲染时光照是旧的」的问题；
 * 队列超过 {@link #MAX_QUEUE} 时立即回退为原版即时计算，避免爆内存。
 */
public final class LightingOptimizer {

    /** 队列上限，超过则放弃批处理、交还原版立即执行。 */
    private static final int MAX_QUEUE = 8192;

    /** 每个世界一个实例；用弱引用避免世界卸载后泄漏。 */
    private static final Map<World, LightingOptimizer> INSTANCES = new WeakHashMap<World, LightingOptimizer>();

    private final World world;

    /**
     * 待处理位置，以 BlockPos 的 long 编码存储，天然去重。
     *
     * <p>用 {@link LongHashSet} 而不是 {@code HashSet<Long>}：后者每次
     * {@code add} 都要把 long 装箱成 {@link Long}，而方块坐标几乎全部落在
     * {@code LongCache}（-128..127）之外，于是每个位置都是一个新对象。
     * 一次爆炸/结构生成可以排入上千个位置，每次 flush 都会产生上千个短命对象，
     * 直接把年轻代填满 —— 这正是“优化本身制造 GC 压力”的典型场景。
     */
    private final LongHashSet pending = new LongHashSet(1024);

    /** 复用的排序缓冲，避免每次 flush 都 new 一个 long[]。 */
    private long[] flushBuffer = new long[1024];

    /** 正在冲刷，期间产生的 checkLight 必须立即执行，否则会漏算。 */
    private boolean flushing;

    // 统计信息
    private long totalQueued;
    private long totalCoalesced;
    private long totalChecks;
    private long totalFlushes;

    private LightingOptimizer(World world) {
        this.world = world;
    }

    public static LightingOptimizer get(World world) {
        synchronized (INSTANCES) {
            LightingOptimizer optimizer = INSTANCES.get(world);
            if (optimizer == null) {
                optimizer = new LightingOptimizer(world);
                INSTANCES.put(world, optimizer);
            }
            return optimizer;
        }
    }

    /**
     * 尝试接管一次光照校验。
     *
     * @return {@code true} 表示已接管（调用方应跳过原版 {@code checkLight}）；
     *         {@code false} 表示调用方应照常立即执行。
     */
    public boolean deferCheckLight(BlockPos pos) {
        if (!LumenConfig.enableLightBatching || flushing) {
            return false;
        }
        if (pending.size() >= MAX_QUEUE) {
            // 队列过载：放弃批处理，立即冲刷后交还原版
            flush();
            return false;
        }

        if (pending.add(pos.toLong())) {
            totalQueued++;
        } else {
            // 同一 tick 内同一位置重复请求，合并掉
            totalCoalesced++;
        }
        return true;
    }

    /**
     * 冲刷所有排队中的光照校验。
     */
    public void flush() {
        if (flushing || pending.isEmpty()) {
            return;
        }

        flushing = true;
        try {
            int count = pending.size();
            if (flushBuffer.length < count) {
                flushBuffer = new long[Math.max(count, flushBuffer.length * 2)];
            }
            // drainTo 直接写进复用的数组并清空集合，没有迭代器、没有装箱。
            int drained = pending.drainTo(flushBuffer);

            // 排序让相邻坐标连续处理，提高缓存命中率（Starlight 同样做法）。
            Arrays.sort(flushBuffer, 0, drained);

            for (int i = 0; i < drained; i++) {
                world.checkLight(BlockPos.fromLong(flushBuffer[i]));
                totalChecks++;
            }
            totalFlushes++;
        } finally {
            flushing = false;
        }
    }

    public int getPendingCount() {
        return pending.size();
    }

    public long getTotalQueued() {
        return totalQueued;
    }

    public long getTotalCoalesced() {
        return totalCoalesced;
    }

    public long getTotalChecks() {
        return totalChecks;
    }

    public long getTotalFlushes() {
        return totalFlushes;
    }

    /** 供统计/调试输出。 */
    public String describe() {
        return "queued=" + totalQueued
                + " coalesced=" + totalCoalesced
                + " checks=" + totalChecks
                + " flushes=" + totalFlushes
                + " pending=" + pending.size();
    }

    /** 世界卸载时的清理钩子。 */
    public void reset() {
        pending.clear();
        flushing = false;
    }
}
