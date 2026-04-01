package com.honghu.ut.test.ai.assigment.testdeepseekr1.config;

import lombok.Data;

/**
 * 旧版聊天提示词配置兼容类。
 *
 * <p>当前项目已统一改为使用 {@link com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.ChatPromptProperties}
 * 管理所有提示词模板路径。保留本类仅用于兼容历史代码引用，避免切分支时出现大面积编译冲突。</p>
 */
@Deprecated
@Data
public class ChatPromptConfig {

    /**
     * 默认系统提示词资源位置。
     *
     * <p>默认从 classpath 下的 prompts 目录加载，
     * 应用启动时只读取一次，后续直接复用内存中的缓存结果。</p>
     */
    private String defaultSystemPromptLocation = "classpath:prompts/default-system-prompt.txt";
}

