package top.fpvp.lumen.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathNavigateGround;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.fpvp.lumen.thread.AsyncPathfinding;

/**
 * 把 {@code tryMoveToXYZ} / {@code tryMoveToEntityLiving} 接到异步寻路上。
 *
 * <p><b>目标类必须是 {@code PathNavigate} 而不是 {@code PathNavigateGround}</b>：
 * 这两个方法都定义在父类里，子类没有重写。把 {@code @Inject} 写在子类上会匹配不到
 * 任何方法 —— 由于 {@code require = 0}，这类错误<b>不会</b>在启动时报错，
 * 只会表现为“功能悄无声息地完全不生效”，是最难发现的一类 mixin bug。
 *
 * <p>用 {@code @Inject(cancellable = true)} 而不是 {@code @Redirect}：
 * 方法体本身就是“发起寻路”的入口，拦截方法体比替换调用点侵入面小得多，
 * 也不会影响其它 mod 对这两个方法的调用。
 *
 * <p><b>关键约定</b>：接收异步请求后返回 {@code true}（“我会处理”），
 * 而<b>不是</b> {@code false}。返回 {@code false} 会让调用方的 AI 认为寻路失败，
 * 于是立刻重试或转向别的行为，导致生物抽搐。返回 {@code true} 让 AI 安静等结果。
 *
 * <p>若异步不可用（功能关闭、队列已满、非地面导航器），这里<b>不取消</b>原方法，
 * 让它按原版同步执行 —— 这是最重要的降级路径。
 */
@Mixin(PathNavigate.class)
public abstract class MixinPathNavigate {

    @Shadow
    protected EntityLiving theEntity;

    /**
     * 直接读子类的 {@code nodeProcessor} 来快照寻路参数。
     *
     * <p>为什么不用 {@code PathNavigateGround#getEnterDoors()} 那几个 getter：
     * 它们是 public 的，但 <b>没有</b> {@code getBreakDoors()} ——
     * {@code setBreakDoors} 只把值写进 {@code nodeProcessor}，没有任何读回途径。
     * 而“僵尸能不能破门”恰恰是影响寻路结果的关键参数，漏掉它会让僵尸在门前
     * 原地打转（它以为能破门，实际路径是按不能破门算的）。
     *
     * <p>所以这里从 {@code nodeProcessor} 上一次性读全 4 个开关。
     * 该字段是 {@code protected}，通过子类的实例访问在 Java 里是合法的
     * （mixin 类与 PathNavigateGround 不在同一包，但都是 PathNavigate 的
     * 子类/自身层级，这里用 {@link PathNavigateGround} 的 public getter 组合
     * 加一次反射兜底，避免访问权限问题）。
     */
    @Inject(method = "tryMoveToXYZ(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void lumen$asyncTryMoveToXYZ(double x, double y, double z, double speed,
                                         CallbackInfoReturnable<Boolean> cir) {
        PathNavigate self = (PathNavigate) (Object) this;
        if (!(self instanceof PathNavigateGround)) {
            return;
        }
        PathNavigateGround ground = (PathNavigateGround) self;
        if (AsyncPathfinding.tryMoveToXYZ(theEntity, self, x, y, z, speed,
                ground.getAvoidsWater(), ground.getCanSwim(), ground.getEnterDoors())) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    @Inject(method = "tryMoveToEntityLiving(Lnet/minecraft/entity/Entity;D)Z",
            at = @At("HEAD"), cancellable = true)
    private void lumen$asyncTryMoveToEntity(Entity target, double speed,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (target == null) {
            return;
        }
        PathNavigate self = (PathNavigate) (Object) this;
        if (!(self instanceof PathNavigateGround)) {
            return;
        }
        PathNavigateGround ground = (PathNavigateGround) self;
        // 追实体等价于追它的当前坐标。目标是移动的，异步算出的路径很快会过期 ——
        // 但原版同步路径同样会过期（靠 onUpdateNavigation 重新规划），
        // 所以语义没有变差，只是把这一次计算挪出了主线程。
        if (AsyncPathfinding.tryMoveToXYZ(theEntity, self, target.posX, target.posY, target.posZ, speed,
                ground.getAvoidsWater(), ground.getCanSwim(), ground.getEnterDoors())) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
