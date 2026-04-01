package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Component;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;

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

    private final OllamaChatModel ollamaChatModel;
    private final ChatMemoryConfig chatMemoryConfig;

    @Override
    public String generateSummary(String model, String systemPrompt, String userPrompt) {
        // 摘要调用使用独立配置参数，避免把主聊天模型的采样策略误带到压缩任务中。
        OllamaOptions options = OllamaOptions.create()
                .withModel(model)
                .withTemperature(chatMemoryConfig.getSummaryTemperature())
                .withNumPredict(chatMemoryConfig.getSummaryMaxTokens());

        // 这里保持最小 Prompt 结构：一个 system 负责约束摘要要求，一个 user 负责携带待压缩原文。
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)
                ),
                options
        );

        // 只返回摘要正文，具体的存库、缓存和 SystemMessage 包装由上层服务统一处理。
        var response = ollamaChatModel.call(prompt);
        return response.getResult().getOutput().getContent();
    }
}

