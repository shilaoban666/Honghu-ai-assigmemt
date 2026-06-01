package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.SkillTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 技能工具定义 {@link SkillTool} 的持久化入口。
 *
 * <p>{@code SkillTool} 描述模型最终能看到并调用的具体工具，包括全局唯一工具名、
 * 工具说明、参数 JSON Schema、风险等级和展示顺序。它和 {@link Skill} 的关系是
 * 一个技能容器可以包含多个工具方法。</p>
 *
 * <p>内置技能启动同步时会先删除某个技能下的旧工具定义，再根据当前 Java 注解重新生成；
 * 运行时解析工具集时会按技能 ID 批量读取这些定义，并和 Spring Bean、反射方法合并成
 * 可执行的 {@code ResolvedTool}。</p>
 */
public interface SkillToolRepository extends JpaRepository<SkillTool, Long> {
    // 启动同步内置技能时先删除旧工具定义，再按当前代码重新写入。
    void deleteBySkillId(Long skillId);

    // 按多个 skillId 查询工具，并保持技能内工具顺序稳定。
    List<SkillTool> findBySkillIdInOrderBySortOrderAsc(Collection<Long> skillIds);

    // 查询某一个技能下的全部工具，按 sort_order 排序；详情页、启动同步清理旧工具时都会用到。
    List<SkillTool> findBySkillIdOrderBySortOrderAsc(Long skillId);

    // 按全局唯一工具名查找，后续工具调用日志或手动调用接口会用到。
    Optional<SkillTool> findByQualifiedName(String qualifiedName);
}
