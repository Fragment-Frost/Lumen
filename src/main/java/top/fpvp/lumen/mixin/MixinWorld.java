package top.fpvp.lumen.mixin;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.lighting.LightingOptimizer;
import top.fpvp.lumen.world.FluidFlowCache;

/**
 * 接管并合并 {@code World#setBlockState} 触发的光照校验。
 *
 * <p>1.8.9 的 {@code setBlockState} 在方块的光照不透明度或发光值发生变化时，
 * 会立即调用 {@code checkLight(pos)}，而 {@code checkLight} 会对天空光与方块光
 * 各做一次 6 邻域洪泛。爆炸、多方块结构生成等场景会在同一 tick 内对同一批
 * 位置反复触发，造成大量重复计算。
 *
 * <p>这里把这一刻的校验登记到 {@link LightingOptimizer}，由它在 tick 边界
 * 统一去重后冲刷一次。
 */
@Mixin(World.class)
public abstract class MixinWorld {

    /**
     * 拦截 {@code setBlockState} 内部唯一的 {@code checkLight} 调用。
     *
     * <p>该调用的返回值在字节码中会被 {@code pop} 掉，所以返回什么并不影响
     * {@code setBlockState} 的语义。但必须注意：{@code @Redirect} 是**完全替换**
     * 原调用，因此当优化被关闭、或队列处于冲刷状态时，必须自己把原版
     * {@code checkLight} 调回去，否则光照将永远不会更新。
     */
    @Redirect(
            method = "setBlockState(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;checkLight(Lnet/minecraft/util/BlockPos;)Z"
            )
    )
    private boolean lumen$deferCheckLight(World world, BlockPos pos) {
        if (LightingOptimizer.get(world).deferCheckLight(pos)) {
            // 已登记，交由 tick 边界统一冲刷
            return false;
        }
        // 优化关闭 / 队列过载 / 正在冲刷：立即执行原版逻辑
        return world.checkLight(pos);
    }

    /**
     * 每 tick 结束时冲刷。
     *
     * <p>{@code World#tick} 会被子类通过 {@code super.tick()} 在最开始调用，
     * 因此这里的 RETURN 实际落在 tick 的起始处 —— 正好把上一 tick 遗留的
     * 校验补齐，保证新 tick 里的所有逻辑读到的是已更新的光照。
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void lumen$flushAtTickStart(CallbackInfo ci) {
        LightingOptimizer.get((World) (Object) this).flush();
    }

    /**
     * 方块 tick 阶段结束时再冲刷一次，缩小同 tick 内的光照延迟窗口。
     */
    @Inject(method = "updateBlocks", at = @At("RETURN"))
    private void lumen$flushAfterUpdateBlocks(CallbackInfo ci) {
        LightingOptimizer.get((World) (Object) this).flush();
    }

    /**
     * 实体 tick 结束时冲刷。
     *
     * <p>爆炸、岩浆引燃、生物改动方块等都在实体的 tick 里发生，而实体 tick
     * 由 {@code MinecraftServer} 在 {@code world.tick()} 之外驱动。若不在这里
     * 冲刷，这批光照要等到下一 tick 才开始计算。
     */
    @Inject(method = "updateEntities", at = @At("RETURN"))
    private void lumen$flushAfterEntities(CallbackInfo ci) {
        LightingOptimizer.get((World) (Object) this).flush();
    }

    /**
     * 每 tick 开头推进流体缓存的时间边界。
     *
     * <p>放在 tick 的 HEAD 而不是 RETURN：实体 tick 在 {@code updateEntities} 里，
     * 而 {@code updateEntities} 由 {@code world.tick()} 内部调用。若在 RETURN 清缓存，
     * 本 tick 内刚算好的流体向量会在下一次实体查询前被丢掉，等于完全没缓存。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void lumen$beginFluidTick(CallbackInfo ci) {
        World world = (World) (Object) this;
        if (FluidFlowCache.isEnabled()) {
            FluidFlowCache.get().beginTick(world.getTotalWorldTime());
        }
    }
}
