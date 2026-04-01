package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.ChatPromptProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统提示词与摘要模板提供器。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>在应用启动时从 resources 中统一加载聊天 / 摘要 / 画像相关提示词模板</li>
 *     <li>按任务类型缓存默认系统提示词，运行时直接按 taskType 获取</li>
 *     <li>避免业务代码硬编码大段提示词，降低后续调优与切换分支的维护成本</li>
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

    /** 按任务类型缓存的默认系统提示词。 */
    private final Map<String, String> promptsByTaskType;

    /** 会话摘要 system prompt 模板。 */
    @Getter
    private final String sessionSummarySystemPromptTemplate;

    /** 用户画像 system prompt 模板。 */
    @Getter
    private final String userProfileSystemPromptTemplate;

    /** 会话摘要包装模板。 */
    @Getter
    private final String sessionSummaryWrapperTemplate;

    /** 用户主体画像包装模板。 */
    @Getter
    private final String userProfileWrapperTemplate;

    /** session 增量更新 prompt 模板。 */
    @Getter
    private final String sessionUpdateUserPromptTemplate;

    /** 已有 session 摘要片段模板。 */
    @Getter
    private final String sessionExistingSummaryBlockTemplate;

    /** 多分片 session 摘要合并 prompt 模板。 */
    @Getter
    private final String sessionMergeUserPromptTemplate;

    /** 单个 session 分片摘要条目模板。 */
    @Getter
    private final String sessionChunkItemPromptTemplate;

    /** 多 session 聚合为用户画像的 prompt 模板。 */
    @Getter
    private final String userProfileAggregationUserPromptTemplate;

    /** 用户画像聚合时单个 session 条目模板。 */
    @Getter
    private final String userProfileSessionItemPromptTemplate;

    public DefaultSystemPromptProvider(ChatPromptProperties chatPromptProperties, ResourceLoader resourceLoader) {
        this.prompt = loadRequiredPrompt(chatPromptProperties.getDefaultSystemPromptLocation(), resourceLoader, "全局默认系统提示词");
        this.promptsByTaskType = loadTaskTypePrompts(chatPromptProperties, resourceLoader);
        this.sessionSummarySystemPromptTemplate = loadRequiredPrompt(chatPromptProperties.getSessionSummarySystemPromptLocation(), resourceLoader, "会话摘要 system prompt");
        this.userProfileSystemPromptTemplate = loadRequiredPrompt(chatPromptProperties.getUserProfileSystemPromptLocation(), resourceLoader, "用户画像 system prompt");
        this.sessionSummaryWrapperTemplate = loadRequiredPrompt(chatPromptProperties.getSessionSummaryWrapperPromptLocation(), resourceLoader, "会话摘要包装模板");
        this.userProfileWrapperTemplate = loadRequiredPrompt(chatPromptProperties.getUserProfileWrapperPromptLocation(), resourceLoader, "用户画像包装模板");
        this.sessionUpdateUserPromptTemplate = loadRequiredPrompt(chatPromptProperties.getSessionUpdateUserPromptLocation(), resourceLoader, "session 增量摘要 prompt");
        this.sessionExistingSummaryBlockTemplate = loadRequiredPrompt(chatPromptProperties.getSessionExistingSummaryBlockPromptLocation(), resourceLoader, "已有摘要片段模板");
        this.sessionMergeUserPromptTemplate = loadRequiredPrompt(chatPromptProperties.getSessionMergeUserPromptLocation(), resourceLoader, "session 摘要合并 prompt");
        this.sessionChunkItemPromptTemplate = loadRequiredPrompt(chatPromptProperties.getSessionChunkItemPromptLocation(), resourceLoader, "session 分片条目模板");
        this.userProfileAggregationUserPromptTemplate = loadRequiredPrompt(chatPromptProperties.getUserProfileAggregationUserPromptLocation(), resourceLoader, "用户画像聚合 prompt");
        this.userProfileSessionItemPromptTemplate = loadRequiredPrompt(chatPromptProperties.getUserProfileSessionItemPromptLocation(), resourceLoader, "用户画像 session 条目模板");

        log.info("提示词模板加载完成：defaultLength={}, taskTypes={}, summaryTemplates=10",
                prompt.length(), promptsByTaskType.keySet());
    }

    public String getPromptByTaskType(String taskType) {
        if (!StringUtils.hasText(taskType)) {
            return null;
        }
        return promptsByTaskType.get(taskType.trim().toUpperCase());
    }

    private Map<String, String> loadTaskTypePrompts(ChatPromptProperties properties, ResourceLoader resourceLoader) {
        if (CollectionUtils.isEmpty(properties.getTaskTypePromptLocations())) {
            return Map.of();
        }

        Map<String, String> loadedPrompts = new LinkedHashMap<>();
        properties.getTaskTypePromptLocations().forEach((taskType, location) -> {
            if (StringUtils.hasText(taskType) && StringUtils.hasText(location)) {
                loadedPrompts.put(taskType.trim().toUpperCase(), loadRequiredPrompt(location, resourceLoader, "任务类型提示词(" + taskType + ")"));
            }
        });
        return Map.copyOf(loadedPrompts);
    }

    private String loadRequiredPrompt(String location, ResourceLoader resourceLoader, String promptName) {
        if (!StringUtils.hasText(location)) {
            throw new IllegalStateException(promptName + "资源路径未配置");
        }

        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(promptName + "资源不存在: " + location);
        }

        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            String content = FileCopyUtils.copyToString(reader);
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException(promptName + "内容为空: " + location);
            }
            return content.trim();
        } catch (IOException e) {
            throw new IllegalStateException("读取" + promptName + "失败: " + location, e);
        }
    }
}

