package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.fpvp.lumen.render.AsyncOcclusionCuller;
import top.fpvp.lumen.render.BlockEntityOptimizer;
import top.fpvp.lumen.render.MoreCulling;
import top.fpvp.lumen.render.OcclusionCuller;

/**
 * 方块实体与实体的剔除接管点。
 *
 * <p>先纠正一个常见的误解：原版 1.8.9 对 {@link TileEntity} <b>已经</b>做了视锥剔除
 * （{@code ICamera#isBoundingBoxInFrustum}），也做了距离剔除。它真正缺的是
 * <b>遮挡</b>剔除 —— 墙后面的箱子照样会被提交给 TESR。
 *
 * <p>因此这里<b>不</b>重复做视锥测试，只在原版已经通过视锥与距离检查之后，
 * 再补几道判定，按代价从低到高：
 * <ol>
 *   <li>{@link MoreCulling}：信标光柱的独立视锥判定、被完全包围的地图/告示牌；</li>
 *   <li>{@link OcclusionCuller}：沿视线采样的遮挡判定（同步）；</li>
 *   <li>{@link BlockEntityOptimizer}：包围盒缓存，减少每帧的对象分配。</li>
 * </ol>
 *
 * <p>拦截点是 {@code TileEntityRendererDispatcher#renderTileEntity}。
 * 原版在世界渲染循环里有多处调用，最终都汇到这里，所以在这一点上拦一次即可。
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {

    /**
     * 在真正提交 TESR 之前做剔除判定。
     *
     * <p>用 {@code @Redirect} 而不是 {@code @Inject}：我们要“跳过这一次调用”，
     * 而 {@code renderTileEntity} 的返回类型是 {@code void}，无法用
     * {@code CallbackInfoReturnable} 表达。{@code @Redirect} 天然支持“不调用原方法”。
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
        // MoreCulling 的专用规则（信标光柱、被完全包围的地图/告示牌）。
        if (MoreCulling.shouldCullTileEntity(tile)) {
            return;
        }
        // 通用遮挡判定。
        if (OcclusionCuller.get().shouldCull(tile)) {
            return;
        }
        dispatcher.renderTileEntity(tile, partialTicks, pass);
    }

    /**
     * 实体渲染的剔除。
     *
     * <p>原版在 {@code RenderManager#shouldRender} 里已经做了视锥测试，
     * 但那个方法同时还判断了 {@code Render#shouldRender}（含距离）。
     * 在它返回 {@code true} 之后加一道遮挡判定，是作用面最小的做法。
     *
     * <p>这里同时驱动<b>异步</b>遮挡剔除：每帧对通过原版检查的实体提交一次
     * 后台判定，并读回上一帧的结果。这样主线程的射线预算不再是瓶颈。
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

        // 异步判定：提交本帧的候选，并读取之前的结果。
        AsyncOcclusionCuller.requestCheck(entity);
        if (AsyncOcclusionCuller.isOccluded(entity.getEntityId())) {
            return false;
        }

        // 同步兜底：异步结果有 1~2 帧延迟，在它生效前用同步判定覆盖。
        return !OcclusionCuller.get().shouldOcclude(entity);
    }
}
