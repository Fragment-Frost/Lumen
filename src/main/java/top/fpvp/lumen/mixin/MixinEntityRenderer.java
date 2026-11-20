package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.render.OcclusionCuller;
import top.fpvp.lumen.render.FrustumCache;

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
    }
}
