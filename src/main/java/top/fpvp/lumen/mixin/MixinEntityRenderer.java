package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.render.AsyncOcclusionCuller;
import top.fpvp.lumen.render.OcclusionCuller;
import top.fpvp.lumen.render.FrustumCache;
import top.fpvp.lumen.render.ParticleOptimizer;
import top.fpvp.lumen.render.SkinLod;

/**
 * 在世界渲染开始时构建视锥，并重置本帧的剔除统计。
 *
 * <p>拦截点选在 {@code ClippingHelperImpl.init()} 调用之前：此刻
 * {@code setupCameraTransform} 已经执行完毕，投影矩阵与模型视图矩阵都反映
 * 本帧的摄像机朝向与位置，而世界还没开始渲染。这是唯一一个“矩阵已就绪、
 * 还没有任何东西被画出来”的时刻。
 *
 * <p>为什么必须在这个位置而不是更早：视图矩阵是在 {@code orientCamera} 里
 * 通过 {@code GlStateManager.rotate/translate} 累积出来的。任何在它之前的
 * 采样都拿到上一帧的朝向 —— 表现是快速转头时物体在视野边缘成片闪烁。
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    @Inject(
            method = "renderWorldPass(IFJ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/culling/ClippingHelperImpl;getInstance()Lnet/minecraft/client/renderer/culling/ClippingHelper;"
            )
    )
    private void lumen$buildFrustum(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        // 先用当前矩阵重建视锥，再重置统计 —— 顺序反了会让首帧的计数偏大。
        FrustumCache.get().rebuild();

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        OcclusionCuller.get().beginFrame(mc.theWorld, mc.getRenderViewEntity(), partialTicks);
        // 异步剔除器独立维护自己的眼睛位置与代次：它需要在本帧内推进代次，
        // 以便把上一帧在途的请求标记为“可交付”。
        AsyncOcclusionCuller.beginFrame(mc.theWorld, mc.getRenderViewEntity(), partialTicks);
        // 粒子的数量/距离预算按帧重置；距离基准也在这一帧锁定，
        // 避免同一帧内因摄像机插值变化导致远近判定不一致。
        ParticleOptimizer.beginFrame(mc.getRenderViewEntity(), partialTicks);
        // 皮肤 LoD 的距离基准同上。
        SkinLod.beginFrame(mc.getRenderViewEntity(), partialTicks);
    }
}
