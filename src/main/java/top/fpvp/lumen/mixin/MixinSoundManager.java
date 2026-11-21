package top.fpvp.lumen.mixin;

import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.audio.SoundOptimizer;

/**
 * 声道数设置的底层兜底点。
 *
 * <h2>为什么需要单独一个 mixin</h2>
 *
 * <p>{@code reloadSoundSystem} 是 <b>{@code SoundManager}</b> 的方法，
 * 不是 {@code SoundHandler} 的。第一版把它写进了
 * {@code @Mixin(SoundHandler.class)} 里，混入给出的反馈是：
 *
 * <pre>
 *   Unable to determine descriptor for @Inject target method
 * </pre>
 *
 * <p>然后因为 {@code require = 0}，这条注入被<b>静默跳过</b> ——
 * 编译通过、启动无异常，只是"资源重载后声道数又变回 28"这件事
 * 永远不会被修复。这正是一开始定的规矩为什么值得：
 * <b>mixin 的静默失败必须被构建期校验抓住</b>，
 * 否则只能靠玩家报告"换了个材质包之后声音又开始丢了"。
 *
 * <p>{@code MixinTargetVerifier} 现在会检查
 * {@code SoundManager#reloadSoundSystem} 的存在性，而
 * {@code MixinGatingSelfTest} 会检查本类是否被正确门控。
 *
 * <h2>为什么重载时必须重设</h2>
 *
 * <p>{@code reloadSoundSystem} 的流程是"卸载旧的 {@code SoundSystem}
 * 实例 → 新建一个"。新建时会重新走 {@code Library} 构造，
 * 而那里按 {@code getNumberNormalChannels()} 分配声道数组。
 * 如果 {@code numberNormalChannels} 已经被我们改成了 256，
 * 这一步会自然拿到 256 —— 但显式再设一次更可靠：
 * 某些资源包/mod 会重置这个静态字段。
 */
@Mixin(SoundManager.class)
public abstract class MixinSoundManager {

    /**
     * 声音系统重载前确认声道数。
     *
     * <p>挂在 HEAD 而不是 RETURN：声道池在重载<b>过程中</b>就分配好了，
     * 挂在 RETURN 就太晚了（数组已经按旧值建好）。
     */
    @Inject(method = "reloadSoundSystem", at = @At("HEAD"), require = 0)
    private void lumen$raiseSoundChannelsBeforeReload(CallbackInfo ci) {
        SoundOptimizer.applyChannelCount();
    }

    /**
     * 同一条路径的另一半：{@code loadSoundSystem} 是 private 且
     * synchronized，它在第一次初始化与重载时都会被调用。
     *
     * <p>在这里也拦一次，能覆盖"未经 {@code reloadSoundSystem}
     * 直接调用 {@code loadSoundSystem}"的情况。
     */
    @Inject(method = "loadSoundSystem", at = @At("HEAD"), require = 0)
    private void lumen$raiseSoundChannelsBeforeLoad(CallbackInfo ci) {
        SoundOptimizer.applyChannelCount();
    }
}
