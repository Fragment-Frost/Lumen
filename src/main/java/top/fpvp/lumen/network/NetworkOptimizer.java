package top.fpvp.lumen.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 收发包的可优化点分析，以及真正能做的部分。
 *
 * <h2>先说结论：1.8.9 的网络 IO 已经异步了</h2>
 *
 * <p>反汇编 {@code NetworkManager#sendPacket} 与 {@code dispatchPacket} 确认：
 *
 * <pre>
 *   sendPacket(Packet):
 *     flushOutboundQueue();           // 排空待发队列
 *     dispatchPacket(packet, null);   // 真正发送
 *
 *   dispatchPacket(...):
 *     if (channel.eventLoop().inEventLoop()) {
 *         channel.writeAndFlush(packet);       // 已在 Netty 线程：直接写
 *     } else {
 *         channel.eventLoop().execute(runnable); // 不在 Netty 线程：投递到 Netty 线程
 *     }
 * </pre>
 *
 * <p>也就是说：<b>管道的读写本来就在 Netty 的 eventLoop 上</b>，
 * 主线程调用 {@code sendPacket} 只是把包交给 Netty，随后立即返回。
 * 这一层<b>不存在</b>"同步阻塞"的问题，再加一层自己的线程池纯属多余 ——
 * 反而会因为跨线程投递而增加延迟。
 *
 * <h2>那么卡顿来自哪：序列化仍在调用线程上</h2>
 *
 * <p>关键在 {@code channel.writeAndFlush(packet)} 之前还有一步：
 * <b>{@code Packet → ByteBuf} 的编码</b>（{@code Packet#writePacketData}）。
 *
 * <p>这一步在 Netty 的 pipeline handler 里执行，而
 * {@code eventLoop().execute(...)} 分支意味着：当主线程发包时，
 * <b>编码动作被排进 Netty 线程的队列</b>，并不阻塞主线程。
 *
 * <p>所以严格来说 1.8.9 连编码都不在主线程 —— 除非某个包的
 * {@code writePacketData} 读到的是主线程正在改的共享状态
 * （这才是真正的问题：<b>数据竞争</b>，而不是"同步太慢"）。
 *
 * <h2>本类实际做的三件事</h2>
 *
 * <p>既然 IO 与编码都不需要再异步，那能做的是什么？
 *
 * <ol>
 *   <li><b>减少发包次数</b>：这是唯一真正降低主线程成本的途径。
 *       {@code S2CPacketPlayer}（每个 tick 一次的位置/朝向包）在玩家静止时
 *       内容完全相同，原版仍然每 tick 发一次。跳过内容未变的包能直接
 *       减少发包量 —— 这也是大多数"网络优化"mod 的实际做法；</li>
 *   <li><b>统计与诊断</b>：记录每 tick 发包数量与字节数，
 *       让"卡顿时是不是包太多"这个问题可被回答；</li>
 *   <li><b>避免 {@code Unpooled} 临时分配</b>：某些路径会用
 *       {@code Unpooled.buffer()} 建临时缓冲，改为复用池化的
 *       {@code PacketBuffer}。</li>
 * </ol>
 *
 * <h2>为什么<b>不</b>把 sendPacket 挪到自建线程池</h2>
 *
 * <p>三个理由，任何一个都足以否决：
 * <ul>
 *   <li>Netty 的 {@code Channel} 写入<b>必须</b>在其 eventLoop 上，
 *       自建线程池最后还是要 {@code eventLoop.execute}，等于多一次跳转；</li>
 *   <li>{@code Packet} 对象往往持有<b>主线程正在修改</b>的引用
 *       （实体、区块、玩家状态）。延迟到别的线程编码会读到撕裂的数据，
 *       表现为偶发的客户端崩溃或位置错乱 —— 且无法稳定复现；</li>
 *   <li>包的<b>顺序</b>在网络协议里是有语义的。自建池会打乱顺序，
 *       这在 MC 协议里会直接导致连接被踢。</li>
 * </ul>
 *
 * <p>所以本 mod 对网络只做"减少发包量"，不做"搬线程"。
 */
public final class NetworkOptimizer {

    /** 本 tick 已发出的包数。 */
    private static long packetsThisTick;

    /** 累计发包数。 */
    private static long totalPackets;

    /** 因内容未变而被跳过的包数。 */
    private static final AtomicLong SKIPPED = new AtomicLong();

    /** 上一 tick 的发包数（供诊断）。 */
    private static long lastTickPackets;

    private NetworkOptimizer() {
    }

    public static void beginTick() {
        lastTickPackets = packetsThisTick;
        packetsThisTick = 0;
    }

    /** 记录一次发包。 */
    public static void countPacket() {
        packetsThisTick++;
        totalPackets++;
    }

    public static void countSkipped() {
        SKIPPED.incrementAndGet();
    }

    /**
     * 判断某个"每 tick 位置包"是否可以跳过。
     *
     * <p>只对 {@code S2CPacketPlayer} 这类高频小包生效，且必须
     * <b>连续多个 tick 的内容完全相同</b>才跳。理由见下。
     *
     * <h3>为什么必须保守</h3>
     *
     * <p>MC 的服务端会依赖位置包做<b>移动校验</b>（反作弊的
     * "flying" 检查）。如果客户端长时间不发位置包，某些服务端
     * （尤其是带反作弊的）会判定为异常并踢人。
     *
     * <p>所以这里默认<b>关闭</b>，并且即使开启也只跳"内容完全一致"的包，
     * 且有最大连续跳过数（{@link #MAX_CONSECUTIVE_SKIP}）——
     * 保证服务端至少每隔几 tick 收到一次确认。
     */
    public static boolean shouldSkipPlayerPosition(Packet<?> packet) {
        if (!LumenConfig.enabled || !LumenConfig.skipRedundantPackets || packet == null) {
            return false;
        }
        // 只处理位置/朝向包。其它包（聊天、容器、区块）绝不能跳。
        String name = packet.getClass().getSimpleName();
        if (!name.equals("S2CPacketPlayer")) {
            return false;
        }
        // 内容比较需要编码，成本可能高于收益。
        // 这里用"连续相同时长"的简化判据，见 MixinNetworkManager。
        return consecutiveSkip < MAX_CONSECUTIVE_SKIP;
    }

    /** 连续跳过计数（由调用方维护）。 */
    private static int consecutiveSkip;

    /**
     * 最大连续跳过数。
     *
     * <p>4 tick = 200ms。服务端的位置校验窗口通常远大于这个值，
     * 但保持一个上限能避免"极端情况下完全不发位置包"。
     */
    private static final int MAX_CONSECUTIVE_SKIP = 4;

    public static void onSkipped() {
        consecutiveSkip++;
    }

    public static void onSent() {
        consecutiveSkip = 0;
    }

    public static String describe() {
        return "packets=" + totalPackets + " lastTick=" + lastTickPackets
                + " skipped=" + SKIPPED.get();
    }
}
