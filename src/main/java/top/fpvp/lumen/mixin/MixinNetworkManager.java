package top.fpvp.lumen.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.network.NetworkOptimizer;

/**
 * 把「每包一刷」改成「攒一攒再刷」。
 *
 * <h2>拦截点为什么是 {@code writeAndFlush}</h2>
 *
 * <p>反汇编 {@code NetworkManager#dispatchPacket}：它内部只有一处
 * {@code Channel.writeAndFlush(packet)}。原版在<b>同一个事件循环分支</b>里
 * 直接调用它，因此每个包都会触发一次 {@code send()} 系统调用。
 *
 * <p>Netty 里 {@code write} 和 {@code flush} 是分开的：
 * <ul>
 *   <li>{@code write}：放进 ChannelOutboundBuffer，用户态，几乎零成本；</li>
 *   <li>{@code flush}：真正 {@code send()}，用户态/内核态切换。</li>
 * </ul>
 *
 * <p>所以把 {@code writeAndFlush(x)} 换成 {@code write(x)}，
 * 再在 tick 边界统一 {@code flush()}，就能把 N 次系统调用压成 1 次。
 *
 * <h2>为什么这个改动是安全的</h2>
 *
 * <p>它<b>不</b>碰这三样东西（那三样正是之前否决"自建发包线程池"的理由）：
 * <ul>
 *   <li>线程：仍在 Netty 的 eventLoop 上；</li>
 *   <li>顺序：仍然按 {@code write} 的调用顺序进入出站缓冲，Netty 保证 FIFO；</li>
 *   <li>编码时机：{@code write} 会立即经过 pipeline 编码成 ByteBuf，
 *       与 {@code writeAndFlush} 的编码时机完全相同。</li>
 * </ul>
 *
 * <p>唯一改变的是"何时把字节交给内核"。
 *
 * <h2>哪些包仍然立即刷</h2>
 *
 * <p>{@link NetworkOptimizer#needsImmediateFlush} 里的包（聊天、容器点击、
 * 挥手、确认交易、断开、握手、心跳）不参与合并 ——
 * 容器点击延迟会导致玩家重复点击，那是刷物品风险，不能碰。
 *
 * <h2>tick 边界的 flush 挂在哪</h2>
 *
 * <p>{@code processReceivedPackets()} 是 {@code NetworkSystem#networkTick}
 * 每 tick 调用一次的方法，原版在它的末尾就已经有一个 {@code channel.flush()}。
 * 我们在 HEAD 补一次 flush，把上一 tick 攒下的包发出去 ——
 * 这样不需要新增 tick 回调，也不会与原有 flush 冲突（多一次空 flush 无害）。
 */
@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {

    /**
     * 最近一次写操作使用的 Channel。
     *
     * <p>为什么需要记住它：{@code processReceivedPackets} 里拿不到
     * {@code dispatchPacket} 的参数，但 flush 需要一个 Channel。
     * 而 {@code NetworkManager} 只有一个 channel 字段（反汇编确认），
     * 所以记下最近用的那个即可。
     *
     * <p>用 {@code volatile}：写操作可能来自主线程，
     * 而 tick flush 走的是客户端线程 —— 两者是不同线程。
     *
     * <p>{@code @Unique} 标记是 Mixin 的规范：避免与目标类将来可能出现的
     * 同名字段冲突（冲突时 Mixin 会报错，导致整个 mod 起不来）。
     */
    @org.spongepowered.asm.mixin.Unique
    private volatile Channel lumen$lastChannel;

    /**
     * 记录本地/局域网连接，供 {@link NetworkOptimizer} 自动降级。
     *
     * <p>本地连接的"网络"是内存拷贝，没有系统调用瓶颈，
     * 合并 flush 只会增加延迟。
     */
    @Inject(method = "isLocalChannel", at = @At("RETURN"), require = 0)
    private void lumen$noteLocalChannel(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        NetworkOptimizer.markLocalConnection(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    /**
     * 把 {@code writeAndFlush} 拆成 {@code write}（按需）。
     *
     * <p>返回一个 {@code ChannelFuture}：批量模式下用 {@code write} 的返回值，
     * 立即模式下用 {@code writeAndFlush} 的返回值。调用方只用它加 listener，
     * 两者语义一致（都是"这次写操作的 future"）。
     */
    @Redirect(
            method = "dispatchPacket",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"
            ),
            require = 0
    )
    private ChannelFuture lumen$batchWrite(Channel channel, Object msg) {
        // 记下 Channel，供 tick 边界的 flush 使用。
        this.lumen$lastChannel = channel;

        if (!NetworkOptimizer.isBatchFlushActive()) {
            return channel.writeAndFlush(msg);
        }
        Packet<?> packet = msg instanceof Packet ? (Packet<?>) msg : null;
        if (NetworkOptimizer.onWrite(packet)) {
            // 需要立刻发（聊天/点击/挥手等，或攒够了）。
            int pending = NetworkOptimizer.pendingFlushCount();
            ChannelFuture f = channel.writeAndFlush(msg);
            NetworkOptimizer.onFlushed(pending + 1);
            return f;
        }
        // 只写不刷：等 tick 边界统一 flush。
        return channel.write(msg);
    }

    /**
     * tick 边界统一 flush。
     *
     * <p>挂在 {@code processReceivedPackets} 的 HEAD：
     * 这个方法每 tick 被 {@code NetworkSystem#networkTick} 调用一次，
     * 而它末尾原版本来就有一次 {@code channel.flush()} ——
     * 我们在开头补一次，把上一 tick 攒的包发出去。
     *
     * <p>用 HEAD 而不是 TAIL：HEAD 时上一 tick 的包已经攒好，
     * 立刻发出；如果挂在 TAIL，这些包会等到下一 tick 才走，
     * 平白多了一 tick 延迟。
     */
    @Inject(method = "processReceivedPackets", at = @At("HEAD"), require = 0)
    private void lumen$flushBatchedPackets(CallbackInfo ci) {
        NetworkOptimizer.beginTick();
        if (!NetworkOptimizer.shouldFlushNow()) {
            return;
        }
        Channel ch = this.lumen$lastChannel;
        if (ch == null || !ch.isOpen()) {
            return;
        }
        int pending = NetworkOptimizer.pendingFlushCount();
        ch.flush();
        NetworkOptimizer.onFlushed(pending);
    }
}
