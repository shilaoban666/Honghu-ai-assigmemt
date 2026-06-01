package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeSkill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeTool;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.SkillSource;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.SkillTool;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillToolRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 内置技能启动注册器。
 *
 * <p>项目中的内置技能以 Java Bean 形式存在，类上使用 {@code @NativeSkill} 声明技能容器，
 * 方法上使用 {@code @NativeTool} 声明可调用工具。应用启动完成后，本注册器扫描这些 Bean，
 * 把技能元数据写入 {@code skill} 表，把工具元数据写入 {@code skill_tool} 表。</p>
 *
 * <p>注册过程是幂等的：技能按稳定 {@code skill_key} upsert，工具按当前代码重新生成。
 * 这样修改工具描述、参数或方法集合后，重启应用即可让数据库、商店展示和模型注入使用同一份定义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinSkillRegistrar {
    // Spring 容器，用来找到所有标了 @NativeSkill 的内置技能 Bean。
    private final ApplicationContext applicationContext;
    // skill 表仓库，负责把“一个 Java 技能类”同步成一条技能定义。
    private final SkillRepository skillRepository;
    // skill_tool 表仓库，负责把技能类里的每个 @NativeTool 方法同步成一条工具定义。
    private final SkillToolRepository skillToolRepository;
    // 反射辅助类，集中处理工具方法扫描、工具名解析和 JSON Schema 生成。
    private final BuiltinToolIntrospector toolIntrospector;
    // 用来把反射生成的参数 schema 序列化成 JSONB 字符串。
    private final ObjectMapper objectMapper;

    /**
     * 扫描并同步所有内置技能定义。
     *
     * <p>该方法在 {@link ApplicationReadyEvent} 后执行，确保 Spring 代理和依赖注入已经完成。
     * 同步失败会阻止启动继续推进，因为工具 schema 不一致会直接影响模型调用正确性。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void registerAll() {
        // 应用完全启动后再扫描 Bean，确保代理对象、依赖注入和配置都已经就绪。
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(NativeSkill.class);
        for (Object bean : beans.values()) {
            // 每个 @NativeSkill 类就是一个 Skill 容器；类上的注解决定商店卡片元数据。
            NativeSkill ann = bean.getClass().getAnnotation(NativeSkill.class);
            // 用 skill_key 做幂等 upsert：第一次启动插入，后续启动更新元数据，避免重复技能。
            Skill skill = skillRepository.findBySkillKey(ann.key()).orElseGet(Skill::new);
            skill.setSkillKey(ann.key());
            skill.setSource(SkillSource.BUILTIN);
            skill.setDisplayName(ann.displayName());
            skill.setDescription(ann.description());
            skill.setIconUrl(ann.icon());
            skill.setCategory(ann.category());
            skill.setDefaultEnabled(ann.defaultEnabled());
            skill.setMandatory(ann.mandatory());
            skill.setRequiredRole(ann.requiredRole());
            skill.setEnabled(true);
            skill = skillRepository.save(skill);

            // 内置工具定义以代码为准。这里改为“按 qualified_name 幂等 upsert + 清理失效工具”，
            // 而不是“先 deleteBySkillId 再全量 insert”。原方案在重启或多实例并发启动时，会与
            // skill_tool.qualified_name 的全局唯一约束冲突（例如 builtin__kb__search 已存在），
            // 导致 ApplicationReadyEvent 抛 DataIntegrityViolationException 并使整个应用启动失败。
            java.util.Set<String> currentQualifiedNames = new java.util.HashSet<>();
            int sort = 0;
            for (Method method : toolIntrospector.findToolMethods(bean)) {
                // 方法上的 @NativeTool 决定 LLM 看到的工具描述和危险等级。
                NativeTool toolAnn = method.getAnnotation(NativeTool.class);
                // 工具名允许注解覆盖；没写 name 时默认使用 Java 方法名。
                String toolName = toolIntrospector.toolName(method);
                // qualified_name 加上 builtin 和 skill key 前缀，避免 MCP/自定义工具与内置工具重名。
                String qualifiedName = "builtin__" + ann.key() + "__" + toolName;
                // 已存在则原地更新，不存在才新建，彻底避免 delete+insert 与唯一约束竞争。
                SkillTool tool = skillToolRepository.findByQualifiedName(qualifiedName).orElseGet(SkillTool::new);
                // skill_id 建立 Tool 到 Skill 的归属关系，前端开关 Skill 时能一次控制多个 Tool。
                tool.setSkillId(skill.getId());
                tool.setToolName(toolName);
                tool.setQualifiedName(qualifiedName);
                // description 是模型选择工具的重要依据，必须来自代码注解而不是前端文案。
                tool.setDescription(toolAnn.description());
                // parameters_schema 告诉模型每个参数的类型、必填性和说明。
                tool.setParametersSchema(toJson(toolIntrospector.parametersSchema(method)));
                // danger_level 后续用于二次确认、权限限制和调用日志风险标记。
                tool.setDangerLevel(toolAnn.dangerLevel());
                // sort_order 保持工具在详情页和模型注入时的稳定顺序。
                tool.setSortOrder(sort++);
                skillToolRepository.save(tool);
                currentQualifiedNames.add(qualifiedName);
            }
            // 清理代码里已经删除、但数据库还残留的旧工具：仍归属该技能但当前已不存在的 qualified_name。
            for (SkillTool existing : skillToolRepository.findBySkillIdOrderBySortOrderAsc(skill.getId())) {
                if (!currentQualifiedNames.contains(existing.getQualifiedName())) {
                    skillToolRepository.delete(existing);
                }
            }
            log.info("Registered builtin skill {} with {} tools", skill.getDisplayName(), sort);
        }
    }

    /**
     * 把内置工具参数 schema 等结构化对象序列化成 JSON 字符串。
     *
     * <p>{@code skill_tool.parameters_schema} 在数据库中是 JSONB 字段，但 JPA 实体里使用 String 承载。
     * 因此启动注册时需要先把 Jackson 节点或 Map 序列化成字符串，再交给 {@code @ColumnTransformer}
     * 写入 PostgreSQL JSONB。</p>
     *
     * @param value 需要写入 JSONB 字段的结构化对象
     * @return 合法 JSON 字符串
     */
    private String toJson(Object value) {
        try {
            // JSONB 字段最终由数据库保存为结构化 JSON，这里先序列化成字符串交给 JPA。
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // schema 生成失败说明代码注解或参数类型不合法，启动期直接失败比运行时才暴露更安全。
            throw new IllegalStateException("Failed to serialize tool schema", e);
        }
    }
}
