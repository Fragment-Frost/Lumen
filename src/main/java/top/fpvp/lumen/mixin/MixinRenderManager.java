package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.fpvp.lumen.render.EntityRenderRange;

/**
 * 可调的实体/生物渲染范围。
 *
 * <h2>为什么注入在 {@code RenderManager#shouldRender}</h2>
 *
 * <p>反汇编确认它只有一件事：取实体的 {@code Render} 实例，
 * 调用 {@code Render#shouldRender(entity, camera, camX, camY, camZ)}。
 * 而那个方法内部做的是"视锥测试 + {@code getRenderDistanceWeight} 距离"。
 *
 * <p>在它的入口加一道距离判定，等于给<b>所有</b>实体类型统一加上
 * 一个可调的渲染范围 —— 而不必为每种实体各写一个 mixin。
 *
 * <h2>为什么用 {@code @Inject} 而不是 {@code @Redirect}</h2>
 *
 * <p>我们只做"提前返回 false"，不改原逻辑。用
 * {@code @Inject(cancellable = true)} 让原方法在需要时照常执行，
 * 语义最清晰，也不会与 OptiFine 对 {@code Render#shouldRender} 的注入冲突。
 *
 * <h2>距离用的是摄像机的还是玩家的</h2>
 *
 * <p>参数里的 {@code camX/camY/camZ} 是摄像机位置。用它是刻意的 ——
 * 旁观模式下摄像机与玩家位置不同，而决定"看不看得见"的永远是摄像机。
 */
@Mixin(RenderManager.class)
public abstract class MixinRenderManager {

    @Inject(
            method = "shouldRender",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void lumen$entityRenderRange(Entity entity, net.minecraft.client.renderer.culling.ICamera camera,
                                         double camX, double camY, double camZ,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) {
            return;
        }
        double dx = entity.posX - camX;
        double dy = entity.posY - camY;
        double dz = entity.posZ - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (EntityRenderRange.shouldSkipByDistance(entity, distSq)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
