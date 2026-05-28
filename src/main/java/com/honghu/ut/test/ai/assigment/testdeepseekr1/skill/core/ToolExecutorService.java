package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.ToolParam;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.ToolInvocationLog;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.ToolInvocationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;

/**
 * 工具执行服务，负责把已解析的工具定义真正调用到 Java 方法上。
 *
 * <p>模型侧传入的是工具名和 JSON arguments，运行时解析阶段已经把工具名绑定成
 * {@link ResolvedTool}，其中包含 Spring Bean、反射 {@link Method}、参数 schema 和技能归属。
 * 本服务负责把 JSON arguments 转换为 Java 方法参数，设置线程级调用上下文，反射执行
 * {@code @NativeTool} 方法，并把调用结果或异常统一写入 {@code tool_invocation_log}。</p>
 *
 * <p>这里是工具系统的审计边界：无论调用成功、失败、缺少参数还是业务方法抛错，都会记录用户、
 * 会话、消息、工具名、耗时、状态和结果预览。业务工具不需要自己写调用日志。</p>
 */
@Service
@RequiredArgsConstructor
public class ToolExecutorService {
    // 工具调用日志仓库；无论成功失败都会在 finally 中落一条审计记录。
    private final ToolInvocationLogRepository logRepository;
    // Jackson 同时负责解析模型传来的 arguments，以及把工具结果压成日志 preview。
    private final ObjectMapper objectMapper;
    // 参数名发现器要和 BuiltinToolIntrospector 保持一致，否则 schema 名和执行取参名会对不上。
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 执行一个已经解析完成的工具。
     *
     * @param tool 运行时工具定义，包含真实 Spring Bean 和方法
     * @param argumentsJson 模型生成的 JSON 参数对象字符串，允许为空
     * @param context 后端附加的用户、会话和消息上下文，可为空
     * @return 工具方法返回值，会交回 Spring AI 作为工具调用结果
     * @throws IllegalStateException 当参数解析、反射调用或工具业务逻辑失败时抛出
     */
    public Object execute(ResolvedTool tool, String argumentsJson, ToolExecutionContext context) {
        // 用纳秒计时，最后转换为毫秒写入日志，便于后续统计慢工具。
        long start = System.nanoTime();
        // 默认认为成功；只有 catch 到异常才改成 ERROR。
        String status = "SUCCESS";
        // resultPreview 只保存截断后的返回内容，避免大结果撑爆日志表。
        String resultPreview = null;
        // errorMessage 保存根因消息，方便调用日志侧边栏展示失败原因。
        String errorMessage = null;
        try {
            // 把当前调用上下文放进 ThreadLocal，内置工具内部需要 user/session 时可以读取。
            ToolExecutionContextHolder.set(context);
            // 模型可能传空参数；空参数按空 JSON 对象处理，兼容无参工具。
            JsonNode arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argumentsJson);
            // 按 Java 方法签名把 JSON 参数转换成反射调用需要的 Object[]。
            Object[] args = convertArguments(tool.method(), arguments);
            // 这里是真正执行工具的地方：调用 Spring Bean 上的 @NativeTool 方法。
            Object result = tool.method().invoke(tool.bean(), args);
            // 工具结果可能很大，只保存 JSON 字符串的前 2KB 作为审计预览。
            resultPreview = preview(objectMapper.writeValueAsString(result));
            return result;
        } catch (Exception e) {
            status = "ERROR";
            // 反射异常通常会包一层 InvocationTargetException；优先取 cause 才是业务真实错误。
            Throwable root = e.getCause() == null ? e : e.getCause();
            errorMessage = root.getMessage();
            throw new IllegalStateException("Tool call failed: " + tool.qualifiedName() + " - " + errorMessage, root);
        } finally {
            // ThreadLocal 必须清理，否则 Web 线程复用时可能把上一个用户上下文泄漏给下一次调用。
            ToolExecutionContextHolder.clear();
            // finally 中统一写日志，确保成功、失败、缺参、反射异常都能被审计。
            logRepository.save(ToolInvocationLog.builder()
                    .userId(context == null ? null : context.userId())
                    .sessionId(context == null ? null : context.sessionId())
                    .messageId(context == null ? null : context.messageId())
                    .skillId(tool.skillId())
                    .toolQualifiedName(tool.qualifiedName())
                    .arguments(argumentsJson)
                    .resultPreview(resultPreview)
                    .durationMs((int) ((System.nanoTime() - start) / 1_000_000L))
                    .status(status)
                    .errorMessage(errorMessage)
                    .build());
        }
    }

    private Object[] convertArguments(Method method, JsonNode arguments) {
        // 反射调用要求参数顺序和方法签名完全一致，因此按 method.getParameters() 顺序构造 Object[]。
        Parameter[] parameters = method.getParameters();
        // 参数名必须和启动期生成 schema 时一致，否则模型传来的 JSON 字段无法匹配到方法参数。
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        Object[] result = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String name = names != null && names.length > i ? names[i] : parameter.getName();
            // 从 JSON arguments 中按参数名取值；缺失字段会返回 MissingNode。
            JsonNode value = arguments.path(name);
            ToolParam param = parameter.getAnnotation(ToolParam.class);
            if (value.isMissingNode() || value.isNull()) {
                if (param == null || param.required()) {
                    // 必填参数缺失时立刻失败，避免工具方法内部拿到 null 后产生更隐晦的错误。
                    throw new IllegalArgumentException("Missing required tool argument: " + name);
                }
                // 非必填参数缺失时传 null，让工具方法自己决定默认行为。
                result[i] = null;
                continue;
            }
            // 使用 Jackson 按 Java 泛型类型转换，List<Double> 等参数也能正确还原。
            result[i] = objectMapper.convertValue(value, objectMapper.getTypeFactory().constructType(parameter.getParameterizedType()));
        }
        return result;
    }

    private String preview(String text) {
        if (text == null) {
            return null;
        }
        // 日志预览最多 2KB：既能看清结果大意，又不会把 tool_invocation_log 写成大对象存储。
        return text.length() > 2048 ? text.substring(0, 2048) : text;
    }
}
