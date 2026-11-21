package top.fpvp.lumen.render;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import top.fpvp.lumen.LumenConfig;

/**
 * 可调的实体/生物渲染范围。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>1.8.9 <b>没有</b>「生物渲染距离」这个设置。原版唯一的距离控制在
 * {@code Render#getRenderDistanceWeight()} 与各实体的
 * {@code getMaxRenderDistanceSquared()}，两者都是<b>按实体类型写死</b>的
 * （OptiFine 才额外加了一个全局滑条）。
 *
 * <p>结果是：视距 16 时，一个 120 格外的僵尸依然要走完整的
 * 模型变换、骨骼动画、纹理绑定、顶点提交 —— 而它在屏幕上不到 2 像素。
 * 刷怪塔、村庄、大量动物的存档里这是实打实的帧率损失。
 *
 * <h2>为什么按类型分档而不是一个全局值</h2>
 *
 * <p>不同类型实体的「远距离可辨识度」差别极大：
 * <ul>
 *   <li><b>生物</b>（僵尸、羊）在远处就是个色块，缩到 48 格基本无法察觉；</li>
 *   <li><b>玩家</b>是 PvP 的核心信息，缩得太狠会让人在远距离看不见对手 ——
 *       这在多人服上是不可接受的，所以默认<b>不缩</b>（0 = 不限制）；</li>
 *   <li><b>掉落物</b>数量可能极大（刷怪塔/农场），但它是玩家会主动去找的东西，
 *       缩太狠会导致"东西掉了却看不见"。</li>
 * </ul>
 *
 * <p>所以三个独立开关，玩家可以只缩生物。
 *
 * <h2>与「远处实体 LoD」的分工</h2>
 *
 * <p>{@link EntityLod} 是<b>降低质量</b>（跳装饰层），本类是<b>降低距离</b>
 * （干脆不画）。两者互补：LoD 在 24 格减细节，本类在 48 格完全不画。
 * 也可以各自单独关闭。
 *
 * <h2>安全边界</h2>
 *
 * <p>这些实体<b>永远不缩</b>：
 * <ul>
 *   <li>玩家自己与摄像机所在实体；</li>
 *   <li>玩家骑乘的实体 / 骑在玩家身上的实体；</li>
 *   <li>有自定义名字的实体（命名牌通常是玩家特意标记的）；</li>
 *   <li>设置了 {@code ignoreFrustumCheck} 的实体（它们自己声明了要始终渲染）。</li>
 * </ul>
 */
public final class EntityRenderRange {

    /** 实体分类。 */
    public enum Category {
        /** 玩家（默认不限制）。 */
        PLAYER,
        /** 生物（EntityLivingBase 且非玩家）。 */
        MOB,
        /** 掉落物。 */
        ITEM,
        /** 其它（箭、船、矿车等）。 */
        OTHER
    }

    private EntityRenderRange() {
    }

    public static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.entityRenderRange;
    }

    /** 该实体属于哪一类。 */
    public static Category categorize(Entity entity) {
        if (entity instanceof EntityPlayer) {
            return Category.PLAYER;
        }
        if (entity instanceof EntityItem) {
            return Category.ITEM;
        }
        if (entity instanceof EntityLivingBase) {
            return Category.MOB;
        }
        return Category.OTHER;
    }

    /** 该分类的渲染距离上限（格）。0 或负数表示不限制。 */
    public static double limitFor(Category category) {
        switch (category) {
            case MOB:
                return LumenConfig.entityRangeMob;
            case ITEM:
                return LumenConfig.entityRangeItem;
            case PLAYER:
                return LumenConfig.entityRangePlayer;
            default:
                return LumenConfig.entityRangeOther;
        }
    }

    /**
     * 该实体是否因距离过远而应跳过渲染。
     *
     * @param distSq 实体到摄像机的距离平方（由调用方算好，避免重复开方）
     */
    public static boolean shouldSkipByDistance(Entity entity, double distSq) {
        if (!isEnabled()) {
            return false;
        }
        if (entity == null) {
            return false;
        }
        // 硬性保护：这些条件与"缩多远"无关，是语义上的边界。
        if (entity.ignoreFrustumCheck) {
            return false;
        }
        if (entity.riddenByEntity != null || entity.ridingEntity != null) {
            return false;
        }
        if (entity.hasCustomName()) {
            return false;
        }

        double limit = limitFor(categorize(entity));
        if (limit <= 0.0D) {
            return false;
        }
        return distSq > limit * limit;
    }

    public static String describe() {
        return "range mob=" + LumenConfig.entityRangeMob
                + " item=" + LumenConfig.entityRangeItem
                + " player=" + LumenConfig.entityRangePlayer
                + " other=" + LumenConfig.entityRangeOther;
    }
}
