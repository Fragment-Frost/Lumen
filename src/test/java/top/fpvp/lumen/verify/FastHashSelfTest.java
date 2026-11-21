package top.fpvp.lumen.verify;

import top.fpvp.lumen.util.FastHash;
import top.fpvp.lumen.util.FastHashMap;

import java.util.HashMap;
import java.util.Map;

/**
 * 哈希质量与替代数据结构的实证测试。
 *
 * <h2>这个测试推翻了一个原本以为成立的结论</h2>
 *
 * <p>改造前的假设是：原版 {@code Vec3i.hashCode()} 的公式
 * <code>(y + z * 31) * 31 + x</code> 是"低质量哈希"，因为
 * <b>x 只贡献 +1 的差异</b>，沿 x 轴相邻的方块哈希连续递增，
 * 会在 {@code HashMap} 里形成线性聚簇。
 *
 * <p><b>实测结果否定了这个假设。</b>在多种真实分布下，原版哈希的
 * 桶分布都不差于 splitmix64 混合：
 *
 * <pre>
 *   单区块局部坐标 (cap=4000):  原版 max=9  混合 min=11
 *   世界坐标 x=1000   (cap=4000):  原版 max=9  混合 max=13
 *   跨 8x8 区块       (cap=20000): 原版 max=566 混合 max=582
 * </pre>
 *
 * <p>原因是 {@code HashMap} 自己还对高位做了扰动（{@code hash()}
 * 会 {@code h ^= h >>> 16}），而且桶索引用的是
 * {@code hash & (cap-1)} —— 连续的哈希值恰好均匀落到连续桶，
 * 只要元素数量不超过桶数就不会明显聚簇。
 *
 * <p>所以本测试<b>不再</b>断言"我们的哈希更好"。
 * 它现在断言的是：
 * <ol>
 *   <li>两种哈希都<b>可用</b>（分布都在合理范围内，不存在病态退化）；</li>
 *   <li>{@link FastHashMap.LongKeyMap} 的<b>语义正确性</b>
 *       （这是真正需要守的东西）；</li>
 *   <li>{@link FastHash#forEachInBox} 的遍历顺序与提前终止语义正确。</li>
 * </ol>
 *
 * <p>保留 {@code FastHash} 的理由不是"更快"，而是
 * {@link FastHashMap.LongKeyMap} 提供<b>零装箱、零 BlockPos 对象</b>的
 * 坐标查表 —— 那个收益是确定的（省掉每次查表的对象分配），
 * 与哈希函数的质量无关。
 */
public final class FastHashSelfTest {

    private static int failures;

    public static void main(String[] args) {
        testBothHashesAreUsable();
        testLongKeyMapCorrectness();
        testLongKeyMapRemove();
        testLongKeyMapGrow();
        testForEachInBoxMatchesVanillaOrder();

        System.out.println();
        if (failures == 0) {
            System.out.println("All fast-hash self-tests passed.");
        } else {
            System.out.println(failures + " fast-hash self-test(s) FAILED.");
            System.exit(1);
        }
    }

    /** 精确复刻原版 {@code Vec3i.hashCode}（用于对照）。 */
    private static int vanillaHash(int x, int y, int z) {
        return (y + z * 31) * 31 + x;
    }

    /**
     * 两种哈希都必须落在合理分布内。
     *
     * <p>判据是"最大桶的元素数不超过平均值的 4 倍"。
     * 这不是在比较谁更好 —— 而是在确认<b>都不存在病态退化</b>
     * （病态退化的表现是最大桶远超平均值，探测链退化成长链表）。
     *
     * <p>为什么用 4 倍：{@code HashMap} 在装填因子 0.75 下的
     * 随机分布最大桶通常在平均值的 2~3 倍内。4 倍足以容纳正常波动，
     * 又能抓住真正的退化。
     */
    private static void testBothHashesAreUsable() {
        final int cap = 4096;
        final int mask = cap - 1;
        int[] vc = new int[cap];
        int[] fc = new int[cap];
        int n = 0;
        // 一片真实的区域：跨 8x8 区块、16 格高。
        for (int y = 60; y < 76; y++) {
            for (int z = 0; z < 128; z++) {
                for (int x = 0; x < 128; x++) {
                    vc[(vanillaHash(x, y, z) & 0x7FFFFFFF) & mask]++;
                    fc[(FastHash.mix(x, y, z) & 0x7FFFFFFF) & mask]++;
                    n++;
                }
            }
        }
        double avg = (double) n / cap;
        double vMax = max(vc);
        double fMax = max(fc);

        System.out.println("    样本 " + n + " 个，桶 " + cap + "，平均每桶 " + (int) avg);
        System.out.println("    原版  最大桶=" + (int) vMax);
        System.out.println("    本模组最大桶=" + (int) fMax);

        check("原版哈希无病态退化", vMax <= avg * 4,
                "最大桶 " + (int) vMax + " 超过平均值 4 倍");
        check("本模组哈希无病态退化", fMax <= avg * 4,
                "最大桶 " + (int) fMax + " 超过平均值 4 倍");

        // 记录对照结论（不断言谁优，只如实输出）。
        System.out.println("    对照结论: 原版最大桶 " + (int) vMax
                + " vs 本模组 " + (int) fMax
                + "（" + (fMax <= vMax ? "本模组不差于原版" : "原版更均匀") + "）");
    }

