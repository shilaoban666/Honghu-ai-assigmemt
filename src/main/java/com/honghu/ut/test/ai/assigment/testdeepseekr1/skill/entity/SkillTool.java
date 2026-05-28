package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.DangerLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 技能下的具体工具定义，对应数据库 {@code skill_tool} 表。
 *
 * <p>{@link Skill} 负责表达一个能力包，而 {@code SkillTool} 负责表达能力包里可被模型调用
 * 的具体函数。每条记录包含模型注入所需的全局工具名、自然语言描述、参数 JSON Schema、
 * 风险等级和排序信息。</p>
 *
 * <p>内置技能由启动注册器根据 {@code @NativeTool} 方法自动生成这些记录；MCP 或自定义技能
 * 后续也会把远程工具描述规范化到同一张表。运行时 provider 会把这张表中的元数据和真实执行
 * 对象合并成 {@code ResolvedTool}，再包装成 Spring AI 的 {@code ToolCallback}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "skill_tool")
public class SkillTool {
    // 数据库自增主键，只用于持久化记录本身。
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 所属技能 id；一个 Skill 可以包含多个 Tool。
    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    // 工具短名称，例如 calculate、tavily_search；用于详情页展示和反射方法匹配。
    @Column(name = "tool_name", nullable = false, length = 200)
    private String toolName;

    // 全局唯一工具名，例如 builtin__math__calculate；真正注入模型时使用它避免重名。
    @Column(name = "qualified_name", nullable = false, unique = true, length = 300)
    private String qualifiedName;

    // 工具描述，模型会根据它判断什么时候调用该工具。
    @Column(name = "description")
    private String description;

    // JSON Schema 字符串，描述工具参数类型、必填项和参数说明。
    @Column(name = "parameters_schema", columnDefinition = "jsonb")
    private String parametersSchema;

    // 风险等级，后续用于危险工具二次确认和权限拦截。
    @Enumerated(EnumType.STRING)
    @Column(name = "danger_level", length = 20)
    private DangerLevel dangerLevel;

    // 同一个技能下工具的展示/注入顺序；启动注册器按方法扫描顺序写入。
    @Column(name = "sort_order")
    private Integer sortOrder;
}
