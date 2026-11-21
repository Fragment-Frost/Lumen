package top.fpvp.lumen.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 远处贴图低分辨率化。
 *
 * <h2>诚实评估：收益有限，风险实在</h2>
 *
 * <p>这个优化在 1.8.9 上的<b>净收益可能是负的</b>，理由如下：
 *
 * <ul>
 *   <li><b>降采样本身要读回显存</b>：{@code glGetTexImage} 是一次同步的
 *       显存→内存传输，在驱动里通常要等 GPU 管线排空。对一张 256×256 的
 *       方块图集分片，这个等待可能是毫秒级的 —— 一次卡顿换几十帧的采样带宽。</li>
 *   <li><b>纹理切换有成本</b>：远近物体交替出现会导致 GL 绑定频繁变更，
 *       而 1.8.9 用的是一个巨大的方块图集，本来切换就少 —— 拆成两套反而变多。</li>
 *   <li><b>显存收益接近零</b>：1.8.9 的方块纹理在一个 256×256（或更大的）
 *       图集里，降采样省下的显存只有几 MB。</li>
 * </ul>
 *
 * <p>因此本功能<b>默认关闭</b>，并且在实现上做了三处缓解：
 * <ol>
 *   <li>只在<b>明确请求</b>时降采样，且结果永久缓存（每个纹理只做一次）；</li>
 *   <li>缓存有上限并做 LRU 淘汰，淘汰时真正删除 GL 纹理，不会泄漏；</li>
 *   <li>任何异常都静默回退原纹理 —— 画面正确性优先于这点带宽。</li>
 * </ol>
 *
 * <h2>为什么按「纹理」而不是按「方块」降采样</h2>
 *
 * <p>1.8.9 把绝大多数方块纹理打包在一张图集里，逐个方块降采样既要拆分图集
 * 又要重建 UV，等于重写整个方块渲染管线。而按整张纹理降采样只需要替换绑定，
 * 不碰几何数据。代价是无法做到"只给远处的方块用低分辨率" ——
 * 只能做到"远处的<b>渲染对象</b>用低分辨率纹理"，这是本类的实际能力边界。
 *
 * <p>也正因为这个限制，本类的开关默认关闭：它不能带来"近处清晰、远处省资源"
 * 的理想效果，而是一个需要权衡的实验性选项。
 */
public final class TextureLod {

    /** 缓存上限。超过后 LRU 淘汰。 */
    private static final int MAX_CACHED = 32;

    /** 原始纹理 location → 降采样纹理 id。 */
    private static final Map<ResourceLocation, Integer> LOD_TEXTURES =
            new LinkedHashMap<ResourceLocation, Integer>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Integer> eldest) {
                    if (size() > MAX_CACHED) {
                        Integer id = eldest.getValue();
                        if (id != null && id > 0) {
                            TextureUtil.deleteTexture(id);
                        }
                        return true;
                    }
                    return false;
                }
            };

    private TextureLod() {
    }

    public static void reset() {
        for (Integer id : LOD_TEXTURES.values()) {
            if (id != null && id > 0) {
                TextureUtil.deleteTexture(id);
            }
        }
        LOD_TEXTURES.clear();
    }

    /**
     * 取某纹理的低分辨率版本 id；返回 {@code <= 0} 表示应使用原纹理。
     *
     * <p>失败是正常路径（纹理未加载、尺寸不合适、GL 上下文异常），
     * 一律静默回退。
     */
    public static int getLodTextureId(ResourceLocation location, int width, int height) {
        if (!LumenConfig.enabled || !LumenConfig.textureLod || location == null) {
            return -1;
        }
        int divisor = LumenConfig.textureLodDivisor;
        if (divisor < 2) {
            return -1;
        }
        // 尺寸不足以降采样：直接放弃。太小再降就成马赛克了。
        if (width / divisor < 4 || height / divisor < 4) {
            return -1;
        }

        Integer cached = LOD_TEXTURES.get(location);
        if (cached != null) {
            return cached;
        }
        return build(location, width, height, divisor);
    }

    /**
     * 执行降采样。
     *
     * <p>用「区块平均」而不是最近邻：远处纹理细节本来就会被 mipmap 抹掉，
     * 平均能让结果更接近"小图看原图"的观感，避免摩尔纹。
     */
    private static int build(ResourceLocation location, int width, int height, int divisor) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            ITextureObject tex = mc.getTextureManager().getTexture(location);
            if (tex == null) {
                return -1;
            }
            int texId = tex.getGlTextureId();
            if (texId <= 0) {
                return -1;
            }

            IntBuffer source = org.lwjgl.BufferUtils.createIntBuffer(width * height);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, source);

            int outW = width / divisor;
            int outH = height / divisor;
            BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);

            int samples = divisor * divisor;
            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    int a = 0;
                    int r = 0;
                    int g = 0;
                    int b = 0;
                    for (int dy = 0; dy < divisor; dy++) {
                        int sy = y * divisor + dy;
                        for (int dx = 0; dx < divisor; dx++) {
                            int sx = x * divisor + dx;
                            int p = source.get(sy * width + sx);
                            a += (p >> 24) & 0xFF;
                            r += (p >> 16) & 0xFF;
                            g += (p >> 8) & 0xFF;
                            b += p & 0xFF;
                        }
                    }
                    image.setRGB(x, y, ((a / samples) << 24) | ((r / samples) << 16)
                            | ((g / samples) << 8) | (b / samples));
                }
            }

            int newId = TextureUtil.glGenTextures();
            TextureUtil.uploadTextureImage(newId, image);
            LOD_TEXTURES.put(location, newId);
            return newId;
        } catch (Throwable t) {
            LumenProfiler.log().debug("[Lumen] 纹理降采样失败，回退原纹理: {}", t.toString());
            return -1;
        }
    }

    public static String describe() {
        return "lodTextures=" + LOD_TEXTURES.size();
    }
}
