package top.fpvp.lumen.lighting;

import net.minecraftforge.fml.common.Loader;
import top.fpvp.lumen.LumenProfiler;

import java.util.ArrayList;
import java.util.List;

/**
 * 检测会与光照重写冲突的 mod，必要时自动关闭相关优化。
 *
 * <h2>为什么必须有这一层</h2>
 *
 * <p>完全重写光照引擎是本项目里<b>侵入性最强</b>的一项：它接管了
 * {@code World#checkLight} 这条所有方块变化都会经过的路径，并且直接写
 * {@code NibbleArray}。任何同样改写光照的 mod 都会与之产生两种冲突：
 *
 * <ul>
 *   <li><b>算法冲突</b>：两个引擎各自维护自己的队列与“权威”光值，
 *       互相覆盖对方的结果，表现为光照闪烁、残留亮斑、部分区域永不更新；</li>
 *   <li><b>数据冲突</b>：一方写了 {@code NibbleArray}，另一方基于旧值继续推导，
 *       最终状态取决于调用顺序 —— 不可预测。</li>
 * </ul>
 *
 * <p>这类问题的可怕之处在于<b>它不崩溃</b>。玩家看到的是“装了某个光影之后
 * 光有点怪”，几乎不可能归因到这个 mod。
 *
 * <h2>策略</h2>
 *
 * <p>按“对方改动得多深”分三档处理：
 * <ol>
 *   <li><b>整体光照重写</b>（OptiFine 的平滑光照/自定义光照、其它 Starlight 移植）
 *       → 直接关闭本 mod 的光照引擎，保留批处理，让原版（或对方）负责；</li>
 *   <li><b>会读光照做渲染</b>（光影包、动态光源）
 *       → 保留引擎，但关闭“区段折叠”这类会延迟光值可见性的优化；</li>
 *   <li><b>无冲突</b> → 全开。</li>
 * </ol>
 *
 * <p>检测结果只算一次并缓存：{@code Loader.isModLoaded} 在运行期是稳定的，
 * 而这段逻辑会在热路径上被问到（{@link LightEngine#isEnabled()}）。
 */
public final class LightEngineCompat {

    /** 明确与光照重写冲突的 mod（整体接过光照职责）。 */
    private static final String[] FULL_LIGHT_ENGINE_MODS = {
            // OptiFine：接管了平滑光照、动态光源、自定义光照贴图。
            "OptiFine",
            "optifine",
            // 其它 Starlight 移植 / 光照重写。
            "starlight",
            "Starlight",
            "phosphor",
            "Phosphor",
            // 常用的光照修复类。
            "lightspeed",
            "LightSpeed",
    };

    /**
     * 只是“消费”光照数据、但不重写引擎的 mod。
     *
     * <p>与这些共存是安全的，但它们对光值的<b>时序</b>敏感（例如动态光源
     * 依赖光值即时更新），所以 coexist 模式下要关掉延迟可见的优化。
     */
    private static final String[] LIGHT_CONSUMER_MODS = {
            "shadersmod",
            "ShadersMod",
            "oculus",
            "Oculus",
            "iris",
            "Iris",
            "dynamiclights",
            "DynamicLights",
    };

    /** 检测结果缓存。0 = 未检测，1 = 无冲突，2 = 消费者，3 = 整体冲突。 */
    private static volatile int state;

    private static volatile String conflictName;

    private LightEngineCompat() {
    }

    private static void detect() {
        if (state != 0) {
            return;
        }
        synchronized (LightEngineCompat.class) {
            if (state != 0) {
                return;
            }

            List<String> found = new ArrayList<String>();

            for (String mod : FULL_LIGHT_ENGINE_MODS) {
                if (isLoaded(mod)) {
                    conflictName = mod;
                    state = 3;
                    logConflict(mod, "整体接管光照", found);
                    return;
                }
            }
            for (String mod : LIGHT_CONSUMER_MODS) {
                if (isLoaded(mod)) {
                    found.add(mod);
                }
            }
            if (!found.isEmpty()) {
                conflictName = found.get(0);
                state = 2;
                logConflict(conflictName, "消费光照数据", found);
                return;
            }

            state = 1;
        }
    }

    private static boolean isLoaded(String modId) {
        try {
            return Loader.isModLoaded(modId);
        } catch (Throwable t) {
            // FML 不可用（例如在纯客户端测试环境里）：按“没装”处理。
            return false;
        }
    }

    private static void logConflict(String mod, String kind, List<String> all) {
        LumenProfiler.log().warn(
                "[Lumen] 检测到 {}（{}）。为避免光照结果互相覆盖，已自动调整：", mod, kind);
        LumenProfiler.log().warn(
                "[Lumen]   - 光照引擎：关闭（交由原版/对方处理）");
        LumenProfiler.log().warn(
                "[Lumen]   - 区段折叠：关闭（保证光值即时可见）");
        if (all.size() > 1) {
            LumenProfiler.log().warn("[Lumen]   全部相关 mod: {}", all);
        }
    }

    /**
     * 是否应该完全禁用本 mod 的光照重写。
     *
     * <p>返回 {@code true} 时 {@link LightEngine#isEnabled()} 为 false，
     * 调用方回退原版 {@code checkLight}。
     */
    public static boolean shouldDisable() {
        detect();
        return state == 3;
    }

    /**
     * 是否应该关闭“延迟可见”的优化（区段折叠）。
     *
     * <p>消费者类 mod 不重写引擎，但依赖光值即时更新。
     */
    public static boolean shouldDisableDeferredLighting() {
        detect();
        return state >= 2;
    }

    /** 供调试输出。 */
    public static String describe() {
        detect();
        switch (state) {
            case 1:
                return "无冲突";
            case 2:
                return "共存(" + conflictName + ")";
            case 3:
                return "已禁用(" + conflictName + ")";
            default:
                return "未检测";
        }
    }
}
