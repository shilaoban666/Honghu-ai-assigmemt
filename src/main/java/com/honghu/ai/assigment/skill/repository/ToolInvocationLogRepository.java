package com.honghu.ai.assigment.skill.repository;

import com.honghu.ai.assigment.skill.entity.ToolInvocationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 工具调用审计日志 {@link ToolInvocationLog} 的查询入口。
 *
 * <p>所有通过 {@code ToolExecutorService} 执行的工具调用都会落库，无论成功、失败、
 * 缺参还是反射异常。日志用于前端展示会话工具调用历史，也用于后续统计慢工具、错误率、
 * 高风险工具调用频次以及问题排查。</p>
 */
public interface ToolInvocationLogRepository extends JpaRepository<ToolInvocationLog, Long> {
    // 前端会话侧边栏只需要最近的调用记录，所以直接按 sessionId 取最新 20 条。
    List<ToolInvocationLog> findTop20BySessionIdOrderByCreatedAtDesc(String sessionId);
}
