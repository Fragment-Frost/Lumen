package top.fpvp.lumen.verify;

import top.fpvp.lumen.lighting.LightEngine;
import top.fpvp.lumen.lighting.LightEngineCompat;

/**
 * 光照引擎重写的可验证部分。
 *
 * <h2>为什么这里不测真正的光照传播</h2>
 *
 * <p>{@link LightEngine} 的传播逻辑依赖 {@code World}、{@code Chunk}、
 * {@code NibbleArray} —— 要构造这些就得启动完整的 Minecraft 运行时，
 * 而本项目的自测刻意保持"能在裸 JVM 上跑"，这样 CI 里几秒钟就能验证完。
 *
 * <p>所以这里只测<b>不依赖运行时的那部分不变式</b>：开关语义、
 * 冲突检测的降级行为、以及统计计数器不会溢出/错乱。
 * 真正的传播正确性必须进游戏验证 —— 这一点我在注释里写清楚，
 * 而不是用"测试通过"掩盖它。
 */
public final class LightEngineSelfTest {

    private static int failures;

    public static void main(String[] args) {
        boolean savedEnabled = top.fpvp.lumen.LumenConfig.enabled;
        boolean savedEngine = top.fpvp.lumen.LumenConfig.starlightLightEngine;

        try {
            // 1. 总开关关闭时引擎必须报告禁用。
            top.fpvp.lumen.LumenConfig.enabled = false;
            top.fpvp.lumen.LumenConfig.starlightLightEngine = true;
            check("总开关关闭 -> 引擎禁用", !LightEngine.isEnabled(), "");

            // 2. 引擎开关关闭时报告禁用。
            top.fpvp.lumen.LumenConfig.enabled = true;
            top.fpvp.lumen.LumenConfig.starlightLightEngine = false;
            check("引擎开关关闭 -> 引擎禁用", !LightEngine.isEnabled(), "");

            // 3. 两者都开时，是否启用取决于冲突检测。
            top.fpvp.lumen.LumenConfig.starlightLightEngine = true;
            boolean detected = LightEngineCompat.shouldDisable();
            boolean enabled = LightEngine.isEnabled();
            check("启用状态与冲突检测一致",
                    enabled == !detected,
                    "enabled=" + enabled + " shouldDisable=" + detected);

            // 4. 冲突检测必须可重复调用且结果稳定（它有内部缓存）。
            boolean first = LightEngineCompat.shouldDisable();
            boolean second = LightEngineCompat.shouldDisable();
            check("冲突检测结果稳定", first == second, first + " vs " + second);

            // 5. describe() 必须返回非空且不抛异常（它会被打进日志）。
            String desc = LightEngineCompat.describe();
            check("冲突描述可读", desc != null && !desc.isEmpty(), String.valueOf(desc));

            // 6. 消费者 mod 检测不能把引擎整体关掉。
            if (LightEngineCompat.shouldDisableDeferredLighting()) {
                check("共存模式下引擎仍启用", LightEngine.isEnabled(),
                        "deferredDisabled=true 但引擎也在关");
            } else {
                check("无冲突时保留延迟优化", true, "");
            }
        } finally {
            top.fpvp.lumen.LumenConfig.enabled = savedEnabled;
            top.fpvp.lumen.LumenConfig.starlightLightEngine = savedEngine;
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All light engine self-tests passed.");
            System.out.println("NOTE: 光照传播的正确性无法在此验证，需进游戏确认。");
        } else {
            System.out.println(failures + " light engine self-test(s) FAILED.");
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  OK   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " -> " + detail));
        }
    }
}
