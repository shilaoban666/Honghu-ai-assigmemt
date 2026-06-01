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
 * 内置 Java 技能的反射解析工具。
 *
 * <p>内置技能以 Spring Bean 的形式存在：类上标 {@code @NativeSkill}，方法上标 {@link NativeTool}。
 * 本组件集中负责把这些 Java 注解转换成运行时和数据库都能理解的工具元数据：</p>
 *
 * <ul>
 *     <li>哪些 public 方法可以暴露给模型调用。</li>
 *     <li>每个工具在技能内部的短名称是什么。</li>
 *     <li>每个方法参数应该生成怎样的 JSON Schema。</li>
 * </ul>
 *
 * <p>生成的 JSON Schema 会在启动期由 {@code BuiltinSkillRegistrar} 写入 {@code skill_tool}。
 * 运行期 provider 再读取同一份 schema 注入给 Spring AI 或 OpenAI-compatible function calling，
 * 这样商店 UI、数据库元数据和真实模型调用看到的是同一份定义。</p>
 */
@Component
public class BuiltinToolIntrospector {

    /** Jackson 用于构造 JSON Schema 节点，避免手写字符串拼 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 在项目使用 {@code -parameters} 编译时发现真实 Java 参数名。
     *
     * <p>执行路径也使用同一个发现器，因此 schema 生成时写出的字段名和运行时读取的字段名能保持一致。</p>
     */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 创建内置工具反射解析器。
     *
     * <p>这里只有一个依赖：Spring 管理的 {@link ObjectMapper}。使用统一的 ObjectMapper 可以保证
     * 这里生成的 JSON Schema 节点，与项目其他地方序列化到数据库或接口响应时使用的 JSON 规则一致。</p>
     *
     * @param objectMapper Spring 容器注入的 JSON 节点构造与序列化工具
     */
    public BuiltinToolIntrospector(ObjectMapper objectMapper) {
        // ObjectMapper 由 Spring 注入，和项目其他 JSON 序列化规则保持一致。
        this.objectMapper = objectMapper;
    }

    /**
     * 返回一个技能 Bean 上所有允许模型调用的 public 方法。
     *
     * @param bean Spring 管理的技能 Bean
     * @return 显式标记了 {@link NativeTool} 的方法列表
     */
    public List<Method> findToolMethods(Object bean) {
        // getMethods() 只返回 public 方法，天然避免把 private/helper 方法暴露给模型。
        return java.util.Arrays.stream(bean.getClass().getMethods())
                // 必须显式标记 @NativeTool 才能成为模型工具。
                .filter(method -> method.isAnnotationPresent(NativeTool.class))
                .toList();
    }

    /**
     * 解析写入 {@code skill_tool.tool_name} 的技能内工具短名称。
     *
     * @param method 标记了 {@link NativeTool} 的方法
     * @return 注解 name 非空时返回注解值，否则返回 Java 方法名
     */
    public String toolName(Method method) {
        // 读取方法上的工具注解。
        NativeTool nativeTool = method.getAnnotation(NativeTool.class);
        // 注解 name 为空时使用 Java 方法名，减少简单工具的样板配置。
        return nativeTool.name().isBlank() ? method.getName() : nativeTool.name();
    }

    /**
     * 为一个工具方法的参数构建 JSON Schema 对象。
     *
     * <p>这里生成的 schema 刻意保持保守，只使用大多数 Ollama、OpenAI-compatible provider 和 MCP client
     * 都支持的 JSON Schema 子集：{@code type}、{@code properties}、{@code required}、{@code description}
     * 以及数组的 {@code items.type}。更复杂的校验应该放在工具方法或 provider 边界里完成。</p>
     *
     * @param method Java 工具方法
     * @return 描述参数对象的 JSON Schema 节点
     */
    public JsonNode parametersSchema(Method method) {
        // 根节点固定是 object，因为模型调用工具时传入的是参数对象。
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        // properties 存放每个参数的字段 schema。
        ObjectNode properties = root.putObject("properties");
        // required 数组记录必填参数名。
        ArrayNode required = root.putArray("required");

        // 解析真实 Java 参数名；需要编译时保留 -parameters。
        String[] discoveredNames = parameterNameDiscoverer.getParameterNames(method);
        // 读取方法的所有参数，逐个生成 schema。
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            // 当前参数的反射对象，包含类型和注解。
            Parameter parameter = parameters[i];
            // 优先用真实参数名；拿不到时退回 JVM 提供的 parameter.getName()。
            String name = discoveredNames != null && discoveredNames.length > i ? discoveredNames[i] : parameter.getName();
            // @ToolParam 提供模型可读说明、是否必填、数组元素类型等信息。
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);

            // 为当前参数在 properties 下创建一个字段 schema。
            ObjectNode paramSchema = properties.putObject(name);
            // rawType 是 Java 原始类型，例如 String、Double、List。
            Class<?> rawType = parameter.getType();
            // 把 Java 类型映射成 JSON Schema 支持的 type。
            paramSchema.put("type", jsonType(rawType));

            if (List.class.isAssignableFrom(rawType)) {
                // List 的泛型运行时可能被擦除，所以用 ToolParam.itemType 明确数组元素类型。
                Class<?> itemType = toolParam == null ? Double.class : toolParam.itemType();
                paramSchema.putObject("items").put("type", jsonType(itemType));
            }
            if (toolParam != null && !toolParam.description().isBlank()) {
                // 参数说明会进入 schema，模型会根据它决定如何填写字段。
                paramSchema.put("description", toolParam.description());
            }
            if (toolParam == null || toolParam.required()) {
                // 没标 ToolParam 时默认必填；标了 required=false 才允许缺失。
                required.add(name);
            }
        }
        // 返回完整参数对象 schema。
        return root;
    }

    /**
     * 把 Java 原始类型映射到常见 LLM 工具调用 API 接受的 JSON Schema type。
     */
    private String jsonType(Class<?> type) {
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != boolean.class && type != char.class) {
            // Java 数字包装类和数值型 primitive 都映射为 JSON number。
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            // Boolean 映射为 JSON boolean。
            return "boolean";
        }
        if (List.class.isAssignableFrom(type)) {
            // List 映射为 JSON array，元素类型在 items 中描述。
            return "array";
        }
        // 其他类型暂按 string 处理，复杂对象不建议直接作为工具参数。
        return "string";
    }
}
