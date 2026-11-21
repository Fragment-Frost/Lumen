package top.fpvp.lumen.mixin;

import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.audio.SoundOptimizer;

/**
 * 声音优化接管点。
 *
 * <h2>注入点一：{@code SoundManager#loadSoundSystem} 的 HEAD</h2>
 *
 * <p>这是<b>唯一正确</b>的设置声道数时机。反汇编确认
 * {@code paulscode.sound.Library} 的构造函数里就把
 * {@code normalChannelSourceNames} 数组按
 * {@code SoundSystemConfig.getNumberNormalChannels()} 的长度分配好了。
 *
 * <p>一旦 {@code loadSoundSystem} 跑过，声道池就定型了。<b>之后再改
 * {@code numberNormalChannels} 字段完全无效</b> —— 字段变了但数组不会重建，
 * 表现为"配了 256 声道但还是丢声音"，这种失败极难归因。
 *
 * <h2>注入点二：{@code SoundHandler#playSound} 的 HEAD</h2>
 *
 * <p>做去重与限流。这一步不碰 OpenAL，纯粹是减少提交量，
 * 所以在主线程上做是安全的、也是必要的（Paulscode 的 {@code SoundSystem}
 * 用 {@code THREAD_SYNC} 监视器把命令串行化，绕开它会破坏其状态机）。
 *
 * <h2>为什么不用 @Redirect 替换 playSound</h2>
 *
 * <p>我们只做"要不要播"的判定，不做"怎么播"的替换。
 * {@code @Inject} + {@code ci.cancel()} 表达的正是这个语义，
 * 而 {@code @Redirect} 会要求我们自己重新实现整个播放路径 ——
 * 那等于把 vanilla 的声音逻辑复制一份，后续任何一方改动都会不一致。
 */
@Mixin(SoundHandler.class)
public abstract class MixinSoundHandler {

    /**
     * 在声音系统加载前提高声道数。
     *
     * <p>写在 {@code SoundHandler} 的构造函数里（HEAD）比写在
     * {@code SoundManager#loadSoundSystem} 更早：{@code SoundHandler}
     * 构造时会创建 {@code SoundManager}，而后者在构造末尾就尝试
     * {@code loadSoundSystem}。在 SoundHandler 构造的最开始设置，
     * 能保证一定赶在声道池分配之前。
     *
     * <p>同时保留了 {@code loadSoundSystem} 上的注入作为兜底
     * （见本类下方的第二个方法），因为资源重载会触发
     * {@code reloadSoundSystem}，那条路径不经过构造函数。
     */
    @Inject(method = "<init>", at = @At("HEAD"), require = 0)
    private void lumen$raiseSoundChannelsEarly(CallbackInfo ci) {
        SoundOptimizer.applyChannelCount();
    }

    /**
     * 兜底：资源重载时 {@code reloadSoundSystem} 会先卸载再重新加载，
     * 那条路径不经过构造函数，需要在这里再设一次。
     *
     * <p>注意 {@code loadSoundSystem} 是 private 且 synchronized，
     * 但 mixin 可以直接注入 private 方法。
     */
    @Inject(method = "reloadSoundSystem", at = @At("HEAD"), require = 0)
    private void lumen$raiseSoundChannelsOnReload(CallbackInfo ci) {
        SoundOptimizer.applyChannelCount();
    }

    /**
     * 去重与限流。
     *
     * <p>拦在 {@code SoundHandler#playSound} 而不是 {@code SoundManager#playSound}：
     * 前者是 mod 和原版都会走的公共入口，覆盖面更全；后者是内部实现，
     * 在某些路径上会被直接调用，拦在那里会漏掉一部分。
     */
    @Redirect(
            method = "playSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/audio/SoundManager;"
                            + "playSound(Lnet/minecraft/client/audio/ISound;)V"
            ),
            require = 0
    )
    private void lumen$throttlePlaySound(SoundManager manager, ISound sound) {
        if (SoundOptimizer.shouldSubmit(sound)) {
            manager.playSound(sound);
        }
    }
}
