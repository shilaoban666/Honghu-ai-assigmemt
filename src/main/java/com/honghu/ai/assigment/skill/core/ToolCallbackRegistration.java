package com.honghu.ai.assigment.skill.core;

import com.honghu.ai.assigment.skill.security.ToolGuard;
import org.springframework.ai.tool.ToolCallback;

/**
 * 绑定在 Spring AI {@link ToolCallback} 旁边的平台运行时元数据。
 *
 * <p>Spring AI 的 ToolCallback 只知道“怎么被模型调用”：名称、描述、参数 schema 和执行函数。
 * 但本平台还必须知道“这个工具属于谁、风险多高、审计时写什么名字”。因此每个 provider
 * 在返回 callback 时都会同时返回这个 record，Resolver 再统一包上 {@link AuditingToolCallback}。</p>
 *
 * @param callback 真正会注入给模型的可执行回调
 * @param skillId 所属 {@code skill.id}，用于审计日志和统计；只有防御性兜底时才可能为空
 * @param skillKey 所属技能的稳定 key，例如 {@code time} 或 {@code mcp:tavily}
 * @param toolQualifiedName 全局唯一工具名，也是模型实际看到并调用的名字
 * @param dangerLevel 调用前由 {@link ToolGuard} 执行的风险等级
 * @param source 工具来源文本，例如 BUILTIN/MCP/CLI，方便日志和调试区分 provider
 */
public record ToolCallbackRegistration(
        ToolCallback callback,
        Long skillId,
        String skillKey,
        String toolQualifiedName,
        DangerLevel dangerLevel,
        String source
) {
    /**
     * 兼容旧调用点的便捷构造器。
     *
     * <p>早期只有内置工具，没有 source 字段；保留这个构造器可以减少调用侧样板代码。</p>
     */
    public ToolCallbackRegistration(
            ToolCallback callback,
            Long skillId,
            String skillKey,
            String toolQualifiedName,
            DangerLevel dangerLevel) {
        // source 为空不影响执行，只是少一个调试标签。
        this(callback, skillId, skillKey, toolQualifiedName, dangerLevel, null);
    }
}
