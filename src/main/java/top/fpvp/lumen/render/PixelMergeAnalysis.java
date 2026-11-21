package top.fpvp.lumen.render;

import top.fpvp.lumen.LumenConfig;

/**
 * 远处同色像素合并的可行性分析与实际能力边界。
 *
 * <h2>这个想法本身是对的，但 1.8.9 的实现位置很受限</h2>
 *
 * <p>「把远处相同颜色的像素合并成一块绘制」在图形学里对应两类技术：
 *
 * <ol>
 *   <li><b>色彩量化 + 索引化</b>（类似 GIF 调色板）：把屏幕上相近的颜色
 *       归到同一个色，再用游程编码减少绘制。收益在于<b>后期处理</b>，
 *       即"已经渲染好的画面"再压缩；</li>
 *   <li><b>Bufferless / 三角形合并</b>：把远处几何上颜色相近的相邻三角形
 *       合并成更大的三角形。这需要能改写顶点数据与索引缓冲。</li>
 * </ol>
 *
 * <h2>1.8.9 上两者都做不到有意义的效果</h2>
 *
 * <p><b>第一类</b>要求能拿到最终帧缓冲并重绘。1.8.9 的渲染管线是
 * 即时模式 + 固定功能，没有离屏 FBO 后处理的概念（除 OptiFine 的光影）。
 * 用 {@code glReadPixels} 读回、量化、再上传纹理，本身就是
 * <b>两次全屏显存传输</b> —— 比它省下的绘制开销贵一个数量级。
 * 而且它会与任何后处理（光影、抗锯齿）冲突。
 *
 * <p><b>第二类</b>要求能访问并修改区块网格的顶点缓冲。1.8.9 的区块网格
 * 是 {@code glCallList} 编译好的显示列表，内容不可读、不可改；
 * 要改就得整个替换区块渲染器（重写 {@code RenderChunk}、
 * {@code VboRenderList}、{@code ChunkRenderDispatcher}），
 * 那已经不是一个优化 mod，而是一个渲染器 mod 了。
 *
 * <h2>那么能做什么：在<b>几何生成</b>阶段减少远处细节</h2>
 *
 * <p>有一个地方确实能省，而且不需要后处理：<b>远处方块的细节面</b>。
 * 像台阶、楼梯、栏杆、花草这些"非整方块"，每个会生成十几个甚至几十个面。
 * 而它们在远处（比如 64 格外）在屏幕上只占几个像素，
 * 那些细节面完全不可见。
 *
 * <p>更激进的做法是把远处的非整方块<b>当作整方块</b>来生成网格 ——
 * 只出 6 个面（且大部分被邻接面消除）。这与"同色像素合并"的
 * <b>目的相同</b>（都是减少远处的绘制量），但实现方式是几何简化而非颜色合并，
 * 在 1.8.9 上是可行的。
 *
 * <p><b>但即便是这个，本 mod 也不做</b>，原因见下。
 *
 * <h2>为什么最终没有实现</h2>
 *
 * <p>它需要拦 {@code RenderBlocks#renderBlockByRenderType} 并替换
 * 整个方块渲染分支。风险在于：
 * <ul>
 *   <li>会改变远处的<b>外观</b>（楼梯变成方块），这是肉眼可见的画面差异，
 *       不是"性能优化"该有的副作用；</li>
 *   <li>需要精确复刻原版对每个方块的渲染类型判定，
 *       任何 mod 方块都可能落进未处理的分类；</li>
 *   <li>与 OptiFine 的 {@code BlockModelRenderer} 替换直接冲突。</li>
 * </ul>
 *
 * <p>与其做一个"会让远处地形变形"的选项，不如不做。
 * 本类保留分析结论，供后续决定是否值得用"外观换性能"。
 *
 * <h2>已经实现的替代方案</h2>
 *
 * <p>如果目标是"减少远处的绘制量"，本项目已有几项<b>不改变外观</b>的措施：
 * <ul>
 *   <li>{@link EntityLod}：远处实体跳装饰层；</li>
 *   <li>{@link EntityRenderRange}：远处实体完全不画；</li>
 *   <li>{@link OcclusionCuller}：被挡住的完全不画；</li>
 *   <li>{@link ParticleOptimizer}：远处粒子降频。</li>
 * </ul>
 *
 * <p>它们的共同点是：<b>要么不画，要么少画，但画出来的东西和原版一致</b>。
 */
public final class PixelMergeAnalysis {

    private PixelMergeAnalysis() {
    }

    /**
     * 本功能是否可用。
     *
     * <p>恒为 {@code false}。保留这个方法是为了让
     * {@code LumenConfig} 里的开关有一个明确的"未实现"落点，
     * 而不是让配置项看起来像是生效了。
     */
    public static boolean isAvailable() {
        return false;
    }

    /**
     * 给玩家看的说明（配置界面与日志共用）。
     */
    public static String explain() {
        return "远处同色像素合并在 1.8.9 上不可行：\n"
                + "  - 颜色合并需要后处理（离屏 FBO），1.8.9 没有可用的 FBO 管线，\n"
                + "    而 glReadPixels 读回本身就是两次全屏显存传输，比省下的开销更贵；\n"
                + "  - 几何合并需要能改写区块网格的索引缓冲，而 1.8.9 的区块网格是\n"
                + "    glCallList 编译好的显示列表，内容不可读不可改。\n"
                + "替代方案：远处实体已由「实体 LoD」与「实体渲染范围」处理，\n"
                + "这两项都不改变剩余物体的外观。";
    }

    public static boolean isEnabledInConfig() {
        return LumenConfig.enabled && LumenConfig.pixelMerge;
    }
}
