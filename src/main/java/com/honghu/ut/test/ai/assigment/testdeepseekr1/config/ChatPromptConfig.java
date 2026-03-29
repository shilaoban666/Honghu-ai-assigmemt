package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Chat 默认系统提示词配置。
 *
 * <p>用于声明默认 system prompt 所在的资源位置，
 * 让提示词内容可以独立存放在 resources 目录中，避免硬编码到业务代码。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.chat.prompt")
public class ChatPromptConfig {

    /**
     * 默认系统提示词资源位置。
     *
     * <p>默认从 classpath 下的 prompts 目录加载，
     * 应用启动时只读取一次，后续直接复用内存中的缓存结果。</p>
     */
    private String defaultSystemPromptLocation = "classpath:prompts/default-system-prompt.txt";
}

