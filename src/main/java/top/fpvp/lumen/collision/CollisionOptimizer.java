package top.fpvp.lumen.collision;

import net.minecraft.util.BlockPos;
import top.fpvp.lumen.LumenConfig;

/**
 * 碰撞检测优化（参考「加速碰撞」类 mod 的思路）。
 *
 * <h2>原版的开销在哪</h2>
 *
 * <p>反汇编 {@code World#getCollidingBoundingBoxes} 确认了两个分配点：
 * <ol>
 *   <li>方法开头 {@code Lists.newArrayList()}（偏移 0-3）；</li>
 *   <li>随后 {@code new BlockPos.MutableBlockPos()}（偏移 93-100）。</li>
 * </ol>
 *
 * <p>调用频率很高：每个实体每 tick 至少一次，摔落、挤在方块里、
 * 骑乘时会被反复调用。100 个实体 = 每秒 6000+ 个短命对象，
 * 全部落在年轻代，直接推高 GC 频率。
 *
 * <h2>为什么只优化第二个</h2>
 *
 * <p>本类<b>只</b>处理 {@code MutableBlockPos}，因为它
 * <b>只在方法内部使用、不会逃逸</b>，换成复用实例与每次新建完全等价。
 *
 * <p>而那个 {@code ArrayList} <b>不能</b>安全复用：方法把它<b>返回给调用方</b>，
 * 调用方（{@code Entity#moveEntity} 等）会持有并遍历它。
 * 要复用它就必须知道调用方何时用完，而 Java 没有可靠的"用完"通知 ——
 * 引用计数或 try/finally 都要求改遍所有调用方（实体、载具、投掷物、
 * 以及所有 mod）。
 *
 * <p>一旦某处漏归还，或内层重入覆盖了外层正在遍历的列表，
 * 表现是<b>实体卡住、穿墙、莫名被弹飞</b>。这类 bug 无法稳定复现，
 * 也几乎不可能归因到碰撞优化上。
 *
 * <p>所以这里明确<b>放弃</b>那个收益。如果有人想"顺手优化"回去，
 * 请先读上面这段。
 *
 * <h2>为什么不做「跳过空气」</h2>
 *
 * <p>看起来在 {@code addCollisionBoxesToList} 之前判断 {@code isAir}
 * 能省一次虚调用，但原版的 {@code Block.addCollisionBoxesToList}
 * 对空气的实现本身就是立即返回（{@code BlockAir} 覆盖为空实现），
 * 而多一层判断反而增加了一次 {@code getBlockState} 已得到的
 * {@code IBlockState} 上的调用。实测没有净收益，故不做。
 */
public final class CollisionOptimizer {

    /** 复用的方块坐标（线程内）。 */
    private static final ThreadLocal<BlockPos.MutableBlockPos> CURSOR =
            new ThreadLocal<BlockPos.MutableBlockPos>() {
                @Override
                protected BlockPos.MutableBlockPos initialValue() {
                    return new BlockPos.MutableBlockPos();
                }
            };

    /** 统计：省下的分配次数。 */
    private static long reusedCursors;

    private CollisionOptimizer() {
    }

    /**
     * 取复用的可变坐标。
     *
     * <p>返回的对象<b>同样不可逃逸</b>：调用方只能在本方法栈帧内使用它，
     * 不能存到字段或返回出去。当前的唯一调用方
     * （{@code MixinWorldCollision} 的 {@code @Redirect}）满足这个约束 ——
     * 它只把该对象传回原方法，原方法也只在栈内用它。
     */
    public static BlockPos.MutableBlockPos cursor() {
        if (isEnabled()) {
            reusedCursors++;
        }
        return CURSOR.get();
    }

    private static boolean isEnabled() {
        return LumenConfig.enabled && LumenConfig.collisionOptimization;
    }

    public static String describe() {
        return "collisionCursorReused=" + reusedCursors;
    }
}
