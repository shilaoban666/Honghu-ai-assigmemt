package com.honghu.ut.test.ai.assigment.testdeepseekr1.memory;

/**
 * 中期记忆摘要生成客户端。
 *
 * <p>抽象出模型调用细节，便于单元测试时直接 mock，
 * 也便于后续替换为更便宜或更专用的摘要模型。</p>
 */
public interface MemorySummaryClient {

    /**
     * 生成摘要文本。
     *
     * @param model 摘要所用模型
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @return 模型生成的摘要正文
     */
    String generateSummary(String model, String systemPrompt, String userPrompt);
}

