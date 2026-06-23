package com.honghu.ai.assigment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 全链路 traceId 过滤器（P0-3）。
 *
 * <p>在请求最外层生成（或透传上游传入的）{@code traceId} 与 {@code requestId}，写入 SLF4J MDC，
 * 这样一次请求从 Controller → Gateway → Provider → Billing 的所有日志行都带同一个 traceId，
 * 出问题可以按 traceId 串起整条链路。同时把 traceId 回写到响应头 {@code X-Trace-Id}，
 * 便于前端/调用方在报障时直接附带它。</p>
 *
 * <p>用 {@link Ordered#HIGHEST_PRECEDENCE} 保证它早于 Spring Security 过滤链执行，
 * 让鉴权失败的日志也带 traceId；请求结束在 finally 里清理 MDC，避免线程复用串号。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = firstNonBlank(request.getHeader(TRACE_ID_HEADER), newId());
        String requestId = firstNonBlank(request.getHeader(REQUEST_ID_HEADER), traceId);
        MDC.put(TRACE_ID, traceId);
        MDC.put(REQUEST_ID, requestId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    private String firstNonBlank(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate.trim() : fallback;
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
