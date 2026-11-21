package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.render.ItemRenderOptimizer;

/**
 * 物品渲染优化：缓存 {@code ItemModelMesher} 的查找结果。
 *
 * <h2>拦的是什么</h2>
 *
 * <p>{@code RenderItem#renderItemIntoGUI} 第一件事就是
 * {@code itemModelMesher.getItemModel(stack)}（字节码偏移 0-8）。
 * 物品栏 36 格 + 打开的容器 + HUD，每帧数百次调用。
 *
 * <p>而同一个 {@code Item} + metadata 的模型<b>在两次资源重载之间是固定的</b>。
 * 把它缓存下来，每个物品每帧省一次哈希查找 + 可能的模型分派。
 *
 * <h2>为什么在 RETURN 处回填缓存</h2>
 *
 * <p>缓存未命中时让原方法照常执行，只在它返回后把结果记下来。
 * 这样<b>不复制任何原版逻辑</b> —— 如果 Forge/OptiFine 改了
 * {@code getItemModel} 的行为，我们的缓存自然跟着变。
 *
 * <p>如果自己实现一遍"怎么算模型"，就会有两份实现，
 * 而漂移的那一份永远是最难查的 bug。
 *
 * <h2>资源重载时必须清缓存</h2>
 *
 * <p>换材质包会替换所有模型实例。不清缓存的话会继续用旧模型渲染，
 * 表现为"换了材质包但物品外观没变" —— 比不缓存糟糕得多。
 * 这里挂在 {@code onResourceManagerReload} 上。
 */
@Mixin(RenderItem.class)
public abstract class MixinRenderItem {

    /**
     * 缓存命中时跳过模型查找。
     *
     * <p>用 {@code @Redirect}：要"命中就不调用原方法"并返回缓存值，
     * 这是替换语义。{@code @Inject} 无法在方法体中间改变某个调用的返回值。
     */
    @Redirect(
            method = "renderItemIntoGUI",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemModelMesher;"
                            + "getItemModel(Lnet/minecraft/item/ItemStack;)"
                            + "Lnet/minecraft/client/resources/model/IBakedModel;"
            ),
            require = 0
    )
    private IBakedModel lumen$cachedItemModel(net.minecraft.client.renderer.ItemModelMesher mesher,
                                              ItemStack stack) {
        IBakedModel cached = ItemRenderOptimizer.getCachedModel(stack);
        if (cached != null) {
            return cached;
        }
        IBakedModel model = mesher.getItemModel(stack);
        ItemRenderOptimizer.putModel(stack, model);
        return model;
    }

    /**
     * 资源重载时清空模型缓存。
     *
     * <p>不清的话，换材质包后物品仍用旧模型渲染。
     */
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 0)
    private void lumen$clearItemModelCache(IResourceManager manager, CallbackInfo ci) {
        ItemRenderOptimizer.reset();
    }
}
