package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.DangerLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记内置技能 Bean 中可以暴露给模型调用的方法。
 *
 * <p>只有显式标记该注解的 public 方法才会被扫描为工具。启动注册器会读取方法名、描述、
 * 参数 schema 和风险等级，写入 {@code skill_tool} 表；运行时执行器再通过反射调用该方法。</p>
 *
 * <p>工具描述是模型选择工具的重要依据，应说明该工具解决什么问题、适合何时调用以及关键限制。
 * 对会修改外部状态、发送消息、删除数据或执行命令的方法，应设置更高的 {@link DangerLevel}。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NativeTool {
    // 工具名；为空时使用 Java 方法名，写入 skill_tool.tool_name。
    String name() default "";

    // 给模型看的工具描述，模型会根据这句话决定是否调用工具。
    String description();

    // 工具风险等级；危险工具后续需要二次确认或更严格权限控制。
    DangerLevel dangerLevel() default DangerLevel.SAFE;
}
