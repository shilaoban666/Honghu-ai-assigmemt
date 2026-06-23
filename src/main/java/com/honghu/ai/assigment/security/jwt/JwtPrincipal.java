package com.honghu.ai.assigment.security.jwt;

import com.honghu.ai.assigment.entity.User;

/**
 * 已通过 JWT 验签的可信调用主体。
 *
 * <p>这是放进 Spring Security {@code SecurityContext} 的 principal。它只携带从签名 token 中
 * 解出来、且服务端信任的字段：userId、username、role。任何授权敏感判断都应基于这里的值，
 * 而不是前端请求体或自定义请求头里的 userId。</p>
 *
 * @param userId   数据库用户 ID（JWT 的 sub）
 * @param username 登录名，便于日志与审计展示
 * @param role     平台角色（GUEST/USER/VIP/ADMIN...），用于方法级 RBAC
 */
public record JwtPrincipal(String userId, String username, User.UserRole role) {

    /**
     * 是否为游客主体。
     *
     * <p>游客 token 的 sub 固定为 {@code guest}，业务侧据此区分“匿名体验”和“已登录用户”。</p>
     */
    public boolean isGuest() {
        return "guest".equals(userId);
    }
}
