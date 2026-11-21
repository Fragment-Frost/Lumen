package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import top.fpvp.lumen.thread.ChunkThreadTuning;

/**
 * 区块构建的等待策略：线程化 / 半堵塞 / 全堵塞。
 *
 * <h2>为什么注入在 {@code RenderGlobal#updateChunks(long)}</h2>
 *
 * <p>这是主线程<b>唯一</b>等待区块构建结果的地方。它的参数 {@code timeSlice}
 * 就是"主线程愿意为等待构建花多少纳秒"，原版传的是
 * {@code 5000000L}（5ms）。
 *
 * <p>三档模式本质上就是往这个参数里填不同的值：
 * <ul>
 *   <li><b>线程化</b>：传 0。主线程完全不等，构建全部留给 worker。
 *       帧率最平滑，但极远/新生成的区块可能短暂显示为白色空缺；</li>
 *   <li><b>半堵塞</b>：传一个小预算（默认 2ms）。比原版更保守，
 *       但仍能保证"几乎就绪"的构建在本帧拿到；</li>
 *   <li><b>全堵塞</b>：传一个大预算（默认 50ms）。等所有排队构建完成，
 *       绝不出现白色空缺，代价是跑图时明显顿挫。</li>
 * </ul>
 *
 * <h2>为什么用 {@code @ModifyVariable} 而不是 {@code @Inject}</h2>
 *
 * <p>只改参数、不改行为。{@code @Inject} 在 HEAD 处<b>无法</b>修改局部变量
 * （{@code CallbackInfo} 只提供取消与返回值，不提供参数改写）；
 * 要改参数必须用 {@code @ModifyVariable} 或 {@code @ModifyArgs}。
 *
 * <p>而<b>不能</b>用 {@code ci.cancel()} 跳过原方法：原方法除了等待，
 * 还负责把 worker 完成的构建<b>上传到显存</b>。跳过它会导致区块
 * 永远显示为白色空缺 —— 比原版的卡顿糟糕得多。
 *
 * <p>{@code argsOnly = true} 精确命中那个 {@code long} 参数，
 * 避免误改方法内部其它 long 局部变量（原方法内部确实还有时间戳变量）。
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalUpdateChunks {

    /**
     * 改写等待预算。返回值单位是<b>纳秒</b>。
     */
    @ModifyVariable(
            method = "updateChunks",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private long lumen$applyChunkBuildMode(long timeSlice) {
        long budgetMs = ChunkThreadTuning.waitBudgetMs();
        if (budgetMs <= 0L) {
            // 线程化：预算 0，等待循环立即退出，但上传逻辑照常执行。
            return 0L;
        }
        return budgetMs * 1_000_000L;
    }
}
