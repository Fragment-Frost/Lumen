package top.fpvp.lumen.render;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import top.fpvp.lumen.LumenConfig;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 可见面集合缓存（Ixeris 思路在 1.8.9 上的可行落点）。
 *
 * <h2>先说明 Ixeris 哪部分在本项目里做不了</h2>
 *
 * <p>Ixeris 的两个核心手段在 1.8.9 上<b>都不成立</b>：
 * <ul>
 *   <li><b>线程化事件轮询</b>：它的做法是把渲染踢到独立线程、主线程只做事件轮询。
 *       这依赖 1.13+ 的渲染线程模型。1.8.9 的 {@code Minecraft#runTick}
 *       把事件处理与渲染耦合在同一个线程的同一个方法里，
 *       拆开等于重写游戏主循环 —— 不是"优化"，是"换一个游戏"。</li>
 *   <li><b>缓冲原始输入</b>：它用 Windows 的 {@code GetRawInputBuffer} 批量读
 *       原始输入事件。1.8.9 走 LWJGL 2 的 {@code org.lwjgl.input.Mouse}，
 *       底层是 {@code DirectInput}，没有 raw input 通道，也没有可插入的
 *       批量读取接口。</li>
 * </ul>
 *
 * <h2>但 Ixeris 指出的"转视角掉帧"在 1.8.9 上有另一个成因</h2>
 *
 * <p>Ixeris 的文档说：转动镜头时会掉帧，因为"游戏在转视角时有额外工作，
 * 比如计算区块可见性"。这句话本身在 1.8.9 上<b>完全成立</b>，
 * 只是成因不同。
 *
 * <p>1.8.9 的 {@code RenderGlobal#setupTerrain} 在相机朝向变化时会重跑整个
 * 可见性计算。其中对<b>每个可见渲染区块</b>都会调用：
 * <ul>
 *   <li>{@code new ContainerLocalRenderInformation(...)}：每次一个对象；</li>
 *   <li>{@code getVisibleFacings(pos)}：每次新建一个 {@code EnumSet}，
 *       并做 6 次邻接方块查询来判断哪一面被挡住。</li>
 * </ul>
 *
 * <p>视距 16 时可见区块常达数百个，也就是每帧几百次对象分配 +
 * 上千次方块查询。<b>而 {@code getVisibleFacings} 的结果只依赖方块数据，
 * 与相机朝向无关</b> —— 转视角时它被白白重算了。
 *
 * <p>这就是本项目能安全叠加的那一层：把 {@code getVisibleFacings} 的结果
 * 按区块位置缓存，区块内容变化时失效。
 *
 * <h2>正确性边界</h2>
 *
 * <p>缓存键是「区块坐标 + 该区块的方块数据版本」。版本号来自
 * {@code RenderChunk#getCompiledChunk()} 的重建计数 —— 但这在 1.8.9 上
 * 拿不到公开接口。因此这里用更保守的策略：
 * <b>每个世界 tick 最多重算一次同一区块的结果</b>，而不是跨 tick 长期缓存。
 *
 * <p>这样做的代价是收益打了折（同 tick 内的多次调用仍能省掉），
 * 但换来的是<b>绝不出现"方块变了但可见面还是旧的"</b>那种幽灵画面。
 * 对可见面这种直接影响"这一面画不画"的数据，宁可少省一点。
 */
public final class VisibleFacingCache {

    /** 槽位数（2 的幂）。 */
    private static final int SLOTS = 2048;
    private static final int MASK = SLOTS - 1;

    /** 缓存的区块位置编码。 */
    private static final long[] KEY = new long[SLOTS];

    /** 缓存的可见面集合。 */
    private static final Set<?>[] VALUE = new Set<?>[SLOTS];

    /** 缓存所属的世界 tick。 */
    private static final long[] TICK = new long[SLOTS];

    /** 当前 tick。 */
    private static long currentTick;
    private static boolean currentTickValid;

    private VisibleFacingCache() {
    }

    /** 每 tick 推进一次，让上一 tick 的缓存整体过期。 */
    public static void beginTick(long worldTime) {
        currentTick = worldTime;
        currentTickValid = true;
    }

    public static void reset() {
        java.util.Arrays.fill(KEY, 0L);
        java.util.Arrays.fill(VALUE, null);
        java.util.Arrays.fill(TICK, 0L);
        currentTickValid = false;
    }

    /**
     * 取缓存的可见面集合；没有缓存时返回 {@code null}。
     *
     * @param pos 区块的方块坐标（调用方传的是对齐到 16 的坐标）
     */
    public static Set<EnumFacing> get(BlockPos pos) {
        if (!isEnabled() || !currentTickValid) {
            return null;
        }
        int slot = slotOf(pos);
        if (VALUE[slot] == null) {
            return null;
        }
        if (KEY[slot] != pos.toLong()) {
            // 槽位碰撞：位置不同，不能复用。
            return null;
        }
        if (TICK[slot] != currentTick) {
            // 上一个 tick 的结果：已过期。
            return null;
        }
        @SuppressWarnings("unchecked")
        Set<EnumFacing> cached = (Set<EnumFacing>) VALUE[slot];
        return cached;
    }

    /** 存入缓存。 */
    public static void put(BlockPos pos, Set<EnumFacing> facings) {
        if (!isEnabled() || !currentTickValid || facings == null) {
            return;
        }
        int slot = slotOf(pos);
        KEY[slot] = pos.toLong();
        // 存一份不可变副本：调用方可能继续修改原集合
        // （原版 getVisibleFacings 返回的是新建的 EnumSet，之后会被读取但不修改，
        //  但存副本能防住"别的 mixin/核心修改器原地改动"这种情况）。
        if (facings.isEmpty()) {
            VALUE[slot] = Collections.emptySet();
        } else {
            VALUE[slot] = Collections.unmodifiableSet(EnumSet.copyOf(facings));
        }
        TICK[slot] = currentTick;
    }

    private static int slotOf(BlockPos pos) {
        // 区块坐标只有三位有效信息，直接用 x/z 低位混合。
        // 这里 pos 已对齐到 16，右移 4 位得到区块索引。
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int h = cx * 0x9E3779B1 ^ cz * 0x85EBCA77;
        return (h ^ (h >>> 15)) & MASK;
    }

    private static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.visibleFacingCache;
    }

    public static String describe() {
        return "facingCacheActive=" + isEnabled();
    }
}
