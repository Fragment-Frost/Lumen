package top.fpvp.lumen.mixin;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.lighting.LightEngine;
import top.fpvp.lumen.lighting.LightingOptimizer;
import top.fpvp.lumen.render.BlockEntityOptimizer;
import top.fpvp.lumen.render.VisibleFacingCache;
import top.fpvp.lumen.world.FluidFlowCache;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 光照系统接管点。
 *
 * <p>按优先级从高到低有三种模式：
 * <ol>
 *   <li><b>Starlight 引擎</b>（{@link LightEngine}）：把 {@code checkLight} 完全
 *       替换为增量式增/减光 BFS。这是侵入性最强、收益也最大的模式；</li>
 *   <li><b>批处理</b>（{@link LightingOptimizer}）：保留原版洪泛，只做去重与
 *       延迟，收益较小但风险低；</li>
 *   <li><b>原版</b>：两者都关闭或检测到冲突时。</li>
 * </ol>
 *
 * <p>模式选择在每次调用时判断，而不是在类加载时固定 —— 这样玩家在游戏内
 * 切换配置能立即生效，不需要重启。
 */
@Mixin(World.class)
public abstract class MixinWorld {

    /** 每个世界一个引擎实例，弱引用避免世界卸载后泄漏。 */
    private static final Map<World, LightEngine> ENGINES = new WeakHashMap<World, LightEngine>();

    private static LightEngine engineFor(World world) {
        synchronized (ENGINES) {
            LightEngine engine = ENGINES.get(world);
            if (engine == null) {
                engine = new LightEngine(world);
                ENGINES.put(world, engine);
            }
            return engine;
        }
    }

    /**
     * 拦截 {@code setBlockState} 内部唯一的 {@code checkLight} 调用。
     *
     * <p>该调用的返回值在字节码中会被 {@code pop} 掉，所以返回什么并不影响
     * {@code setBlockState} 的语义。但必须注意：{@code @Redirect} 是<b>完全替换</b>
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
        // 第一优先：Starlight 引擎。
        if (LightEngine.isEnabled()) {
            return engineFor(world).onBlockChange(pos);
        }
        // 第二优先：批处理。
        if (LightingOptimizer.get(world).deferCheckLight(pos)) {
            return false;
        }
        // 兜底：原版。
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
     * 每 tick 开头推进流体缓存与可见面缓存的时间边界。
     *
     * <p>放在 tick 的 HEAD 而不是 RETURN：实体 tick 在 {@code updateEntities} 里，
     * 而 {@code updateEntities} 由 {@code world.tick()} 内部调用。若在 RETURN 清缓存，
     * 本 tick 内刚算好的流体向量会在下一次实体查询前被丢掉，等于完全没缓存。
     *
     * <p>可见面缓存同理需要每 tick 整体过期：它的结果依赖方块数据，
     * 而方块随时可能被修改，1.8.9 又没有可靠的区块版本号可用。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void lumen$beginFluidTick(CallbackInfo ci) {
        World world = (World) (Object) this;
        if (FluidFlowCache.isEnabled()) {
            FluidFlowCache.get().beginTick(world.getTotalWorldTime());
        }
        VisibleFacingCache.beginTick(world.getTotalWorldTime());
    }

    /**
     * 每次 tick 开头清理方块实体包围盒缓存。
     *
     * <p>逐 tick 清理而不是逐帧：包围盒在一 tick 内不会变化，而缓存按
     * 「实例 + 位置」比对，即使不清理也只会在方块实体被替换时失效 ——
     * 但世界卸载后复用的位置可能撞上旧记录，清理最稳妥。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void lumen$resetBlockEntityCache(CallbackInfo ci) {
        BlockEntityOptimizer.reset();
    }
}
