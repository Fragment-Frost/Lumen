package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.fpvp.lumen.render.BackFaceCulling;

/**
 * 实体背面剔除。
 *
 * <h2>为什么只做实体，不做方块</h2>
 *
 * <p>1.8.9 的方块网格在<b>生成阶段</b>就只产生可见面：被完全包围的方块
 * 不生成四边形，相邻实心方块的接触面也不生成。这比背面剔除更彻底 ——
 * 背面剔除是丢掉"已经存在的背面三角形"，而原版根本不生成它们。
 *
 * <p>所以对方块再加一层剔除没有收益，只有风险（依赖双面渲染的 mod 模型会缺面）。
 * 详见 {@link BackFaceCulling} 的类注释。
 *
 * <h2>为什么包在 {@code doRender} 前后</h2>
 *
 * <p>{@code RenderManager#doRenderEntity} 对每个实体调用一次
 * {@code Render#doRender}。在这里用 {@code @Inject} 的 HEAD/RETURN
 * 成对设置与恢复 GL 状态，等于覆盖所有实体类型与所有 mod 的渲染器。
 *
 * <p>关键是<b>成对</b>：只开不关会让后续的 GUI、粒子、粒子文字渲染
 * 都处于剔除开启状态 —— 而它们大量使用面向摄像机的四边形，
 * 开启背面剔除会让它们整片消失（这类 bug 看起来像"UI 渲染坏了"，
 * 极难归因到实体渲染上）。
 *
 * <h2>与 OptiFine 区域渲染的关系</h2>
 *
 * <p>实体渲染与 OptiFine 的区域渲染是<b>两个独立阶段</b>，
 * 不共享 GL 状态假设，因此这里不因 OptiFine 而降级。
 * 会在 OptiFine 下降级的是方块侧（本类不涉及）。
 */
@Mixin(RenderManager.class)
public abstract class MixinRenderManagerBackFace {

    @Inject(method = "doRenderEntity", at = @At("HEAD"), require = 0)
    private void lumen$enableCullBeforeEntity(Entity entity, double x, double y, double z,
                                              float entityYaw, float partialTicks,
                                              boolean hideEntity,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!BackFaceCulling.shouldCullEntityFaces()) {
            return;
        }
        // 只设剔除模式与开关，不动其它状态。
        GlStateManager.cullFace(GL11.GL_BACK);
        GlStateManager.enableCull();
    }

    @Inject(method = "doRenderEntity", at = @At("RETURN"), require = 0)
    private void lumen$restoreCullAfterEntity(Entity entity, double x, double y, double z,
                                              float entityYaw, float partialTicks,
                                              boolean hideEntity,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!BackFaceCulling.shouldCullEntityFaces()) {
            return;
        }
        // 恢复成"关闭"而不是"保持开启"。
        //
        // 原版在实体渲染之后会画粒子、光晕、GUI 叠加层，
        // 它们用面向摄像机的四边形；保留剔除会让它们消失。
        // 宁可多一次状态切换，也不能污染后续渲染。
        GlStateManager.disableCull();
    }
}
