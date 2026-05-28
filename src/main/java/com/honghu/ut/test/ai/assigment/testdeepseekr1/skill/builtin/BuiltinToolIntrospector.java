package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeTool;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.ToolParam;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * 内置工具反射分析器。
 *
 * <p>内置技能使用 Java 注解声明：类上标记 {@code @NativeSkill}，方法上标记
 * {@code @NativeTool}，参数上可选标记 {@code @ToolParam}。这个组件负责扫描可调用方法、
 * 解析模型可见的工具名，并根据 Java 方法签名生成工具参数 JSON Schema。</p>
 *
 * <p>生成出的 schema 会在启动注册阶段写入 {@code skill_tool.parameters_schema}，
 * 运行时再被 provider 读取并注入给 Spring AI。参数名发现策略必须和执行器保持一致，
 * 否则模型生成的 arguments 字段名会和反射调用取参名不一致。</p>
 */
@Component
public class BuiltinToolIntrospector {
    // 用 Jackson 构造 JSON Schema 节点，避免手写字符串导致引号、转义或类型错误。
    private final ObjectMapper objectMapper;
    // Spring 的参数名发现器；编译参数可用时能拿到真实方法参数名，生成更友好的 schema。
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public BuiltinToolIntrospector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 查找某个内置技能 Bean 上所有显式暴露给模型的工具方法。
     *
     * @param bean Spring 管理的内置技能 Bean
     * @return 标记了 {@code @NativeTool} 的 public 方法列表
     */
    public List<Method> findToolMethods(Object bean) {
        // getMethods 会包含 public 继承方法；这里只保留明确标注 @NativeTool 的方法作为可调用工具。
        return java.util.Arrays.stream(bean.getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(NativeTool.class))
                .toList();
    }

    /**
     * 解析模型和数据库中使用的工具短名称。
     *
     * @param method 内置工具方法
     * @return 注解指定的名称；未指定时返回 Java 方法名
     */
    public String toolName(Method method) {
        // 支持 @NativeTool(name="...") 显式指定工具名；为空时回退到 Java 方法名。
        NativeTool nativeTool = method.getAnnotation(NativeTool.class);
        return nativeTool.name().isBlank() ? method.getName() : nativeTool.name();
    }

    /**
     * 根据 Java 方法参数生成模型调用所需的 JSON Schema。
     *
     * @param method 内置工具方法
     * @return 描述 arguments 对象结构的 JSON Schema 节点
     */
    public JsonNode parametersSchema(Method method) {
        // 根节点固定是 object，因为模型调用工具时传入的是 JSON 对象形式的 arguments。
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        // properties 保存每个参数的 schema；required 保存必填参数名。
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        // 优先用 Spring 发现到的参数名；没有调试信息时再退回 JDK Parameter#getName。
        String[] discoveredNames = parameterNameDiscoverer.getParameterNames(method);
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String name = discoveredNames != null && discoveredNames.length > i ? discoveredNames[i] : parameter.getName();
            // @ToolParam 用来补充参数说明和必填性；不写注解时默认必填。
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            ObjectNode paramSchema = properties.putObject(name);
            // 把 Java 参数类型粗略映射为 JSON Schema 基础类型，足够满足当前内置工具调用。
            paramSchema.put("type", jsonType(parameter.getType()));
            if (List.class.isAssignableFrom(parameter.getType())) {
                // 当前列表参数主要用于数字统计，所以默认 items=number；未来如需字符串列表可扩展 ToolParam。
                paramSchema.putObject("items").put("type", "number");
            }
            if (toolParam != null && !toolParam.description().isBlank()) {
                // description 会直接影响模型如何填参数，尽量保持清晰短句。
                paramSchema.put("description", toolParam.description());
            }
            if (toolParam == null || toolParam.required()) {
                // 未标注 ToolParam 或 required=true 的参数都加入 required，避免工具执行时缺参。
                required.add(name);
            }
        }
        return root;
    }

    private String jsonType(Class<?> type) {
        // Number 子类以及除 boolean/char 外的 primitive 都归为 JSON number。
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != boolean.class && type != char.class) {
            return "number";
        }
        // Boolean/boolean 归为 JSON boolean。
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        // List 统一归为 JSON array，元素类型当前在 parametersSchema 中单独处理。
        if (List.class.isAssignableFrom(type)) {
            return "array";
        }
        // 其余类型先按 string 处理，保证工具 schema 简单稳定。
        return "string";
    }
}
