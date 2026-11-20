package top.fpvp.lumen.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;
import top.fpvp.lumen.render.HudTextureCache;
import top.fpvp.lumen.render.OcclusionCuller;
import top.fpvp.lumen.thread.AsyncPathfinding;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;
import top.fpvp.lumen.world.FluidFlowCache;

/**
 * 每帧把后台完成的结果交付到主线程，并做跨世界的状态清理。
 *
 * <p><b>为什么交付点选在这里</b>：{@code Minecraft#runTick} 是客户端一帧的
 * 唯一入口，在它返回处交付有两个好处：
 * <ol>
 *   <li>此刻既不在渲染中途、也不在实体 tick 中途，写任何游戏状态都安全；</li>
 *   <li>交付发生在下一帧的 tick 与渲染之前，所以本帧算出的路径/网格
 *       能被立刻用上，不会白等一帧。</li>
 * </ol>
 *
 * <p><b>交付必须有预算。</b>一次交付上千个区块网格会让这一帧明显变长，
 * 表现为“偶尔卡一下”。这里按 {@link #MAX_DELIVERIES_PER_TICK} 限流，
 * 把突发的工作摊到后续几帧，反而更平滑。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    /**
     * 每 tick 最多交付多少个结果。
     *
     * <p>取值思路：交付一个网格的实际成本约几十微秒，64 个大约 2~4ms，
     * 在一帧 16ms 的预算里是可控的上限。超出部分留到下一 tick。
     */
    private static final int MAX_DELIVERIES_PER_TICK = 64;

    @Inject(method = "runTick", at = @At("RETURN"))
    private void lumen$deliverAsyncResults(CallbackInfo ci) {
        if (!LumenConfig.enabled) {
            return;
        }
        AsyncTaskDispatcher.get().drainMainThread(MAX_DELIVERIES_PER_TICK);
        LumenProfiler.endFrame();
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void lumen$beginFrame(CallbackInfo ci) {
        if (LumenConfig.enabled) {
            LumenProfiler.beginFrame();
        }
    }

    /**
     * 退出游戏时停掉后台线程。
     *
     * <p>worker 是守护线程，理论上进程退出就会终止；但 DevAuth / Forge 的
     * 关闭流程会在主线程上 join 一段时间，此时还在跑的 worker 可能与
     * 正在拆除的世界对象竞争，产生一堆无意义的异常刷屏。显式停掉更干净。
     */
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void lumen$shutdown(CallbackInfo ci) {
        AsyncTaskDispatcher.get().shutdown();
        HudTextureCache.get().invalidateAll();
        OcclusionCuller.get().reset();
        FluidFlowCache.get().reset();
        AsyncPathfinding.reset();
    }

    /**
     * 切换世界时作废在途结果并清空所有按世界缓存。
     *
     * <p>{@code Minecraft#loadWorld} 是客户端唯一的“换世界”入口（单人进出存档、
     * 多人进出服务器都会走它）。在这里统一清理，比在每个用到缓存的地方
     * 各自判断要可靠得多 —— 漏掉一处的表现就是“进服务器后实体被隐形墙挡住”。
     */
    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void lumen$onWorldChange(CallbackInfo ci) {
        AsyncTaskDispatcher.get().bumpGeneration();
        OcclusionCuller.get().reset();
        FluidFlowCache.get().reset();
        AsyncPathfinding.reset();
        HudTextureCache.get().onWorldUnload();
    }
}
