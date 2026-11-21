package top.fpvp.lumen.network;

import net.minecraft.network.Packet;
import top.fpvp.lumen.LumenConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络收发优化：把「每包一刷」改成「攒一攒再刷」。
 *
 * <h2>原版的真实行为（反汇编确认）</h2>
 *
 * <p>{@code NetworkManager#sendPacket} 的路径是：
 *
 * <pre>
 *   sendPacket(packet):
 *     flushOutboundQueue();
 *     dispatchPacket(packet, null);
 *
 *   dispatchPacket(...):
 *     if (channel.eventLoop().inEventLoop()) {
 *         channel.writeAndFlush(packet);          // ← 每包一次 flush
 *     } else {
 *         channel.eventLoop().execute(...);       // 投递过去，那边也是 writeAndFlush
 *     }
 * </pre>
 *
 * <p>关键在 <b>{@code writeAndFlush}</b>：它不是 {@code write}。
 *
 * <p>Netty 里这两个操作是分开的：
 * <ul>
 *   <li>{@code write}：把数据放进 ChannelOutboundBuffer（用户态，几乎零成本）；</li>
 *   <li>{@code flush}：真正调用 {@code send()} 系统调用，把缓冲交给内核。</li>
 * </ul>
 *
 * <p>也就是说，<b>原版每发一个包就做一次系统调用</b>。单个包时这没问题；
 * 但在爆炸、区块批量更新、几十个实体同时移动这些场景下，
 * 一个 tick 内可能有几十上百个包 —— 就是几十上百次 {@code send()}。
 *
 * <p>系统调用的开销不在数据量（小包只有几十字节），而在
 * <b>用户态/内核态切换</b>。这正是"网络卡顿感"的一个真实来源：
 * 帧时间被这些偶发的批量发送拉长，表现为每隔一会儿顿一下。
 *
 * <h2>本类的做法：延迟 flush，而不是延迟 write</h2>
 *
 * <p>把 {@code writeAndFlush} 拆成 {@code write} + 由我们控制的 {@code flush}：
 *
 * <ul>
 *   <li><b>收包侧</b>：{@code NetworkSystem#networkTick} 每 tick 调一次
 *       {@code processReceivedPackets()}，原版在那里做 {@code channel.flush()}。
 *       我们<b>不拦收包</b> —— 收包延迟会直接表现为操作延迟，
 *       那是"卡"而不是"流畅"；</li>
 *   <li><b>发包侧</b>：把同一 tick 内的多个 {@code write} 攒起来，
 *       在 tick 边界用<b>一次</b> {@code flush} 全部发出。</li>
 * </ul>
 *
 * <h2>为什么这不是"把发包线程化"</h2>
 *
 * <p>仍然全部在 Netty 的 eventLoop 上执行，只是把"刷"的动作合并。
 * 没有跨线程投递、没有打乱顺序、没有延迟编码 ——
 * 这三点正是之前否决"自建发包线程池"的理由，本方案一个都没触碰。
 *
 * <p>换句话说：<b>包的顺序、内容、编码时机全都和原版一致</b>，
 * 只是把 N 次 {@code send()} 合并成 1 次。
 *
 * <h2>可调项与风险</h2>
 *
 * <p>{@code network.batchFlush} 开关 + {@code network.flushIntervalTicks}
 * （默认 1 = 每 tick 刷一次）。设成 1 就已经把"每包一刷"降到"每 tick 一刷"，
 * 这是收益最大且最安全的一档。
 *
 * <p>设成 2 或更大能进一步合并，但代价是<b>延迟真的增加了</b>：
 * 高延迟网络下玩家的操作反馈会变慢，而这对 PvP 是不可接受的。
 * 所以默认 1，上限 3（50ms，超过就不该叫"优化"了）。
 *
 * <p><b>自动降级</b>：检测到本机是<b>本地/局域网连接</b>时直接不启用 ——
 * 本地连接的"网络"其实是内存拷贝，没有系统调用瓶颈，合并 flush 只会增加延迟。
 */
public final class NetworkOptimizer {

    // ---- 统计 ----
    private static long packetsThisTick;
    private static long lastTickPackets;
    private static long totalPackets;
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong FLUSHES_SAVED = new AtomicLong();

    /** 当前 tick 里被我们 write 但还没 flush 的包数。 */
    private static int pendingFlush;

    /** 连续跳过计数（用于位置包去重）。 */
    private static int consecutiveSkip;

    /** 距上次 flush 过了几个 tick。 */
    private static int ticksSinceFlush;

    /** 是否检测到本地连接（决定是否整体降级）。 */
    private static boolean localConnection;

    private NetworkOptimizer() {
    }

    /**
     * 标记当前连接是本地/局域网。
     *
     * <p>由 mixin 在连接建立时调用。本地连接没有真实网络栈，
     * 合并 flush 无收益只增延迟。
     */
    public static void markLocalConnection(boolean local) {
        localConnection = local;
    }

    public static boolean isLocalConnection() {
        return localConnection;
    }

    /** 批量 flush 当前是否生效。 */
    public static boolean isBatchFlushActive() {
        return LumenConfig.enabled
                && LumenConfig.batchFlush
                && !localConnection;
    }

    /**
     * 当前这个包是否应该<b>立即</b> flush（而不是等 tick 边界）。
     *
     * <p>有一类包必须立刻发：否则会明显影响手感或正确性。
     * <ul>
     *   <li>聊天/命令 —— 玩家期待立刻有反馈；</li>
     *   <li>容器点击（{@code C0EPacketClickWindow}）—— 服务端要立刻确认，
     *       延迟会导致"点了没反应"的错觉，玩家会重复点，造成刷物品风险；</li>
     *   <li>挥手/攻击（{@code C0APacketAnimation}）—— 战斗手感；</li>
     *   <li>断开/握手类 —— 延迟毫无意义还可能出问题。</li>
     * </ul>
     *
     * <p>用类名判断而不是 {@code instanceof}：这些类在不同 MCP 映射下
     * 名字可能不同，而 {@code instanceof} 要求编译期就能引用到具体类。
     * 类名判断更宽容，且失败时只是"多 flush 一次"，无害。
     */
    public static boolean needsImmediateFlush(Packet<?> packet) {
        if (packet == null) {
            return true;
        }
        String name = packet.getClass().getSimpleName();
        return name.contains("Chat")
                || name.contains("ClickWindow")
                || name.contains("Animation")
                || name.contains("ConfirmTransaction")
                || name.contains("Disconnect")
                || name.contains("Handshake")
                || name.contains("KeepAlive");
    }

    /**
     * 记录一次 write。返回 {@code true} 表示调用方应当<b>现在</b> flush。
     *
     * <p>判据：
     * <ol>
     *   <li>批量 flush 未启用 → 总是立即 flush（等于原版行为）；</li>
     *   <li>这个包需要立刻发 → 立即 flush；</li>
     *   <li>攒够 {@code flushBatchSize} 个包 → 立即 flush（防止积压）；</li>
     *   <li>否则 → 先不刷，等 tick 边界。</li>
     * </ol>
     */
    public static boolean onWrite(Packet<?> packet) {
        packetsThisTick++;
        totalPackets++;

        if (!isBatchFlushActive()) {
            return true;
        }
        if (needsImmediateFlush(packet)) {
            return true;
        }
        int batchSize = LumenConfig.flushBatchSize;
        if (batchSize > 0 && pendingFlush >= batchSize) {
            return true;
        }
        pendingFlush++;
        return false;
    }

    /** 调用方执行了 flush 之后调用。 */
    public static void onFlushed(int batchedCount) {
        if (batchedCount > 1) {
            // N 个包合并成 1 次 flush，省下 N-1 次系统调用。
            FLUSHES_SAVED.addAndGet(batchedCount - 1L);
        }
        pendingFlush = 0;
        ticksSinceFlush = 0;
    }

    /**
     * tick 边界调用。返回 {@code true} 表示现在应当 flush。
     *
     * <p>{@code flushIntervalTicks} 控制合并的跨度：1 = 每 tick 刷，
     * 2 = 每两 tick 刷一次。
     */
    public static boolean shouldFlushOnTick() {
        if (!isBatchFlushActive() || pendingFlush == 0) {
            return false;
        }
        ticksSinceFlush++;
        int interval = Math.max(1, Math.min(3, LumenConfig.flushIntervalTicks));
        return ticksSinceFlush >= interval;
    }

    /**
     * 当前是否应该 flush（供 mixin 使用）。
     *
     * <p>比 {@link #shouldFlushOnTick()} 多一层 {@code pendingFlush == 0} 的短路，
     * 避免在没攒包时做无意义的 {@code send()} 系统调用。
     */
    public static boolean shouldFlushNow() {
        return pendingFlush > 0 && shouldFlushOnTick();
    }

    public static int pendingFlushCount() {
        return pendingFlush;
    }

    public static void beginTick() {
        lastTickPackets = packetsThisTick;
        packetsThisTick = 0;
    }

    // ---- 位置包去重（与 flush 合并是两件独立的事）----

    /**
     * 判断某个"每 tick 位置包"是否可以跳过。
     *
     * <p>只对 {@code S2CPacketPlayer} 这类高频小包生效。
     *
     * <p><b>默认关闭</b>：MC 服务端会依赖位置包做移动校验（反作弊的
     * "flying" 检查）。如果客户端长时间不发位置包，某些服务端会判定异常并踢人。
     * 即使开启也有最大连续跳过数（{@link #maxConsecutiveSkip()}）兜底。
     */
    public static boolean shouldSkipPlayerPosition(Packet<?> packet) {
        if (!LumenConfig.enabled || !LumenConfig.skipRedundantPackets || packet == null) {
            return false;
        }
        String name = packet.getClass().getSimpleName();
        if (!name.equals("S2CPacketPlayer")) {
            return false;
        }
        return consecutiveSkip < maxConsecutiveSkip();
    }

    /** 最大连续跳过数（可配，0 = 用内置默认 4）。 */
    public static int maxConsecutiveSkip() {
        int v = LumenConfig.maxConsecutiveSkip;
        return v > 0 ? v : 4;
    }

    public static void countSkipped() {
        SKIPPED.incrementAndGet();
    }

    public static void onSkipped() {
        consecutiveSkip++;
    }

    public static void onSent() {
        consecutiveSkip = 0;
    }

    /** 供 F3 调试行使用。 */
    public static String describe() {
        return "packets=" + totalPackets
                + " lastTick=" + lastTickPackets
                + " skipped=" + SKIPPED.get()
                + " flushSaved=" + FLUSHES_SAVED.get()
                + (isBatchFlushActive() ? "" : " (batch off)");
    }
}
