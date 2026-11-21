package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.render.EntityLod;

/**
 * 远处实体低细节化：跳过装饰性渲染层。
 *
 * <h2>为什么拦在 {@code LayerRenderer#doRenderLayer}</h2>
 *
 * <p>{@code LayerRenderer} 是<b>所有</b>装饰层的统一接口：羊毛、雪、马铠、
 * 装备、鞍具、羊皮……全部实现它。拦这一个接口等于一次性覆盖所有当前与
 * 未来的层，而不用为每种实体各写一个 mixin。
 *
 * <h2>接口 mixin 的方法为什么必须是 default</h2>
 *
 * <p>接口里没有 {@code private} 实例方法（Java 8 的接口成员默认是
 * {@code public abstract}）。如果这里写成 {@code private void ...}，
 * 编译器会把它当成一个新的抽象方法 —— 于是 mixin 目标被视为"未实现"，
 * 注入静默失效；而如果漏掉 {@code default}，还会让
 * {@code LayerRenderer} 的所有实现类因"未实现抽象方法"而无法加载。
 *
 * <p>写法上必须是 {@code default}（不能加 {@code private}）：
 * Java 8 的接口允许 {@code default} 具体方法，但<b>不允许</b>
 * {@code private} 方法（那是 Java 9 才有的）。写成 {@code private default}
 * 会直接编译失败；写成 {@code private void} 则会被当成新的抽象方法声明，
 * 导致 {@code LayerRenderer} 的所有实现类无法加载。
 */
@Mixin(LayerRenderer.class)
public interface MixinLayerRenderer {

    @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true, require = 0)
    default void lumen$skipLayersForDistantEntity(EntityLivingBase entity,
                                                  float limbSwing, float limbSwingAmount,
                                                  float partialTicks, float ageInTicks,
                                                  float netHeadYaw, float headPitch,
                                                  float scale, CallbackInfo ci) {
        if (entity == null) {
            return;
        }
        // 先用类名白名单排除"必须保留"的层（手持物品、盔甲）。
        if (EntityLod.isLayerAlwaysKept(this)) {
            return;
        }
        if (EntityLod.shouldSkipLayers(entity)) {
            ci.cancel();
        }
    }
}
