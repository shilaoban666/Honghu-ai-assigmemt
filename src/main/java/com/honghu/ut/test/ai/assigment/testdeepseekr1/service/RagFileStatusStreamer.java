package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.RagFileStatusResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
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

    /** 测试可注入自定义 scheduler；生产环境用默认有界池。 */
    @Autowired
    public RagFileStatusStreamer(RagIngestionStatusService ragIngestionStatusService) {
        this(ragIngestionStatusService, Executors.newScheduledThreadPool(8, daemonThreadFactory()));
    }

    RagFileStatusStreamer(RagIngestionStatusService ragIngestionStatusService,
                          ScheduledExecutorService scheduler) {
        this.ragIngestionStatusService = ragIngestionStatusService;
        this.scheduler = scheduler;
    }

    @PreDestroy
    void shutdown() {
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

        long timeoutMillis = clamp(timeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        long safePollInterval = Math.max(MIN_POLL_INTERVAL_MILLIS, pollIntervalMillis);
        SseEmitter emitter = new SseEmitter(timeoutMillis);

        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME_STATUS).data(initialStatus));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
            return emitter;
        }

        if (initialStatus.isCompleted()) {
            emitter.complete();
            return emitter;
        }

        AtomicReference<RagFileStatusResponse> last = new AtomicReference<>(initialStatus);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        futureRef.set(scheduler.scheduleWithFixedDelay(
                () -> pollOnce(callerUserId, fileId, deadline, last, futureRef, emitter),
                safePollInterval, safePollInterval, TimeUnit.MILLISECONDS));

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
            if (System.currentTimeMillis() >= deadline) {
                emitter.send(SseEmitter.event().name(EVENT_NAME_TIMEOUT).data(last.get()));
                emitter.complete();
                cancel(futureRef);
                return;
            }
            RagFileStatusResponse current = ragIngestionStatusService.getFileStatus(callerUserId, fileId);
            if (hasStateChanged(current, last.get())) {
                emitter.send(SseEmitter.event().name(EVENT_NAME_STATUS).data(current));
                last.set(current);
            }
            if (current.isCompleted()) {
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
        return !Objects.equals(current.getDocumentStatus(), previous.getDocumentStatus())
                || !Objects.equals(current.getRagStatus(), previous.getRagStatus())
                || !Objects.equals(current.getIngestionStatus(), previous.getIngestionStatus());
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void cancel(AtomicReference<ScheduledFuture<?>> ref) {
        ScheduledFuture<?> f = ref.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "rag-sse-poller");
            thread.setDaemon(true);
            return thread;
        };
    }
}
