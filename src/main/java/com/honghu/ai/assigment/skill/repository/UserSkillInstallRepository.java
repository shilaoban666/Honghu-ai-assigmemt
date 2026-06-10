package com.honghu.ai.assigment.skill.repository;

import com.honghu.ai.assigment.skill.entity.UserSkillInstall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 用户安装技能关系 {@link UserSkillInstall} 的持久化入口。
 *
 * <p>系统技能表只描述一个技能是否存在以及全局元数据，用户是否安装、是否启用、私有配置
 * 则由 {@code user_skill_install} 单独记录。这样同一个 MCP 技能可以被多个用户安装，
 * 每个用户保存自己的 API Key、endpoint 或其他配置。</p>
 *
 * <p>安装接口通过 {@link #findByUserIdAndSkillId(String, Long)} 做幂等更新；
 * 会话工具解析通过 {@link #findEnabledSkillIdsByUser(String)} 把用户已安装并启用的技能
 * 合并进默认工具集。</p>
 */
public interface UserSkillInstallRepository extends JpaRepository<UserSkillInstall, Long> {
    // 判断某个用户是否已经安装指定技能，用于安装接口做幂等更新。
    Optional<UserSkillInstall> findByUserIdAndSkillId(String userId, Long skillId);

    // 查询用户安装的全部技能，用于“我的”Tab。
    List<UserSkillInstall> findByUserId(String userId);

    // 只返回启用中的 skillId，SkillResolverService 用它合并用户已安装技能。
    @Query("""
            select u.skillId from UserSkillInstall u
            where u.userId = :userId and u.enabled = true
            """)
    List<Long> findEnabledSkillIdsByUser(@Param("userId") String userId);
}
