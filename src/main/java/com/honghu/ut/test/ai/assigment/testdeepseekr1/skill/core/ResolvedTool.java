package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 运行时已经解析完成的工具定义。
 *
 * <p>数据库中的 {@code skill_tool} 只保存工具元数据；真正执行时还需要 Spring Bean 和
 * Java Method。这个 record 把“数据库元数据”和“反射执行目标”合在一起，供
 * {@link ToolExecutorService} 调用，也方便上层转换为 Spring AI 的工具回调。</p>
 *
 * @param skillId 所属技能 ID，用于调用日志和后续统计
 * @param skillKey 所属技能业务 key，例如 {@code math}、{@code kb}、{@code mcp:tavily}
 * @param skillName 所属技能展示名，方便日志或调试时读懂来源
 * @param qualifiedName 注入模型的全局唯一工具名
 * @param toolName 技能内部短工具名
 * @param description 给模型看的工具描述
 * @param parametersSchema 工具参数 JSON Schema
 * @param dangerLevel 工具风险等级
 * @param bean Spring 管理的技能 Bean
 * @param method Bean 上被 {@code @NativeTool} 标记的方法
 */
public record ResolvedTool(
        // 所属技能 id，用于调用日志和后续统计。
        Long skillId,
        // 所属技能业务 key，例如 math、kb、mcp:tavily。
        String skillKey,
        // 所属技能展示名，方便日志或调试时读懂来源。
        String skillName,
        // 注入模型的全局唯一工具名。
        String qualifiedName,
        // 技能内部短工具名。
        String toolName,
        // 给模型看的工具描述。
        String description,
        // 工具参数 JSON Schema。
        JsonNode parametersSchema,
        // 工具风险等级。
        DangerLevel dangerLevel,
        // Spring 管理的技能 Bean。
        Object bean,
        // Bean 上被 @NativeTool 标记的方法。
        java.lang.reflect.Method method
) {
}
