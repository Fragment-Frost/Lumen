package top.fpvp.lumen.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import top.fpvp.lumen.LumenConfig;

/**
 * 物品渲染优化（Carbon 思路在 1.8.9 上可行的子集）。
 *
 * <h2>先说清楚 Carbon 哪部分在 1.8.9 上做不到</h2>
 *
 * <p>Carbon 是「物品模型渲染的 GPU instancing」：把同一模型的多个实例
 * （掉落物、容器里的物品、GUI 里的物品）用一次 {@code glDrawElementsInstanced}
 * 画完，而不是每个实例一次 draw call。
 *
 * <p>这需要三样 1.8.9 没有的东西：
 * <ul>
 *   <li><b>GPU instancing（GL 3.1+）</b>：1.8.9 用 LWJGL 2，默认 GL 2.1 上下文，
 *       {@code glDrawElementsInstanced} 不可用；</li>
 *   <li><b>可保留的顶点缓冲</b>：1.8.9 的 {@code RenderItem} 每个物品都
 *       {@code GlStateManager.pushMatrix()} + 逐顶点即时模式提交，
 *       没有可复用的 VBO；</li>
 *   <li><b>模型与变换的分离</b>：instancing 要求"一次绑定、多次变换"，
 *       而 1.8.9 的 {@code ForgeHooksClient.handleCameraTransforms} 是
 *       per-item 的，无法抽取。</li>
 * </ul>
 *
 * <p>所以<b>真正的 GPU instancing 不做</b>。强行在 GL 2.1 上用
 * {@code glBegin} 模拟只会更慢。
 *
 * <h2>能做的：消除 per-item 的冗余工作</h2>
 *
 * <p>反汇编 {@code RenderItem#renderItemIntoGUI} 确认了三个纯浪费：
 *
 * <ol>
 *   <li><b>{@code itemModelMesher.getItemModel(stack)} 每次都查表</b>
 *       （偏移 0-8）。物品栏 36 格 + 容器，每帧数百次；
 *       而同一个 {@code Item} 的模型<b>在资源重载之间是固定的</b>。</li>
 *   <li><b>每个物品都 {@code bindTexture} + {@code getTexture} + {@code setBlurMipmap}</b>
 *       （偏移 13-40）。同一个 GUI 里所有物品用的是<b>同一张</b>
 *       {@code locationBlocksTexture}，重复绑定毫无意义
 *       （{@code GlStateManager} 内部虽然会跳过相同的绑定，
 *       但 {@code setBlurMipmap} 是一次真实的状态变更）。</li>
 *   <li><b>{@code enableRescaleNormal / enableAlpha / alphaFunc / enableBlend / blendFunc}</b>
 *       每次都重设（偏移 40-67）。这些在同一 GUI pass 内完全不变。</li>
 * </ol>
 *
 * <p>本类处理第 1 项（模型缓存）。第 2、3 项是 GL 状态，已经由
 * {@code GlStateManager} 的布尔状态缓存处理了大部分，剩下的
 * {@code setBlurMipmap} 用按帧去重解决。
 *
 * <h2>模型缓存的正确性</h2>
 *
 * <p>缓存键必须是 {@code Item} + metadata，因为同一个物品的不同损伤值
 * 可能是不同模型（例如有 metadata 的方块）。
 *
 * <p>缓存必须在<b>资源重载时清空</b> —— 资源包切换会替换模型实例，
 * 不清空就会继续用旧模型渲染，表现为"换了材质包但物品外观没变"。
 * 这比不缓存更糟，所以 {@link #reset()} 由 {@code MixinRenderItem}
 * 挂在 {@code onResourceManagerReload} 上。
 */
public final class ItemRenderOptimizer {

    /**
     * 缓存槽数（2 的幂）。
     *
     * <p>用 {@code Item} 的 identity hash 直接寻址。物品总数是有限的
     * （原版约 300 个 + mod），4096 足够且几乎无碰撞。
     */
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;

    /** 缓存的物品实例。 */
    private static final Item[] CACHED_ITEM = new Item[SLOTS];

    /** 缓存的 metadata。 */
    private static final int[] CACHED_META = new int[SLOTS];

    /** 缓存的模型。 */
    private static final IBakedModel[] CACHED_MODEL = new IBakedModel[SLOTS];

    /** 本帧是否已设置过模糊/mipmap 状态。 */
    private static boolean blurStateSetThisFrame;

    /** 统计。 */
    private static long hits;
    private static long misses;

    private ItemRenderOptimizer() {
    }

    /** 每帧开始时重置一次性状态。 */
    public static void beginFrame() {
        blurStateSetThisFrame = false;
    }

    /** 资源重载时清空（模型实例已被替换）。 */
    public static void reset() {
        java.util.Arrays.fill(CACHED_ITEM, null);
        java.util.Arrays.fill(CACHED_MODEL, null);
        java.util.Arrays.fill(CACHED_META, 0);
        blurStateSetThisFrame = false;
    }

    /**
     * 取该物品的模型，命中缓存则跳过 {@code ItemModelMesher} 的查找。
     *
     * @return 缓存的模型；{@code null} 表示未命中（调用方应走原版路径）
     */
    public static IBakedModel getCachedModel(ItemStack stack) {
        if (!isEnabled() || stack == null) {
            return null;
        }
        Item item = stack.getItem();
        if (item == null) {
            return null;
        }
        int meta = stack.getMetadata();
        int slot = slotOf(item, meta);
        if (CACHED_ITEM[slot] == item && CACHED_META[slot] == meta
                && CACHED_MODEL[slot] != null) {
            hits++;
            return CACHED_MODEL[slot];
        }
        misses++;
        return null;
    }

    /** 记录模型到缓存。 */
    public static void putModel(ItemStack stack, IBakedModel model) {
        if (!isEnabled() || stack == null || model == null) {
            return;
        }
        Item item = stack.getItem();
        if (item == null) {
            return;
        }
        int meta = stack.getMetadata();
        int slot = slotOf(item, meta);
        CACHED_ITEM[slot] = item;
        CACHED_META[slot] = meta;
        CACHED_MODEL[slot] = model;
    }

    /**
     * 本帧是否需要重新设置模糊/mipmap。
     *
     * <p>返回 {@code true} 表示调用方应该设置，随后本帧内所有其它物品
     * 都会返回 {@code false}。
     *
     * <p>为什么这样安全：{@code setBlurMipmap} 设置的是<b>纹理对象上</b>
     * 的采样参数，与当前画的是什么无关。同一个 GUI pass 内所有物品
     * 共享同一张图集纹理，所以每帧设一次就够。
     */
    public static boolean shouldSetBlurState() {
        if (!isEnabled()) {
            return true;
        }
        if (blurStateSetThisFrame) {
            return false;
        }
        blurStateSetThisFrame = true;
        return true;
    }

    private static int slotOf(Item item, int meta) {
        int h = System.identityHashCode(item) * 31 + meta;
        // 混合低位，避免 hash 相近的物品挤在同一槽。
        h ^= (h >>> 16);
        return h & MASK;
    }

    private static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.itemRenderCache;
    }

    public static String describe() {
        long total = hits + misses;
        int pct = total == 0 ? 0 : (int) (100L * hits / total);
        return "itemModelHit=" + pct + "% (" + hits + "/" + total + ")";
    }
}
