package top.fpvp.lumen.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把静态 HUD 文本烘焙成纹理，用一次四边形取代逐字形提交。
 *
 * <p>原版 {@code FontRenderer#drawString} 对每一行文本都要：逐字符查宽度、
 * 解析 {@code §} 颜色码、往 {@code Tessellator} 里塞顶点（每字符 4 个顶点、
 * 每顶点 7 个 float）。对于计分板、信息面板、状态角标这类<b>内容几乎不变</b>
 * 的文本，这份工作每帧重复而输出完全相同 —— 典型的可缓存计算。
 *
 * <p>做法：第一次遇到某段文本时，把它渲染进一张离屏纹理；之后每帧只画一个
 * 贴了该纹理的四边形。
 *
 * <h3>两个容易踩的坑（都在这里处理了）</h3>
 *
 * <p><b>1. 不能直接 {@code glClear} 一个纹理。</b>{@code glClear} 清的是
 * <b>当前绑定的 framebuffer</b>；纹理只是“被渲染目标”时才受它影响。若像常见
 * 的偷懒写法那样把纹理绑成“纹理”然后 clear，缓存的图会一帧帧叠加，
 * 最终变成一块黑斑。
 *
 * <p><b>2. 不能依赖 fp 精度。</b>GL 默认按 8 位量化颜色，若把文本的 alpha 直接
 * 烘进纹理再乘以颜色的 alpha，半透明文本会被量化两次。这里烘焙时 alpha 固定为
 * 不透明，颜色与 alpha 全部留给绘制时的 {@code GlStateManager.color}，
 * 因此任何 {@code ARGB} 都能正确着色。
 *
 * <p>烘焙路径复用原版 {@code FontRenderer}，所以 Unicode 字体、颜色码、
 * 粗体/斜体/<u>下划线</u> 都与原版完全一致，不存在“自己重写字形排版”那类
 * 视觉偏差。
 */
public final class HudTextureCache {

    private static final class Entry {
        final ResourceLocation location;
        /** GUI 坐标系下的宽高（已除以缩放系数）。 */
        final int width;
        final int height;

        Entry(ResourceLocation location, int width, int height) {
            this.location = location;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * LRU。{@code accessOrder=true} 让最久未使用的条目先被淘汰。
     *
     * <p>淘汰时<b>必须</b>同时 {@code deleteTexture}：只从 map 移除而不删纹理
     * 会泄漏显存，几百次之后驱动层就会报错。
     */
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            if (size() > LumenConfig.hudCacheEntries) {
                destroy(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    private int counter;

    /** 加载中：烘焙过程会改变 GL 状态并触发一次字体绘制，期间禁止重入。 */
    private boolean baking;

    private static final HudTextureCache INSTANCE = new HudTextureCache();

    private HudTextureCache() {
    }

    public static HudTextureCache get() {
        return INSTANCE;
    }

    /**
     * 把一段文本画到屏幕上，优先走纹理缓存。
     *
     * @param font  用于光栅化的字体渲染器
     * @param text  文本（可含 {@code §} 颜色码）
     * @param x     GUI 坐标（左上角）
     * @param y     GUI 坐标
     * @param color ARGB 颜色
     */
    public void drawString(FontRenderer font, String text, int x, int y, int color) {
        if (!LumenConfig.enabled || !LumenConfig.hudTextureCache || baking
                || text == null || text.isEmpty()) {
            font.drawString(text, x, y, color);
            return;
        }

        Entry entry = entryFor(font, text);
        if (entry == null) {
            // 烘焙不可用（无 GL 上下文等）：退回原版，正确性不受影响。
            font.drawString(text, x, y, color);
            return;
        }
        blit(entry, x, y, color);
    }

    /** 与 {@link #drawString} 相同，但带阴影（原版的 {@code drawStringWithShadow}）。 */
    public void drawStringWithShadow(FontRenderer font, String text, int x, int y, int color) {
        if (!LumenConfig.enabled || !LumenConfig.hudTextureCache || baking
                || text == null || text.isEmpty()) {
            font.drawStringWithShadow(text, x, y, color);
            return;
        }
        // 阴影就是“偏移一格、亮度减半”，用两次 blit 实现，比再烘一张纹理省得多。
        int shadowColor = (color & 0xFCFCFC) >> 2 | color & 0xFF000000;
        Entry entry = entryFor(font, text);
        if (entry == null) {
            font.drawStringWithShadow(text, x, y, color);
            return;
        }
        blit(entry, x + 1, y + 1, shadowColor);
        blit(entry, x, y, color);
    }

    private Entry entryFor(FontRenderer font, String text) {
        // 键必须包含字体身份、Unicode 标志与缩放：换字号、切 Unicode 字体、
        // 改 GUI 缩放都会改变光栅化结果。少一个维度就会“改完设置 HUD 变糊”。
        Minecraft mc = Minecraft.getMinecraft();
        int scale = new ScaledResolution(mc).getScaleFactor();
        String key = scale + "|" + font.getUnicodeFlag() + "|" + text;

        Entry entry = entries.get(key);
        if (entry != null) {
            LumenProfiler.hudCacheHits++;
            return entry;
        }

        LumenProfiler.hudCacheMisses++;
        entry = bake(font, text);
        if (entry == null) {
            return null;
        }
        entries.put(key, entry);
        return entry;
    }

    /**
     * 把文本光栅化进一张新纹理。
     *
     * @return 成功时返回条目；失败返回 {@code null}（调用方退回原版绘制）
     */
    private Entry bake(FontRenderer font, String text) {
        Minecraft mc = Minecraft.getMinecraft();
        int logicalWidth = Math.max(1, font.getStringWidth(text));
        int logicalHeight = Math.max(1, font.FONT_HEIGHT);
        int scale = new ScaledResolution(mc).getScaleFactor();
        int texWidth = logicalWidth * scale;
        int texHeight = logicalHeight * scale;

        // 0x8CA6 = GL_FRAMEBUFFER_BINDING，0x8CE0 = GL_COLOR_ATTACHMENT0。
        // GL11 不含这两个常量（它们属于 EXT/ARB 扩展），故直接用字面量。
        int previousFramebuffer = GL11.glGetInteger(0x8CA6);
        int[] previousViewport = new int[4];
        GL11.glGetInteger(GL11.GL_VIEWPORT, (java.nio.IntBuffer) BufferUtils.createIntBuffer(4)
                .put(previousViewport).flip());

        int framebuffer = 0;
        baking = true;
        try {
            DynamicTexture texture = new DynamicTexture(texWidth, texHeight);
            int glTexture = texture.getGlTextureId();

            // 必须走 OpenGlHelper：GL11 里没有 framebuffer 的常量与入口，
            // 它们在 1.8.9 上由 EXT/ARB 扩展提供，OpenGlHelper 会选对那一套。
            framebuffer = OpenGlHelper.glGenFramebuffers();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, 0x8CE0,
                    GL11.GL_TEXTURE_2D, glTexture, 0);
            if (OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER)
                    != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
                GL11.glDeleteTextures(glTexture);
                LumenProfiler.log().warn("[Lumen] HUD 纹理帧缓冲不完整，已禁用缓存");
                LumenConfig.hudTextureCache = false;
                return null;
            }

            GL11.glViewport(0, 0, texWidth, texHeight);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // 用原版管线绘制，一次性获得颜色码/粗体/Unicode 的正确行为。
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            // 注意 near/far 与 translate 必须配套：原版 HUD 是在 z=-2000 处绘制，
            // 正交矩阵的裁剪范围若不覆盖这个深度，文字会被整段裁掉。
            GlStateManager.ortho(0.0D, logicalWidth, logicalHeight, 0.0D, 1000.0D, 3000.0D);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.translate(0.0F, 0.0F, -2000.0F);

            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            // 白色 + 不透明：颜色完全留给绘制阶段，避免 alpha 被量化两次。
            font.drawString(text, 0, 0, 0xFFFFFFFF, false);

            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);

            // 把渲染结果读回 CPU 缓冲，再由 DynamicTexture 上传。
            // 必须读回而不是指望纹理自动更新：DynamicTexture 的像素源是那个 int[]。
            ByteBuffer pixels = BufferUtils.createByteBuffer(texWidth * texHeight * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, texWidth, texHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            uploadToDynamicTexture(texture, pixels, texWidth, texHeight);
            texture.updateDynamicTexture();

            counter++;
            ResourceLocation location = new ResourceLocation("lumen", "hud/" + counter);
            mc.getTextureManager().loadTexture(location, texture);

            return new Entry(location, logicalWidth, logicalHeight);
        } catch (Throwable t) {
            LumenProfiler.log().warn("[Lumen] HUD 纹理烘焙失败，已回退到原版绘制", t);
            return null;
        } finally {
            baking = false;
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            if (framebuffer != 0) {
                OpenGlHelper.glDeleteFramebuffers(framebuffer);
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * 把 RGBA（行序自下而上，与 GL 一致）搬进 {@code DynamicTexture} 期待的 ABGR int[]。
     *
     * <p>{@code DynamicTexture#updateDynamicTexture} 用 {@code GL_UNSIGNED_INT_8_8_8_8}
     * 上传，在小端机器上的字节序是 R,G,B,A 从低到高 —— 也就是 int 的
     * {@code 0xAABBGGRR}。所以这里要把读回来的 RGB 顺序反过来。
     */
    private static void uploadToDynamicTexture(DynamicTexture texture, ByteBuffer pixels, int width, int height) {
        int[] target = texture.getTextureData();
        if (target == null || target.length < width * height) {
            return;
        }
        for (int i = 0; i < width * height; i++) {
            int r = pixels.get(i * 4) & 0xFF;
            int g = pixels.get(i * 4 + 1) & 0xFF;
            int b = pixels.get(i * 4 + 2) & 0xFF;
            int a = pixels.get(i * 4 + 3) & 0xFF;
            target[i] = a << 24 | b << 16 | g << 8 | r;
        }
    }

    /** 用一次四边形把缓存的纹理贴到屏幕上。 */
    private void blit(Entry entry, int x, int y, int color) {
        float alpha = (float) (color >>> 24) / 255.0F;
        float red = (float) (color >> 16 & 255) / 255.0F;
        float green = (float) (color >> 8 & 255) / 255.0F;
        float blue = (float) (color & 255) / 255.0F;

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        Minecraft.getMinecraft().getTextureManager().bindTexture(entry.location);
        GlStateManager.color(red, green, blue, alpha);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + entry.height, 0.0D).tex(0.0D, 1.0D).endVertex();
        buffer.pos(x + entry.width, y + entry.height, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(x + entry.width, y, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(x, y, 0.0D).tex(0.0D, 0.0D).endVertex();
        tessellator.draw();

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void destroy(Entry entry) {
        if (entry == null) {
            return;
        }
        try {
            Minecraft.getMinecraft().getTextureManager().deleteTexture(entry.location);
        } catch (RuntimeException e) {
            // 纹理管理器可能已随世界销毁：忽略，GL 上下文没了也就无所谓泄漏。
        }
    }

    /** 资源重载 / 换字体 / 改缩放时调用。 */
    public void invalidateAll() {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            destroy(it.next().getValue());
            it.remove();
        }
    }

    /** 世界卸载时调用。 */
    public void onWorldUnload() {
        invalidateAll();
    }

    /** 缓存命中统计，供调试行使用。 */
    public int cachedEntries() {
        return entries.size();
    }
}
