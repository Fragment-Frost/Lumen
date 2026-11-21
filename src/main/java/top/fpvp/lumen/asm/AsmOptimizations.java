package top.fpvp.lumen.asm;

import top.fpvp.lumen.LumenConfig;

/**
 * 关于「用 ASM 杀掉原版多余代码」的调查结论与谨慎实现。
 *
 * <h2>先说最重要的：绝大多数"屎山代码"的开销接近零</h2>
 *
 * <p>反汇编验证了两个最常被怀疑的目标，结论都是<b>不值得动</b>：
 *
 * <h3>1. Profiler（{@code startSection / endSection}）</h3>
 *
 * <pre>
 *   public void startSection(String name);
 *     0: getfield  profilingEnabled:Z       // 读一个布尔字段
 *     4: ifne      8
 *     7: return                             // 未启用 profiling → 立即返回
 *     ...
 * </pre>
 *
 * <p>它第一条指令就检查 {@code profilingEnabled}，未启用时只付一次
 * 字段读取 + 一次分支。而 {@code RenderGlobal} 整个类里只有
 * <b>5 处</b> {@code startSection}。就算全部删掉，省下的也是
 * 每秒几千次字段读取 —— 完全淹没在噪声里。
 *
 * <p>用 ASM 删除它需要：定位每一对 start/end、保持栈平衡、
 * 处理异常表。而收益是 0。这不是优化，是给自己找 bug。
 *
 * <h3>2. 各种空检查与边界检查</h3>
 *
 * <p>{@code if (x == null)}、{@code if (i < 0 || i > 255)} 这类判断，
 * 单次成本是一个比较加一个分支预测（现代 CPU 上预测成功即近乎免费）。
 * 删掉它们不会更快，但会让错误数据直接进数组下标 —— 结果是
 * 崩溃堆栈指向一处"本来没问题"的代码。
 *
 * <h2>那什么才真正值得用 ASM 删</h2>
 *
 * <p>只有一个类别：<b>整段被无条件跳过的工作</b>，即那些
 * "结果被丢弃、但代价很大"的代码。典型例子是
 * <b>计算了却不使用</b>的返回值，或<b>为调试而保留的重计算</b>。
 *
 * <p>本项目用 ASM 做的只有一件事：<b>跳过原版 {@code RenderGlobal} 中
 * 对 {@code chunksToUpdate} 的无谓去重遍历</b>——
 * 但即便是这一项，经过实测也只是在特定场景（大量区块同时更新）
 * 有可测量的收益，普通场景下无差别。
 *
 * <h2>因此本类默认什么都不做</h2>
 *
 * <p>{@link #isEnabled()} 恒为 {@code false}。这不是偷懒 ——
 * 是在"用 ASM 改字节码"这件事上，<b>没有证据支持的改动一律不做</b>。
 *
 * <p>ASM 变换的特殊风险在于：它<b>绕过了 Java 的类型系统与编译器检查</b>，
 * 一旦写错，错误不是编译失败，而是运行期某个远处的
 * {@code VerifyError} 或 {@code NullPointerException}，
 * 堆栈指向被改写的类。而且它与 OptiFine、其它核心 mod
 * 同时改写同一个方法时，冲突表现为游戏直接起不来。
 *
 * <p>所以这个类保留调查结论，但不启用任何变换。
 * 如果后续找到有实测收益的目标，在这里加，并补对应的
 * 字节码校验测试（验证改后的类能通过 {@code CheckClassAdapter}）。
 */
public final class AsmOptimizations {

    private AsmOptimizations() {
    }

    /** 恒为 false：当前没有任何经过验证的 ASM 变换。 */
    public static boolean isEnabled() {
        return false;
    }

    /**
     * 给配置界面与日志用的说明。
     */
    public static String explain() {
        return "ASM 字节码改写：当前未启用任何变换。\n"
                + "调查结论：\n"
                + "  - Profiler 的开销是一个布尔检查（未启用 profiling 时立即返回），\n"
                + "    且 RenderGlobal 全文只有 5 处 startSection，删掉收益为 0；\n"
                + "  - 空检查/边界检查的成本是分支预测，删掉不会更快，但会让错误\n"
                + "    数据直接进数组下标，崩溃堆栈指向本来没问题的代码。\n"
                + "ASM 的错误不表现为编译失败，而是运行期 VerifyError 或\n"
                + "与 OptiFine 同时改写同一方法导致游戏起不来。\n"
                + "因此在找到有实测收益的目标前，本项目不做字节码改写。";
    }

    /** 供配置读取（即使为 false 也保留开关，便于将来启用）。 */
    public static boolean isConfiguredEnabled() {
        return LumenConfig.enabled && LumenConfig.asmOptimizations;
    }
}
