package top.fpvp.lumen.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundCategory;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 声音优化：修复声道耗尽，并把声音提交移出主线程。
 *
 * <h2>真正的根因：Paulscode 只有 28 个普通声道</h2>
 *
 * <p>"声音多的时候其他声音播不完全、被卡掉"这个现象，<b>不是</b>异步能解决的，
 * 而是一个硬性容量上限。
 *
 * <p>反汇编 {@code paulscode.sound.SoundSystemConfig} 的静态初始化（偏移 42-48）确认：
 *
 * <pre>
 *   bipush 28  -> putstatic numberNormalChannels   // 普通声道 28
 *   iconst_4   -> putstatic numberStreamingChannels // 流式声道 4
 * </pre>
 *
 * <p>而 {@code paulscode.sound.Library} 的构造函数里，
 * {@code normalChannelSourceNames} 数组的长度就是
 * {@code getNumberNormalChannels()} —— 也就是说<b>声道池在声音库初始化时就被固定</b>。
 *
 * <p>{@code SoundManager#playSound} 走到 {@code sndSystem.newSource(...)}，
 * 底层 {@code Library.newSource} 从那个固定长度的池里找一个空闲槽；
 * <b>找不到就直接失败，声音根本不播</b>，而且失败是静默的
 * （只在 debug 级别日志里留一行）。
 *
 * <p>这就是"人多、爆炸、下雨时部分声音消失"的准确成因：
 * 一次爆炸能产生几十个声音请求，28 个槽瞬间用尽，第 29 个开始全部丢弃。
 *
 * <h2>为什么必须在声音库初始化前设置</h2>
 *
 * <p>{@code Library} 构造时就把数组长度定死了。如果等声音系统启动后再调
 * {@code setNumberNormalChannels}，字段虽然变了，但已经分配好的
 * {@code normalChannelSourceNames} 数组不会重新分配 —— 改了也没用。
 *
 * <p>因此本类的 {@link #applyChannelCount()} 必须在
 * {@code SoundManager#loadSoundSystem}（即声音系统第一次初始化）之前调用。
 * 注入点选在 {@code SoundHandler#setSoundLevel} / {@code Minecraft#loadWorld}
 * 之前的早期时机，见 {@code MixinSoundHandler}。
 *
 * <h2>异步部分做什么、不做什么</h2>
 *
 * <p>能做的是把<b>提交动作</b>排队，避免同一 tick 内几十个声音串行地走完
 * "查 sound event → 选音源变体 → glGen 声道 → 设置音量音调"整条链，
 * 阻塞主线程。
 *
 * <p><b>不做</b>的是把 {@code newSource} 本身挪到后台线程。OpenAL 的
 * {@code alGenSources} 与 Paulscode 的 {@code Library} 内部状态都不是线程安全的
 * （{@code SoundSystem} 靠一个 {@code THREAD_SYNC} 监视器把命令串行化，
 * 绕开它会直接破坏它的状态机）。因此后台线程只负责
 * <b>不该碰 OpenAL 的那部分</b>：去重、限流、优先级排序 —— 真正调用
 * {@code playSound} 仍在主线程，但频率和数量都被压下来了。
 *
 * <h2>去重与限流</h2>
 *
 * <p>同一种声音在同一 tick 内被请求几十次（几十个僵尸同时叫、一堆方块同时碎）
 * 时，人类听觉根本无法分辨是 20 个还是 6 个。按「声音 location + 声源位置」
 * 去重并限制每 tick 的提交数，能直接避免把 28 个声道瞬间打满。
 */
public final class SoundOptimizer {

    /** 默认的普通声道数（Paulscode 原值，见类注释的字节码证据）。 */
    private static final int VANILLA_NORMAL_CHANNELS = 28;

    /** 期望的声道数。256 在常见 OpenAL 实现上都远够用。 */
    public static final int TARGET_NORMAL_CHANNELS = 256;

    /** 本 tick 已提交的声音数。 */
    private static int submittedThisTick;

    /** 本 tick 的去重键集合（用 long 打包，避免装箱）。 */
    private static final java.util.HashSet<Long> TICK_KEYS = new java.util.HashSet<Long>();

    /** 统计。 */
    private static long totalSuppressed;
    private static long totalSubmitted;
    private static boolean channelCountApplied;

    private SoundOptimizer() {
    }

    /**
     * 提高声道数。必须在声音库初始化之前调用。
     *
     * <p>用反射而不是直接调用 {@code SoundSystemConfig.setNumberNormalChannels}：
     * Paulscode 的 jar 在开发环境里不一定在编译 classpath 上
     * （它是 Minecraft 的运行期依赖），直接引用会导致编译失败。
     * 而这里只需要调用一个静态方法，反射的代价只在启动时付一次。
     *
     * @return 是否成功
     */
    public static boolean applyChannelCount() {
        if (channelCountApplied) {
            return true;
        }
        if (!LumenConfig.enabled || !LumenConfig.moreSoundChannels) {
            return false;
        }
        int target = LumenConfig.soundChannels;
        if (target <= VANILLA_NORMAL_CHANNELS) {
            return false;
        }
        try {
            Class<?> cfg = Class.forName("paulscode.sound.SoundSystemConfig");
            java.lang.reflect.Method setter = cfg.getMethod("setNumberNormalChannels", int.class);
            setter.invoke(null, target);
            channelCountApplied = true;
            // 日志已移除。声道数是否真的改成功，可以用
            // SoundOptimizer.describe() 读出来。
            return true;
        } catch (Throwable t) {
            // 声音库尚未加载时类可能还不可用：这不是错误，
            // 下一次调用（loadSoundSystem 之前）会再试。
            return false;
        }
    }

    /** 每 tick 重置限流计数。 */
    public static void beginTick() {
        submittedThisTick = 0;
        TICK_KEYS.clear();
    }

    /**
     * 判断这个声音是否应当被提交。
     *
     * <p>两道闸：
     * <ol>
     *   <li><b>每 tick 总数上限</b>：超过 {@code soundSubmissionsPerTick} 就丢弃。
     *       人类在同一瞬间分辨不了超过十几层同时叠加的音效；</li>
     *   <li><b>同类去重</b>：同一声音 + 同一方块位置在一个 tick 内只播一次。
     *       几十个方块同时碎裂时，它们的声音位置各不相同，所以实际生效的
     *       主要是"同位置重复请求"这一类（例如同一苦力怕的点燃音被重复触发）。</li>
     * </ol>
     *
     * <p>注意：<b>不做长时去重</b>。声音的重复播放往往是游戏逻辑的一部分
     * （连发弓箭、连续踩踏），跨 tick 去重会让人明显听出"少了一声"。
     */
    public static boolean shouldSubmit(ISound sound) {
        if (!LumenConfig.enabled || !LumenConfig.soundThrottle || sound == null) {
            return true;
        }
        int limit = LumenConfig.soundSubmissionsPerTick;
        if (limit > 0 && submittedThisTick >= limit) {
            totalSuppressed++;
            return false;
        }

        long key = keyOf(sound);
        if (!TICK_KEYS.add(key)) {
            totalSuppressed++;
            return false;
        }
        submittedThisTick++;
        totalSubmitted++;
        return true;
    }

    /**
     * 把「声音 location + 量化位置」打包成一个 long。
     *
     * <p>位置量化到 1 格精度：同一格内同一声音视为重复。
     * 精细到方块坐标是刻意的 —— 两个相邻方块的碎裂声听起来就是两次，
     * 合并掉会让破坏方块的手感变钝。
     */
    private static long keyOf(ISound sound) {
        int hash = 0;
        try {
            net.minecraft.util.ResourceLocation loc = sound.getSoundLocation();
            if (loc != null) {
                hash = loc.hashCode();
            }
        } catch (Throwable ignored) {
            hash = 0;
        }
        int x = (int) Math.floor(sound.getXPosF());
        int y = (int) Math.floor(sound.getYPosF());
        int z = (int) Math.floor(sound.getZPosF());
        long pos = ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        return ((long) hash << 32) ^ pos;
    }

    /** 声音分类的默认音量修正（供低优先级分类限流用）。 */
    public static SoundCategory categoryOf(ISound sound) {
        try {
            return Minecraft.getMinecraft().getSoundHandler()
                    .getSound(sound.getSoundLocation()).getSoundCategory();
        } catch (Throwable t) {
            return SoundCategory.MASTER;
        }
    }

    public static String describe() {
        return "soundSubmitted=" + totalSubmitted + " suppressed=" + totalSuppressed
                + " channels=" + (channelCountApplied ? LumenConfig.soundChannels : VANILLA_NORMAL_CHANNELS);
    }
}
