package top.fpvp.lumen.thread;

/**
 * 一次可以在后台线程完成的计算，结果回主线程交付。
 *
 * <p>调度契约（写错了就会死锁/拖慢帧率，务必遵守）：
 * <ul>
 *   <li>{@link #compute()} 里<b>绝对不能</b>碰 OpenGL、Tessellator、Chat/ChatLine，
 *       也不能调用任何会回调主线程的 API。后台线程没有渲染上下文，
 *       这类调用会立刻抛异常，或者更糟——静默地把 GL 状态改坏。</li>
 *   <li>{@link #compute()} 里<b>不能</b>等待其它任务的结果。任务之间一旦互相等待，
 *       就构成了死锁的最小单元；本调度器刻意不提供 {@code Future.get()}。</li>
 *   <li>结果对象一经提交就视为不可变：主线程读它时，后台线程可能已经在写下一份结果。</li>
 *   <li>{@link #deliver(Object)} 在主线程执行，可以做任何事，但必须<b>短</b>——
 *       它占用的是帧时间。</li>
 * </ul>
 *
 * @param <T> 计算结果类型
 */
public abstract class AsyncTask<T> {

    /** 提交时的世界代次；代次不匹配说明世界已经换过，结果必须丢弃。 */
    volatile int generation;

    /** 提交序号，用于调试与超时归因。 */
    volatile long sequence;

    /** 任务开始执行的纳秒时间戳，0 表示尚未开始。 */
    volatile long startedAtNanos;

    volatile boolean cancelled;

    /**
     * 在后台线程执行。允许返回 {@code null}（表示“没有结果”），
     * 此时 {@link #deliver(Object)} 不会被调用。
     */
    protected abstract T compute() throws Exception;

    /**
     * 在主线程交付结果。
     *
     * <p>默认空实现，便于“只为了副作用”的任务使用。
     */
    protected void deliver(T result) {
    }

    /**
     * 结果被丢弃时回调（世界切换、代次过期、队列拥塞等）。在主线程执行。
     *
     * <p>覆写它可以释放挂起的资源（例如把 {@code RenderChunk} 标记回待更新状态），
     * 否则被丢弃的任务会让对应对象永远停在“正在计算”的中间态。
     */
    protected void onDiscard(DiscardReason reason) {
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    /** 丢弃原因，用于统计与排查。 */
    public enum DiscardReason {
        /** 已切换世界，结果无意义。 */
        STALE_GENERATION,
        /** 主线程显式取消。 */
        CANCELLED,
        /** 结果队列拥塞，主动丢弃以保护内存与帧时间。 */
        BACKLOG,
        /** 任务抛异常。 */
        ERROR
    }
}
