package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 默认系统提示词提供器。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>在应用启动时从 resources 中加载默认 system prompt</li>
 *     <li>对加载结果进行非空校验，避免运行期使用无效提示词</li>
 *     <li>把最终提示词缓存到单例 Bean 中，避免每次请求重复读取文件</li>
 * </ul>
 */
@Slf4j
@Component
public class DefaultSystemPromptProvider {

    /**
     * 已缓存的默认系统提示词。
     *
     * <p>该字段只在 Bean 初始化时赋值一次，后续所有请求直接复用，
     * 因此读取成本是常量级，没有额外 I/O 开销。</p>
     */
    @Getter
    private final String prompt;

    public DefaultSystemPromptProvider(ChatPromptConfig chatPromptConfig, ResourceLoader resourceLoader) {
        this.prompt = loadPrompt(chatPromptConfig.getDefaultSystemPromptLocation(), resourceLoader);
        log.info("默认系统提示词加载完成：location={}, length={}",
                chatPromptConfig.getDefaultSystemPromptLocation(),
                prompt.length());
    }

    private String loadPrompt(String location, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("默认系统提示词资源不存在: " + location);
        }

        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            String content = FileCopyUtils.copyToString(reader);
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("默认系统提示词内容为空: " + location);
            }
            return content.trim();
        } catch (IOException e) {
            throw new IllegalStateException("读取默认系统提示词失败: " + location, e);
        }
    }
}

