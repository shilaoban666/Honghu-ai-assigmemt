package com.honghu.ai.assigment.rag.monitor;

import com.honghu.ai.assigment.dto.RagFileStatusResponse;
import com.honghu.ai.assigment.exception.RagAccessDeniedException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 文件状态 SSE 推送器。
 *
 * <p>把"开 SSE → 周期性拉取状态 → 状态变化时推送 → 完成或失败时回收"整段流程
 * 从 controller 里搬出来，原因有三：</p>
 * <ol>
 *     <li>controller 不该持有线程池等基础设施资源；</li>
 *     <li>把异步轮询做成可单测的服务（不依赖 MockMvc + 异步 hack）；</li>
 *     <li>多个入口可以复用同一段 SSE 推送逻辑（未来可以接 EventListener 改成推 → 拉混合）。</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 * <p>专用 {@link ScheduledExecutorService}（默认 8 个 daemon 线程），独立于 ForkJoinPool.commonPool。
 * 每个 SSE 连接占一个 task，{@code scheduleWithFixedDelay} 间隔 ≥ 500ms。
 * 客户端断连/超时/异常时通过 {@link SseEmitter#onCompletion}/{@link SseEmitter#onTimeout}/{@link SseEmitter#onError}
 * 回收 task。</p>
 */
@Slf4j
@Service
public class RagFileStatusStreamer {

    /** SSE 单连接最长存活时间下限（秒），防止前端传入过短导致刚连上就断开。 */
    public static final long MIN_TIMEOUT_SECONDS = 5L;
    /** SSE 单连接最长存活时间上限（秒），防止单连接过长占用线程。 */
    public static final long MAX_TIMEOUT_SECONDS = 600L;
    /** SSE 轮询间隔下限（毫秒），防止瞬时打爆数据库。 */
    public static final long MIN_POLL_INTERVAL_MILLIS = 500L;

    private static final String EVENT_NAME_STATUS = "rag-status";
    private static final String EVENT_NAME_TIMEOUT = "timeout";

    private final RagIngestionStatusService ragIngestionStatusService;
    private final ScheduledExecutorService scheduler;

    /**
     * 生产环境构造器。
     *
     * <p>这里只注入状态查询服务，调度线程池由类内部创建。
     * 线程池使用 daemon 线程，是为了让应用关闭时不会被 SSE 轮询线程阻塞。</p>
     *
     * @param ragIngestionStatusService RAG 文件状态查询服务
     */
    @Autowired
    public RagFileStatusStreamer(RagIngestionStatusService ragIngestionStatusService) {
        // 生产默认构造：创建一个固定大小的 daemon 调度线程池，专门服务 SSE 轮询。
        this(ragIngestionStatusService, Executors.newScheduledThreadPool(8, daemonThreadFactory()));
    }

    RagFileStatusStreamer(RagIngestionStatusService ragIngestionStatusService,
                          ScheduledExecutorService scheduler) {
        this.ragIngestionStatusService = ragIngestionStatusService;
        this.scheduler = scheduler;
    }

    @PreDestroy
    void shutdown() {
        // 应用关闭时立即中断所有 SSE 轮询任务，避免线程泄漏。
        scheduler.shutdownNow();
    }

    /**
     * 开启一条 SSE 流并返回 emitter。
     *
     * <p>调用前 controller 已经做过基础鉴权（X-User-Id 真实存在），
     * 这里再做一次 {@link RagIngestionStatusService#getFileStatus} 拿到首屏状态——
     * 该方法内部会再做 ownerFolder 校验，确保攻击者不能用别人 fileId 起 SSE 接收别人状态。</p>
     *
     * <p>逻辑要点：</p>
     * <ol>
     *     <li>先同步推一帧 initial 状态（让前端拿到第一帧不必等 pollInterval）。</li>
     *     <li>已是终态直接 complete，不开调度。</li>
     *     <li>调度任务里只在状态变化时才 send，节省带宽。</li>
     *     <li>到达 deadline 推一条 timeout 事件后 complete。</li>
     *     <li>onCompletion / onTimeout / onError 三处都会取消 future，回收线程。</li>
     * </ol>
     *
     * @param callerUserId 已经 controller 鉴权过的 user_id
     * @param fileId       要订阅的文件 ID
     * @param timeoutSeconds 客户端期望的最大等待秒数（被 clamp 到 [MIN, MAX]）
     * @param pollIntervalMillis 轮询间隔毫秒（不低于 {@link #MIN_POLL_INTERVAL_MILLIS}）
     */
    public SseEmitter stream(String callerUserId, String fileId, long timeoutSeconds, long pollIntervalMillis) {
        // 首屏状态查询。如果鉴权失败 / 文件不存在，让异常向上抛由 controller 翻成 4xx。
        RagFileStatusResponse initialStatus = ragIngestionStatusService.getFileStatus(callerUserId, fileId);

        // 对客户端传入的 timeout 和轮询间隔做边界保护，防止极端值打爆系统或秒断流。
        long timeoutMillis = clamp(timeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        long safePollInterval = Math.max(MIN_POLL_INTERVAL_MILLIS, pollIntervalMillis);

        // 创建 SSE 发射器，超时时间就是本次连接的最大存活时间。
        SseEmitter emitter = new SseEmitter(timeoutMillis);

        try {
            // 先立即发一帧首屏状态，前端拿到连接后不用额外再等一次轮询。
            emitter.send(SseEmitter.event().name(EVENT_NAME_STATUS).data(initialStatus));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
            return emitter;
        }

        if (initialStatus.isCompleted()) {
            // 如果首屏状态已经是终态，就没必要启动后台轮询任务，直接关流即可。
            emitter.complete();
            return emitter;
        }

        // last 保存上一帧状态，用于“仅在发生变化时才推送”。
        AtomicReference<RagFileStatusResponse> last = new AtomicReference<>(initialStatus);
        // deadline 是这条 SSE 流的绝对结束时间点。
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        // 启动固定延迟轮询任务，每隔 safePollInterval 去查一次最新状态。
        futureRef.set(scheduler.scheduleWithFixedDelay(
                () -> pollOnce(callerUserId, fileId, deadline, last, futureRef, emitter),
                safePollInterval, safePollInterval, TimeUnit.MILLISECONDS));

        // 无论正常结束、超时还是异常，都要记得取消后台 task，避免资源泄漏。
        emitter.onCompletion(() -> cancel(futureRef));
        emitter.onTimeout(() -> cancel(futureRef));
        emitter.onError(ex -> cancel(futureRef));
        return emitter;
    }

    private void pollOnce(String callerUserId,
                          String fileId,
                          long deadline,
                          AtomicReference<RagFileStatusResponse> last,
                          AtomicReference<ScheduledFuture<?>> futureRef,
                          SseEmitter emitter) {
        try {
            // 到达 deadline 后，先发一条 timeout 事件通知前端，再主动关闭流。
            if (System.currentTimeMillis() >= deadline) {
                emitter.send(SseEmitter.event().name(EVENT_NAME_TIMEOUT).data(last.get()));
                emitter.complete();
                cancel(futureRef);
                return;
            }

            // 拉取最新状态；状态服务内部会再次做归属校验。
            RagFileStatusResponse current = ragIngestionStatusService.getFileStatus(callerUserId, fileId);
            if (hasStateChanged(current, last.get())) {
                // 只有三元组状态发生变化时才推送，避免无意义重复帧。
                emitter.send(SseEmitter.event().name(EVENT_NAME_STATUS).data(current));
                last.set(current);
            }
            if (current.isCompleted()) {
                // 到达终态后立刻关流，避免继续占用调度线程。
                emitter.complete();
                cancel(futureRef);
            }
        } catch (RagAccessDeniedException denied) {
            // 中途权限失效 → 直接关流，不向前端透露原因
            emitter.complete();
            cancel(futureRef);
        } catch (NoSuchElementException notFound) {
            emitter.complete();
            cancel(futureRef);
        } catch (Exception ex) {
            log.warn("SSE 推送状态时异常: fileId={}, err={}", fileId, ex.toString());
            emitter.completeWithError(ex);
            cancel(futureRef);
        }
    }

    /** 状态三元组任一发生变化即视为有更新值得推送。 */
    static boolean hasStateChanged(RagFileStatusResponse current, RagFileStatusResponse previous) {
        // 只要 document / ingestion / rag 三个状态字段任意一个变化，就认为值得推送一帧新状态。
        return !Objects.equals(current.getDocumentStatus(), previous.getDocumentStatus())
                || !Objects.equals(current.getRagStatus(), previous.getRagStatus())
                || !Objects.equals(current.getIngestionStatus(), previous.getIngestionStatus());
    }

    static long clamp(long value, long min, long max) {
        // 把任意输入值压缩到 [min, max] 区间内，防止前端传入极端参数。
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 取消某条 SSE 连接对应的后台轮询任务。
     *
     * <p>同一个 emitter 可能因为正常完成、超时、异常、客户端断连等多个回调同时触发取消。
     * 这里用 {@link AtomicReference#getAndSet(Object)} 原子取出并清空 future，
     * 确保重复调用时只有第一次真正 cancel，后续调用安全无副作用。</p>
     *
     * @param ref 保存 ScheduledFuture 的原子引用
     */
    private static void cancel(AtomicReference<ScheduledFuture<?>> ref) {
        // 原子地取出并清空 future，确保多处回调重复 cancel 时也安全。
        ScheduledFuture<?> f = ref.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * 创建 SSE 轮询线程的 ThreadFactory。
     *
     * <p>线程名固定为 {@code rag-sse-poller}，方便通过日志、线程 dump 或监控快速识别；
     * daemon=true 表示这些后台轮询线程不会阻止 JVM 退出。</p>
     *
     * @return 用于 ScheduledExecutorService 的线程工厂
     */
    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            // 线程名固定成 rag-sse-poller，便于在线程 dump 或监控中快速识别来源。
            Thread thread = new Thread(runnable, "rag-sse-poller");
            // 设为 daemon，避免这类后台轮询线程阻止 JVM 正常退出。
            thread.setDaemon(true);
            return thread;
        };
    }
}
