package top.fpvp.lumen.mixin;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.fpvp.lumen.collision.CollisionOptimizer;

/**
 * 碰撞检测的零分配优化（参考「加速碰撞」类 mod）。
 *
 * <h2>拦的是什么</h2>
 *
 * <p>反汇编 {@code World#getCollidingBoundingBoxes} 确认：方法一开始就
 * {@code new BlockPos.MutableBlockPos()}（字节码偏移 93-100），
 * 用于在遍历碰撞箱覆盖的方块时复用坐标。
 *
 * <p>这个对象<b>只在方法内部使用、不会逃逸</b>，因此换成线程内复用实例
 * 在语义上与每次新建完全等价。而它被调用的频率很高：
 * 每个实体每 tick 至少一次，摔落/挤在方块里时会被反复调用。
 * 100 个实体 = 每秒 6000+ 个短命对象。
 *
 * <h2>为什么<b>不</b>缓存返回的 ArrayList</h2>
 *
 * <p>原版同样每次 {@code Lists.newArrayList()}。看起来很诱人也更省，
 * 但那是<b>不能</b>安全复用的：这个方法把列表<b>返回给调用方</b>，
 * 调用方（{@code Entity#moveEntity} 等）会持有它并遍历。
 *
 * <p>如果改成共享池，就必须知道调用方何时用完 —— 而 Java 没有
 * 可靠的"用完"通知。用引用计数或 try/finally 都要求改调用方，
 * 而调用方遍布实体、载具、投掷物、以及所有 mod。
 *
 * <p>一旦某处忘记归还，或者内层重入覆盖了外层正在遍历的列表，
 * 表现是<b>实体卡住、穿墙、莫名被弹飞</b> —— 这类 bug 无法稳定复现，
 * 且几乎不可能归因到碰撞优化上。
 *
 * <p>所以这里只做"零逃逸对象"的那一项，把有风险的收益让掉。
 * 本类的注释保留这个取舍记录，避免以后有人"顺手优化"回去。
 */
@Mixin(World.class)
public abstract class MixinWorldCollision {

    /**
     * 复用 MutableBlockPos。
     *
     * <p>{@code @Redirect} 的 {@code NEW} 类型可以精确替换
     * {@code new BlockPos.MutableBlockPos()} 这一次分配。
     */
    @Redirect(
            method = "getCollidingBoundingBoxes",
            at = @At(
                    value = "NEW",
                    target = "(III)Lnet/minecraft/util/BlockPos$MutableBlockPos;"
            ),
            require = 0
    )
    private BlockPos.MutableBlockPos lumen$reuseCursor(int x, int y, int z) {
        BlockPos.MutableBlockPos cursor = CollisionOptimizer.cursor();
        cursor.set(x, y, z);
        return cursor;
    }
}
