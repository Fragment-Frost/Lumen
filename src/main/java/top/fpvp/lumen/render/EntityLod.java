package top.fpvp.lumen.render;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import top.fpvp.lumen.LumenConfig;

/**
 * 远处实体低细节化（LoD，参考 FPSFlow 的 Entity LOD）。
 *
 * <h2>为什么针对「装饰层」而不是整个模型</h2>
 *
 * <p>远处实体跳过整个模型会造成明显的"实体凭空消失/现身"，观感很差。
 * 而装饰层（羊毛、雪层、装备、鞍具、马铠）的特点是：<b>单体开销接近主体模型，
 * 但视觉贡献在远处几乎为零</b> —— 30 格外的羊，你分不出它有没有毛。
 *
 * <p>所以策略是「保主体、砍装饰」。这也正是 FPSFlow 的做法：
 * 它按距离分档降低实体渲染频率，而不是简单地在某距离外完全隐藏。
 *
 * <h2>三项不能砍的东西</h2>
 *
 * <ol>
 *   <li><b>玩家自己</b>：第三人称下装饰缺失极其刺眼；</li>
 *   <li><b>玩家对手（其它玩家）</b>：在 PvP 里，对手穿什么甲、
 *       拿什么物品是<b>战术信息</b>。砍掉等于让 mod 在 PvP 服上不可用；</li>
 *   <li><b>手持物品层</b>：同理，看到对方拿什么武器是核心信息。</li>
 * </ol>
 *
 * <p>这三条不是遗漏，是刻意的边界。宁可少省一点性能，
 * 也不能让 mod 在多人对战里变成劣势。
 *
 * <h2>距离基准</h2>
 *
 * <p>用「到摄像机的距离」而不是「到玩家的距离」。旁观模式下两者不同，
 * 而决定"看得清不清"的永远是摄像机。
 */
public final class EntityLod {

    /** 这些层即使在远处也必须保留（见类注释）。 */
    private static final String[] ALWAYS_KEEP_LAYER_HINTS = {
            "LayerHeldItem",
            "LayerBipedArmor",
            "LayerArmorBase"
    };

    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;
    private static boolean eyeValid;

    private EntityLod() {
    }

    /** 每帧在开始世界渲染时调用（由 MixinEntityRenderer 驱动）。 */
    public static void beginFrame(Entity viewEntity, float partialTicks) {
        if (viewEntity == null) {
            eyeValid = false;
            return;
        }
        eyeX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        eyeY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks
                + viewEntity.getEyeHeight();
        eyeZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
        eyeValid = true;
    }

    public static void reset() {
        eyeValid = false;
    }

    private static double distanceSqTo(Entity entity) {
        if (!eyeValid) {
            return 0.0D;
        }
        double dx = entity.posX - eyeX;
        double dy = entity.posY - eyeY;
        double dz = entity.posZ - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 该实体的装饰层本帧是否应跳过。
     */
    public static boolean shouldSkipLayers(Entity entity) {
        if (!LumenConfig.enabled || !LumenConfig.entityLod) {
            return false;
        }
        if (!eyeValid || entity == null) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        // 玩家（包括自己）永远不砍装饰层：PvP 信息 + 第三人称观感。
        if (entity instanceof EntityPlayer) {
            return false;
        }
        // 玩家正在骑乘的东西也保留（马铠、船桨等）。
        if (entity == mc.thePlayer) {
            return false;
        }
        if (mc.thePlayer != null && mc.thePlayer.ridingEntity == entity) {
            return false;
        }

        double limit = LumenConfig.entityLodLayerDistance;
        return distanceSqTo(entity) > limit * limit;
    }

    /**
     * 该实体是否应完全跳过渲染。
     *
     * <p>只在 {@code entityLodCullDistance > 0} 时生效，且同样排除玩家与骑乘目标。
     */
    public static boolean shouldSkipEntity(Entity entity) {
        if (!LumenConfig.enabled || !LumenConfig.entityLod) {
            return false;
        }
        double limit = LumenConfig.entityLodCullDistance;
        if (limit <= 0.0D) {
            return false;
        }
        if (!eyeValid || entity == null) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (entity instanceof EntityPlayer || entity == mc.thePlayer) {
            return false;
        }
        if (mc.thePlayer != null && mc.thePlayer.ridingEntity == entity) {
            return false;
        }
        // 有名字的实体（命名牌）通常是玩家特意标记的，保留。
        if (entity.hasCustomName()) {
            return false;
        }

        return distanceSqTo(entity) > limit * limit;
    }

    /**
     * 某个具体的渲染层是否属于"必须保留"的那一类。
     *
     * <p>按类名判断而不是按类型：1.8.9 的层类很多（各个 mod 还会加），
     * 而持有物品/盔甲这两个语义是稳定的。这里只在<b>实例</b>上做一次
     * 类名比较，不涉及反射调用。
     */
    public static boolean isLayerAlwaysKept(Object layer) {
        if (layer == null) {
            return false;
        }
        String name = layer.getClass().getSimpleName();
        for (String hint : ALWAYS_KEEP_LAYER_HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    public static String describe() {
        return "entityLodActive=" + (LumenConfig.entityLod && eyeValid);
    }
}
