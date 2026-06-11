package com.honghu.ai.assigment.skill.builtin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 给内置 Java 工具方法的单个参数补充模型可读元数据。
 *
 * <p>Java 参数类型只能告诉运行时如何把模型生成的 JSON 转成 Java 值，但这不足以让模型正确调用工具。
 * 模型还需要知道这个参数是什么意思、是否必填，以及当参数是 {@link java.util.List} 时数组元素应该是什么类型。</p>
 *
 * <p>这个注解会被两个地方读取：</p>
 * <ul>
 *     <li>{@code BuiltinToolIntrospector} 用它生成 {@code skill_tool.parameters_schema} 中的 JSON Schema。</li>
 *     <li>{@code ToolExecutorService} 用 {@link #required()} 判断缺少参数时是否应在调用 Java 方法前报错。</li>
 * </ul>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /**
     * 写入 JSON Schema、展示给模型看的参数说明。
     *
     * <p>这里应该简短、可操作。模型会根据这段文字决定如何填参数，所以“例如 Asia/Shanghai 这样的 IANA 时区”
     * 比面向 UI 的营销文案更有用。</p>
     */
    String description() default "";

    /**
     * 模型是否必须提供该参数。
     *
     * <p>如果为 true 且参数缺失或为 null，{@code ToolExecutorService} 会抛出
     * {@link IllegalArgumentException}。如果为 false，Java 方法会收到 null，并可在方法内部应用默认值。</p>
     */
    boolean required() default true;

    /**
     * 当 Java 参数是 {@link java.util.List} 时使用的数组元素类型。
     *
     * <p>很多情况下 Java 反射拿不到泛型元素类型。如果没有这个字段，所有 List 都可能被生成成
     * {@code items:number}，这对 {@code List<Double>} 统计工具没问题，但对标签、文件 id、命令参数、
     * 资源 id 这类字符串数组是错误的。非 List 参数会忽略这个值。</p>
     */
    Class<?> itemType() default Double.class;
}