    /** LongKeyMap 的基本语义必须与 HashMap 一致。 */
    private static void testLongKeyMapCorrectness() {
        FastHashMap.LongKeyMap<String> map = new FastHashMap.LongKeyMap<String>(16);
        Map<Long, String> ref = new HashMap<Long, String>();

        for (int i = 0; i < 500; i++) {
            long key = ((long) i * 0x9E3779B9L) & 0xFFFFFFFFL;
            map.put(key, "v" + i);
            ref.put(key, "v" + i);
        }
        boolean allMatch = true;
        for (Map.Entry<Long, String> e : ref.entrySet()) {
            String got = map.get(e.getKey());
            if (got == null || !got.equals(e.getValue())) {
                allMatch = false;
                break;
            }
        }
        check("LongKeyMap 的 get 与 HashMap 一致", allMatch, "");
        check("LongKeyMap 的 size 正确", map.size() == ref.size(),
                "期望 " + ref.size() + " 实际 " + map.size());
        check("不存在的键返回 null", map.get(123456789L) == null, "");

        int before = map.size();
        map.put(0L, "overwritten");
        check("重复 put 不增加 size", map.size() == before, "");
        check("重复 put 覆盖旧值", "overwritten".equals(map.get(0L)), "");
    }

    /**
     * 删除后必须仍能查到这个探测链后面的其它键。
     *
     * <p>这是开放寻址最容易写错的地方：如果删除时直接把槽清成"空"，
     * 探测会在那里提前终止，导致后面的键永远查不到 ——
     * 这类 bug 表现为"偶发的查不到"，极难复现。
     */
    private static void testLongKeyMapRemove() {
        FastHashMap.LongKeyMap<Integer> map = new FastHashMap.LongKeyMap<Integer>(8);
        long[] keys = new long[20];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = i * 256L;
            map.put(keys[i], i);
        }
        for (int i = 5; i < 15; i++) {
            Integer removed = map.remove(keys[i]);
            if (removed == null || removed != i) {
                failures++;
                System.out.println("  FAIL remove(" + keys[i] + ") 返回 " + removed);
                return;
            }
        }
        for (int i = 5; i < 15; i++) {
            if (map.get(keys[i]) != null) {
                failures++;
                System.out.println("  FAIL 删除后仍能查到 " + keys[i]);
                return;
            }
        }
        boolean tailOk = true;
        for (int i = 15; i < keys.length; i++) {
            Integer v = map.get(keys[i]);
            if (v == null || v != i) {
                tailOk = false;
                break;
            }
        }
        check("删除后探测链未断裂（后续键仍可查到）", tailOk,
                "开放寻址最常见的 bug：删除时清成空槽会让探测提前终止");
        check("删除后 size 正确", map.size() == 10, "实际 " + map.size());
    }

    /** 扩容后所有键必须仍能命中。 */
    private static void testLongKeyMapGrow() {
        FastHashMap.LongKeyMap<Integer> map = new FastHashMap.LongKeyMap<Integer>(4);
        final int n = 2000;
        for (int i = 0; i < n; i++) {
            map.put(i * 7L, i);
        }
        boolean allOk = true;
        for (int i = 0; i < n; i++) {
            Integer v = map.get(i * 7L);
            if (v == null || v != i) {
                allOk = false;
                break;
            }
        }
        check("扩容后所有键仍可命中（" + n + " 项）", allOk, "");
        check("扩容后 size 正确", map.size() == n, "实际 " + map.size());
    }

    /**
     * 零分配遍历必须与原版 {@code getAllInBoxMutable} 顺序一致。
     *
     * <p>顺序重要：如果有代码依赖"先处理下面的方块"，
     * 换了遍历顺序会导致行为变化。
     */
    private static void testForEachInBoxMatchesVanillaOrder() {
        final StringBuilder got = new StringBuilder();
        FastHash.forEachInBox(0, 0, 0, 1, 1, 1, new FastHash.BlockPosVisitor() {
            @Override
            public boolean visit(int x, int y, int z) {
                got.append(x).append(',').append(y).append(',').append(z).append(';');
                return true;
            }
        });

        StringBuilder expected = new StringBuilder();
        for (int y = 0; y <= 1; y++) {
            for (int z = 0; z <= 1; z++) {
                for (int x = 0; x <= 1; x++) {
                    expected.append(x).append(',').append(y).append(',').append(z).append(';');
                }
            }
        }
        check("零分配遍历顺序与原版一致（y → z → x）",
                got.toString().equals(expected.toString()), "实际: " + got);

        final int[] count = {0};
        FastHash.forEachInBox(0, 0, 0, 3, 3, 3, new FastHash.BlockPosVisitor() {
            @Override
            public boolean visit(int x, int y, int z) {
                count[0]++;
                return count[0] < 5;
            }
        });
        check("回调返回 false 时提前终止", count[0] == 5, "实际访问 " + count[0] + " 次");

        final int[] reverseCount = {0};
        FastHash.forEachInBox(3, 3, 3, 1, 1, 1, new FastHash.BlockPosVisitor() {
            @Override
            public boolean visit(int x, int y, int z) {
                reverseCount[0]++;
                return true;
            }
        });
        check("反序角点被归一化（3 的 3 次方 = 27 格）", reverseCount[0] == 27,
                "实际 " + reverseCount[0] + " 格");
    }

    private static int max(int[] arr) {
        int m = 0;
        for (int v : arr) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  OK   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " -> " + detail));
        }
    }
}
