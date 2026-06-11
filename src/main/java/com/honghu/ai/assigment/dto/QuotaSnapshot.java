package com.honghu.ai.assigment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 配额快照。
 *
 * <p>后台展示时同时需要“标准 token”和“金额”两套口径：标准 token 给用户和运营人员看，
 * 金额给成本核算和真实账单看。为了兼容旧接口，{@link #used} 和 {@link #limit} 仍然保留，
 * 语义等同于 {@link #moneyUsed} 和 {@link #moneyLimit}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaSnapshot {

    /** 查询的用户 ID。 */
    private String userId;

    /** 查询的 workspace ID；企业空间代表团队额度池，个人空间代表个人额度池。 */
    private String workspaceId;

    /** 当前周期已消耗金额，单位 CNY。保留字段名 used 是为了兼容旧前端。 */
    private BigDecimal used;

    /** 当前周期金额上限，单位 CNY；为空表示不限。保留字段名 limit 是为了兼容旧前端。 */
    private BigDecimal limit;

    /** 金额已用别名，语义比 used 更清晰。 */
    private BigDecimal moneyUsed;

    /** 金额上限别名，语义比 limit 更清晰。 */
    private BigDecimal moneyLimit;

    /** 实际调用产生的原始 token，用于排查模型返回和审计。 */
    private BigDecimal rawTokenUsed;

    /**
     * 标准 token 已消耗量。
     *
     * <p>后台配额仍然按成本结算，但展示层统一成“标准 token”。高级模型因为单价更高，
     * 同样的原始 token 会折算成更多标准 token，看起来就是“消耗更快”。</p>
     */
    private BigDecimal tokenUsed;

    /** 当前周期标准 token 上限；金额不限时这里也为空。 */
    private BigDecimal tokenLimit;

    /** 当前周期剩余标准 token；不限时为空。 */
    private BigDecimal tokenRemaining;

    /** 当前周期 token 口径使用百分比，0-100；不限时为 0。 */
    private BigDecimal usagePercent;

    /** 周期类型：DAILY 或 MONTHLY。 */
    private String period;

    /** 是否不限额度。 */
    private boolean unlimited;
}
