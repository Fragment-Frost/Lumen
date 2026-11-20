package top.fpvp.lumen.util;

/**
 * 以 {@code long} 为键的开放寻址哈希集合，用于替代 {@code HashSet<Long>}。
 *
 * <p>原版与绝大多数 mod 在“按坐标去重”的场景里都会写成 {@code HashSet<Long>}：
 * 每次 {@code add} 都要把 {@code long} 装箱成 {@link Long}（在 JVM 上要么命中
 * {@code LongCache} 之外的 -128..127 区间而新建对象，要么分配在 TLAB 里），
 * 而坐标几乎全部落在缓存区间外，于是每个方块位置、每次 tick 都会产生垃圾。
 * 在爆炸、跑图、流体更新这类“一次处理上万格”的场景下，这些短命对象会直接
 * 把年轻代塞满，触发更频繁的 Minor GC，也就是玩家感知到的周期性卡顿。
 *
 * <p>本类用一条 {@code long[]} 做开放寻址（线性探测），完全没有装箱，
 * 扩容时复用同一个数组并做 rehash，容量只增不减，因此稳态下可以做到
 * “零分配”。它只服务于单线程的批处理路径，<b>不是线程安全的</b>。
 *
 * <p>实现细节：{@link Long#MIN_VALUE} 被当作“空槽”哨兵。{@code BlockPos#toLong()}
 * 把 x/y/z 打包进 64 位，其中 y 只有 12 位（带符号扩展），永远不会产生
 * {@code Long.MIN_VALUE}，所以这个哨兵是安全的；{@link #add(long)} 仍会显式
 * 拒绝该值以免误用。
 */
public final class LongHashSet {

    /** 空槽哨兵。BlockPos.toLong() 不可能产生该值。 */
    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;

    private int size;

    /** 触发扩容的元素数量阈值。 */
    private int threshold;

    public LongHashSet(int expectedSize) {
        int capacity = 16;
        int target = Math.max(4, expectedSize) * 2;
        while (capacity < target) {
            capacity <<= 1;
        }
        this.keys = new long[capacity];
        java.util.Arrays.fill(this.keys, EMPTY);
        this.threshold = capacity * 3 / 5;
    }

    private static int indexOf(long key, int mask) {
        // 混合高低位，避免坐标的低位对齐（方块坐标经常是 16 的倍数）导致聚簇。
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    /**
     * @return {@code true} 表示这是一个新元素；{@code false} 表示已存在。
     */
    public boolean add(long key) {
        if (key == EMPTY) {
            throw new IllegalArgumentException("Long.MIN_VALUE is reserved as the empty sentinel");
        }
        int mask = keys.length - 1;
        int i = indexOf(key, mask);
        while (true) {
            long current = keys[i];
            if (current == EMPTY) {
                keys[i] = key;
                if (++size > threshold) {
                    grow();
                }
                return true;
            }
            if (current == key) {
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    public boolean contains(long key) {
        if (key == EMPTY) {
            return false;
        }
        int mask = keys.length - 1;
        int i = indexOf(key, mask);
        while (true) {
            long current = keys[i];
            if (current == key) {
                return true;
            }
            if (current == EMPTY) {
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        long[] old = keys;
        long[] expanded = new long[old.length << 1];
        java.util.Arrays.fill(expanded, EMPTY);
        int mask = expanded.length - 1;
        for (long key : old) {
            if (key == EMPTY) {
                continue;
            }
            int i = indexOf(key, mask);
            while (expanded[i] != EMPTY) {
                i = (i + 1) & mask;
            }
            expanded[i] = key;
        }
        keys = expanded;
        threshold = expanded.length * 3 / 5;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        if (size == 0) {
            return;
        }
        java.util.Arrays.fill(keys, EMPTY);
        size = 0;
    }

    /**
     * 把全部键写入 {@code out}，返回写入个数。
     *
     * <p>调用方应保证 {@code out.length >= size()}；提供这个方法是让热路径
     * 能把结果直接排进一个可复用的 {@code long[]}，而不是每 tick new 一个数组、
     * 再走 {@code Set.iterator()} 的装箱迭代器。
     */
    public int drainTo(long[] out) {
        int n = 0;
        for (long key : keys) {
            if (key != EMPTY) {
                if (n >= out.length) {
                    break;
                }
                out[n++] = key;
            }
        }
        size = 0;
        java.util.Arrays.fill(keys, EMPTY);
        return n;
    }

    /** 当前底层数组容量，供调试/统计使用。 */
    public int capacity() {
        return keys.length;
    }
}
