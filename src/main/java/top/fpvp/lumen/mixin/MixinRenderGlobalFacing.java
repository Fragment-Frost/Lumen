package top.fpvp.lumen.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.fpvp.lumen.render.VisibleFacingCache;

import java.util.Set;

/**
 * 可见面集合缓存（Ixeris 指出的"转视角掉帧"在 1.8.9 上的落点）。
 *
 * <h2>拦的是什么</h2>
 *
 * <p>{@code RenderGlobal#getVisibleFacings(BlockPos)} 判断某个区块的哪几个面
 * 被邻居挡住（挡住的面不必渲染，本身是一个很有效的剔除）。它对每个可见渲染
 * 区块都会被调用一次：视距 16 时是数百次，每次做 6 次邻接方块查询 +
 * 新建一个 {@code EnumSet}。
 *
 * <p>关键在于：<b>它的结果只依赖方块数据，与相机朝向无关</b>。
 * 而原版在相机朝向变化时会重跑整个可见性计算
 * （{@code setupTerrain} 里只要 yaw/pitch 变了就重建全部 {@code renderInfos}），
 * 于是这些与朝向无关的计算被<b>白白重算</b>。这正是转动视角时掉帧的一个真实成因。
 *
 * <h2>为什么用 @Inject + CallbackInfoReturnable 而不是 @Redirect</h2>
 *
 * <p>目标方法是 {@code private}。用 {@code @Redirect} 替换它之后，
 * 想在缓存未命中时"调用原方法"就得额外借助 {@code @Invoker} 访问器接口，
 * 多一个 mixin、多一个失败点。而 {@code @Inject} 在 HEAD 处
 * {@code setReturnValue(...)} + {@code cancel()} 天然表达"命中就走、不命中就
 * 让原方法继续执行"，一行访问器都不需要。
 *
 * <p>返回值是 {@code Set}，泛型在描述符里被擦除，所以 mixin 方法签名写
 * {@code CallbackInfoReturnable<Set>} 即可，不会因为泛型不匹配而注入失败。
 *
 * <h2>为什么缓存只活一个 tick</h2>
 *
 * <p>见 {@link VisibleFacingCache} 的类注释。方块可能变化，而 1.8.9 没有
 * 可靠的"区块方块数据版本号"可用。宁可每个 tick 重算一次，也不能让玩家看到
 * "方块拆了但那一面还是不画"。
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalFacing {

    @Inject(
            method = "getVisibleFacings",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void lumen$useCachedVisibleFacings(BlockPos pos,
                                               CallbackInfoReturnable<Set<EnumFacing>> cir) {
        Set<EnumFacing> cached = VisibleFacingCache.get(pos);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        // 未命中：让原方法继续算。算完的结果由 setupTerrain 之后的路径
        // 通过 put 登记 —— 见下面的 TAIL 注入。
    }

    /**
     * 原方法算完之后把结果登记进缓存。
     *
     * <p>分成 HEAD（查）与 RETURN（存）两处注入，而不是在 HEAD 里
     * "自己算一遍再存"：后者等于把原方法的逻辑复制一份，
     * 一旦原版或其它核心修改器改了算法，两份实现就会不一致。
     */
    @Inject(
            method = "getVisibleFacings",
            at = @At("RETURN"),
            require = 0
    )
    private void lumen$storeVisibleFacings(BlockPos pos,
                                           CallbackInfoReturnable<Set<EnumFacing>> cir) {
        Set<EnumFacing> result = cir.getReturnValue();
        if (result != null) {
            VisibleFacingCache.put(pos, result);
        }
    }
}
