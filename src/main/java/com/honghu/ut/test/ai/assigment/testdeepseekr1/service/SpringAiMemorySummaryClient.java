package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Spring AI + Ollama 的摘要模型客户端。
 *
 * <p>它只负责“把摘要 Prompt 发给指定小模型并返回文本结果”，
 * 不关心 session/user 画像如何存储，因此与上层的记忆编排逻辑解耦。</p>
 */
@Component
@RequiredArgsConstructor
public class SpringAiMemorySummaryClient implements MemorySummaryClient {

  private final AiModelAccessService aiModelAccessService;
  private final AiChatModelGatewayService aiChatModelGatewayService;
    private final ChatMemoryConfig chatMemoryConfig;

    @Override
    public String generateSummary(String model, String systemPrompt, String userPrompt) {
        AiModelDefinition summaryModel = aiModelAccessService.requireEnabledModel(model);

        // 摘要调用同样走统一模型网关，这样就能自动复用：
        // 1. 外部 provider 适配
        // 2. 本地 Ollama 不可用时的云端回退
        // 3. 一致的温度 / maxTokens 参数控制
        return aiChatModelGatewayService.chat(
            summaryModel,
            List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
            ),
            chatMemoryConfig.getSummaryTemperature(),
            chatMemoryConfig.getSummaryMaxTokens()
        ).getContent();
    }
}

