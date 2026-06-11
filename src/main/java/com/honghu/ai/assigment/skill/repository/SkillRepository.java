package com.honghu.ai.assigment.skill.repository;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.skill.core.SkillSource;
import com.honghu.ai.assigment.skill.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 技能主表 {@link Skill} 的持久化入口。
 *
 * <p>技能系统把一个可开关、可安装、可展示的能力抽象为 {@code Skill}，再由
 * {@code skill_tool} 记录该技能下实际可被模型调用的工具。这个仓库负责读取和维护
 * 技能容器本身的元数据，包括来源、展示信息、默认启用规则、必装规则和角色门槛。</p>
 *
 * <p>主要使用场景包括：应用启动时内置技能注册器按 {@code skillKey} 做幂等同步；
 * MCP 市场安装时把远程 MCP 规范化成内部技能；会话解析工具集时按用户角色计算必须注入
 * 和默认启用的技能集合。</p>
 */
public interface SkillRepository extends JpaRepository<Skill, Long> {
    // 按业务 key 查技能；启动注册器用它做幂等 upsert。
    Optional<Skill> findBySkillKey(String skillKey);

    // 按来源读取启用中的技能，供商店不同 Tab 展示。
    List<Skill> findBySourceAndEnabledTrueOrderByDisplayNameAsc(SkillSource source);

    // 批量按 skillKey 查询，适合会话开关 API 把前端传来的 key 转成实体。
    List<Skill> findBySkillKeyIn(Collection<String> skillKeys);

    // 查询当前角色范围内所有必装技能 id；这些技能会被后端强制注入。
    @Query("""
            select s.id from Skill s
            where s.enabled = true and s.mandatory = true and s.requiredRole in :roles
            """)
    List<Long> findMandatoryEnabledIds(@Param("roles") Collection<User.UserRole> roles);

    // 查询当前角色范围内默认启用技能 id；只有会话没有显式设置时才使用。
    @Query("""
            select s.id from Skill s
            where s.enabled = true and s.defaultEnabled = true and s.requiredRole in :roles
            """)
    List<Long> findDefaultEnabledIds(@Param("roles") Collection<User.UserRole> roles);
}
