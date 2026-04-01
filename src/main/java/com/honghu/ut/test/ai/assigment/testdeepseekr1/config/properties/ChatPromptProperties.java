package com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天提示词资源配置。
 *
 * <p>这里不直接在 Java 代码里硬编码大段提示词，而是统一从 resources 下的文本文件加载。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.chat.prompt")
public class ChatPromptProperties {

    /** 全局默认系统提示词路径。 */
    private String defaultSystemPromptLocation;

    /** 按任务类型区分的默认系统提示词路径。 */
    private Map<String, String> taskTypePromptLocations = new LinkedHashMap<>();

    /** session 摘要系统提示词模板路径。 */
    private String sessionSummarySystemPromptLocation;

    /** user 画像系统提示词模板路径。 */
    private String userProfileSystemPromptLocation;

    /** session 摘要包装模板路径。 */
    private String sessionSummaryWrapperPromptLocation;

    /** user 画像包装模板路径。 */
    private String userProfileWrapperPromptLocation;

    /** session 增量更新 user prompt 模板路径。 */
    private String sessionUpdateUserPromptLocation;

    /** 已有 session 摘要片段模板路径。 */
    private String sessionExistingSummaryBlockPromptLocation;

    /** 多分片 session 摘要合并 user prompt 模板路径。 */
    private String sessionMergeUserPromptLocation;

    /** session 分片摘要条目模板路径。 */
    private String sessionChunkItemPromptLocation;

    /** 多 session 聚合为 user 画像的 user prompt 模板路径。 */
    private String userProfileAggregationUserPromptLocation;

    /** 用户画像聚合时，单个 session 摘要条目模板路径。 */
    private String userProfileSessionItemPromptLocation;
}

