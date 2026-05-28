package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话级技能开关实体，对应数据库 {@code session_skill_setting} 表。
 *
 * <p>用户可以在单个会话中显式启用或关闭某些技能。只要该会话存在设置记录，
 * {@code SkillResolverService} 就会优先使用会话快照，而不是重新应用系统默认启用规则，
 * 从而保证旧会话的工具集不会因为后端默认配置变化而自动改变。</p>
 *
 * <p>主键由 {@code sessionId} 和 {@code skillId} 组成，表示某个会话对某个技能的一条明确选择。
 * 必装技能即使在这里被关闭，也会在后端解析阶段被强制补回。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(SessionSkillSettingId.class)
@Table(name = "session_skill_setting")
public class SessionSkillSetting {
    // 会话 id；与 skillId 一起组成联合主键，表示某个会话对某个技能的显式设置。
    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    // 技能 id；指向 skill.id。
    @Id
    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    // 当前会话是否启用该技能；false 表示用户在这个会话里明确关掉过。
    @Column(name = "enabled")
    private boolean enabled;
}
