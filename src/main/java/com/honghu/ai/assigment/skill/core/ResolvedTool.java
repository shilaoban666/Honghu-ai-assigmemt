package com.honghu.ai.assigment.skill.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Method;

/**
 * 一个已经解析完成、可以执行的内置 Java 工具定义。
 *
 * <p>{@code skill_tool} 数据库行只保存模型可见的元数据，例如工具名、描述、参数 schema 和危险等级。
 * 但内置 Java 工具真正执行时还需要当前 JVM 中的 Spring Bean 和 Java Method。{@code ResolvedTool}
 * 把这两部分合在一起：既有持久化元数据用于展示、注入和审计，也有反射目标交给
 * {@link ToolExecutorService} 调用。</p>
 *
 * @param skillId 所属 {@code skill.id}，用于审计和统计
 * @param skillKey 稳定技能 key，例如 {@code time} 或 {@code kb}
 * @param skillName 技能展示名，便于调试和未来 UI 展示
 * @param qualifiedName 全局唯一、模型实际看到的工具名
 * @param toolName 技能内部短工具名
 * @param description 展示给模型的工具说明
 * @param parametersSchema 描述参数的 JSON Schema 对象
 * @param dangerLevel 执行前需要检查的风险等级
 * @param bean Spring 管理的技能 Bean
 * @param method 标记了 {@code @NativeTool} 的 Java 方法
 */
public record ResolvedTool(
        Long skillId,
        String skillKey,
        String skillName,
        String qualifiedName,
        String toolName,
        String description,
        JsonNode parametersSchema,
        DangerLevel dangerLevel,
        Object bean,
        Method method
) {
}
