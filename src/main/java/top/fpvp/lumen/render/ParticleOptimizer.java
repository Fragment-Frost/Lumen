package top.fpvp.lumen.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import top.fpvp.lumen.LumenConfig;

/**
 * 粒子系统的数量上限与距离分档 LoD（参考 AsyncParticles / FPSFlow）。
 *
 * <h2>为什么粒子需要单独一套策略</h2>
 *
 * <p>粒子的开销与实体、方块实体完全不同，不能套用同一套剔除逻辑：
 * <ul>
 *   <li><b>数量级不同</b>：1331 个营火能产生 16000+ 粒子（AsyncParticles 的实测场景），
 *       而实体通常只有几十个；</li>
 *   <li><b>单体极小</b>：一个粒子的包围盒可能只有 0.1 格，用遮挡剔除的代价
 *       （每条射线 6 次方块查询）远高于它本身的渲染成本；</li>
 *   <li><b>生命周期极短</b>：粒子通常只活几十 tick，迟滞类优化（连续 N 帧判定）
 *       在它们身上根本跑不满。</li>
 * </ul>
 *
 * <p>因此这里用<b>数量上限 + 距离分档</b>，而不是逐个射线判定：
 * <ol>
 *   <li><b>数量上限</b>：每帧最多绘制 N 个粒子，超出的直接跳过。
 *       这是唯一能把最坏情况兜住的手段 —— 密集粒子场景本来就会淹没画面，
 *       少画一部分在观感上几乎无差别，但帧时间立刻可预测。</li>
 *   <li><b>距离分档</b>：按距玩家的距离分三档（全绘 / 隔一个画 / 不画）。
 *       对应的正是 FPSFlow 的 "three-zone density LOD"。</li>
 * </ol>
 *
 * <h2>为什么用"隔一个画"而不是"隔帧画"</h2>
 *
 * <p>AsyncParticles 的异步 tick 与 FPSFlow 的 Entity LOD 都用「隔 tick 更新」。
 * 但粒子不能这么做：粒子的位置每 tick 都在变，隔 tick 更新会让它们肉眼可见地
 * 一顿一顿跳。而「同一帧内按索引隔一个画」不会改变单个粒子的运动，
 * 只会让远处粒子密度减半 —— 在视觉上是"变稀"，不是"卡顿"。
 *
 * <h2>降级顺序</h2>
 *
 * <p>先按距离降级（远处的先减），再按数量截断。顺序反了的话，
 * 排序靠后的近处粒子会被远处的挤掉，玩家正眼前的爆炸效果会先消失。
 */
public final class ParticleOptimizer {

    /**
     * 当前帧已绘制的粒子数。
     *
     * <p>每帧在 {@link #beginFrame()} 里归零。用普通 int 而非原子类型：
     * 渲染线程独占，不需要同步。
     */
    private static int drawnThisFrame;

    /** 当前帧的参考距离平方（玩家到粒子的距离基准）。 */
    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;
    private static boolean eyeValid;

    /** 当前帧生效的绘制上限（可能被动态调整）。 */
    private static int currentBudget;

    /** 因超预算被跳过的数量（统计）。 */
    private static int skippedByBudget;

    /** 因距离分档被跳过的数量（统计）。 */
    private static int skippedByDistance;

    /** 用于"隔一个画"的轮转计数器。 */
    private static int decimationCounter;

    private ParticleOptimizer() {
    }

    /** 每帧在世界渲染开始时调用。 */
    public static void beginFrame(Entity viewEntity, float partialTicks) {
        drawnThisFrame = 0;
        skippedByBudget = 0;
        skippedByDistance = 0;
        decimationCounter = 0;

        currentBudget = LumenConfig.particleLimit;

        if (viewEntity != null) {
            eyeX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
            eyeY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks
                    + viewEntity.getEyeHeight();
            eyeZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
            eyeValid = true;
        } else {
            eyeValid = false;
        }
    }

    public static void reset() {
        drawnThisFrame = 0;
        eyeValid = false;
    }

    /**
     * 判定一个粒子本帧是否应该被绘制。
     *
     * @param particle 粒子实例（只读取位置，不修改）
     * @return {@code true} 表示应当绘制
     */
    public static boolean shouldRender(EntityFX particle) {
        if (!LumenConfig.enabled || !LumenConfig.particleLod || !LumenConfig.particleCulling) {
            return true;
        }

        // ---- 1. 数量上限（最坏情况的兜底） ----
        //
        // 这里的顺序很重要：先判上限再判距离，能让"超预算"的统计反映真实截断量。
        // 反过来（先距离后上限）会让统计混淆两种跳过原因。
        if (currentBudget > 0 && drawnThisFrame >= currentBudget) {
            skippedByBudget++;
            return false;
        }

        if (!eyeValid) {
            drawnThisFrame++;
            return true;
        }

        // ---- 2. 距离分档 ----
        double dx = particle.posX - eyeX;
        double dy = particle.posY - eyeY;
        double dz = particle.posZ - eyeZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        double mid = LumenConfig.particleLodMid;
        double far = LumenConfig.particleLodFar;

        if (far > 0.0D && distSq > far * far) {
            // 最远档：完全不画。
            skippedByDistance++;
            return false;
        }

        if (mid > 0.0D && distSq > mid * mid) {
            // 中间档：隔一个画一个，密度减半。
            //
            // 用计数器而不是哈希粒子对象：计数器是 O(1) 且零分配，
            // 并且天然保证"每两个画一个"的严格交替，不会出现连续跳过多个。
            decimationCounter++;
            if ((decimationCounter & 1) == 0) {
                skippedByDistance++;
                return false;
            }
        }

        drawnThisFrame++;
        return true;
    }

    public static int getDrawnThisFrame() {
        return drawnThisFrame;
    }

    public static int getSkippedByBudget() {
        return skippedByBudget;
    }

    public static int getSkippedByDistance() {
        return skippedByDistance;
    }

    public static String describe() {
        return "drawn=" + drawnThisFrame
                + " budgetSkip=" + skippedByBudget
                + " distSkip=" + skippedByDistance;
    }
}
