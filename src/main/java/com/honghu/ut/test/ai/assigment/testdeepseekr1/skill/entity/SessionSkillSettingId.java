package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * {@link SessionSkillSetting} 的 JPA 联合主键类型。
 *
 * <p>会话技能设置以 {@code sessionId + skillId} 唯一定位一条记录，表示某个会话对某个技能
 * 的显式启用或关闭状态。JPA 的 {@code @IdClass} 要求提供一个可序列化、字段名与实体主键字段
 * 一致的主键类，因此单独定义该类型。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionSkillSettingId implements Serializable {
    // JPA 联合主键字段之一：会话 id。
    private String sessionId;
    // JPA 联合主键字段之二：技能 id。
    private Long skillId;
}
