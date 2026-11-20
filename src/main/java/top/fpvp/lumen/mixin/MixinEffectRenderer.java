package top.fpvp.lumen.mixin;

import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.fpvp.lumen.render.OcclusionCuller;

/**
 * 粒子视锥剔除。
 *
 * <p>原版对粒子<b>完全不剔除</b>：{@code EffectRenderer#renderParticles} 会遍历
 * {@code fxLayers} 里的每一个粒子无条件调用 {@code renderParticle}。
 * 一次爆炸、一个刷怪笼、一场雨就能产生上千个粒子，其中过半在摄像机背后 ——
 * 它们全部被提交进顶点缓冲，白占 CPU 与带宽。
 *
 * <p>拦截点是 {@code EntityFX#renderParticle} 这个唯一入口，两个渲染方法
 * （{@code renderParticles} 与 {@code renderLitParticles}）都汇到这里，
 * 所以只需在这一点上处理。
 *
 * <p><b>注意剔除不能影响“粒子还在存活”这件事</b>：这里只跳过<b>绘制</b>，
 * 粒子的 tick 与位置更新完全不受影响。因此被剔除的粒子在重新进入视野时
 * 会出现在它“本该在”的位置，不会有拖影或位置跳变。
 */
@Mixin(EffectRenderer.class)
public abstract class MixinEffectRenderer {

    @Redirect(
            method = "renderParticles",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/EntityFX;"
                            + "renderParticle(Lnet/minecraft/client/renderer/WorldRenderer;"
                            + "Lnet/minecraft/entity/Entity;FFFFFF)V"
            ),
            require = 0
    )
    private void lumen$cullParticle(EntityFX particle, WorldRenderer buffer, Entity viewEntity,
                                    float partialTicks, float rotationX, float rotationZ,
                                    float rotationYZ, float rotationXY, float rotationXZ) {
        // 用实体包围盒估算粒子尺寸：particleScale 是 protected 字段，
        // 从 mixin 里直接读需要 accessor，而包围盒是公共 API，语义也够用。
        AxisAlignedBB box = particle.getEntityBoundingBox();
        float scale = 0.5F;
        if (box != null) {
            scale = (float) Math.max(box.maxX - box.minX, Math.max(box.maxY - box.minY, box.maxZ - box.minZ));
        }
        if (OcclusionCuller.get().shouldCullParticle(particle.posX, particle.posY, particle.posZ, scale)) {
            return;
        }
        particle.renderParticle(buffer, viewEntity, partialTicks, rotationX, rotationZ,
                rotationYZ, rotationXY, rotationXZ);
    }
}
