package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.SessionSkillSetting;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.SessionSkillSettingId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话级技能开关 {@link SessionSkillSetting} 的持久化入口。
 *
 * <p>技能默认启用规则只适用于新会话。用户在某个会话里显式打开或关闭技能后，
 * 该会话应稳定使用自己的开关快照，避免后续系统默认值变化导致旧会话工具集自动漂移。
 * 这个仓库就是用于读取和重置这种会话级显式配置。</p>
 */
public interface SessionSkillSettingRepository extends JpaRepository<SessionSkillSetting, SessionSkillSettingId> {
    // 读取某个会话的全部技能开关；SkillResolverService 用它判断是否存在会话级显式配置。
    List<SessionSkillSetting> findBySessionId(String sessionId);

    // 重置某个会话的技能开关时使用，先删旧设置再批量写入新设置。
    void deleteBySessionId(String sessionId);

    // 卸载某个能力或清理单个会话开关时使用，只删除“这个会话 + 这个技能”的一条显式设置。
    void deleteBySessionIdAndSkillId(String sessionId, Long skillId);
}
