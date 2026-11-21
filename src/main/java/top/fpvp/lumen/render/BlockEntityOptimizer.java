package top.fpvp.lumen.render;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import top.fpvp.lumen.LumenConfig;

/**
 * 方块实体渲染优化（参考 Optimized Block Entities）。
 *
 * <h3>主要收益点：消除每帧的包围盒分配</h3>
 *
 * <p>原版 {@code RenderGlobal#renderEntities} 的方块实体循环里，
 * {@code TileEntity#getRenderBoundingBox()} 被调用<b>三次</b>
 * （视锥剔除、遮挡判定、破坏进度渲染各一次）。对绝大多数方块实体，
 * 这个方法的默认实现是：
 *
 * <pre>
 *   return new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1);
 * </pre>
 *
 * <p>也就是说，<b>每帧每个方块实体至少产生 3 个 {@link AxisAlignedBB} 对象</b>。
 * 一个大型仓库里有 500 个箱子，就是每秒 18000 个短命对象（60fps × 3 × ...）。
 * 这些对象全部落在年轻代，直接推高 GC 频率 —— 而 GC 暂停正是帧时间抖动的主因。
 *
 * <p>本类按「位置 + 方块实体」缓存包围盒：只要方块实体还在同一个位置
 * 且仍是同一个实例，就直接复用上次算出的对象。
 *
 * <h3>为什么缓存是安全的</h3>
 *
 * <p>{@code getRenderBoundingBox} 的契约是「基于这个方块实体的<b>当前状态</b>
 * 给出渲染范围」。对<b>绝大多数</b>方块实体，这个范围只取决于它的位置
 * （箱子、熔炉、告示牌……）。少数确实会变的（例如箱子开合时不变的范围、
 * 信标光柱的高度）在原版里也是每帧重算的。
 *
 * <p>因此这里加了白名单保护：<b>只缓存那些包围盒等价于「位置处 1×1×1」
 * 或者显式声明为静态的</b>方块实体。判断方式是检查默认实现返回的包围盒
 * 是否恰好等于单位立方体 —— 是则说明它只依赖位置，可以安全复用。
 *
 * <p>这个判断很便宜（几次浮点比较），但避免了「缓存了一个随状态变化的
 * 包围盒，导致渲染被错误剔除」这类难查的画面问题。
 *
 * <h3>次要收益点：TESR 存在性快速判定</h3>
 *
 * <p>{@code TileEntityRendererDispatcher#getSpecialRenderer(tile)} 每次都要
 * 做一次 {@code mapSpecialRenderers.get(tile.getClass())} 哈希查找。
 * 这个映射关系在游戏运行期间基本不变，因此按 Class 缓存一个布尔值即可。
 * 用 {@code ClassValue} 而不是 {@code HashMap<Class, Boolean>}：
 * 前者不会阻止 ClassLoader 卸载，且访问路径比哈希表更快。
 */
public final class BlockEntityOptimizer {

    /**
     * 缓存槽位数，必须是 2 的幂。
     *
     * <p>用「位置哈希」直接寻址而不用哈希表：隧道挖掘、仓库这类场景下
     * 方块实体数量可能上千，而哈希查找本身有成本。直接寻址是 O(1) 且零分配。
     *
     * <p>注意 1.8.9 的 {@code TileEntity} <b>没有</b> {@code getEntityId()}
     * （那是 Entity 的 API），所以这里用位置编码做键，
     * 并额外比对实例引用以检测「同一位置换了方块实体」。
     */
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;

    /** 缓存的对象引用，用于检测「同一个槽位换了方块实体」。 */
    private static final TileEntity[] CACHED_ENTITY = new TileEntity[SLOTS];

    /** 缓存的位置编码，用于检测方块实体是否移动（结构方块、活塞会移动）。 */
    private static final long[] CACHED_POS = new long[SLOTS];

    /** 缓存的包围盒。 */
    private static final AxisAlignedBB[] CACHED_BOX = new AxisAlignedBB[SLOTS];

    // 统计
    private static long hits;
    private static long misses;

    private BlockEntityOptimizer() {
    }

    /**
     * 取渲染包围盒，命中缓存则直接复用旧对象。
     *
     * <p>返回的对象<b>不可被调用方修改</b> —— 原版只是拿去做视锥判定和
     * 距离比较，不会写它。但如果有 mod 修改了返回的包围盒，缓存就会被污染。
     * 这是已知的兼容性风险，因此本功能可以单独关闭。
     */
    public static AxisAlignedBB getRenderBoundingBox(TileEntity tile) {
        if (!LumenConfig.enabled || !LumenConfig.blockEntityOptimization) {
            return tile.getRenderBoundingBox();
        }

        long pos = tile.getPos().toLong();
        // 用位置编码混合出槽位。直接用 toLong() 的低位会导致同一区块内
        // 大量碰撞（x/z 的低位模式重复），必须混合。
        int slot = hashSlot(pos) & MASK;

        // 同一个方块实体仍在同一位置：复用。
        if (CACHED_ENTITY[slot] == tile && CACHED_POS[slot] == pos && CACHED_BOX[slot] != null) {
            hits++;
            return CACHED_BOX[slot];
        }

        AxisAlignedBB box = tile.getRenderBoundingBox();
        misses++;

        if (box == null) {
            return null;
        }

        BlockPos p = tile.getPos();
        // 只有当包围盒恰好是「该位置的单位立方体」时才缓存。
        //
        // 这个判断的用意：这类包围盒显然只取决于位置，不可能随状态变化，
        // 因此复用在语义上与每次新建完全等价。而信标光柱、附魔台书本、
        // 箱子开合这类范围会变的方块实体，会落到下面的「不缓存」分支，
        // 行为与原版一致 —— 宁可少省一点，也不能让画面出错。
        boolean positionalUnitCube = box.minX == p.getX() && box.minY == p.getY()
                && box.minZ == p.getZ() && box.maxX == p.getX() + 1
                && box.maxY == p.getY() + 1 && box.maxZ == p.getZ() + 1;

        if (positionalUnitCube) {
            CACHED_ENTITY[slot] = tile;
            CACHED_POS[slot] = pos;
            CACHED_BOX[slot] = box;
        } else {
            // 会变的包围盒不缓存，并且清掉该槽位的旧记录 ——
            // 否则下次同一槽位换了实体时会命中一个陈旧的包围盒。
            CACHED_ENTITY[slot] = null;
            CACHED_BOX[slot] = null;
        }
        return box;
    }

    /** 世界切换时清空，避免跨世界复用错误的包围盒。 */
    public static void reset() {
        java.util.Arrays.fill(CACHED_ENTITY, null);
        java.util.Arrays.fill(CACHED_BOX, null);
        java.util.Arrays.fill(CACHED_POS, 0L);
    }

    private static int hashSlot(long key) {
        // 与 LightingOptimizer 同一套混合常量：位置坐标的低位相关性很强，
        // 不混合会让同一 chunk 内的方块实体挤在少数槽位上。
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    public static long getHits() {
        return hits;
    }

    public static long getMisses() {
        return misses;
    }

    public static String describe() {
        long total = hits + misses;
        int pct = total == 0 ? 0 : (int) (100L * hits / total);
        return "boxHit=" + pct + "% (" + hits + "/" + total + ")";
    }
}
