package com.honghu.ai.assigment.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeSkill;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeTool;
import com.honghu.ai.assigment.skill.core.ResolvedTool;
import com.honghu.ai.assigment.skill.entity.Skill;
import com.honghu.ai.assigment.skill.entity.SkillTool;
import com.honghu.ai.assigment.skill.repository.SkillToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置技能运行时工具提供器。
 *
 * <p>启动注册器只负责把内置技能和工具的元数据同步到数据库；真正给模型注入工具时，还需要把
 * 数据库中的 {@link SkillTool} 记录重新绑定到当前 JVM 内的 Spring Bean 和 Java 方法。
 * 这个 provider 就负责完成这一步，并输出 {@link ResolvedTool} 列表。</p>
 *
 * <p>它只处理 {@code SkillSource.BUILTIN} 类型的技能。MCP 和自定义技能应有独立 provider，
 * 但都可以输出同一种 {@code ResolvedTool} 或 Spring AI callback 形态，从而让上层解析逻辑保持统一。</p>
 */
@Component
@RequiredArgsConstructor
public class BuiltinSkillProvider {
    // 通过 ApplicationContext 重新拿到技能 Bean，确保执行时调用的是 Spring 管理的对象而不是手工 new 出来的对象。
    private final ApplicationContext applicationContext;
    // 读取启动期同步到数据库的 skill_tool 记录，保证运行时工具集和商店详情一致。
    private final SkillToolRepository skillToolRepository;
    // 反射工具类，负责从 Bean 上找 @NativeTool 方法并解析工具名。
    private final BuiltinToolIntrospector toolIntrospector;
    // 把数据库中的 parameters_schema 字符串还原为 JsonNode，交给 Spring AI ToolDefinition 使用。
    private final ObjectMapper objectMapper;

    /**
     * 把启用的内置技能转换为可执行工具。
     *
     * @param skills 已经过角色、会话和全局启用规则过滤的 BUILTIN 技能列表
     * @return 可被执行器调用的运行时工具列表
     */
    public List<ResolvedTool> toolsFor(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            // 没有内置技能需要注入时直接返回空列表，避免后续查询数据库。
            return List.of();
        }
        // 建立 skill_key -> Bean 的索引，后续可以根据数据库 Skill 快速找到对应执行对象。
        Map<String, Object> beansBySkillKey = nativeBeansBySkillKey();
        Map<Long, Skill> skillById = new HashMap<>();
        for (Skill skill : skills) {
            // 数据库工具表只存 skill_id，这里先构建 id -> Skill 映射，方便还原 skillKey/displayName。
            skillById.put(skill.getId(), skill);
        }
        // 按 sort_order 读取所有工具，确保 UI 和模型注入顺序稳定。
        List<SkillTool> rows = skillToolRepository.findBySkillIdInOrderBySortOrderAsc(skillById.keySet());
        return rows.stream()
                // 把数据库行 + Spring Bean + Method 合并成运行时可执行的 ResolvedTool。
                .map(row -> toResolvedTool(row, skillById.get(row.getSkillId()), beansBySkillKey))
                // 如果代码里的 Bean 或方法已经不存在，跳过这条数据库残留，避免整体工具解析失败。
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 把数据库中的一条工具定义绑定回当前 JVM 中真实可调用的 Java 方法。
     *
     * <p>数据库只保存元数据：工具名、描述、参数 schema、风险等级等；它并不保存 Java 对象引用。
     * 因此运行时必须根据 skillKey 找到对应 {@code @NativeSkill} Bean，再根据 toolName 找到对应
     * {@code @NativeTool} 方法，最后组合成 {@link ResolvedTool}，供执行器反射调用。</p>
     *
     * @param row skill_tool 表中的工具定义行
     * @param skill row 所属的 Skill；可能因异常数据为空
     * @param beansBySkillKey 当前 Spring 容器中内置技能 Bean 的索引
     * @return 绑定成功后的运行时工具；如果数据库和代码不同步则返回 null 并跳过
     */
    private ResolvedTool toResolvedTool(SkillTool row, Skill skill, Map<String, Object> beansBySkillKey) {
        if (skill == null) {
            // 数据库外键正常时不会发生；保留兜底，避免异常数据拖垮整轮工具解析。
            return null;
        }
        Object bean = beansBySkillKey.get(skill.getSkillKey());
        if (bean == null) {
            // 说明数据库里有 Skill，但当前代码没有对应 @NativeSkill Bean，通常发生在代码删除技能后。
            return null;
        }
        Method method = toolIntrospector.findToolMethods(bean).stream()
                // 数据库保存的是 toolName；这里在 Bean 方法里反查同名工具方法。
                .filter(candidate -> toolIntrospector.toolName(candidate).equals(row.getToolName()))
                .findFirst()
                .orElse(null);
        if (method == null || !method.isAnnotationPresent(NativeTool.class)) {
            // 找不到方法说明代码和数据库不同步；跳过比抛错更适合生产环境热升级。
            return null;
        }
        try {
            return new ResolvedTool(
                    // skillId 用于调用日志和后续按技能统计。
                    skill.getId(),
                    // skillKey 用于前端、日志和跨环境稳定识别。
                    skill.getSkillKey(),
                    // displayName 用于调试和未来工具调用侧边栏展示。
                    skill.getDisplayName(),
                    // qualifiedName 是模型实际看到的唯一工具名。
                    row.getQualifiedName(),
                    // toolName 是技能内部的短名称，便于详情页展示。
                    row.getToolName(),
                    // description 是模型选择工具的语义提示。
                    row.getDescription(),
                    // parametersSchema 描述工具参数，模型据此生成 arguments。
                    objectMapper.readTree(row.getParametersSchema()),
                    // dangerLevel 后续用于危险工具确认和权限守卫。
                    row.getDangerLevel(),
                    // bean + method 是真正执行工具时需要的反射目标。
                    bean,
                    method
            );
        } catch (Exception e) {
            // parameters_schema 是启动期生成的，如果这里解析失败，说明数据库内容已经损坏。
            throw new IllegalStateException("Invalid parameters_schema for tool " + row.getQualifiedName(), e);
        }
    }

    /**
     * 扫描当前 Spring 容器中的所有内置技能 Bean，并按技能稳定 key 建立索引。
     *
     * <p>索引 key 使用 {@code @NativeSkill.key()}，不是 Spring Bean 名称，也不是 Java 类名。
     * 这样即使重命名类或调整 Bean 名称，只要 key 不变，数据库里的 skill.skill_key 仍然能找到正确执行对象。</p>
     *
     * @return skillKey 到 Spring Bean 实例的映射
     */
    private Map<String, Object> nativeBeansBySkillKey() {
        Map<String, Object> result = new HashMap<>();
        applicationContext.getBeansWithAnnotation(NativeSkill.class).values()
                // key 来自 @NativeSkill，而不是 Bean 名称；这样重命名 Java 类不会影响数据库 skill_key。
                .forEach(bean -> result.put(bean.getClass().getAnnotation(NativeSkill.class).key(), bean));
        return result;
    }
}
