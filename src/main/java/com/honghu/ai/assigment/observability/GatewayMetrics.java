package com.honghu.ai.assigment.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * 网关业务语义指标（P0-2）。
 *
 * <p>把“一次模型调用”的业务事实导出成 Prometheus 指标，让 Grafana 能直接回答：
 * 本地 vs 云端路由比例、是否大量回退、各模型 token 与成本、调用时延、各结果状态占比、
 * 以及流式首 token 时延（TTFT）。这些是传统 {@code http.server.requests} 看不到的 LLM 专属视角。</p>
 *
 * <p>所有标签都是低基数（provider/model/status/route/tier 都是有限枚举），不会打爆 Prometheus 时序。</p>
 */
@Component
@RequiredArgsConstructor
public class GatewayMetrics {

    /** 本地模型 provider 前缀，用于区分 local / cloud 路由比例。 */
    private static final String LOCAL_PROVIDER_PREFIX = "ollama";

    private final MeterRegistry registry;

    /**
     * 记录一次调用流水对应的业务指标（在 UsageEventService 落库点统一触发）。
     *
     * @param providerCode    实际执行 provider 编码
     * @param requestedModel  最初请求/路由选中的模型编码
     * @param effectiveModel  实际执行的模型编码（可能因本地不可用回退到云端）
     * @param status          结果状态（SUCCESS / FAILED / BLOCKED_BY_QUOTA / BLOCKED_BY_PRICING）
     * @param promptTokens    prompt token 数
     * @param completionTokens completion token 数
     * @param billedCost      平台计费成本
     * @param latencyMs       调用耗时（毫秒）
     */
    public void recordUsage(String providerCode,
                            String requestedModel,
                            String effectiveModel,
                            String status,
                            int promptTokens,
                            int completionTokens,
                            BigDecimal billedCost,
                            long latencyMs) {
        String provider = nz(providerCode);
        String model = nz(effectiveModel);
        boolean fallback = StringUtils.hasText(requestedModel)
                && StringUtils.hasText(effectiveModel)
                && !requestedModel.equals(effectiveModel);
        String route = fallback ? "fallback" : "direct";
        String tier = provider.startsWith(LOCAL_PROVIDER_PREFIX) ? "local" : "cloud";

        // 注意：计数器名不带 _total 后缀——Micrometer 的 Prometheus 注册表会自动补 _total，
        // 否则会得到 llm_gateway_requests_total_total 这种重复后缀。
        Tags requestTags = Tags.of("provider", provider, "model", model, "status", nz(status), "route", route, "tier", tier);
        registry.counter("llm_gateway_requests", requestTags).increment();

        if (promptTokens > 0) {
            registry.counter("llm_gateway_tokens", "type", "prompt", "model", model).increment(promptTokens);
        }
        if (completionTokens > 0) {
            registry.counter("llm_gateway_tokens", "type", "completion", "model", model).increment(completionTokens);
        }
        if (billedCost != null && billedCost.signum() > 0) {
            registry.counter("llm_gateway_cost_billed", "provider", provider, "model", model)
                    .increment(billedCost.doubleValue());
        }

        Timer.builder("llm_gateway_call_duration")
                .tags("provider", provider, "status", nz(status), "route", route, "tier", tier)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);
    }

    /**
     * 记录流式调用的首 token 时延（TTFT）。
     *
     * @param providerCode 实际执行 provider
     * @param model        实际执行模型
     * @param ttftMs       从发起到收到首个内容分片的毫秒数
     */
    public void recordFirstTokenLatency(String providerCode, String model, long ttftMs) {
        Timer.builder("llm_gateway_first_token_duration")
                .tags("provider", nz(providerCode), "model", nz(model))
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, ttftMs), TimeUnit.MILLISECONDS);
    }

    private String nz(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }
}
