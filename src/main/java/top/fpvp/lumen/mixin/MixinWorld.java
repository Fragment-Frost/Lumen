package top.fpvp.lumen.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.util.BlockPos;
import top.fpvp.lumen.lighting.LightEngine;
import top.fpvp.lumen.lighting.LightingOptimizer;
import top.fpvp.lumen.render.AsyncOcclusionCuller;
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
 *   <li><b>Starlight 引擎</b>（{@link LightEngine}）：方块变化时只登记坐标，
 *       tick 边界批量做增/减光 BFS。侵入性最强、收益也最大；</li>
 *   <li><b>批处理</b>（{@link LightingOptimizer}）：保留原版洪泛，只做去重与延迟；</li>
 *   <li><b>原版</b>：两者都关闭或检测到冲突时。</li>
 * </ol>
 *
 * <p>模式在每次调用时判断，而不是类加载时固定 —— 这样游戏内切换配置立即生效。
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
     * <p>该调用的返回值在字节码中被 {@code pop} 掉，所以返回什么不影响
     * {@code setBlockState} 的语义。但 {@code @Redirect} 是<b>完全替换</b>原调用，
     * 因此当优化关闭时必须自己把原版 {@code checkLight} 调回去。
     *
     * <p><b>关键：这里只登记，不计算。</b>批量的真正处理在 {@link #lumen$flushLight}。
     * 在方块变更的当下就计算光照，会读到尚未稳定的邻居光值，
     * 大批量变更（爆炸、结构生成）时会算出一片错误的光 —— 这是上一版
     * "阴影渲染错误"的根因。详见 {@link LightEngine} 的类注释。
     */
    @Redirect(
            method = "setBlockState(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;checkLight(Lnet/minecraft/util/BlockPos;)Z"
            )
    )
    private boolean lumen$deferCheckLight(World world, BlockPos pos) {
        // 第一优先：Starlight 引擎（只登记，不计算）。
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
     * 冲刷光照队列。
     *
     * <p>挂在 {@code updateEntities} 的 RETURN：爆炸、岩浆引燃、生物改方块
     * 全部发生在实体 tick 里，这里冲刷能保证"同一 tick 内的所有方块变更
     * 都已完成"之后才计算光照 —— 这正是增量算法需要的自洽输入。
     *
     * <p>为什么不在 {@code setBlockState} 之后立刻 flush：那等于没有延迟，
     * 回到逐个计算的旧行为。
     *
     * <p>为什么不在 {@code tick} 的 HEAD flush：那时新 tick 的方块变更
     * 已经开始，flush 会与它们交错。
     */
    @Inject(method = "updateEntities", at = @At("RETURN"))
    private void lumen$flushLight(CallbackInfo ci) {
        World world = (World) (Object) this;
        if (LightEngine.isEnabled()) {
            engineFor(world).flush();
        }
        LightingOptimizer.get(world).flush();
    }

    /**
     * 方块 tick 阶段结束时也冲刷一次。
     *
     * <p>{@code updateBlocks} 里的随机 tick（草蔓延、火传播、流体流动）
     * 同样会改方块。在它结束处再冲刷，能缩短"光照延迟到 tick 末"的窗口，
     * 让紧接着的实体 tick 读到正确的光照。
     */
    @Inject(method = "updateBlocks", at = @At("RETURN"))
    private void lumen$flushLightAfterBlocks(CallbackInfo ci) {
        World world = (World) (Object) this;
        if (LightEngine.isEnabled()) {
            engineFor(world).flush();
        }
        LightingOptimizer.get(world).flush();
    }

    /**
     * 每 tick 开头推进各个缓存的 tick 边界，并清理世界级临时状态。
     *
     * <p>放在 HEAD 而不是 RETURN：实体 tick 在 {@code updateEntities} 里，
     * 而它在 {@code world.tick()} 内部。若在 RETURN 清缓存，本 tick 内
     * 刚算好的数据会在下次查询前被丢掉，等于完全没缓存。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void lumen$beginTick(CallbackInfo ci) {
        World world = (World) (Object) this;
        long time = world.getTotalWorldTime();
        if (FluidFlowCache.isEnabled()) {
            FluidFlowCache.get().beginTick(time);
        }
        VisibleFacingCache.beginTick(time);
        // 方块实体包围盒缓存按 tick 清理：位置在 tick 内不会变化，
        // 但世界卸载后复用的位置可能撞上旧记录。
        BlockEntityOptimizer.reset();
        // 异步剔除器的迟滞计数按 tick 衰减，避免实体长期停在"已遮挡"状态。
        AsyncOcclusionCuller.decayStreaks();
    }
}
