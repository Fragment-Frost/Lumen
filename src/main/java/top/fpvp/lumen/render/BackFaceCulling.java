package top.fpvp.lumen.render;

import top.fpvp.lumen.LumenConfig;

/**
 * 方块与实体的背面剔除（BackFaceCulling）。
 *
 * <h2>先说清楚：1.8.9 的方块渲染<b>已经</b>做了背面剔除</h2>
 *
 * <p>这一点必须澄清，否则会做出一个纯属多余的功能。
 *
 * <p>1.8.9 的区块网格构建（{@code BlockModelRenderer} / {@code RenderBlocks}）
 * 在生成顶点时<b>只生成可见面</b>：一个被完全包围的方块不产生任何四边形，
 * 相邻两个实心方块之间的接触面也不会生成。这是"隐面消除"，
 * 比背面剔除更彻底 —— 背面剔除是在<b>已经有背面三角形</b>的前提下
 * 把它们丢掉，而 1.8.9 根本不生成它们。
 *
 * <p>因此对<b>方块</b>再加一层背面剔除<b>没有收益</b>，只有风险
 * （某些 mod 的方块模型依赖双面渲染，强制 {@code glCullFace} 会让它们缺面）。
 *
 * <h2>本项目实际做的：按图层启用背面剔除</h2>
 *
 * <p>真正缺的是<b>实体</b>与<b>部分方块模型</b>。1.8.9 渲染实体时
 * 通过 {@code RenderManager} 设置 GL 状态，其中：
 * <ul>
 *   <li>实体模型（{@code ModelBase} 的盒子）默认是<b>六面全生成</b>的，
 *       内部面完全看不到，但依然提交给 GPU；</li>
 *   <li>{@code GlStateManager.enableCull()} 只被部分渲染器调用，
 *       而且很多 mod 的渲染器会把它关掉后忘记恢复。</li>
 * </ul>
 *
 * <p>本类做的事情很窄：<b>在实体渲染前强制开启背面剔除，渲染后恢复</b>，
 * 并把 {@code cullFace} 设为 {@code GL_BACK}。
 *
 * <h2>与 OptiFine「区域渲染」的冲突（这是问得对的地方）</h2>
 *
 * <p>OptiFine 的 Region Renderer 把多个区块网格<b>合并成一次 draw call</b>，
 * 并且为每个合并区域重新生成索引缓冲。它与本类的关系：
 *
 * <ul>
 *   <li><b>不冲突</b>：OptiFine 的区域渲染作用于<b>方块</b>网格，
 *       而 1.8.9 的方块网格本来就只含可见面，两者在同一层面。
 *       本类不改方块网格，也不改任何索引缓冲，所以碰不到它；</li>
 *   <li><b>会冲突的情形</b>：如果 OptiFine 在合并时<b>保留了背向面</b>
 *       （某些版本为兼容双面材质会这么做），并依赖"不开启 cull"来显示它们。
 *       强制开启 cull 会让这些面消失。</li>
 * </ul>
 *
 * <p>因此：<b>检测到 OptiFine 时本功能自动跳过方块层，只作用于实体</b>。
 * 实体渲染与 OptiFine 的区域渲染是两个独立的阶段，不存在共享 GL 状态的问题。
 *
 * <p>这个降级是自动的，原因会打进日志。玩家不需要理解 OptiFine 的内部机制，
 * 但需要知道"为什么这里没生效"。
 *
 * <h2>不做的：双面材质检测</h2>
 *
 * <p>判断某个模型"是否依赖双面渲染"需要检查其所有四边形的绕序，
 * 而 1.8.9 的 {@code IBakedModel} 没有暴露这个信息（要遍历
 * {@code BakedQuad} 并假设绕序一致）。强行猜测会误伤 ——
 * 表现是某些方块或实体突然缺一半面，且只在特定模型上出现。
 *
 * <p>所以本类<b>只</b>处理"确定安全"的部分：实体的标准盒子模型。
 */
public final class BackFaceCulling {

    /** 是否检测到 OptiFine（启动时探测一次）。 */
    private static final boolean OPTIFINE_PRESENT = detectOptifine();

    private BackFaceCulling() {
    }

    /**
     * 探测 OptiFine。
     *
     * <p>用类名探测而不是加载它的类：{@code Class.forName} 会触发
     * OptiFine 的静态初始化，那可能是重的一步。用
     * {@code Class.forName(name, false, loader)} 只加载不初始化。
     */
    private static boolean detectOptifine() {
        String[] candidates = {
                "optifine.OptiFineForgeTweaker",
                "net.optifine.Config",
                "Config"
        };
        for (String name : candidates) {
            try {
                Class.forName(name, false, BackFaceCulling.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
                // 不存在就试下一个。
            }
        }
        return false;
    }

    public static boolean isOptifinePresent() {
        return OPTIFINE_PRESENT;
    }

    /**
     * 实体渲染前是否应该强制开启背面剔除。
     *
     * <p>实体阶段与 OptiFine 的区域渲染无关，所以这里<b>不</b>因
     * OptiFine 而降级。
     */
    public static boolean shouldCullEntityFaces() {
        return LumenConfig.enabled && LumenConfig.entityBackFaceCulling;
    }

    /**
     * 方块渲染是否应该启用背面剔除。
     *
     * <p><b>默认关闭</b>，且检测到 OptiFine 时强制返回 {@code false}。
     * 理由见类注释：1.8.9 的方块网格本来就没有背面，
     * 而 OptiFine 的区域合并可能有自己的假设。
     */
    public static boolean shouldCullBlockFaces() {
        if (!LumenConfig.enabled || !LumenConfig.blockBackFaceCulling) {
            return false;
        }
        // 检测到 OptiFine 时跳过。
        //
        // 原本这里会打一行日志说明降级原因；日志已移除。
        // 现在这个状态通过 BackFaceCulling.isOptifinePresent()
        // 暴露给配置界面，玩家在配置里能看到"检测到 OptiFine"。
        return !OPTIFINE_PRESENT;
    }

    public static String describe() {
        return "backface entity=" + LumenConfig.entityBackFaceCulling
                + " block=" + LumenConfig.blockBackFaceCulling
                + " optifine=" + OPTIFINE_PRESENT;
    }
}
