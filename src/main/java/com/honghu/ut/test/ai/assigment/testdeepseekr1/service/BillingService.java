package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.CostBreakdown;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelPricing;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.PricingNotConfiguredException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.AiModelPricingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * AI 调用计费服务。
 *
 * <p>它的职责非常聚焦：只负责“给定模型、token 用量和时间，算出多少钱”。
 * 它不负责：</p>
 * <ul>
 *     <li>不负责判断用户是否还有额度；那是 {@link QuotaService} 的职责。</li>
 *     <li>不负责落使用流水；那是 {@link UsageEventService} 的职责。</li>
 *     <li>不负责决定用哪个模型；那是网关和模型访问层的职责。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    /**
     * 价格表中的单位是“每 100 万 token 多少钱”，所以这里统一用 1_000_000 做换算。
     *
     * <p>内部计算保留 8 位小数，原因是单次 AI 调用费用可能很小，如果过早四舍五入，
     * 在日/月聚合时会累计出明显误差。展示给前端时可以再按页面需要格式化。</p>
     */
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private final AiModelPricingRepository aiModelPricingRepository;

    /**
     * 根据模型、token 用量和调用时间计算一次 AI 调用的成本。
     *
     * <p>这个服务只负责“算账”，不写数据库。这样做的好处是：</p>
     * <ul>
     *     <li>网关、后台重算、测试都可以复用同一套计价逻辑。</li>
     *     <li>调用流水落库由 {@link UsageEventService} 负责，职责更清楚。</li>
     *     <li>价格缺失时可以明确抛出 {@link PricingNotConfiguredException}，由上层决定如何返回给用户。</li>
     * </ul>
     *
     * <p>计费规则：</p>
     * <ol>
     *     <li>先按调用时间找到当时生效的价格快照。</li>
     *     <li>普通 prompt token 按 {@code promptPricePerMillion} 计费。</li>
     *     <li>cached prompt token 优先按 {@code cachedInputPricePerMillion} 计费；如果没配置缓存价，则按普通 prompt 价计费，避免少计费。</li>
     *     <li>completion token 按 {@code completionPricePerMillion} 计费。</li>
     *     <li>最后叠加单次请求附加费，并乘以平台加价倍率 {@code markupRatio}。</li>
     * </ol>
     *
     * @param modelCode 模型编码，对应 {@code ai_model_definition.model_code}
     * @param usage token 用量；为空时按 0 token 处理，但仍需要模型有价格配置
     * @param at 调用发生时间；为空时使用当前时间
     * @return 供应商成本、平台计费成本、价格快照 ID 和币种
     */
    public CostBreakdown calculate(String modelCode, ChatResponse.TokenUsage usage, Instant at) {
        LocalDateTime localAt = LocalDateTime.ofInstant(at == null ? Instant.now() : at, ZoneId.systemDefault());
        AiModelPricing pricing = aiModelPricingRepository.findActivePricing(modelCode, localAt)
                .orElseThrow(() -> new PricingNotConfiguredException(modelCode));
        int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        int cachedPromptTokens = usage == null || usage.getCachedPromptTokens() == null ? 0 : usage.getCachedPromptTokens();
        int regularPromptTokens = Math.max(0, promptTokens - cachedPromptTokens);

        BigDecimal promptCost = pricing.getPromptPricePerMillion()
                .multiply(BigDecimal.valueOf(regularPromptTokens))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal cachedPromptUnitPrice = pricing.getCachedInputPricePerMillion() == null
                ? pricing.getPromptPricePerMillion()
                : pricing.getCachedInputPricePerMillion();
        BigDecimal cachedPromptCost = cachedPromptUnitPrice
                .multiply(BigDecimal.valueOf(cachedPromptTokens))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal completionCost = pricing.getCompletionPricePerMillion()
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal vendorCost = promptCost.add(cachedPromptCost).add(completionCost).add(nullToZero(pricing.getRequestSurcharge()));
        BigDecimal billedCost = vendorCost.multiply(nullToOne(pricing.getMarkupRatio())).setScale(8, RoundingMode.HALF_UP);

        return CostBreakdown.builder()
                .vendorCost(vendorCost.setScale(8, RoundingMode.HALF_UP))
                .billedCost(billedCost)
                .pricingId(pricing.getId())
                .currency(pricing.getCurrency())
                .build();
    }

    /**
     * 空值按 0 处理。
     *
     * <p>价格表里的 requestSurcharge 是可空字段；当供应商没有“按次固定收费”时，
     * 这里返回 0，保证后面的加法链路不用到处判空。</p>
     */
    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 空值按 1 处理。
     *
     * <p>markupRatio 为空时视为“不加价”，所以默认值应是 1 而不是 0。</p>
     */
    private BigDecimal nullToOne(BigDecimal value) {
        return value == null ? BigDecimal.ONE : value;
    }
}
