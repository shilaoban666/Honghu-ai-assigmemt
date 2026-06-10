package com.honghu.ai.assigment.skill.builtin.claude;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.skill.security.SkillAccessPolicy;
import com.honghu.ai.assigment.skill.core.SkillSource;
import com.honghu.ai.assigment.skill.entity.Skill;
import com.honghu.ai.assigment.skill.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 将已启用的 Claude 风格 Skill 注入系统提示词。
 *
 * <p>Claude Skills 本质是“提示词运行时能力”，不是函数工具。用户在会话里启用某个 Skill 后，
 * 本解析器会把该 Skill 的简短说明追加到基础 system prompt 后面，让模型在回答时遵循这些额外指导。</p>
 *
 * <p>这里绝不执行 Skill 中提到的脚本。任何可执行动作都必须通过 CLI provider、沙箱和危险工具审批链路。</p>
 */
@Component
@RequiredArgsConstructor
public class SkillPromptResolver {

    /** 注入技能说明的保守字符预算，避免 system prompt 被技能正文撑得过长。 */
    private static final int MAX_SKILL_PROMPT_CHARS = 6000;

    /** 读取 skill 表，按 enabledSkillIds 找出当前会话启用的 Claude Skills。 */
    private final SkillRepository skillRepository;

    /** 角色策略，保证低权限用户不会注入高权限 Skill 的提示。 */
    private final SkillAccessPolicy accessPolicy;

    /** 解析 SKILL.md 风格文本，拿到 frontmatter 和正文。 */
    private final SkillMdParser skillMdParser;

    /**
     * 把启用的 Claude Skill 指导追加到基础 system prompt 后面。
     *
     * @param basePrompt 聊天路由选出的原始系统提示词
     * @param enabledSkillIds resolver 计算出的最终启用技能 id 集合
     * @param role 可信用户角色
     * @return 如果有可用 Claude Skill，则返回追加后的 prompt；否则返回原 prompt
     */
    public String appendSkillPrompts(String basePrompt, Set<Long> enabledSkillIds, User.UserRole role) {
        if (enabledSkillIds == null || enabledSkillIds.isEmpty()) {
            // 没有启用技能时不改变原系统提示词。
            return basePrompt;
        }
        // 从最终启用 id 中筛出当前用户可用、仍然启用、来源为 CLAUDE_SKILL 的技能。
        List<Skill> skills = skillRepository.findAllById(enabledSkillIds).stream()
                .filter(skill -> skill.isEnabled() && skill.getSource() == SkillSource.CLAUDE_SKILL)
                .filter(skill -> accessPolicy.canUse(role, skill.getRequiredRole()))
                // 排序是为了让同一组技能每次注入顺序稳定，方便测试和排查。
                .sorted(Comparator.comparing(Skill::getDisplayName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
        if (skills.isEmpty()) {
            // 当前会话没有可注入的 Claude Skill。
            return basePrompt;
        }

        // basePrompt 可能为 null，按空字符串处理。
        StringBuilder builder = new StringBuilder(basePrompt == null ? "" : basePrompt);
        // 明确分隔技能区块，降低模型把它和基础系统提示混淆的概率。
        builder.append("\n\n[Enabled Claude Skills]\n");
        // remaining 记录还能追加多少字符。
        int remaining = MAX_SKILL_PROMPT_CHARS;
        for (Skill skill : skills) {
            // 当前实现先用内置默认 markdown；后续可扩展为读取真实 SKILL.md 正文。
            String body = defaultSkillMarkdown(skill);
            // 解析 markdown，主要是拿正文部分；frontmatter 当前不参与注入。
            SkillMdParser.ParsedSkillMd parsed = skillMdParser.parse(body);
            // 每个技能形成一个独立块：名称 + 描述 + 正文指导。
            String block = "- " + nullToDefault(skill.getDisplayName(), skill.getSkillKey()) + ": "
                    + nullToDefault(skill.getDescription(), "Follow the enabled skill guidance.") + "\n"
                    + parsed.body() + "\n";
            if (block.length() > remaining) {
                // 如果当前块超过剩余预算，只追加能容纳的前缀并标记截断。
                builder.append(block, 0, Math.max(0, remaining)).append("\n[skill prompt truncated]\n");
                break;
            }
            // 预算足够时完整追加当前技能块。
            builder.append(block);
            // 扣减剩余字符预算。
            remaining -= block.length();
        }
        return builder.toString();
    }

    /**
     * 返回当前阶段内置的 Claude Skill markdown。
     */
    private String defaultSkillMarkdown(Skill skill) {
        // skillKey 可能为空，先转成空字符串避免 NPE。
        String key = skill.getSkillKey() == null ? "" : skill.getSkillKey();
        if ("skill:document-writer".equals(key)) {
            // 文档写作技能：指导模型处理长文、改写、报告、总结等任务。
            return """
                    ---
                    name: Document Writer Skill
                    ---
                    Use this skill when the user asks for long-form writing, rewriting, outlining, summaries, reports, or structured documents.
                    Keep the target audience and document purpose explicit. Prefer clear section headings, concise paragraphs, and concrete next steps.
                    When source material is available, preserve facts and mark uncertainty instead of inventing citations.
                    """;
        }
        if ("skill:data-analysis".equals(key)) {
            // 数据分析技能：指导模型说明假设、计算路径和观察/建议区分。
            return """
                    ---
                    name: Data Analysis Skill
                    ---
                    Use this skill when the user asks to interpret tabular data, compare metrics, explain trends, or summarize analysis results.
                    State assumptions, identify missing data, show the calculation path when useful, and separate observations from recommendations.
                    """;
        }
        // 未内置具体正文的 Skill 使用通用指导，且明确禁止绕过 CLI 审批执行脚本。
        return """
                ---
                name: Generic Skill
                ---
                Follow the skill description as additional task guidance. Do not execute scripts or commands unless a separate approved CLI tool is called.
                """;
    }

    /**
     * 有文本时返回文本，否则返回 fallback。
     */
    private String nullToDefault(String value, String fallback) {
        // StringUtils.hasText 会排除 null、空串和纯空白。
        return StringUtils.hasText(value) ? value : fallback;
    }
}
