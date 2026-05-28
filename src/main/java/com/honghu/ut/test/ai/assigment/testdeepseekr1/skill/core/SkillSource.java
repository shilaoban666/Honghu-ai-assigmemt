package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

/**
 * 技能来源枚举。
 *
 * <p>{@code Skill} 是面向用户展示和开关的“能力包”，{@code Tool} 是模型真正调用的方法。
 * 不同来源的技能最终都会被 {@code SkillResolverService} 归一成 Spring AI 的工具回调，
 * 但安装方式、同步方式、配置来源和安全策略不同，因此需要用 {@code source} 字段明确区分。</p>
 */
public enum SkillSource {
    /**
     * 内置技能。
     *
     * <p>由后端代码中的 {@code @NativeSkill} / {@code @NativeTool} Bean 提供，
     * 应用启动时 {@code BuiltinSkillRegistrar} 会扫描并同步到 {@code skill}、
     * {@code skill_tool} 表。</p>
     */
    BUILTIN,
    /**
     * MCP 技能。
     *
     * <p>来自外部 MCP Server 或 MCP 市场，例如 Tavily、GitHub。这类技能需要安装、
     * 配置用户 API Key，并通过 MCP Client 转成工具回调。</p>
     */
    MCP,
    /**
     * 自定义技能。
     *
     * <p>由用户上传 OpenAPI Schema 或配置自定义 HTTP 工具生成。后续
     * {@code CustomSkillProvider} 会读取自定义定义并动态生成可调用工具。</p>
     */
    CUSTOM
}
