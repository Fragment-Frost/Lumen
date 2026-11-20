package top.fpvp.lumen.world;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.util.Arrays;

/**
 * 流体流向向量缓存。
 *
 * <p>{@code BlockLiquid#getFlowVector} 会做 4 次 {@code getBlockState} + 多次
 * {@code getFlowDirection}，是一段不便宜的邻居扫描。它被调用的频率远高于
 * “水流真的变了”的频率：
 * <ul>
 *   <li>每个泡在水里的实体，每 tick 都要 {@code modifyAcceleration} 一次；</li>
 *   <li>流体方块自己的 {@code updateFlow} 每 tick 也会查询；</li>
 *   <li>船、掉落物、生物在温水区大量聚集时，同一格会被反复查询几十次。</li>
 * </ul>
 * 而流体状态只在 {@code updateFlow} 真的改动了方块时才会变。
 *
 * <p>因此这里做一层“按 tick + 按位置”的缓存：同一世界 tick 内，同一位置的
 * 流向向量只算一次。键用 {@code BlockPos#toLong()}，值用直接寻址的开放寻址表，
 * 不产生装箱。
 *
 * <p><b>失效策略</b>：缓存只在<b>同一 tick 内</b>有效。跨 tick 一律重算。
 * 这是刻意的保守选择 —— 流体更新有随机 tick，若依赖“方块变化时精确失效”，
 * 只要漏了一处，玩家就会看到“水明明改道了，实体还在往老方向漂”。以 tick 为
 * 边界则最多延迟 1 tick（50ms），玩家不可能感知，但绝不可能长时间错。
 */
public final class FluidFlowCache {

    /** 表容量，必须是 2 的幂。 */
    private static final int CAPACITY = 8192;
    private static final int MASK = CAPACITY - 1;

    private final long[] keys = new long[CAPACITY];
    private final double[] xs = new double[CAPACITY];
    private final double[] ys = new double[CAPACITY];
    private final double[] zs = new double[CAPACITY];

    /** 对应世界 tick。0 表示尚未初始化。 */
    private long tick = Long.MIN_VALUE;

    private static final FluidFlowCache INSTANCE = new FluidFlowCache();

    private FluidFlowCache() {
        Arrays.fill(keys, Long.MIN_VALUE);
    }

    public static FluidFlowCache get() {
        return INSTANCE;
    }

    /** 新的一 tick 开始时清空。由 mixin 在每个世界 tick 调用。 */
    public void beginTick(long worldTick) {
        if (worldTick != tick) {
            Arrays.fill(keys, Long.MIN_VALUE);
            tick = worldTick;
        }
    }

    private static int index(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & MASK;
    }

    /** 查缓存；命中则把结果写进 {@code out} 并返回 {@code true}。 */
    public boolean lookup(long key, double[] out) {
        int i = index(key);
        for (int probes = 0; probes < CAPACITY; probes++) {
            long k = keys[i];
            if (k == key) {
                out[0] = xs[i];
                out[1] = ys[i];
                out[2] = zs[i];
                LumenProfiler.fluidCacheHits++;
                return true;
            }
            if (k == Long.MIN_VALUE) {
                return false;
            }
            i = (i + 1) & MASK;
        }
        return false;
    }

    /**
     * 写入缓存。
     *
     * <p>表满时<b>不扩容、不淘汰</b>，而是直接放弃写入。理由：流体向量缓存是
     * 纯性能优化，放弃写入只是少一次命中，正确性不受影响；而扩容会在热路径里
     * 分配数组并 rehash，反而制造卡顿。
     */
    public void store(long key, double x, double y, double z) {
        int i = index(key);
        for (int probes = 0; probes < CAPACITY; probes++) {
            long k = keys[i];
            if (k == key) {
                xs[i] = x;
                ys[i] = y;
                zs[i] = z;
                return;
            }
            if (k == Long.MIN_VALUE) {
                keys[i] = key;
                xs[i] = x;
                ys[i] = y;
                zs[i] = z;
                return;
            }
            i = (i + 1) & MASK;
        }
        // 表满：放弃写入。
        LumenProfiler.fluidCacheMisses++;
    }

    /** 世界卸载时清空。 */
    public void reset() {
        Arrays.fill(keys, Long.MIN_VALUE);
        tick = Long.MIN_VALUE;
    }

    public static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.fluidFlowCache;
    }

    /**
     * 是否值得缓存这个位置。
     *
     * <p>只缓存水与岩浆。其它 {@code Material} 走 {@code BlockLiquid} 的概率极低，
     * 但一旦发生（mod 的自定义流体），多一层判断比缓存错更安全。
     */
    public static boolean isCacheable(IBlockAccess access, BlockPos pos) {
        IBlockState state = access.getBlockState(pos);
        Material material = state == null ? null : state.getBlock().getMaterial();
        return material == Material.water || material == Material.lava;
    }
}
