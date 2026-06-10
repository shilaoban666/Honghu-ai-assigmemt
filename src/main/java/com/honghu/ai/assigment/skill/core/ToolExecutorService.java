package com.honghu.ai.assigment.skill.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.skill.builtin.annotation.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 执行一个已经解析好的内置 Java 工具。
 *
 * <p>这里的“内置工具”指后端 Java Bean 上标记 {@code @NativeTool} 的方法。
 * {@link SkillResolverService} 会先把数据库中的工具定义、Spring Bean 和 Java Method 合并成
 * {@link ResolvedTool}，本服务再负责把模型传来的 JSON 参数转换成 Java 参数并通过反射调用方法。</p>
 *
 * <p>注意：本类故意不写审计日志，也不做危险等级判断。统一审计和风险拦截放在
 * {@link AuditingToolCallback}，因为 MCP 和 CLI 工具并不走 Java 反射，如果这里写审计就会造成
 * 内置工具和外部工具策略不一致，甚至内置工具重复落日志。</p>
 *
 * <p>安全边界也很明确：模型只能控制 {@code argumentsJson}，不能控制 userId/sessionId/messageId。
 * 这些可信上下文通过 {@link ToolExecutionContext} 传入，并在调用期间放入
 * {@link ToolExecutionContextHolder} 供业务工具读取。</p>
 */
@Service
@RequiredArgsConstructor
public class ToolExecutorService {

    /** Jackson 负责把 JSON 参数转换成精确的 Java 参数类型。 */
    private final ObjectMapper objectMapper;

    /**
     * 参数名发现逻辑必须和 {@code BuiltinToolIntrospector} 保持一致。
     *
     * <p>如果 schema 生成时使用一个字段名，而执行时读取另一个字段名，模型生成的参数就无法映射回 Java 方法参数。</p>
     */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 执行 {@link ResolvedTool} 背后的 Java 方法。
     *
     * @param tool 已解析的内置工具，包含 Bean、Method 和元数据
     * @param argumentsJson 模型生成的 JSON 参数对象；空字符串表示无参数
     * @param context 本次工具调用的可信服务端上下文
     * @return 注解 Java 方法的原始返回值
     * @throws IllegalStateException 解析、类型转换或反射调用失败时抛出
     */
    public Object execute(ResolvedTool tool, String argumentsJson, ToolExecutionContext context) {
        try {
            // 将可信上下文放到 ThreadLocal，业务工具方法不用把 userId/sessionId 设计成模型可填参数。
            ToolExecutionContextHolder.set(context);
            // 空参数按 {} 处理；非空参数必须是 JSON，由上层审计装饰器通常已经规范过一次。
            JsonNode arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argumentsJson);
            // 按 Java 方法参数列表，把 JSON 字段逐一转换为对应 Java 类型。
            Object[] args = convertArguments(tool.method(), arguments);
            // 通过反射调用真实 Spring Bean 上的方法；返回值交给 Spring AI callback 序列化/回传。
            return tool.method().invoke(tool.bean(), args);
        } catch (Exception e) {
            // Method.invoke 会把业务异常包一层 InvocationTargetException，这里取 cause 让报错更接近真实原因。
            Throwable root = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Tool call failed: " + tool.qualifiedName() + " - " + root.getMessage(), root);
        } finally {
            /*
             * 这里即使 AuditingToolCallback 也会 clear，仍然要再清一次。原因是测试、管理后台或未来内部任务
             * 可能直接调用 ToolExecutorService，不经过审计装饰器；ThreadLocal 泄漏会导致复用线程时串用户上下文。
             */
            ToolExecutionContextHolder.clear();
        }
    }

    /**
     * 将 JSON 参数对象转换成 Java 反射调用需要的 Object[]。
     *
     * @param method 即将被调用的工具方法
     * @param arguments 模型生成的 JSON 参数对象
     * @return 与 method 参数顺序完全一致的 Java 参数数组
     */
    private Object[] convertArguments(Method method, JsonNode arguments) {
        // 读取 Java 反射参数；顺序必须与真实方法声明顺序一致。
        Parameter[] parameters = method.getParameters();
        // 使用和 schema 生成相同的参数名发现器，保证模型看到的字段名和执行时读取的字段名一致。
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        // 反射调用需要 Object[]，长度必须等于方法参数个数。
        Object[] result = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            // 当前 Java 参数对象，包含类型、泛型和注解信息。
            Parameter parameter = parameters[i];
            // 优先使用编译期保留的真实参数名；拿不到时退回 JVM 反射名，例如 arg0。
            String name = names != null && names.length > i ? names[i] : parameter.getName();
            // 从 JSON 参数对象中按字段名取值；path 不存在时返回 MissingNode，便于统一判断。
            JsonNode value = arguments.path(name);
            // ToolParam 决定该参数是否必填，以及 schema 中的说明。
            ToolParam param = parameter.getAnnotation(ToolParam.class);
            if (value.isMissingNode() || value.isNull()) {
                // 没传必填参数时提前失败，不让业务方法收到 null 后产生更隐晦的异常。
                if (param == null || param.required()) {
                    throw new IllegalArgumentException("Missing required tool argument: " + name);
                }
                // 非必填参数缺失时传 null，让业务方法自行应用默认值。
                result[i] = null;
                continue;
            }
            // Jackson 按 Java 参数的完整类型转换，支持 List<Double> 等带泛型的参数。
            result[i] = objectMapper.convertValue(
                    value,
                    objectMapper.getTypeFactory().constructType(parameter.getParameterizedType()));
        }
        // 返回按声明顺序排列的参数数组，供 Method.invoke 使用。
        return result;
    }
}
