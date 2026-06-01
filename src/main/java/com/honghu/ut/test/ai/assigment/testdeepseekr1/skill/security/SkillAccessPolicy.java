package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 技能安装和解析共用的角色策略。
 *
 * <p>技能系统在多个地方都需要比较用户角色：市场安装、会话解析、provider 过滤和测试断言。
 * 把角色比较集中在一个组件里，可以避免“前端允许安装但运行时又过滤掉”，或者更危险的
 * “安装接口忘记检查 requiredRole 但运行时检查了”的不一致安全问题。</p>
 *
 * <p>下面的顺序就是平台订阅/权限阶梯。高等级角色包含所有低等级角色可用的技能。
 * 例如 {@code VIP} 可以使用 requiredRole={@code USER} 的技能，但 {@code USER} 不能安装或运行
 * requiredRole={@code ADMIN} 的技能。</p>
 */
@Component
public class SkillAccessPolicy {

    /** 从低到高的角色阶梯，所有技能权限判断都使用这个顺序。 */
    private static final List<User.UserRole> ROLE_ORDER = List.of(
            User.UserRole.GUEST,
            User.UserRole.USER,
            User.UserRole.PRO,
            User.UserRole.PLUS,
            User.UserRole.PRO_PLUS,
            User.UserRole.VIP,
            User.UserRole.ADMIN
    );

    /**
     * 返回当前角色可以满足的所有 requiredRole 值。
     *
     * @param actorRole 当前可信调用者角色；null 按 GUEST 处理
     * @return 可直接用于仓库查询 {@code requiredRole in :roles} 的角色集合
     */
    public Set<User.UserRole> allowedRequiredRoles(User.UserRole actorRole) {
        // 没有角色时按访客处理，不能默认给 USER 权限。
        User.UserRole safeRole = actorRole == null ? User.UserRole.GUEST : actorRole;
        // 找到当前角色在阶梯里的位置。
        int index = ROLE_ORDER.indexOf(safeRole);
        if (index < 0) {
            // 未知角色兜底到 GUEST。
            index = 0;
        }
        // EnumSet 比 HashSet 更适合枚举集合，内存小且查询快。
        EnumSet<User.UserRole> roles = EnumSet.noneOf(User.UserRole.class);
        for (int i = 0; i <= index; i++) {
            // 当前角色及其以下所有角色都可满足。
            roles.add(ROLE_ORDER.get(i));
        }
        return roles;
    }

    /**
     * 判断当前角色是否可以使用某个 requiredRole 的技能。
     *
     * @param actorRole 可信调用者角色
     * @param requiredRole 技能最低角色；null 按 USER 处理
     * @return true 表示该技能允许被安装或解析
     */
    public boolean canUse(User.UserRole actorRole, User.UserRole requiredRole) {
        // 老数据 requiredRole 为空时按 USER，避免无意中变成所有访客可用。
        User.UserRole safeRequiredRole = requiredRole == null ? User.UserRole.USER : requiredRole;
        return allowedRequiredRoles(actorRole).contains(safeRequiredRole);
    }
}
