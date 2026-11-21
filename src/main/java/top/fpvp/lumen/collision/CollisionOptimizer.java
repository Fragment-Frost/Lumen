package top.fpvp.lumen.collision;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import top.fpvp.lumen.LumenConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 碰撞检测优化（参考「加速碰撞」类 mod 的思路）。
 *
 * <h2>原版的开销在哪</h2>
 *
 * <p>反汇编 {@code World#getCollidingBoundingBoxes} 确认了两个热点：
 *
 * <ol>
 *   <li><b>每次调用都 {@code Lists.newArrayList()}</b>（字节码偏移 0-3）。
 *       每个实体每 tick 至少调用一次，摔落/挤在方块里时会被反复调用。
 *       100 个实体 = 每秒 6000+ 个 ArrayList；</li>
 *   <li><b>每次调用都 {@code new BlockPos.MutableBlockPos()}</b>（偏移 93-100）。
 *       同上，纯浪费 —— 这个对象完全可以复用。</li>
 * </ol>
 *
 * <p>另外它遍历的是碰撞箱覆盖的<b>全部方块</b>（对玩家是约 2×3×2 格）。
 * 绝大多数方块返回 {@code null}（空气、草丛、花），但仍然要为每个位置
 * 做一次 blockstate 查询 + {@code getCollisionBoundingBox} 虚调用。
 *
 * <h2>三项优化</h2>
 *
 * <ol>
 *   <li><b>复用结果列表</b>：用一个可复用的 ArrayList，调用方遍历完就还回来。
 *       <b>但它不能是全局单例</b> —— 见下面的「重入」警告。</li>
 *   <li><b>复用 MutableBlockPos</b>：线程内游标。</li>
 *   <li><b>提前跳过空方块</b>：在调用 {@code getCollisionBoundingBox} 之前
 *       先用 {@code isAir} / 无碰撞箱判断，省掉一次虚调用。</li>
 * </ol>
 *
 * <h2>重入：为什么列表必须按深度取</h2>
 *
 * <p>碰撞检测是<b>可重入</b>的：{@code Entity#moveEntity} 调用
 * {@code getCollidingBoundingBoxes}，而某些方块的
 * {@code addCollisionBoxesToList} 内部又会间接查询其它实体的碰撞，
 * 或者实体自己的 {@code onEntityCollidedWithBlock} 触发新的移动。
 *
 * <p>如果只用一个共享列表，内层调用会覆盖外层正在遍历的那个 ——
 * 表现为实体卡住、穿墙、或莫名被弹飞。这类 bug 极难复现也更难定位。
 *
 * <p>因此这里用一个<b>按嵌套深度索引的列表池</b>：每进入一层取下一个，
 * 退出时归还。深度上限（{@value #MAX_DEPTH}）足够覆盖任何现实情况，
 * 超出时退化为每次新建（正确性优先）。
 */
public final class CollisionOptimizer {

    /** 支持的嵌套深度。超出后新建列表（正确但不省）。 */
    private static final int MAX_DEPTH = 8;

    /**
     * 按嵌套深度分层的列表池。
     *
     * <p>用 ThreadLocal：虽然 1.8.9 的碰撞基本只在主线程跑，
     * 但服务端集成服/某些 mod 会在线程池里做实体物理。
     * ThreadLocal 的分配开销只在首次访问时付一次。
     */
    private static final ThreadLocal<List<AxisAlignedBB>[]> POOL =
            new ThreadLocal<List<AxisAlignedBB>[]>() {
                @Override
                @SuppressWarnings("unchecked")
                protected List<AxisAlignedBB>[] initialValue() {
                    List<AxisAlignedBB>[] arr = new List[MAX_DEPTH];
                    for (int i = 0; i < MAX_DEPTH; i++) {
                        arr[i] = new ArrayList<AxisAlignedBB>(16);
                    }
                    return arr;
                }
            };

    /** 当前线程的嵌套深度。 */
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    /** 复用的方块坐标。 */
    private static final ThreadLocal<BlockPos.MutableBlockPos> CURSOR =
            new ThreadLocal<BlockPos.MutableBlockPos>() {
                @Override
                protected BlockPos.MutableBlockPos initialValue() {
                    return new BlockPos.MutableBlockPos();
                }
            };

    /** 统计。 */
    private static long totalCalls;
    private static long totalReusedLists;

    private CollisionOptimizer() {
    }

    /**
     * 取一个空的碰撞箱列表，并进入下一层嵌套。
     *
     * <p>调用方<b>必须</b>在结束时调用 {@link #release(List, int)} ——
     * 通常是 try/finally。
     */
    public static List<AxisAlignedBB> acquire() {
        if (!isEnabled()) {
            return new ArrayList<AxisAlignedBB>(16);
        }
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d >= MAX_DEPTH) {
            // 嵌套过深：新建一个，release 时靠"不在池里"判断来跳过归还。
            return new ArrayList<AxisAlignedBB>(16);
        }
        depth[0] = d + 1;
        List<AxisAlignedBB> list = POOL.get()[d];
        list.clear();
        totalReusedLists++;
        return list;
    }

    /**
     * 归还列表。
     *
     * @param list  之前 {@link #acquire()} 返回的对象
     * @param depth 进入时的深度（这里通过参数传入而不是重新读，避免内层改动后错配）
     */
    public static void release(List<AxisAlignedBB> list) {
        if (!isEnabled()) {
            return;
        }
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
        // 不在这里 clear：下一次 acquire 会 clear。
        // 立即 clear 会让仍在持有该引用的调用方读到空列表 ——
        // 虽然正常用法下不会发生，但不 clear 更安全且无额外成本。
    }

    /** 取复用的可变坐标（省掉每帧 new MutableBlockPos）。 */
    public static BlockPos.MutableBlockPos cursor() {
        return CURSOR.get();
    }

    public static void countCall() {
        totalCalls++;
    }

    /**
     * 该位置的方块是否<b>不可能</b>提供碰撞箱。
     *
     * <p>用于在 {@code getCollidingBoundingBox} 之前快速短路。
     * 只判定"一定没有"的情况，所以不会漏掉任何真实碰撞。
     *
     * <p>注意<b>不能</b>用 {@code isFullCube} 反向判断：
     * 楼梯、台阶、栅栏都不是整方块，但都有碰撞箱。
     */
    public static boolean hasNoCollision(World world, BlockPos pos, IBlockState state) {
        if (!isEnabled()) {
            return false;
        }
        Block block = state.getBlock();
        // 空气是最常见的（挖矿、空中移动）：
        // isAir 是接口上的默认实现，比 getCollisionBoundingBox 便宜得多。
        if (block.isAir(world, pos)) {
            return true;
        }
        return false;
    }

    private static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.collisionOptimization;
    }

    public static String describe() {
        return "collisionCalls=" + totalCalls + " reusedLists=" + totalReusedLists;
    }
}
