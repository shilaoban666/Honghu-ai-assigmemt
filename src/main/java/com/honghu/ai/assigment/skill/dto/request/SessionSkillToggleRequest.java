package com.honghu.ai.assigment.skill.dto.request;

/**
 * 会话级能力开关请求。
 *
 * <p>这个开关只写入 {@code session_skill_setting}，不会安装/卸载能力，也不会修改全局默认值。
 * 关闭某个非 mandatory 能力后，{@code SkillResolverService} 在该 session 的下一次聊天请求中不应再解析出它。</p>
 *
 * @param enabled true 表示本会话启用，false 表示本会话禁用
 */
public record SessionSkillToggleRequest(boolean enabled) {
}
