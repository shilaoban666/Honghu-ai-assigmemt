package com.honghu.ut.test.ai.assigment.testdeepseekr1.exception;

public class PricingNotConfiguredException extends RuntimeException {

    /**
     * 模型缺少可用价格配置时抛出。
     *
     * <p>没有价格就不能准确扣额度，所以网关会把这类调用记为 BLOCKED_BY_PRICING，
     * 并通过全局异常处理返回 503，提示管理员去后台补价格。</p>
     *
     * @param modelCode 缺少价格的模型编码
     */
    public PricingNotConfiguredException(String modelCode) {
        super("模型未配置计价: " + modelCode);
    }
}
