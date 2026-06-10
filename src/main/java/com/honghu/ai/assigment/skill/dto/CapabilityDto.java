package com.honghu.ai.assigment.skill.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 前端统一消费的能力 DTO。
 *
 * <p>无论底层来源是内置 Java 工具、MCP Server、Claude Skill 还是 CLI 白名单条目，
 * API 都返回这个结构。这样商店页、设置页、输入区胶囊和技能弹窗可以共享一套状态字段，
 * 只在 {@link #kind()} 和 {@link #metadata()} 上做差异化展示。</p>
 *
 * @param skillId 数据库 {@code skill.id}，仅后端调试或管理后台需要，前端业务逻辑优先用 {@code skillKey}
 * @param id 前端兼容字段，当前等于 {@code skillKey}
 * @param kind 能力大类：{@code builtin}/{@code mcp}/{@code skill}/{@code cli}
 * @param skillKey 全局唯一能力键，对应 {@code skill.skill_key}，例如 {@code time}、{@code mcp:github}、{@code cli:git}
 * @param source 数据来源枚举，对应 {@code SkillSource}，例如 {@code BUILTIN}/{@code MCP}/{@code CLAUDE_SKILL}/{@code CLI}
 * @param name 展示名称
 * @param slug 面向 URL、CSS 或测试选择器的短标识，由 {@code skillKey} 归一化得到
 * @param description 简短说明，列表卡片和详情页都会展示
 * @param icon 图标文本或图标 URL；当前支持短文本、emoji、URL 三类
 * @param accent 前端卡片强调色
 * @param category 市场分类 key
 * @param origin 来源类型：{@code builtin}/{@code community}/{@code official}/{@code custom}
 * @param publisher 发布者或同步来源名称
 * @param version 能力版本；没有版本时返回 {@code v1}
 * @param rating 市场评分，内置能力可为空
 * @param downloads 下载量或热度计数
 * @param verified 是否经过系统验证；当前内置能力为 true，外部目录后续接审核系统
 * @param installed 当前用户是否已安装；内置、mandatory、defaultEnabled 会被视为隐式安装
 * @param enabled 当前会话是否启用；这个字段必须与 {@code SkillResolverService} 的解析结果一致
 * @param mandatory 是否系统强制启用，true 时前端不能关闭或卸载
 * @param defaultEnabled 是否默认启用；用户或会话可以显式覆盖非 mandatory 能力
 * @param requiredRole 使用该能力所需的最低角色
 * @param toolNames 该能力下可注入给模型的工具名摘要
 * @param metadata 按 kind 分支的扩展信息；mcp/skill/cli/builtin 的字段含义不同
 */
@Builder
public record CapabilityDto(
        Long skillId,
        String id,
        String kind,
        String skillKey,
        String source,
        String name,
        String slug,
        String description,
        String icon,
        String accent,
        String category,
        String origin,
        String publisher,
        String version,
        BigDecimal rating,
        Integer downloads,
        boolean verified,
        boolean installed,
        boolean enabled,
        boolean mandatory,
        boolean defaultEnabled,
        String requiredRole,
        // 本会话启用该能力的时间（ISO-8601 字符串）；未在会话显式启用时为空。供前端悬停提示「启用于 X」。
        String enabledAt,
        List<String> toolNames,
        Map<String, Object> metadata
) {
}
