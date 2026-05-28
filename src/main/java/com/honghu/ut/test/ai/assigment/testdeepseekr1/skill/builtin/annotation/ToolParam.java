package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 补充内置工具方法参数的模型可见元数据。
 *
 * <p>Java 参数类型只能提供有限的结构信息，模型还需要知道每个参数的业务含义以及是否必填。
 * {@code BuiltinToolIntrospector} 会读取该注解，把说明和必填状态写入工具参数 JSON Schema；
 * {@code ToolExecutorService} 执行时也会根据 {@link #required()} 判断缺参是否应立刻失败。</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {
    // 参数说明，会写入 JSON Schema 的 description，帮助模型正确生成 arguments。
    String description() default "";

    // 是否必填；false 时模型没传该参数会给 Java 方法传 null。
    boolean required() default true;
}
