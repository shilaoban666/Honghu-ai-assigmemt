package com.honghu.ai.assigment.skill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 用户安装技能关系实体，对应数据库 {@code user_skill_install} 表。
 *
 * <p>同一个全局技能可以被多个用户安装，每个用户也可以保存独立配置和启用状态。这个实体用于
 * 表达“某用户拥有某技能”的关系，尤其适合 MCP 技能这类需要用户级 API Key、OAuth 授权或
 * endpoint 配置的外部能力。</p>
 *
 * <p>安装关系不会替代 {@link Skill} 的全局启用开关。即使用户已安装某技能，如果技能被管理员
 * 全局下线，解析会话工具集时也不会注入给模型。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_skill_install")
public class UserSkillInstall {
    // 安装记录主键。
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 安装技能的用户 id；同一个用户同一个 skill 由数据库唯一约束保证只安装一次。
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    // 被安装的技能 id。
    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    // 用户私有配置，例如 MCP/API Key；生产环境应在写入前加密。
    @Column(name = "user_config", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String userConfig;

    // 用户是否启用该安装；卸载可以物理删除，也可以先置为 false 做软停用。
    @Column(name = "enabled")
    private boolean enabled;

    // 安装时间，后续可用于“我的技能”排序。
    @CreationTimestamp
    @Column(name = "installed_at", updatable = false)
    private LocalDateTime installedAt;
}
