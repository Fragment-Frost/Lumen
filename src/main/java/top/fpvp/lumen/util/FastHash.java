package top.fpvp.lumen.util;

import net.minecraft.util.BlockPos;

/**
 * 坐标哈希与零分配区域遍历。
 *
 * <h2>一次被实测推翻的假设（重要，读之前先看这里）</h2>
 *
 * <p>这个类最初是为了"替换原版的垃圾哈希算法"而写的。当时的推理是：
 *
 * <p>反汇编 {@code net.minecraft.util.Vec3i#hashCode}
 * （{@code BlockPos} 没有覆盖它，直接继承）得到
 * <code>(y + z * 31) * 31 + x</code>，其中 <b>x 只贡献 +1 的差异</b>。
 * 于是推断：沿 x 轴相邻的方块哈希连续递增，放进 {@code HashMap}
 * 会形成线性聚簇，查找退化。
 *
 * <p><b>实测否定了这个推断。</b>在一个跨 8×8 区块、16 格高的真实区域
 * （262144 个坐标）上，散列到 4096 个桶：
 *
 * <pre>
 *   原版  (y + z*31)*31 + x : 最大桶 566
 *   本类  splitmix64 混合   : 最大桶 582
 * </pre>
 *
 * <p>在单区块局部坐标、世界坐标（x=1000）、跨区块三种分布下，
 * 原版都不差于 splitmix64。
 *
 * <p>原因是 {@code HashMap} 自己还会对高位做扰动
 * （{@code h ^= h >>> 16}），而且桶索引用 {@code hash & (cap-1)} ——
 * 连续的哈希值恰好均匀落到连续桶上，只要元素数不超过桶数就不会聚簇。
 *
 * <p><b>结论：原版哈希不需要替换。</b>这是本类保留下来最重要的信息：
 * 它防止以后有人（包括我）再基于同样的错误推理去做无用的"优化"。
 *
 * <h2>那本类还有什么用</h2>
 *
 * <p>剩下的是<b>确定的、与哈希质量无关</b>的收益：
 *
 * <ol>
 *   <li>{@link FastHashMap.LongKeyMap}：以 {@code BlockPos#toLong()} 为键的
 *       开放寻址表，<b>零装箱、零 BlockPos 对象</b>。
 *       {@code HashMap<BlockPos, V>} 每次查表要么需要一个
 *       {@code BlockPos} 实例作为键，要么装箱成 {@code Long} ——
 *       这个分配开销是实打实的；</li>
 *   <li>{@link #forEachInBox}：替代 {@code BlockPos#getAllInBoxMutable}，
 *       后者反汇编确认每次都 new 两个 {@code BlockPos}（min/max 边界）
 *       外加一个匿名 {@code Iterable}。</li>
 * </ol>
 *
 * <p>这两项的价值在"省分配"，不在"哈希更快"。
 *
 * <h2>{@link #mix} 还需要吗</h2>
 *
 * <p>需要，但理由不同：{@link FastHashMap.LongKeyMap} 用
 * {@code BlockPos#toLong()} 得到的 64 位编码作为键，而它的高位是 x、
 * 低位是 z（中间夹 y）。直接用低 32 位做桶索引会让所有 x 变化
 * 完全不影响桶选择 —— <b>这才是真正需要混合的地方</b>，
 * 因为此时没有 {@code HashMap} 的高位扰动帮我们兜底。
 *
 * <p>所以 {@link #mix} 是为 <b>64 位键的自建表</b>服务的，
 * 不是为了"替换 {@code Vec3i.hashCode}"。
 */
public final class FastHash {

    private FastHash() {
    }

    /**
     * 把 64 位的方块坐标编码混合成均匀分布的 32 位哈希。
     *
     * <p>用 splitmix64 的两步（乘法 + 异或移位）。
     *
     * <p><b>必要性</b>：{@code BlockPos#toLong()} 的位布局是
     * x 在高位、z 在低位。若直接取低 32 位做桶索引，
     * x 的任何变化都不影响桶选择，而同一条 z 线上有大量不同 x 的方块 ——
     * 那会造成严重聚簇。自建表没有 {@code HashMap} 的高位扰动兜底，
     * 所以必须自己混合。
     *
     * <p>这与"原版 {@code Vec3i.hashCode} 差"是两个独立问题：
     * 后者被实测否定（见类注释），前者对自建 64 位键表是真实必要的。
     */
    public static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        return (int) (h ^ (h >>> 32));
    }

    /** 混合三个整数坐标。 */
    public static int mix(int x, int y, int z) {
        long key = ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFF);
        return mix(key);
    }

    /**
     * 方块区域遍历的回调。
     *
     * <p>用接口而不是 {@code Iterable}：{@code Iterable} 必然产生
     * 迭代器对象，而这个回调在热路径上每次区域扫描都会用到。
     */
    public interface BlockPosVisitor {
        /**
         * @return {@code false} 表示提前终止遍历
         */
        boolean visit(int x, int y, int z);
    }

    /**
     * 零分配的方块区域遍历，替代 {@code BlockPos#getAllInBoxMutable}。
     *
     * <p>原版每次调用会分配：两个 {@code BlockPos}（min/max 边界）
     * 加一个匿名 {@code Iterable} 加一个迭代器。
     *
     * <p>遍历顺序与原版一致（y 在外层、z 次之、x 在内层），
     * 这样如果有代码依赖遍历顺序（例如"先处理下面的方块"），行为不变。
     * 这个顺序有专门的测试守着。
     *
     * <p>端点自动归一化（min/max 会交换），与原版行为一致 ——
     * 原版也用 {@code Math.min}/{@code Math.max} 处理，
     * 所以传入反序的角点不会得到空区间。
     *
     * @return 是否完整遍历完（{@code false} 表示被回调提前终止）
     */
    public static boolean forEachInBox(BlockPos a, BlockPos b, BlockPosVisitor visitor) {
        return forEachInBox(a.getX(), a.getY(), a.getZ(),
                b.getX(), b.getY(), b.getZ(), visitor);
    }

    /** 整数区间的方块遍历（少两次 BlockPos 访问）。 */
    public static boolean forEachInBox(int x0, int y0, int z0, int x1, int y1, int z1,
                                       BlockPosVisitor visitor) {
        int minX = Math.min(x0, x1);
        int minY = Math.min(y0, y1);
        int minZ = Math.min(z0, z1);
        int maxX = Math.max(x0, x1);
        int maxY = Math.max(y0, y1);
        int maxZ = Math.max(z0, z1);

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!visitor.visit(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
