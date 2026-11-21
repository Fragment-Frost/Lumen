package top.fpvp.lumen.mixin;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.render.SkinLod;

/**
 * 玩家皮肤 LoD：远处跳过披风渲染。
 *
 * <p>为什么单独针对披风：{@code LayerCape} 是玩家模型上<b>单体开销最高的装饰层</b> ——
 * 它要做一次完整的变形 + 一次双面四边形提交，还要处理披风飘动的物理插值。
 * 而一个 32 格外的玩家，你根本看不清他有没有披风。
 *
 * <p>用 {@code @Inject} 而不是 {@code @Redirect}：{@code doRenderLayer} 返回
 * {@code void}，{@code @Inject} + {@code cancellable} 是最直接的表达。
 *
 * <p>注意拦截在 <b>方法开头</b>，在它做任何 GL 状态变更之前 ——
 * 如果在中间取消，会留下未恢复的 {@code GlStateManager} 状态，
 * 污染后续所有渲染（表现为其它实体发黑、贴图错位）。
 */
@Mixin(LayerCape.class)
public abstract class MixinLayerCape {

    @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void lumen$skipCapeForDistantPlayer(AbstractClientPlayer player,
                                                float limbSwing, float limbSwingAmount,
                                                float partialTicks, float ageInTicks,
                                                float netHeadYaw, float headPitch,
                                                float scale, CallbackInfo ci) {
        if (SkinLod.shouldSkipLayers(player)) {
            ci.cancel();
        }
    }
}
