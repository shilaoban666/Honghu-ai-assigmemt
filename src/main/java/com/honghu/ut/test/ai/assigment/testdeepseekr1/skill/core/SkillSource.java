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
     * Claude Skills 兼容技能。
     *
     * <p>这类能力的核心不是一个远程服务，而是一套 {@code SKILL.md} 目录语义：
     * frontmatter 描述技能，正文教模型怎么做事，{@code resources/} 提供参考资料，
     * {@code scripts/} 提供可选脚本。当前阶段只把它作为 prompt 型目录项展示和开关；
     * 后续接入 {@code SkillPromptResolver} 后，才会把启用的 skill 注入系统提示。</p>
     */
    CLAUDE_SKILL,
    /**
     * 受控 CLI 能力。
     *
     * <p>CLI 不是“浏览器直接执行 shell”。它必须经过后端白名单、权限策略、审批流、沙箱和审计。
     * 当前阶段只把精选命令行能力写入目录并支持安装/会话开关；真正执行器和沙箱在后续阶段补齐。</p>
     */
    CLI,
    /**
     * 自定义技能。
     *
     * <p>由用户上传 OpenAPI Schema 或配置自定义 HTTP 工具生成。后续
     * {@code CustomSkillProvider} 会读取自定义定义并动态生成可调用工具。</p>
     */
    CUSTOM
}
