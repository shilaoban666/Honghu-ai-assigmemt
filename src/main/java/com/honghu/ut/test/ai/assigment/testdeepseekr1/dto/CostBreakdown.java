package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单次 AI 调用的成本拆分结果。
 *
 * <p>之所以拆成 vendorCost 和 billedCost 两个字段，是因为平台通常同时关心两套数字：</p>
 * <ul>
 *     <li>供应商原始成本：用于对外部模型厂商做成本核算和毛利分析。</li>
 *     <li>平台计费成本：用于扣减用户/团队额度，代表业务真正“消耗了多少预算”。</li>
 * </ul>
 * <p>这个对象只描述“算出来的结果”，不负责持久化；真正落库由 usage event 流水来完成。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostBreakdown {

    /**
     * 按供应商原始价格算出来的成本。
     *
     * <p>这个值适合做供应商对账、毛利分析。它不包含平台加价倍率。</p>
     */
    private BigDecimal vendorCost;

    /**
     * 平台最终向用户额度池扣减的成本。
     *
     * <p>它通常等于 vendorCost * markupRatio。配额系统使用这个字段做日/月额度消耗。</p>
     */
    private BigDecimal billedCost;

    /** 本次计算命中的 ai_model_pricing.id，用于把历史调用绑定到当时的价格快照。 */
    private Long pricingId;

    /** 币种，目前默认 CNY。 */
    private String currency;
}
