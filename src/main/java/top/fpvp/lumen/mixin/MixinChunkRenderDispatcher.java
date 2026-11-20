package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import top.fpvp.lumen.LumenConfig;

/**
 * 让区块网格构建的 worker 线程数可配置，并<b>同步扩容配套的构建器池</b>。
 *
 * <p>原版在 {@code ChunkRenderDispatcher} 构造函数里硬编码了两个数字：
 * <ul>
 *   <li>{@code 2} —— worker 线程数；</li>
 *   <li>{@code 5} —— 空闲构建器池 {@code queueFreeRenderBuilders}
 *       的容量，以及往池里放几个构建器的循环次数（同一个字面量出现两次）。</li>
 * </ul>
 *
 * <h3>为什么必须两个一起改</h3>
 *
 * <p>这是本类最重要的一点，也是“只改线程数”最容易踩中的死锁：
 * {@code ChunkRenderWorker#getRegionRenderCacheBuilder()} 在某条分支里会调用
 * {@code ChunkRenderDispatcher#allocateRenderBuilder()}，而后者是
 * <b>无条件的 {@code BlockingQueue#take()}</b> —— 队列空时它会一直等下去。
 *
 * <p>于是：worker 数一旦超过空闲构建器数量，多出来的 worker 就永远拿不到构建器，
 * 永久阻塞在 {@code take()} 上。表现出来的正是“改了线程数，帧数不升反降”：
 * 队列里的任务没人取，主线程以为后台在忙，跑图时区块一片片不加载。
 * 它<b>不会崩溃、不会报错</b>，只会静静地卡住 —— 极难定位。
 *
 * <p>因此这里把 worker 数与构建器池一起改，保证每个 worker 随时都能拿到构建器，
 * 外加少量余量给主线程的同步构建路径。
 *
 * <h3>为什么用 ordinal</h3>
 *
 * <p>{@code 5} 在构造函数里出现了两次（队列容量、循环次数）。若不指定
 * {@code ordinal}，{@code @ModifyConstant} 会面对多个匹配点，行为取决于 Mixin 版本
 * （有的注入全部，有的直接报错）。显式指定 ordinal 让注入点唯一确定。
 * 两处本来就应该取同一个值，所以两个方法返回同样的计算结果。
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinChunkRenderDispatcher {

    /**
     * 空闲构建器的余量。
     *
     * <p>主线程在 {@code updateChunkLater} 的同步路径上也可能需要一个构建器，
     * 留出余量可以避免主线程与 worker 抢同一个池。每个
     * {@code RegionRenderCacheBuilder} 持有 4 个按图层划分的
     * {@code WorldRenderer}，内存开销可观，但与“卡死”相比完全值得。
     */
    private static final int BUILDER_HEADROOM = 3;

    /**
     * 期望的 worker 线程数。
     *
     * <p>{@code 0} = 保持原版（2 个）。上限按可用处理器数封顶：
     * 超过物理核心数只会让 worker 互相抢 L3 缓存，并与 GC 线程争 CPU，
     * 帧时间方差变大而吞吐不变。
     */
    private static int desiredWorkers() {
        if (!LumenConfig.enabled || LumenConfig.chunkBuilderThreads <= 0) {
            return 2;
        }
        int cap = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.min(LumenConfig.chunkBuilderThreads, cap);
    }

    /** 构建器池应有的容量：必须 >= worker 数，否则 worker 会永久阻塞。 */
    private static int desiredBuilderPool() {
        return Math.max(5, desiredWorkers() + BUILDER_HEADROOM);
    }

    @ModifyConstant(
            method = "<init>",
            constant = @Constant(intValue = 2, ordinal = 0),
            require = 0
    )
    private int lumen$workerThreadCount(int original) {
        int desired = desiredWorkers();
        // 只在玩家显式调大时才改动，默认行为与原版完全一致。
        return desired == 2 ? original : desired;
    }

    /**
     * 扩容 {@code queueFreeRenderBuilders} 的容量（构造函数里第一个 {@code 5}）。
     */
    @ModifyConstant(
            method = "<init>",
            constant = @Constant(intValue = 5, ordinal = 0),
            require = 0
    )
    private int lumen$renderBuilderPoolCapacity(int original) {
        return desiredBuilderPool();
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
            constant = @Constant(intValue = 5, ordinal = 1),
            require = 0
    )
    private int lumen$renderBuilderPrefillCount(int original) {
        return desiredBuilderPool();
    }
}
