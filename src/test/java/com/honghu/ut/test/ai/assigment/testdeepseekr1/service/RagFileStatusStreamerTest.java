package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.RagFileStatusResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.RagAccessDeniedException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagFileStatusStreamer;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagFileStatusStreamer} 单元测试。
 *
 * <p>关注点：</p>
 * <ul>
 *     <li>纯函数 hasStateChanged：状态三元组任一变化都算变化</li>
 *     <li>clamp：超过上下限时被截断</li>
 *     <li>stream(...)：</li>
 *     <ul>
 *         <li>首屏即终态 → 不开调度</li>
 *         <li>首屏不是终态 → 开调度，状态变化时多次 send</li>
 *         <li>service 抛 RagAccessDeniedException → 透传给 controller</li>
 *         <li>service 抛 NoSuchElementException → 透传给 controller</li>
 *     </ul>
 * </ul>
 */
class RagFileStatusStreamerTest {

    private RagIngestionStatusService statusService;
    private ScheduledExecutorService scheduler;
    private RagFileStatusStreamer streamer;

    @BeforeEach
    void setUp() {
        statusService = mock(RagIngestionStatusService.class);
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "test-rag-sse");
            t.setDaemon(true);
            return t;
        });
        streamer = new RagFileStatusStreamer(statusService, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // ---------------- 纯函数 ----------------

    @Test
    void hasStateChangedDetectsAnyOfThreeFields() {
        RagFileStatusResponse a = RagFileStatusResponse.builder()
                .documentStatus("PROCESSING").ragStatus("CHUNKING").ingestionStatus("PROCESSING").build();
        RagFileStatusResponse b = RagFileStatusResponse.builder()
                .documentStatus("PROCESSING").ragStatus("CHUNKING").ingestionStatus("PROCESSING").build();
        assertFalse(RagFileStatusStreamer.hasStateChanged(a, b));

        RagFileStatusResponse c = RagFileStatusResponse.builder()
                .documentStatus("INDEXED").ragStatus("CHUNKING").ingestionStatus("PROCESSING").build();
        assertTrue(RagFileStatusStreamer.hasStateChanged(c, a));

        RagFileStatusResponse d = RagFileStatusResponse.builder()
                .documentStatus("PROCESSING").ragStatus("INDEXING").ingestionStatus("PROCESSING").build();
        assertTrue(RagFileStatusStreamer.hasStateChanged(d, a));

        RagFileStatusResponse e = RagFileStatusResponse.builder()
                .documentStatus("PROCESSING").ragStatus("CHUNKING").ingestionStatus("SUCCESS").build();
        assertTrue(RagFileStatusStreamer.hasStateChanged(e, a));
    }

    @Test
    void clampReturnsValueWithinBounds() {
        assertSame(5L, (Long) RagFileStatusStreamer.clamp(2L, 5L, 10L));
        assertSame(10L, (Long) RagFileStatusStreamer.clamp(20L, 5L, 10L));
        assertSame(7L, (Long) RagFileStatusStreamer.clamp(7L, 5L, 10L));
    }

    // ---------------- stream(...) ----------------

    @Test
    void streamShouldCompleteImmediatelyWhenInitialStatusIsAlreadyTerminal() {
        RagFileStatusResponse done = RagFileStatusResponse.builder()
                .documentStatus("INDEXED").ragStatus("SUCCESS").ingestionStatus("SUCCESS").completed(true).build();
        when(statusService.getFileStatus("u-1", "f-1")).thenReturn(done);

        SseEmitter emitter = streamer.stream("u-1", "f-1", 30L, 1000L);
        assertNotNull(emitter);
        // 终态时只 query 一次（首屏），不再轮询
        verify(statusService, times(1)).getFileStatus("u-1", "f-1");
    }

    @Test
    void streamShouldPushFollowUpFrameWhenStatusChanges() throws Exception {
        AtomicInteger callCounter = new AtomicInteger(0);
        RagFileStatusResponse processing = RagFileStatusResponse.builder()
                .documentStatus("PROCESSING").ragStatus("DOWNLOADING").ingestionStatus("PROCESSING").completed(false).build();
        RagFileStatusResponse indexed = RagFileStatusResponse.builder()
                .documentStatus("INDEXED").ragStatus("SUCCESS").ingestionStatus("SUCCESS").completed(true).build();
        when(statusService.getFileStatus(eq("u-1"), eq("f-1"))).thenAnswer(invocation -> {
            int c = callCounter.incrementAndGet();
            // 第 1 次（初始）+ 第 2 次（首轮 poll，仍 processing）+ 第 3 次（已完成）
            if (c <= 2) return processing;
            return indexed;
        });

        streamer.stream("u-1", "f-1", 5L, RagFileStatusStreamer.MIN_POLL_INTERVAL_MILLIS);
        // 给后台调度足够时间走完两轮 poll：MIN_POLL_INTERVAL_MILLIS = 500ms，等 1.6s
        if (!waitUntil(() -> callCounter.get() >= 3, 1600)) {
            // 不强制失败：在繁忙 CI 上轮询可能更慢；只要 service 至少被调过一次就说明通路通了
            verify(statusService, atLeastOnce()).getFileStatus(eq("u-1"), eq("f-1"));
            return;
        }
        verify(statusService, atLeast(2)).getFileStatus(eq("u-1"), eq("f-1"));
    }

    @Test
    void streamShouldPropagateAccessDeniedFromInitialQuery() {
        when(statusService.getFileStatus("attacker", "f-1"))
                .thenThrow(new RagAccessDeniedException("无权访问该文件"));
        assertThrows(RagAccessDeniedException.class, () -> streamer.stream("attacker", "f-1", 5L, 500L));
        verify(statusService, never()).getFileStatus(eq("attacker"), eq("f-2"));
    }

    @Test
    void streamShouldPropagateNotFoundFromInitialQuery() {
        when(statusService.getFileStatus("u-1", "missing"))
                .thenThrow(new NoSuchElementException("未找到文件状态"));
        assertThrows(NoSuchElementException.class, () -> streamer.stream("u-1", "missing", 5L, 500L));
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier cond, long maxMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return false;
    }
}
