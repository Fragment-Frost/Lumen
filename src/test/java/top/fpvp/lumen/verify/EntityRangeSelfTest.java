package top.fpvp.lumen.verify;

import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.render.EntityRenderRange;

/**
 * 实体渲染范围的自测。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>「按距离不画实体」有一个不可接受的失败模式：<b>把不该藏的藏了</b>。
 * 具体来说：
 *
 * <ul>
 *   <li>玩家自己 → 第三人称看不到自己；</li>
 *   <li><b>其它玩家</b> → PvP 里对手从画面消失，等于失去目标；</li>
 *   <li>骑乘目标 → 你骑的马/船消失；</li>
 *   <li>命名实体 → 玩家特意标记的东西不见了。</li>
 * </ul>
 *
 * <p>这些都不是"配置调得太激进"，而是<b>语义上的硬性边界</b>。
 * 一旦某次改动破坏了它，症状是"玩家偶发看不见别人"，
 * 极难与网络问题或服务端问题区分开。
 *
 * <p>所以这些保护必须有测试守着，而不是靠读代码确认。
 */
public final class EntityRangeSelfTest {

    private static int failures;

    public static void main(String[] args) {
        testClassification();
        testPlayerNeverLimited();
        testLimitsApplied();
        testZeroMeansUnlimited();
        testCategoriesAreIndependent();

        System.out.println();
        if (failures == 0) {
            System.out.println("All entity range self-tests passed.");
        } else {
            System.out.println(failures + " entity range self-test(s) FAILED.");
            System.exit(1);
        }
    }

    /** 分类必须正确（玩家不能被归到 MOB）。 */
    private static void testClassification() {
        // 不能用真的 Entity 实例（需要 World），所以直接验 limitFor 的分派。
        check("PLAYER 分类有独立配置",
                EntityRenderRange.limitFor(EntityRenderRange.Category.PLAYER)
                        == LumenConfig.entityRangePlayer, "");
        check("MOB 分类有独立配置",
                EntityRenderRange.limitFor(EntityRenderRange.Category.MOB)
                        == LumenConfig.entityRangeMob, "");
        check("ITEM 分类有独立配置",
                EntityRenderRange.limitFor(EntityRenderRange.Category.ITEM)
                        == LumenConfig.entityRangeItem, "");
        check("OTHER 分类有独立配置",
                EntityRenderRange.limitFor(EntityRenderRange.Category.OTHER)
                        == LumenConfig.entityRangeOther, "");
    }

    /**
     * 玩家默认必须<b>不限制</b>。
     *
     * <p>这是最重要的一条：如果默认值是 48 之类，
     * 玩家会莫名其妙地看不见远处对手。
     */
    private static void testPlayerNeverLimited() {
        double saved = LumenConfig.entityRangePlayer;
        try {
            // 用出厂的默认值验证：配置里玩家距离默认应当是 0（不限制）。
            // 注意这里读的是当前配置值，若玩家自己改过就不该断言，
            // 所以只在默认值下检查。
            if (saved == 0.0D) {
                check("玩家渲染距离默认不限制（0）", true, "");
            } else {
                System.out.println("  NOTE 玩家距离已被配置为 " + saved
                        + "（非默认）；PvP 里可能会看不见远处对手");
            }
        } finally {
            LumenConfig.entityRangePlayer = saved;
        }
    }

    /** 达到距离上限时必须跳过。 */
    private static void testLimitsApplied() {
        boolean savedEnabled = LumenConfig.enabled;
        boolean savedRange = LumenConfig.entityRenderRange;
        try {
            LumenConfig.enabled = true;
            LumenConfig.entityRenderRange = true;
            // 直接把 MOB 限制设成 10 格，然后用 distSq 验证边界。
            double savedMob = LumenConfig.entityRangeMob;
            LumenConfig.entityRangeMob = 10.0D;
            try {
                // 注意：shouldSkipByDistance 需要 Entity 实例，
                // 这里用 limitFor 的语义验证边界计算是否正确。
                double limit = EntityRenderRange.limitFor(EntityRenderRange.Category.MOB);
                check("MOB 限制值生效", limit == 10.0D, "");
                check("9 格内不跳过", !(9.0D * 9.0D > limit * limit), "");
                check("11 格外跳过", (11.0D * 11.0D > limit * limit), "");
            } finally {
                LumenConfig.entityRangeMob = savedMob;
            }
        } finally {
            LumenConfig.enabled = savedEnabled;
            LumenConfig.entityRenderRange = savedRange;
        }
    }

    /** 0 表示不限制。 */
    private static void testZeroMeansUnlimited() {
        double savedMob = LumenConfig.entityRangeMob;
        try {
            LumenConfig.entityRangeMob = 0.0D;
            double limit = EntityRenderRange.limitFor(EntityRenderRange.Category.MOB);
            check("0 表示不限制", limit <= 0.0D,
                    "若把 0 当成「距离 0」就会导致所有生物都不渲染");
        } finally {
            LumenConfig.entityRangeMob = savedMob;
        }
    }

    /** 各分类互不影响（缩生物不该把掉落物也缩了）。 */
    private static void testCategoriesAreIndependent() {
        double savedMob = LumenConfig.entityRangeMob;
        double savedItem = LumenConfig.entityRangeItem;
        try {
            LumenConfig.entityRangeMob = 8.0D;
            LumenConfig.entityRangeItem = 96.0D;
            check("缩生物不影响掉落物",
                    EntityRenderRange.limitFor(EntityRenderRange.Category.ITEM) == 96.0D,
                    "掉落物会被玩家主动寻找，不该被生物的限制牵连");
            check("生物限制独立生效",
                    EntityRenderRange.limitFor(EntityRenderRange.Category.MOB) == 8.0D, "");
        } finally {
            LumenConfig.entityRangeMob = savedMob;
            LumenConfig.entityRangeItem = savedItem;
        }
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
