package top.fpvp.lumen.verify;

import top.fpvp.lumen.lighting.LightEngineQueueLogic;

/**
 * 光照引擎的<b>减光语义</b>回归测试。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>这里守住的是一个真实发生过的线上 bug：减光 BFS 的判断条件写成了
 *
 * <pre>
 *   if (neighborLevel &lt; oldLevel || (oldLevel == MAX_LIGHT &amp;&amp; type == SKY))
 * </pre>
 *
 * <p>那个 {@code ||} 后半段是错的。它让"天空光为 15 时移除光源"变成
 * <b>无条件清零所有邻居</b>，包括那些自己就能看到天空、本该保持 15 的方块。
 * 清零后又被压进减光队列，于是"清零"沿整个天空照亮区级联扩散，
 * 把大片室外区域清成 0 —— 玩家看到的就是"大批量方块出现后阴影渲染错误"。
 *
 * <p>这个 bug 的可怕之处在于：<b>单点改方块时几乎不暴露</b>。
 * 只有大批量变更（爆炸、结构生成）才把"天空光 15 被移除"这条路径踩出来。
 * 所以必须有测试直接守住这条语义，而不是靠进游戏试。
 *
 * <h2>测什么</h2>
 *
 * <p>把真实的判断逻辑抽到 {@link LightEngineQueueLogic}（生产代码用的就是它），
 * 然后用一组人造的光值分布验证：
 * <ol>
 *   <li>邻居光值严格小于移除值 → 必须清零并继续扩散；</li>
 *   <li>邻居光值等于移除值 → 必须保留（它有自己的光源）；</li>
 *   <li>邻居光值大于移除值 → 必须保留；</li>
 *   <li>天空光 15 移除时，相邻的 15 值方块<b>不能</b>被清零。</li>
 * </ol>
 *
 * <p>第 4 条正是原 bug 的直击点。
 */
public final class LightEngineSelfTest2 {

    private static int failures;

    public static void main(String[] args) {
        testStrictlyLessIsCleared();
        testEqualIsKept();
        testGreaterIsKept();
        testSkyLightDoesNotCascade();
        testPropagationDecay();
        testOpacityFloor();

        System.out.println();
        if (failures == 0) {
            System.out.println("All light engine self-tests passed.");
        } else {
            System.out.println(failures + " light engine self-test(s) FAILED.");
            System.exit(1);
        }
    }

    /** 邻居严格小于移除值：必须清零并继续扩散。 */
    private static void testStrictlyLessIsCleared() {
        // 移除 12，邻居 11 → 它原本靠这个光源照亮，应清零。
        check("减光: 邻居 11 < 移除 12 -> 清零",
                LightEngineQueueLogic.shouldClearOnDecrease(11, 12), "");
        // 移除 15，邻居 14 → 应清零。
        check("减光: 邻居 14 < 移除 15 -> 清零",
                LightEngineQueueLogic.shouldClearOnDecrease(14, 15), "");
        // 移除 1，邻居 0 → 0 光本来就黑，清零无害。
        check("减光: 邻居 0 < 移除 1 -> 清零",
                LightEngineQueueLogic.shouldClearOnDecrease(0, 1), "");
    }

    /** 邻居等于移除值：必须保留（说明它有自己的光源）。 */
    private static void testEqualIsKept() {
        check("减光: 邻居 12 == 移除 12 -> 保留",
                !LightEngineQueueLogic.shouldClearOnDecrease(12, 12), "");
        check("减光: 邻居 15 == 移除 15 -> 保留",
                !LightEngineQueueLogic.shouldClearOnDecrease(15, 15),
                "相等说明是并列光源，不能清零");
    }

    /** 邻居大于移除值：必须保留。 */
    private static void testGreaterIsKept() {
        check("减光: 邻居 13 > 移除 12 -> 保留",
                !LightEngineQueueLogic.shouldClearOnDecrease(13, 12), "");
        check("减光: 邻居 15 > 移除 1 -> 保留",
                !LightEngineQueueLogic.shouldClearOnDecrease(15, 1), "");
    }

    /**
     * 原 bug 的直击点：天空光 15 被移除时，相邻的 15 值方块不能被清零。
     *
     * <p>上一版的条件里有 {@code oldLevel == MAX_LIGHT && type == SKY}
     * 这个额外的 OR 分支，会让这种情况一律返回"清零"。
     */
    private static void testSkyLightDoesNotCascade() {
        // 场景：一片开阔地，所有方块天空光都是 15。
        // 在其中一个位置放了不透明方块，于是该位置的 15 被移除（降为 0）。
        // 此时检查它的邻居：邻居光值也是 15，绝不能因为"移除值是 15"就清零。
        boolean cleared = LightEngineQueueLogic.shouldClearOnDecrease(15, 15);
        check("关键: 天空光 15 移除时相邻 15 不被级联清零",
                !cleared,
                "这是「大批量方块出现后阴影错误」的根因——上一版的额外条件会让整个天空照亮区被清成 0");

        // 再验证一遍：即使移除值就是上限 15，判定也必须只依赖大小关系。
        for (int neighbor = 0; neighbor <= 15; neighbor++) {
            boolean expected = neighbor < 15;
            boolean actual = LightEngineQueueLogic.shouldClearOnDecrease(neighbor, 15);
            if (actual != expected) {
                failures++;
                System.out.println("  FAIL 天空光移除值 15, 邻居 " + neighbor
                        + " -> 期望 " + (expected ? "清零" : "保留")
                        + " 实际 " + (actual ? "清零" : "保留"));
                return;
            }
        }
        System.out.println("  OK   天空光移除值 15 时，仅 0..14 被清零，15 保留");
    }

    /** 增光衰减：邻居 = 当前 - max(1, opacity)。 */
    private static void testPropagationDecay() {
        check("增光: opacity 0 -> 衰减 1",
                LightEngineQueueLogic.propagatedLevel(15, 0) == 14, "");
        check("增光: opacity 1 -> 衰减 1",
                LightEngineQueueLogic.propagatedLevel(15, 1) == 14, "");
        check("增光: opacity 3 -> 衰减 3",
                LightEngineQueueLogic.propagatedLevel(15, 3) == 12, "");
        check("增光: 光值耗尽时 <=0",
                LightEngineQueueLogic.propagatedLevel(1, 1) <= 0, "");
        check("增光: opacity >= 15 -> 不透光",
                LightEngineQueueLogic.propagatedLevel(15, 15) <= 0, "");
    }

    /** 不透明度下限：小于 1 时按 1 处理。 */
    private static void testOpacityFloor() {
        check("不透明度: 0 -> 1", LightEngineQueueLogic.effectiveOpacity(0, 0) == 1, "");
        check("不透明度: -5 -> 1", LightEngineQueueLogic.effectiveOpacity(-5, 0) == 1, "");
        check("不透明度: 5 -> 5", LightEngineQueueLogic.effectiveOpacity(5, 0) == 5, "");
        // 原版特例：完全不透明但会发光的方块（萤石），opacity 折为 1。
        check("不透明度: 15 且发光 -> 1（萤石特例）",
                LightEngineQueueLogic.effectiveOpacity(15, 15) == 1, "");
        // 不透明且不发光：保持 15（完全不透光）。
        check("不透明度: 15 且不发光 -> 15",
                LightEngineQueueLogic.effectiveOpacity(15, 0) == 15, "");
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
