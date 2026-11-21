package top.fpvp.lumen.util;

import net.minecraft.util.BlockPos;
import top.fpvp.lumen.LumenConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * 坐标查表结构。
 *
 * <h2>先说结论：原版的哈希<b>不需要</b>替换</h2>
 *
 * <p>这个类最初基于一个假设：原版 {@code Vec3i.hashCode()}
 * （<code>(y + z*31)*31 + x</code>）因为"x 只贡献 +1"而聚簇严重，
 * 所以需要换成 splitmix64 混合。
 *
 * <p><b>实测否定了这个假设。</b>跨 8×8 区块、16 格高的 262144 个坐标
 * 散列到 4096 桶：原版最大桶 566，splitmix64 最大桶 582 —— 原版更好。
 * 在单区块、世界坐标、跨区块三种分布下结论一致。
 *
 * <p>原因是 {@code HashMap} 自己会对高位做扰动，且桶索引用
 * {@code hash & (cap-1)}：连续哈希恰好均匀落到连续桶上。
 *
 * <p>详细数据见 {@link FastHash} 的类注释。
 *
 * <h2>本类真正的收益：零装箱、零 BlockPos 对象</h2>
 *
 * <p>与哈希质量无关的确定收益：
 *
 * <ul>
 *   <li>{@code HashMap<BlockPos, V>} 需要 {@code BlockPos} 实例作为键。
 *       如果手头只有坐标（x,y,z），就得先 {@code new BlockPos(...)}；</li>
 *   <li>{@code HashMap<Long, V>} 需要把 {@code long} 装箱成
 *       {@link Long}。而坐标编码几乎全部落在 {@code LongCache}
 *       （-128..127）之外，于是每次都产生新对象。</li>
 * </ul>
 *
 * <p>{@link LongKeyMap} 以原始 {@code long} 为键，
 * 这两个分配都省掉。
 *
 * <h2>为什么这里<b>必须</b>混哈希（与上面的结论不矛盾）</h2>
 *
 * <p>{@code BlockPos#toLong()} 的位布局是 x 在高位、z 在低位。
 * 如果直接取低 32 位做桶索引，<b>x 的任何变化都不影响桶选择</b>，
 * 而同一条 z 线上有大量不同 x 的方块 —— 严重聚簇。
 *
 * <p>自建表<b>没有</b> {@code HashMap} 的 {@code h ^= h >>> 16}
 * 高位扰动兜底，所以必须自己混合。这是两件不同的事：
 * <ul>
 *   <li>"替换 {@code Vec3i.hashCode}" → 不需要（实测否定）；</li>
 *   <li>"64 位键的自建表要自己混哈希" → 必要（否则 x 维度完全塌陷）。</li>
 * </ul>
 */
public final class FastHashMap {

    private FastHashMap() {
    }

    /** 混合三个整数坐标（供自建使用）。 */
    public static int hash(int x, int y, int z) {
        return FastHash.mix(x, y, z);
    }

    /**
     * 以 {@code BlockPos#toLong()} 为键的开放寻址映射。
     *
     * <p>这是本项目推荐的坐标查表方式：
     * <ul>
     *   <li><b>零装箱</b>：键是 {@code long}，不是 {@code Long} 对象；</li>
     *   <li><b>零对象</b>：不像 {@code HashMap<BlockPos, V>} 那样需要
     *       {@code BlockPos} 实例作为键；</li>
     *   <li><b>好哈希</b>：用 {@link FastHash#mix(long)}，
     *       而不是 {@code Vec3i} 那个低质量的。</li>
     * </ul>
     *
     * <p>线性探测。装填因子超过 0.7 时扩容一倍 ——
     * 与 {@code HashMap} 的默认阈值一致。
     *
     * <p>值用 {@code Object[]} 存，{@code null} 表示空槽。
     * 不支持存 {@code null} 值（那与"空槽"无法区分）。
     */
    public static final class LongKeyMap<V> {

        private long[] keys;
        private Object[] values;
        private int size;
        private int threshold;

        /** 用于标记"该槽曾被占用过但已删除"，避免删除后探测链断裂。 */
        private static final long DELETED = Long.MIN_VALUE;

        public LongKeyMap() {
            this(64);
        }

        public LongKeyMap(int initialCapacity) {
            int cap = Integer.highestOneBit(Math.max(4, initialCapacity) - 1) << 1;
            keys = new long[cap];
            values = new Object[cap];
            threshold = (int) (cap * 0.7F);
        }

        @SuppressWarnings("unchecked")
        public V get(long key) {
            int mask = keys.length - 1;
            int idx = FastHash.mix(key) & mask;
            while (true) {
                Object v = values[idx];
                if (v == null) {
                    if (keys[idx] != DELETED) {
                        // 真空槽：键不存在。
                        return null;
                    }
                    // 已删除槽：继续探测。
                } else if (keys[idx] == key) {
                    return (V) v;
                }
                idx = (idx + 1) & mask;
            }
        }

        public V put(long key, V value) {
            if (value == null) {
                throw new IllegalArgumentException("LongKeyMap 不支持 null 值（与空槽无法区分）");
            }
            if (size >= threshold) {
                resize();
            }
            int mask = keys.length - 1;
            int idx = FastHash.mix(key) & mask;
            int firstDeleted = -1;
            while (true) {
                Object v = values[idx];
                if (v == null) {
                    if (keys[idx] == DELETED) {
                        if (firstDeleted < 0) {
                            firstDeleted = idx;
                        }
                    } else {
                        // 真空槽：插入到这里（或更早的已删除槽）。
                        int target = firstDeleted >= 0 ? firstDeleted : idx;
                        keys[target] = key;
                        values[target] = value;
                        size++;
                        return null;
                    }
                } else if (keys[idx] == key) {
                    Object old = values[idx];
                    values[idx] = value;
                    @SuppressWarnings("unchecked")
                    V prev = (V) old;
                    return prev;
                }
                idx = (idx + 1) & mask;
            }
        }

        @SuppressWarnings("unchecked")
        public V remove(long key) {
            int mask = keys.length - 1;
            int idx = FastHash.mix(key) & mask;
            while (true) {
                Object v = values[idx];
                if (v == null) {
                    if (keys[idx] != DELETED) {
                        return null;
                    }
                } else if (keys[idx] == key) {
                    values[idx] = null;
                    keys[idx] = DELETED;
                    size--;
                    return (V) v;
                }
                idx = (idx + 1) & mask;
            }
        }

        public int size() {
            return size;
        }

        public void clear() {
            java.util.Arrays.fill(keys, 0L);
            java.util.Arrays.fill(values, null);
            size = 0;
        }

        private void resize() {
            long[] oldKeys = keys;
            Object[] oldValues = values;
            int newCap = keys.length << 1;
            keys = new long[newCap];
            values = new Object[newCap];
            threshold = (int) (newCap * 0.7F);
            size = 0;
            // 重新散列所有存活项。
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldValues[i] != null) {
                    @SuppressWarnings("unchecked")
                    V v = (V) oldValues[i];
                    put(oldKeys[i], v);
                }
            }
        }
    }

    public static boolean isEnabled() {
        return LumenConfig.enabled;
    }
}
