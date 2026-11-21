package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import top.fpvp.lumen.thread.ChunkThreadTuning;

/**
 * 区块网格构建线程数与构建器池的自适应调整。
 *
 * <p>原版在 {@code ChunkRenderDispatcher} 构造函数里硬编码了两个数字：
 * <ul>
 *   <li>{@code 2} —— worker 线程数；</li>
 *   <li>{@code 5} —— 空闲构建器池 {@code queueFreeRenderBuilders} 的容量，
 *       以及往池里预置几个构建器的循环次数（同一个字面量出现两次）。</li>
 * </ul>
 *
 * <p>具体的推算公式与设计理由在 {@link ChunkThreadTuning} 里，本类只负责注入。
 * 把它抽出去是因为启动日志也要打印“实际会开几个线程”——
 * 公式写两遍必然漂移，日志说 10 个、实际开 6 个，比不打印更糟。
 *
 * <h3>必须两个常量一起改</h3>
 *
 * <p>{@code allocateRenderBuilder()} 是无条件的 {@code BlockingQueue#take()}。
 * 只改线程数而不扩容构建器池，多出来的 worker 会永久阻塞在 {@code take()} 上 ——
 * 不崩溃、不报错，只是跑图时区块一片片不加载、帧数反而下降。
 * {@link ChunkThreadTuning#builderCount()} 里有 {@code builders >= workers} 的硬性兜底。
 *
 * <h3>为什么用 ordinal</h3>
 *
 * <p>{@code 5} 在构造函数里出现两次（队列容量、循环次数）。若不指定
 * {@code ordinal}，{@code @ModifyConstant} 面对多个匹配点时行为取决于 Mixin 版本。
 * 显式指定 ordinal 让注入点唯一确定。两处必须取同一个值。
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinChunkRenderDispatcher {

    @ModifyConstant(
            method = "<init>",
            constant = @Constant(intValue = ChunkThreadTuning.VANILLA_WORKERS, ordinal = 0),
            require = 0
    )
    private int lumen$workerThreadCount(int original) {
        int desired = ChunkThreadTuning.workerCount();
        // 与原版一致时返回原始常量，保持字节码不变。
        return desired == ChunkThreadTuning.VANILLA_WORKERS ? original : desired;
    }

    /**
     * 扩容 {@code queueFreeRenderBuilders} 的容量（构造函数里第一个 {@code 5}）。
     */
    @ModifyConstant(
            method = "<init>",
            constant = @Constant(intValue = ChunkThreadTuning.VANILLA_BUILDERS, ordinal = 0),
            require = 0
    )
    private int lumen$renderBuilderPoolCapacity(int original) {
        return ChunkThreadTuning.builderCount();
    }

    /**
     * 扩容往池里预置构建器的循环次数（构造函数里第二个 {@code 5}）。
     *
     * <p>必须与容量取同一个值：容量变大而预置数量不变，池里就只有 5 个构建器，
     * worker 照样会阻塞；反之容量小、预置数量大，多出来的 {@code add} 会被
     * 静默丢弃（{@code add} 在满队列上返回 false 而不是抛异常）。
     */
    @ModifyConstant(
            method = "<init>",
            constant = @Constant(intValue = ChunkThreadTuning.VANILLA_BUILDERS, ordinal = 1),
            require = 0
    )
    private int lumen$renderBuilderPrefillCount(int original) {
        return ChunkThreadTuning.builderCount();
    }
}
