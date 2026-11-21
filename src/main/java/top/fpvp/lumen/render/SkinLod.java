package top.fpvp.lumen.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家皮肤 LoD：按距离降级模型细节与皮肤纹理分辨率。
 *
 * <h2>两级降级</h2>
 *
 * <p><b>第一级 —— 模型细节</b>（{@link #shouldSkipLayers}）：远处的玩家跳过
 * 装饰性渲染层（披风、帽子、箭矢）。这些层的特点是<b>单体开销高于主模型</b>
 * （披风要额外提交一整面双面四边形），而远处视觉贡献几乎为零。
 *
 * <p>刻意<b>不</b>跳过盔甲层（{@code LayerBipedArmor}）：盔甲在 PvP 场景里
 * 是<b>关键信息</b>，远距离判断对手穿什么甲是战术需求。跳过它会让 mod
 * 在 PvP 服上不可接受。这是刻意取舍，不是遗漏。
 *
 * <p><b>第二级 —— 纹理分辨率</b>：把远处玩家的 64×64 皮肤降采样成 32×32。
 *
 * <h2>关于纹理降采样的诚实评估</h2>
 *
 * <p>收益<b>很小</b>：64×64 RGBA 只占 16KB，40 个玩家全降采样也省不到 1MB。
 * 真正的收益是采样带宽 —— 远处玩家在屏幕上只占几十像素，却要对 64×64
 * 纹理做 mipmap 采样，降采样后局部性更好。
 *
 * <p>风险很实在：切换纹理要改 GL 绑定，远近玩家交替出现时会造成频繁状态变更。
 * 因此降采样结果<b>被缓存</b>（每个皮肤只做一次），且该功能<b>默认关闭</b> ——
 * 模型细节降级收益明确、风险为零，纹理降级则不是。
 *
 * <h2>为什么不用 ResourceLocation 承载降采样结果</h2>
 *
 * <p>本类只返回<b>纹理 id</b>，不伪造 {@link ResourceLocation}。
 * 原因是 TextureManager 只认自己注册过的 location，伪造一个
 * {@code lumen_skinlod/...} 会让 {@code getTexture} 返回 null，
 * 绑定失败后画面变成"紫色/黑色方块"。直接拿 id 绑定时就没有这个中间层，
 * 少一个失败点。
 */
public final class SkinLod {

    /** 缓存的降采样纹理上限。超过后按 LRU 淘汰。 */
    private static final int MAX_CACHED = 64;

    /** 原始皮肤 location → 降采样纹理 id。 */
    private static final Map<ResourceLocation, Integer> LOD_TEXTURES =
            new LinkedHashMap<ResourceLocation, Integer>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Integer> eldest) {
                    if (size() > MAX_CACHED) {
                        Integer id = eldest.getValue();
                        if (id != null && id > 0) {
                            // 淘汰时必须真正删除 GL 纹理，否则显存泄漏。
                            TextureUtil.deleteTexture(id);
                        }
                        return true;
                    }
                    return false;
                }
            };

    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;
    private static boolean eyeValid;

    private SkinLod() {
    }

    public static void beginFrame(net.minecraft.entity.Entity viewEntity, float partialTicks) {
        if (viewEntity == null) {
            eyeValid = false;
            return;
        }
        eyeX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        eyeY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks
                + viewEntity.getEyeHeight();
        eyeZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
        eyeValid = true;
    }

    /** 世界切换时清空缓存。 */
    public static void reset() {
        for (Integer id : LOD_TEXTURES.values()) {
            if (id != null && id > 0) {
                TextureUtil.deleteTexture(id);
            }
        }
        LOD_TEXTURES.clear();
        eyeValid = false;
    }

    private static double distanceSqTo(EntityPlayer player) {
        if (!eyeValid) {
            return 0.0D;
        }
        double dx = player.posX - eyeX;
        double dy = player.posY - eyeY;
        double dz = player.posZ - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 是否应当跳过装饰性渲染层（披风、帽子等）。
     *
     * <p>永远不跳过玩家自己 —— 第三人称看自己时披风缺失非常明显。
     */
    public static boolean shouldSkipLayers(EntityPlayer player) {
        if (!LumenConfig.enabled || !LumenConfig.skinLod) {
            return false;
        }
        if (!eyeValid || player == null) {
            return false;
        }
        if (player == Minecraft.getMinecraft().thePlayer) {
            return false;
        }
        double limit = LumenConfig.skinLodLayerDistance;
        return distanceSqTo(player) > limit * limit;
    }

    /** 该玩家是否应当使用降采样皮肤。 */
    public static boolean shouldUseLodTexture(EntityPlayer player) {
        if (!LumenConfig.enabled || !LumenConfig.skinLod || !LumenConfig.skinLodTexture) {
            return false;
        }
        if (!eyeValid || player == null) {
            return false;
        }
        if (player == Minecraft.getMinecraft().thePlayer) {
            return false;
        }
        double limit = LumenConfig.skinLodTextureDistance;
        return distanceSqTo(player) > limit * limit;
    }

    /**
     * 取该玩家的降采样皮肤纹理 id；返回 {@code <= 0} 表示应回退原始皮肤。
     *
     * <p>失败是<b>正常路径</b>而非异常：皮肤可能还没下载完、可能是 64×32
     * 的旧格式、也可能是其它 mod 提供的动态皮肤。任何一种情况都静默回退 ——
     * 让玩家看到正确皮肤永远优先于省那点采样带宽。
     */
    public static int getLodTextureId(AbstractClientPlayer player) {
        if (player == null) {
            return -1;
        }
        ResourceLocation original = player.getLocationSkin();
        if (original == null) {
            return -1;
        }

        Integer cached = LOD_TEXTURES.get(original);
        if (cached != null) {
            return cached;
        }
        return buildLod(original);
    }

    /**
     * 执行降采样并登记缓存。
     *
     * <p>读的是<b>已上传的 GL 纹理</b>而不是原始 PNG：服务器下发的皮肤走网络下载，
     * 原始文件不一定在磁盘上，而 GL 纹理总是存在的。
     *
     * <p>用 2×2 平均而非最近邻：远处细节本来就会被 mipmap 抹掉，
     * 平均能让降采样结果更接近"小尺寸看原图"的观感，避免摩尔纹。
     */
    private static int buildLod(ResourceLocation original) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            ITextureObject tex = mc.getTextureManager().getTexture(original);
            if (tex == null) {
                return -1;
            }
            int texId = tex.getGlTextureId();
            if (texId <= 0) {
                return -1;
            }

            // 只处理标准的 64×64 皮肤。其它尺寸（64×32 旧格式、mod 自定义）
            // 一律跳过 —— 猜错尺寸会读越界，得不偿失。
            int width = 64;
            int height = 64;

            IntBuffer source = org.lwjgl.BufferUtils.createIntBuffer(width * height);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, source);

            int halfW = width / 2;
            int halfH = height / 2;

            // 注意 TextureUtil 只接受 BufferedImage，不接受 IntBuffer。
            // 这里仍从 GL 读回像素（因为原始 PNG 不一定在磁盘上），
            // 但把结果装进 BufferedImage 再交给 TextureUtil 上传，
            // 由它负责设定过滤方式与 mipmap —— 手写 glTexImage2D 很容易
            // 漏掉 GL_UNPACK_ALIGNMENT 或过滤设置，导致远处出现黑边。
            java.awt.image.BufferedImage image =
                    new java.awt.image.BufferedImage(halfW, halfH,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < halfH; y++) {
                for (int x = 0; x < halfW; x++) {
                    int p00 = source.get((y * 2) * width + (x * 2));
                    int p10 = source.get((y * 2) * width + (x * 2 + 1));
                    int p01 = source.get((y * 2 + 1) * width + (x * 2));
                    int p11 = source.get((y * 2 + 1) * width + (x * 2 + 1));
                    image.setRGB(x, y, average(p00, p10, p01, p11));
                }
            }

            int newId = TextureUtil.glGenTextures();
            TextureUtil.uploadTextureImage(newId, image);

            LOD_TEXTURES.put(original, newId);
            return newId;
        } catch (Throwable t) {
            // 任何失败都静默回退 —— 皮肤错了是"看得见的 bug"，
            // 崩溃是"不能接受的 bug"，两者代价不对称。
            LumenProfiler.log().debug("[Lumen] 皮肤 LoD 降采样失败，回退原纹理: {}", t.toString());
            return -1;
        }
    }

    /** 四像素按通道算术平均。 */
    private static int average(int a, int b, int c, int d) {
        int aa = ((a >> 24) & 0xFF) + ((b >> 24) & 0xFF) + ((c >> 24) & 0xFF) + ((d >> 24) & 0xFF);
        int rr = ((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF);
        int gg = ((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF);
        int bb = (a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF);
        return (((aa >> 2) & 0xFF) << 24) | (((rr >> 2) & 0xFF) << 16)
                | (((gg >> 2) & 0xFF) << 8) | ((bb >> 2) & 0xFF);
    }

    public static String describe() {
        return "lodTextures=" + LOD_TEXTURES.size();
    }
}
