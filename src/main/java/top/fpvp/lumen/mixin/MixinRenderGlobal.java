package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.fpvp.lumen.render.OcclusionCuller;

/**
 * 补上原版缺失的方块实体遮挡剔除。
 *
 * <p>先纠正一个常见的误解：原版 1.8.9 对 {@link TileEntity} <b>已经</b>做了视锥剔除
 * （{@code ICamera#isBoundingBoxInFrustum}），也做了距离剔除。它真正缺的是
 * <b>遮挡</b>剔除 —— 墙后面的箱子照样会被提交给 TESR。
 *
 * <p>因此这里<b>不</b>重复做视锥测试，只在原版已经通过视锥与距离检查之后，
 * 再问一次“它是不是被墙挡住了”。这样做的额外好处是不会与任何重写了
 * 视锥逻辑的 mod 冲突。
 *
 * <p>拦截点是 {@code TileEntityRendererDispatcher#renderTileEntity} 这个<b>唯一的</b>
 * 实际绘制调用。原版在世界渲染循环里有两处（普通集合与 {@code setTileEntities}），
 * 两处最终都汇到这里，所以在这一点上拦一次即可覆盖全部路径。
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {

    /**
     * 在真正提交 TESR 之前做遮挡判定。
     *
     * <p>用 {@code @Redirect} 而不是 {@code @Inject}：我们要“跳过这一次调用”，
     * 而 {@code renderTileEntity} 的返回类型是 {@code void}，无法用
     * {@code CallbackInfoReturnable} 表达。{@code @Redirect} 天然支持“不调用原方法”。
     *
     * <p>方法名是 {@code renderEntities}：1.8.9 里方块实体的渲染循环<b>就写在</b>
     * {@code RenderGlobal#renderEntities} 内部（普通集合与 {@code setTileEntities}
     * 两处），并没有单独的 {@code renderTileEntities} 方法。
     */
    @Redirect(
            method = "renderEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;"
                            + "renderTileEntity(Lnet/minecraft/tileentity/TileEntity;FI)V"
            ),
            require = 0
    )
    private void lumen$occludeTileEntity(TileEntityRendererDispatcher dispatcher, TileEntity tile,
                                         float partialTicks, int pass) {
        if (OcclusionCuller.get().shouldCull(tile)) {
            return;
        }
        dispatcher.renderTileEntity(tile, partialTicks, pass);
    }

    /**
     * 实体渲染的遮挡剔除。
     *
     * <p>原版在 {@code RenderManager#shouldRender} 里已经做了视锥测试，
     * 但那个方法同时还判断了 {@code Render#shouldRender}（含距离）。
     * 在它返回 {@code true} 之后加一道遮挡判定，是作用面最小的做法。
     */
    @Redirect(
            method = "renderEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RenderManager;"
                            + "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z"
            ),
            require = 0
    )
    private boolean lumen$occludeEntity(net.minecraft.client.renderer.entity.RenderManager manager,
                                        Entity entity, ICamera camera,
                                        double camX, double camY, double camZ) {
        // 先让原版判定（含视锥、距离、Render#shouldRender 的自定义逻辑）。
        if (!manager.shouldRender(entity, camera, camX, camY, camZ)) {
            return false;
        }
        // 原版认为可见的，再补一道遮挡判定。
        return !OcclusionCuller.get().shouldOcclude(entity);
    }
}
