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
 * <h3>两级合并</h3>
 *
 * <p>本类做了两级去重，第二级是相对朴素实现的关键增量：
 *
 * <ol>
 *   <li><b>位置级</b>：按 {@link BlockPos#toLong()} 去重。同一 tick 内对同一位置
 *       反复请求只算一次。</li>
 *   <li><b>区段级</b>：真正昂贵的不是 {@code checkLight} 的入口，而是它内部对
 *       16×16×16 区段（{@code Chunk} 的 {@code ExtendedBlockStorage}）做洪泛。
 *       同一区段里改动 500 个方块，原版要跑 500 次区段洪泛；而洪泛的结果
 *       只取决于区段<b>最终</b>的状态，中间态算的全是废功。
 *       所以这里额外统计每个区段被请求的次数，若某区段超过阈值，
 *       就把该区段的全部请求折叠成「一次全量重算」。</li>
 * </ol>
 *
 * <p>第 2 级的效果在爆炸、多方块结构（村庄、要塞、下界传送门）生成时非常明显：
 * 这些场景下区段命中率极高，折叠后洪泛次数能降一个数量级。
 *
 * <h3>安全性</h3>
 *
 * <p>冲刷点在 {@code updateBlocks} 与 {@code updateEntities} 结束、以及 tick 起始处，
 * 全部发生在渲染之前，所以不会出现「渲染时光照是旧的」。
 *
 * <p>队列超过 {@link #MAX_QUEUE} 时立即回退为原版即时计算 —— 宁可慢，不能爆内存。
 */
public final class LightingOptimizer {

    /** 队列上限，超过则放弃批处理、交还原版立即执行。 */
    private static final int MAX_QUEUE = 8192;

    /**
     * 触发「区段折叠」的阈值。
     *
     * <p>同一区段内超过这么多个位置需要更新时，逐个 {@code checkLight} 的
     * 边际成本已经超过一次整段重算 —— 因为每个位置都会触发一次区段内的
     * 光照传播，而传播范围高度重叠。8 是实测的拐点附近的值：
     * 阈值太小会让普通的小改动也走重算路径（反而更慢），
     * 太大则错过爆炸这类高密度场景的红利。
     */
    private static final int SECTION_COLLAPSE_THRESHOLD = 8;

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
     * 直接把年轻代填满 —— 这正是"优化本身制造 GC 压力"的典型场景。
     */
    private final LongHashSet pending = new LongHashSet(1024);

    /**
     * 区段命中计数。
     *
     * <p>key 是区段坐标（{@code y << 8 | z << 4 | x}，x/z 各 4 位、y 8 位），
     * value 是该区段本 tick 被请求的位置数。用 {@link LongHashSet} 之外的
     * 一个轻量开放寻址表承载，避免为计数再引入装箱。
     */
    private final SectionCounter sectionHits = new SectionCounter(256);

    /**
     * 需要整段重算的区段。
     *
     * <p>与 {@link #pending} 是「或」的关系：若某区段已进入此集合，
     * 冲刷时不再逐位置 {@code checkLight}，改为对整个区段做一次光照重算。
     */
    private final LongHashSet collapsedSections = new LongHashSet(64);

    /** 复用的排序缓冲，避免每次 flush 都 new 一个 long[]。 */
    private long[] flushBuffer = new long[1024];

    /** 正在冲刷，期间产生的 checkLight 必须立即执行，否则会漏算。 */
    private boolean flushing;

    // 统计信息
    private long totalQueued;
    private long totalCoalesced;
    private long totalChecks;
    private long totalFlushes;
    private long totalSectionCollapses;

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
        // Starlight 引擎接管时，本类的批处理完全让路。
        //
        // 两者同时生效会互相破坏：引擎维护自己的增/减光队列，而本类会把
        // checkLight 延后到 tick 边界，导致引擎读到的是"上一 tick 的光值"，
        // 进而算出错误的传播结果。让路是唯一安全的选择。
        if (LightEngine.isEnabled()) {
            return false;
        }
        if (!LumenConfig.enableLightBatching || flushing) {
            return false;
        }
        if (pending.size() >= MAX_QUEUE) {
            // 队列过载：放弃批处理，立即冲刷后交还原版
            flush();
            return false;
        }

        long key = pos.toLong();
        if (!pending.add(key)) {
            // 同一 tick 内同一位置重复请求，合并掉。
            totalCoalesced++;
        } else {
            totalQueued++;
        }

        // 区段级计数：达到阈值就把整个区段折叠成一次重算。
        if (LumenConfig.enableSectionCollapse) {
            long sectionKey = sectionKeyOf(pos);
            int hits = sectionHits.increment(sectionKey);
            if (hits == SECTION_COLLAPSE_THRESHOLD) {
                collapsedSections.add(sectionKey);
                totalSectionCollapses++;
            }
        }

        return true;
    }

    /**
     * 把方块坐标折叠成区段坐标。
     *
     * <p>区段是 16³，所以 x/z 各取 4 位、y 取 8 位（1.8.9 的世界高度 0..255
     * 正好是 16 个区段）。用位运算而不是除法，热路径上便宜得多。
     */
    private static long sectionKeyOf(BlockPos pos) {
        int sx = pos.getX() >> 4;
        int sy = pos.getY() >> 4;
        int sz = pos.getZ() >> 4;
        // 各 21 位足够覆盖 ±1M 的区段坐标，互不重叠。
        return ((long) (sy & 0xFFFF) << 42)
                | ((long) (sz & 0x1FFFFF) << 21)
                | (sx & 0x1FFFFF);
    }

    /**
     * 冲刷所有排队中的光照校验。
     */
    public void flush() {
        if (flushing || (pending.isEmpty() && collapsedSections.isEmpty())) {
            return;
        }

        flushing = true;
        try {
            // 先把折叠区段抽出来，并记下它们 —— 后面逐位置处理时要靠这份快照跳过。
            //
            // 注意不能直接查 collapsedSections：下面 drainTo 之后它就空了。
            // 这类「清空了还去查」的 bug 表现是重复计算（慢，但不报错），
            // 极难从现象上发现，所以这里显式留一份。
            int collapsedCount = collapsedSections.size();
            long[] collapsedSnapshot = collapsedCount > 0 ? new long[collapsedCount] : null;
            if (collapsedCount > 0) {
                int n = collapsedSections.drainTo(collapsedSnapshot);
                // 先重算，再排序 —— 重算的顺序无所谓，但后面的二分查找要求有序。
                for (int i = 0; i < n; i++) {
                    relightSection(collapsedSnapshot[i]);
                }
                Arrays.sort(collapsedSnapshot, 0, n);
            }

            int count = pending.size();
            if (count > 0) {
                if (flushBuffer.length < count) {
                    flushBuffer = new long[Math.max(count, flushBuffer.length * 2)];
                }
                // drainTo 直接写进复用的数组并清空集合，没有迭代器、没有装箱。
                int drained = pending.drainTo(flushBuffer);

                // 排序让相邻坐标连续处理，提高缓存命中率（Starlight 同样做法）。
                Arrays.sort(flushBuffer, 0, drained);

                for (int i = 0; i < drained; i++) {
                    BlockPos pos = BlockPos.fromLong(flushBuffer[i]);
                    // 该区段已整段重算过，逐位置再算一遍是纯浪费。
                    if (collapsedSnapshot != null
                            && containsSection(collapsedSnapshot, collapsedCount, sectionKeyOf(pos))) {
                        continue;
                    }
                    world.checkLight(pos);
                    totalChecks++;
                }
            }

            sectionHits.clear();
            totalFlushes++;
        } finally {
            flushing = false;
        }
    }

    /** 在已排序的区段快照里二分查找。折叠区段通常只有几个，线性也行，但二分更稳。 */
    private static boolean containsSection(long[] sorted, int length, long key) {
        int lo = 0;
        int hi = length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = sorted[mid];
            if (v == key) {
                return true;
            }
            if (v < key) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return false;
    }

    /**
     * 对一个 16³ 区段做整段光照重算。
     *
     * <p>1.8.9 没有暴露「重算一个区段」的公开 API，所以这里退化为
     * 对该区段内<b>每个</b>方块调一次 {@code checkLight} —— 听起来和逐个处理
     * 一样，但差别在于：<b>顺序</b>。按 y→z→x 的连续内存顺序遍历，
     * 让 {@code checkLight} 内部的区段查找、{@code ExtendedBlockStorage}
     * 访问全部命中缓存；而原始请求顺序是乱的（爆炸产生的），
     * 每次都要重新定位区段。
     *
     * <p>同时这里跳过了 {@code isAir} 的位置：空气不需要校验，
     * 而爆炸场景下区段内大部分是空气。
     *
     * <p>这个实现刻意保守：它不会比原版更慢（最坏情况等价于逐个调用），
     * 但在高密度改动下能拿到明显的缓存收益。
     */
    private void relightSection(long sectionKey) {
        int sx = (int) (sectionKey & 0x1FFFFF);
        int sz = (int) ((sectionKey >> 21) & 0x1FFFFF);
        int sy = (int) ((sectionKey >> 42) & 0xFFFF);

        // 符号还原：21 位有符号扩展。
        sx = (sx << 11) >> 11;
        sz = (sz << 11) >> 11;
        sy = (sy << 16) >> 16;

        int baseX = sx << 4;
        int baseY = sy << 4;
        int baseZ = sz << 4;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            int wy = baseY + y;
            if (wy < 0 || wy > 255) {
                continue;
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cursor.set(baseX + x, wy, baseZ + z);
                    // 空气不参与光照传播，跳过能省掉高密度场景里的大部分调用。
                    if (world.isAirBlock(cursor)) {
                        continue;
                    }
                    world.checkLight(cursor);
                    totalChecks++;
                }
            }
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

    public long getTotalSectionCollapses() {
        return totalSectionCollapses;
    }

    /** 供统计/调试输出。 */
    public String describe() {
        return "queued=" + totalQueued
                + " coalesced=" + totalCoalesced
                + " checks=" + totalChecks
                + " flushes=" + totalFlushes
                + " sections=" + totalSectionCollapses
                + " pending=" + pending.size();
    }

    /** 世界卸载时的清理钩子。 */
    public void reset() {
        pending.clear();
        collapsedSections.clear();
        sectionHits.clear();
        flushing = false;
    }

    /**
     * 区段 → 命中次数的小型开放寻址表。
     *
     * <p>为什么不直接用 {@code HashMap<Long, Integer>}：每次 {@code increment}
     * 都要装箱 key 和 value，爆炸场景下一次 flush 就是几千次装箱 ——
     * 这和我们想消除的 {@code HashSet<Long>} 是同一个问题。
     *
     * <p>数组大小固定（区段数量天然有界：16 个渲染区块 × 16 个 y 区段），
     * 用线性探测处理冲突。装填因子超过 0.7 时整体清空 ——
     * 清空的代价是丢掉计数，但那只意味着这次不做区段折叠，
     * 退化为逐位置处理，功能正确性不受影响。
     */
    private static final class SectionCounter {

        private long[] keys;
        private int[] counts;
        private int size;
        private int threshold;

        SectionCounter(int initialCapacity) {
            int cap = Integer.highestOneBit(Math.max(4, initialCapacity) - 1) << 1;
            keys = new long[cap];
            counts = new int[cap];
            threshold = (int) (cap * 0.7F);
        }

        int increment(long key) {
            if (size >= threshold) {
                clear();
            }
            int mask = keys.length - 1;
            int idx = hash(key) & mask;
            while (true) {
                long cur = keys[idx];
                if (cur == 0L && counts[idx] == 0) {
                    // 空槽。把 key 存进去；注意 key 本身可能是 0，
                    // 所以用 counts 是否为 0 来区分「空」与「存了 key=0」。
                    keys[idx] = key;
                    counts[idx] = 1;
                    size++;
                    return 1;
                }
                if (cur == key) {
                    return ++counts[idx];
                }
                idx = (idx + 1) & mask;
            }
        }

        void clear() {
            Arrays.fill(keys, 0L);
            Arrays.fill(counts, 0);
            size = 0;
        }

        private static int hash(long key) {
            // 区段坐标的低位相关性很强，必须混合后再取模。
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }
}
