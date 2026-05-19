package com.honghu.ut.test.ai.assigment.testdeepseekr1.exception;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.QuotaCheckResult;
import lombok.Getter;

@Getter
public class QuotaExceededException extends RuntimeException {

    /**
     * 配额检查结果。
     *
     * <p>异常处理器会把这里的 used、limit、resetAt 返回给前端，
     * 前端可以据此展示“今日已用多少、上限多少、何时恢复”。</p>
     */
    private final QuotaCheckResult quota;

    /**
     * 创建配额超限异常。
     *
     * @param quota 触发拦截时的配额快照
     */
    public QuotaExceededException(QuotaCheckResult quota) {
        super(quota == null ? "Quota exceeded" : quota.getReason());
        this.quota = quota;
    }
}
