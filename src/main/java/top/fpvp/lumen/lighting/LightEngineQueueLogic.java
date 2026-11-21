package top.fpvp.lumen.lighting;

/**
 * 光照队列的<b>纯函数</b>判定逻辑。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * <p>光照引擎的主体逻辑必须读写 {@code World}、{@code Chunk}、{@code NibbleArray}，
 * 这些都无法在裸 JVM 里构造，所以主体没法做单元测试 —— 只能进游戏验证。
 *
 * <p>但真正出过 bug 的那几行（减光该不该清零、增光衰减多少、
 * 不透明度怎么折）都是<b>纯算术</b>，不碰任何游戏对象。把它们抽到这里，
 * 就能用自测直接守住语义，而不是每次改动后靠"进游戏看看阴影对不对"。
 *
 * <h2>这是一次真实故障的产物</h2>
 *
 * <p>减光判定曾经写成：
 * <pre>
 *   if (neighborLevel &lt; oldLevel || (oldLevel == MAX_LIGHT &amp;&amp; type == SKY))
 * </pre>
 * 那个额外的 OR 分支让「天空光 15 被移除」变成无条件清零所有邻居，
 * 于是清零沿着整个天空照亮区级联扩散，把大片室外区域清成 0。
 * 表现就是"大批量方块出现后阴影渲染错误"。
 *
 * <p>把这个判定收进 {@link #shouldClearOnDecrease} 之后，
 * {@code LightEngineSelfTest} 就能直接验证它 —— 包括那个
 * "移除值等于 15 时，相邻的 15 必须保留"的关键用例。
 */
public final class LightEngineQueueLogic {

    /** 光值上限。 */
    public static final int MAX_LIGHT = 15;

    private LightEngineQueueLogic() {
    }

    /**
     * 减光 BFS 中，某个邻居是否应当被清零。
     *
     * <p>唯一正确的语义：<b>邻居光值严格小于被移除的光值</b>，
     * 说明它原本就是靠这个（已被移除的）光源照亮的，必须清零并继续扩散。
     *
     * <p>相等或更大时，说明它有自己的光源（或自己能看到天空），
     * 必须保留，并作为边界点稍后重新向内灌光。
     *
     * <p><b>不要在这里加重言</b>（比如"移除值是 15 时特殊处理"）。
     * 那正是曾经引入级联清零 bug 的地方。
     *
     * @param neighborLevel 邻居当前的光值
     * @param removedLevel  正在被移除的光值（该位置清零前的旧值）
     * @return {@code true} 表示应清零并继续扩散
     */
    public static boolean shouldClearOnDecrease(int neighborLevel, int removedLevel) {
        return neighborLevel < removedLevel;
    }

    /**
     * 增光 BFS 中，光传到邻居后剩余的光值。
     *
     * <p>等于 {@code level - max(1, opacity)}。原版语义是"每格至少衰减 1"，
     * 所以空气（opacity 0）也不是无损传播。
     *
     * @return 传播后的光值；{@code <= 0} 表示传不过去
     */
    public static int propagatedLevel(int level, int opacity) {
        int eff = effectiveOpacity(opacity, 0);
        if (eff >= MAX_LIGHT) {
            return 0;
        }
        return level - eff;
    }

    /**
     * 计算实际生效的不透明度。
     *
     * <p>三条规则，全部对齐原版 {@code World#getRawLight}：
     * <ol>
     *   <li>完全不透明（{@code >= 15}）但会发光的方块（萤石、海晶灯），
     *       不透明度折为 1，好让光能透出去一格；</li>
     *   <li>小于 1 时折为 1（空气/水的处理，保证"每格至少衰减 1"）；</li>
     *   <li>其余保持原值。</li>
     * </ol>
     *
     * @param opacity  方块的光不透明度
     * @param emission 方块的发光值
     */
    public static int effectiveOpacity(int opacity, int emission) {
        if (opacity >= MAX_LIGHT && emission > 0) {
            return 1;
        }
        if (opacity < 1) {
            return 1;
        }
        return opacity;
    }
}
