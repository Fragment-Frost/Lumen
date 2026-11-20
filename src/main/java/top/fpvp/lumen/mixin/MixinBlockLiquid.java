package top.fpvp.lumen.mixin;

import net.minecraft.block.BlockLiquid;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.fpvp.lumen.world.FluidFlowCache;

/**
 * 给 {@code BlockLiquid#getFlowVector} 加一层按 tick 的缓存。
 *
 * <p>用 {@code @Inject(cancellable = true)} 而不是 {@code @Redirect}：
 * 我们只关心这个方法的<b>返回值</b>，不替换任何调用点。用 {@code @Redirect} 拦截
 * 所有调用方会侵入到每一个调用点（包括 mod 的），而 {@code @Inject} 只作用于
 * 这个方法本身，作用域最小、最不容易与其它 mod 冲突。
 *
 * <p>注意这里的 {@code getFlowVector} 是 {@code protected} —— mixin 直接写方法头
 * 时并不关心可见性，注解处理器会按目标方法的实际描述符去匹配。
 */
@Mixin(BlockLiquid.class)
public abstract class MixinBlockLiquid {

    /** 复用的输出缓冲，避免每次查询都 new 一个 double[3]。 */
    private static final double[] LUMEN$OUT = new double[3];

    @Inject(method = "getFlowVector", at = @At("HEAD"), cancellable = true)
    private void lumen$cachedFlowVector(IBlockAccess access, BlockPos pos,
                                        CallbackInfoReturnable<Vec3> cir) {
        if (!FluidFlowCache.isEnabled()) {
            return;
        }

        // 先查缓存，再判断材料。
        //
        // 顺序很重要：{@code isCacheable} 自己要做一次 getBlockState，
        // 如果放在前面，那么“命中缓存”这条本该最便宜的路径反而多付了一次方块查询。
        // 先查哈希是纯算术，命中就直接返回。
        //
        // 这样做是安全的：我们只会把水/岩浆的向量写进缓存，
        // 而缓存的有效期只有一个 tick，不存在“水变成石头了还返回旧向量”的窗口。
        FluidFlowCache cache = FluidFlowCache.get();
        long key = pos.toLong();
        if (cache.lookup(key, LUMEN$OUT)) {
            cir.setReturnValue(new Vec3(LUMEN$OUT[0], LUMEN$OUT[1], LUMEN$OUT[2]));
        }
    }

    /**
     * 在方法返回处把结果写回缓存。
     *
     * <p>用 {@code RETURN} 而不是在 HEAD 里调用原方法：{@code getFlowVector} 内部
     * 会处理 {@code LEVEL} 属性、相邻方块等细节，任何自己重写的版本都可能与
     * 原版产生行为差异。这里只在“原版算完之后”顺手存一份。
     */
    @Inject(method = "getFlowVector", at = @At("RETURN"))
    private void lumen$storeFlowVector(IBlockAccess access, BlockPos pos,
                                       CallbackInfoReturnable<Vec3> cir) {
        if (!FluidFlowCache.isEnabled()) {
            return;
        }
        Vec3 result = cir.getReturnValue();
        if (result == null) {
            return;
        }
        if (!FluidFlowCache.isCacheable(access, pos)) {
            return;
        }
        FluidFlowCache.get().store(pos.toLong(), result.xCoord, result.yCoord, result.zCoord);
    }
}
