package com.honghu.ai.assigment.skill.builtin.annotation;

import com.honghu.ai.assigment.entity.User;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Spring Bean 为内置技能容器。
 *
 * <p>被该注解标记的类会自动成为 Spring 组件，并在应用启动后由
 * {@code BuiltinSkillRegistrar} 扫描同步到 {@code skill} 表。类上的元数据用于技能商店、
 * 会话技能开关和角色过滤；类中的 {@code @NativeTool} 方法则会同步为该技能下的具体工具。</p>
 *
 * <p>{@link #key()} 是跨环境稳定标识，后续会被写入 {@code skill.skill_key}。不要随意修改，
 * 否则数据库会把它视为一个新技能，旧会话开关和用户安装关系也无法自动迁移。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface NativeSkill {
    // 技能稳定 key，会写入 skill.skill_key；不要随意改，否则会被视为一个新技能。
    String key();

    // 商店卡片和详情页展示名称。
    String displayName();

    // 技能简介，描述这个 Skill 容器整体能做什么。
    String description() default "";

    // 图标文本或图标地址；内置技能当前通常放一个简短符号。
    String icon() default "🧩";

    // 技能分类；默认“通用”，方便商店按领域筛选。
    String category() default "通用";

    // 新会话是否默认启用；用户仍然可以在会话级开关里关闭非 mandatory 技能。
    boolean defaultEnabled() default false;

    // 是否必装；mandatory 技能后端会强制注入，前端应显示锁定。
    boolean mandatory() default false;

    // 使用该技能所需的最低用户角色。
    User.UserRole requiredRole() default User.UserRole.USER;
}
