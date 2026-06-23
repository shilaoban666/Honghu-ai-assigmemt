package com.honghu.ai.assigment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.dto.AiCallContext;
import com.honghu.ai.assigment.dto.ChatResponse;
import com.honghu.ai.assigment.dto.CostBreakdown;
import com.honghu.ai.assigment.entity.AiModelDefinition;
import com.honghu.ai.assigment.entity.AiUsageEvent;
import com.honghu.ai.assigment.observability.GatewayMetrics;
import com.honghu.ai.assigment.repository.AiUsageEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * AI 用量流水记录服务。
 *
 * <p>网关层每次真正“试图调用模型”时，都会尽量通过这个服务写一条 {@code ai_usage_event}。
 * 成功请求会写成本和 token，用于配额与对账；失败请求也会写状态和错误信息，用于审计与排障。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageEventService {

    private final AiUsageEventRepository aiUsageEventRepository;
    private final ObjectMapper objectMapper;
    private final GatewayMetrics gatewayMetrics;

    /**
     * 记录一次成功的 AI 调用流水。
     *
     * <p>调用成功时，流水会保存 token 用量、价格快照、供应商成本、平台计费成本。
     * 这些数据之后会作为配额、用量分析、对账的事实来源。</p>
     *
     * <p>这里使用 {@link Propagation#REQUIRES_NEW}，是为了保证即使外层聊天事务失败，
     * 已经发生过的上游调用也尽量能留下审计记录。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(AiCallContext context,
                              AiModelDefinition requestedModel,
                              AiModelDefinition executableModel,
                              ChatResponse.TokenUsage usage,
                              CostBreakdown cost,
                              long latencyMs) {
        saveEvent(context, requestedModel, executableModel, usage, cost, latencyMs, AiUsageEvent.Status.SUCCESS, null);
    }

    /**
     * 记录一次失败或被拦截的 AI 调用流水。
     *
     * <p>失败也要落库，原因有两个：</p>
     * <ul>
     *     <li>运营上可以看到是配额拦截、缺价格、还是上游失败。</li>
     *     <li>审计上可以还原一次请求为什么没有产生正常回复。</li>
     * </ul>
     *
     * <p>失败流水的费用统一为 0，不会消耗用户额度。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AiCallContext context,
                              AiModelDefinition requestedModel,
                              AiModelDefinition executableModel,
                              long latencyMs,
                              AiUsageEvent.Status status,
                              String errorMessage) {
        saveEvent(context, requestedModel, executableModel, null, null, latencyMs, status, errorMessage);
    }

    /**
     * 保存调用流水的内部统一方法。
     *
     * <p>{@code requestId + attemptNo} 是幂等键。流式调用和异常处理链路里，
     * 同一次请求可能经过多个回调，重复进入这里时会直接跳过，避免把一次调用记成多次费用。</p>
     *
     * <p>{@code requestedModel} 和 {@code executableModel} 分开保存：
     * requestedModel 表示用户或路由最初选择的模型；
     * executableModel 表示最终真正执行的模型，例如本地 Ollama 不可用时可能回退到云端模型。</p>
     */
    private void saveEvent(AiCallContext context,
                           AiModelDefinition requestedModel,
                           AiModelDefinition executableModel,
                           ChatResponse.TokenUsage usage,
                           CostBreakdown cost,
                           long latencyMs,
                           AiUsageEvent.Status status,
                           String errorMessage) {
        AiCallContext safeContext = context == null ? AiCallContext.anonymous("unknown") : context;
        safeContext.ensureRequestId();
        if (aiUsageEventRepository.existsByRequestIdAndAttemptNo(safeContext.getRequestId(), safeContext.getAttemptNo())) {
            log.warn("跳过重复 usage event: requestId={}, attemptNo={}", safeContext.getRequestId(), safeContext.getAttemptNo());
            return;
        }
        AiModelDefinition effectiveModel = executableModel != null ? executableModel : requestedModel;
        ChatResponse.TokenUsage safeUsage = usage == null ? new ChatResponse.TokenUsage(0, 0, 0, 0) : usage;
        CostBreakdown safeCost = cost == null
                ? CostBreakdown.builder().vendorCost(BigDecimal.ZERO).billedCost(BigDecimal.ZERO).currency("CNY").build()
                : cost;

        AiUsageEvent event = AiUsageEvent.builder()
                .requestId(safeContext.getRequestId())
                .attemptNo(safeContext.getAttemptNo())
                .userId(blankToNull(safeContext.getUserId()))
                .workspaceId(blankToNull(safeContext.getWorkspaceId()))
                .sessionId(blankToNull(safeContext.getSessionId()))
                .chatId(safeContext.getChatId())
                .modelCode(requestedModel != null ? requestedModel.getModelCode() : effectiveModel.getModelCode())
                .effectiveModelCode(effectiveModel != null ? effectiveModel.getModelCode() : null)
                .providerCode(effectiveModel != null ? effectiveModel.getProviderCode() : null)
                .promptTokens(nullToZero(safeUsage.getPromptTokens()))
                .completionTokens(nullToZero(safeUsage.getCompletionTokens()))
                .cachedPromptTokens(nullToZero(safeUsage.getCachedPromptTokens()))
                .totalTokens(nullToZero(safeUsage.getTotalTokens()))
                .pricingId(safeCost.getPricingId())
                .costVendor(nullToZero(safeCost.getVendorCost()))
                .costBilled(nullToZero(safeCost.getBilledCost()))
                .currency(StringUtils.hasText(safeCost.getCurrency()) ? safeCost.getCurrency() : "CNY")
                .latencyMs(latencyMs)
                .status(status)
                .errorMessage(truncate(errorMessage, 1000))
                .rawUsageJson(toJsonQuietly(usage))
                .build();
        aiUsageEventRepository.save(event);

        // 落库成功后同步导出 Prometheus 业务指标（路由比例 / token / 成本 / 时延 / 状态）。
        // 放在 dedup 校验之后，确保和流水一样“每次有效调用只计一次”。
        gatewayMetrics.recordUsage(
                event.getProviderCode(),
                event.getModelCode(),
                event.getEffectiveModelCode(),
                status.name(),
                event.getPromptTokens(),
                event.getCompletionTokens(),
                event.getCostBilled(),
                latencyMs);
    }

    /** 把空 Integer 归一化成 0，避免数据库非空字段写入失败。 */
    private Integer nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 把空金额归一化成 0，保证失败事件也能安全落库。 */
    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 把空串/纯空白统一转成 null，避免数据库里出现大量没有业务意义的空字符串。 */
    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    /** 截断过长错误信息，保证不会超过数据库字段长度。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 尝试把对象序列化成 JSON。
     *
     * <p>这里采用“安静失败”策略：序列化失败不影响主调用链，最多就是 rawUsageJson 为空。</p>
     */
    private String toJsonQuietly(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}
