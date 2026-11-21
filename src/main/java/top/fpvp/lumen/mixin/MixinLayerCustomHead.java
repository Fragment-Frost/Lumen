package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.render.SkinLod;

/**
 * 玩家皮肤 LoD：远处跳过自定义头饰（帽子层）渲染。
 *
 * <p>{@code LayerCustomHead} 会为一个方块头（头颅、南瓜、凋灵骷髅头等）
 * 建立<b>独立的方块渲染上下文</b>：切换纹理、压栈变换矩阵、提交一个完整方块、
 * 再恢复。单次开销接近画一个方块。
 *
 * <p>只对<b>玩家</b>生效：其它生物（僵尸戴南瓜、雪人）的头饰在近处才有意义，
 * 但对玩家来说这是最常见的装饰层，也是配置里最值得省的一项。
 *
 * <p>同样拦截在 HEAD：{@code LayerCustomHead} 内部第一件事就是
 * {@code GlStateManager.pushMatrix()}，在它之前取消才不会破坏矩阵栈。
 */
@Mixin(LayerCustomHead.class)
public abstract class MixinLayerCustomHead {

    @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void lumen$skipCustomHeadForDistantPlayer(EntityLivingBase entity,
                                                      float limbSwing, float limbSwingAmount,
                                                      float partialTicks, float ageInTicks,
                                                      float netHeadYaw, float headPitch,
                                                      float scale, CallbackInfo ci) {
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        if (SkinLod.shouldSkipLayers((EntityPlayer) entity)) {
            ci.cancel();
        }
    }
}
