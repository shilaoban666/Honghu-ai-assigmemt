package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.ToolInvocationLog;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.ToolInvocationLogRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolAccessDecision;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolGuard;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * 所有“模型可见工具”的审计与策略装饰器。
 *
 * <p>这是技能系统最关键的运行时边界。无论底层工具来自 Java 反射、远程 MCP，还是受限 CLI，
 * 在真正执行前都会先被包进这个装饰器，因此审计、危险等级拦截、上下文注入和错误记录都能走同一套逻辑。</p>
 *
 * <p>本类不关心工具具体做什么，只负责四件事：</p>
 * <ul>
 *     <li>从 Spring AI 的 {@link ToolContext} 中读取可信的 userId、sessionId、messageId 和原始问题。</li>
 *     <li>调用 {@link ToolGuard} 检查危险等级，危险工具没有审批时直接返回 DENIED。</li>
 *     <li>在工具执行期间把上下文放入 {@link ToolExecutionContextHolder}，让内置工具能读取当前用户和会话。</li>
 *     <li>无论成功、失败还是被拒绝，都写入一条 {@code tool_invocation_log} 审计记录。</li>
 * </ul>
 */
public class AuditingToolCallback implements ToolCallback {

    /** 被装饰的真实工具回调，可能是内置 Java 工具、MCP 工具或 CLI 工具。 */
    private final ToolCallback delegate;

    /** 工具的归属、全局名称、来源和危险等级等平台元数据。 */
    private final ToolCallbackRegistration registration;

    /** 统一危险等级守卫，决定本次调用是否允许进入真实工具。 */
    private final ToolGuard toolGuard;

    /** 工具调用审计日志仓库，finally 中必定尝试写入。 */
    private final ToolInvocationLogRepository logRepository;

    /** 用于校验/序列化 JSON 参数，以及生成结构化 DENIED 响应。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建一个审计装饰器。
     *
     * @param registration 工具回调与平台元数据
     * @param toolGuard 危险工具审批守卫
     * @param logRepository 调用日志仓库
     * @param objectMapper JSON 工具
     */
    public AuditingToolCallback(ToolCallbackRegistration registration,
                                ToolGuard toolGuard,
                                ToolInvocationLogRepository logRepository,
                                ObjectMapper objectMapper) {
        // Spring AI 只会调用 ToolCallback；这里先取出真实 callback，后续所有调用都委派给它。
        this.delegate = registration.callback();
        // 保存完整注册信息，方便审计日志写 skillId、工具全名和危险等级。
        this.registration = registration;
        // 保存守卫对象，call(...) 时先检查再执行。
        this.toolGuard = toolGuard;
        // 保存日志仓库，finally 中落库。
        this.logRepository = logRepository;
        // 保存 ObjectMapper，后续参数规范化和 DENIED JSON 都要用。
        this.objectMapper = objectMapper;
    }

    /**
     * 返回工具定义。
     *
     * <p>模型需要看到的名称、描述和参数 schema 都来自真实 callback，本装饰器不能改写这些内容，
     * 否则注册阶段看到的工具和执行阶段调用的工具会不一致。</p>
     */
    @Override
    public ToolDefinition getToolDefinition() {
        // 直接透传真实工具定义，让 Spring AI 正常生成工具列表。
        return delegate.getToolDefinition();
    }

    /**
     * 返回 Spring AI 工具元数据。
     */
    @Override
    public ToolMetadata getToolMetadata() {
        // 元数据仍然以真实 callback 为准，装饰器只增加平台侧的审计与权限逻辑。
        return delegate.getToolMetadata();
    }

    /**
     * 兼容不带 ToolContext 的调用入口。
     *
     * <p>测试或少数框架路径可能只传 JSON 参数，不传上下文；此时仍然要走同一套审计逻辑，
     * 只是审计日志里的 userId/sessionId/messageId 会为空。</p>
     */
    @Override
    public String call(String toolInput) {
        // 统一委派到完整入口，避免两套执行逻辑分叉。
        return call(toolInput, null);
    }

    /**
     * 执行一次工具调用，并在执行前后补齐安全、上下文和审计逻辑。
     *
     * @param toolInput 模型生成的工具参数，理论上是 JSON 对象字符串
     * @param toolContext 后端注入的可信上下文，包含用户、会话、消息和原始问题
     * @return 工具返回给模型的字符串结果
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 用纳秒记录起点，最后转换为毫秒写入审计表；纳秒差值比当前时间戳更适合计算耗时。
        long start = System.nanoTime();
        // 从 ToolContext 提取可信上下文。模型只能控制 toolInput，不能伪造这里的 userId/sessionId。
        ToolExecutionContext executionContext = fromToolContext(toolContext);
        // 只规整一次参数，后续执行和审计落库复用同一份，避免“执行参数”和“日志参数”不一致。
        String normalizedArguments = normalizeArguments(toolInput);
        // 默认按成功处理；后续如果被拒绝或抛异常，再改成 DENIED / ERROR。
        String status = "SUCCESS";
        // resultPreview 只保存截断后的结果摘要，防止大结果撑爆日志表。
        String resultPreview = null;
        // errorMessage 保存被拒绝原因或异常根因；成功时保持 null。
        String errorMessage = null;
        try {
            // 先做危险等级检查。所有来源的工具都必须过这个守卫，不能让 provider 自己决定是否绕过。
            ToolAccessDecision decision = toolGuard.check(registration, executionContext);
            if (!decision.allowed()) {
                // 守卫拒绝时不调用真实工具，避免产生外部副作用。
                status = "DENIED";
                // 拒绝原因既返回给模型，也写入审计表，方便后续定位为什么没有执行。
                errorMessage = decision.reason();
                // 返回结构化 JSON，方便模型把“被拒绝”理解为一种可处理的工具结果，而不是裸字符串。
                String deniedResult = deniedJson(decision.reason());
                // 审计仍然记录返回给模型的简短结果。
                resultPreview = preview(deniedResult);
                return deniedResult;
            }
            // 把可信上下文放入 ThreadLocal，给 KnowledgeBaseTool / SessionHistoryTool 等内置工具读取。
            ToolExecutionContextHolder.set(executionContext);
            // 进入真实工具。这里传入的是规范化后的 JSON 参数，toolContext 原样传给 Spring AI callback。
            String result = delegate.call(normalizedArguments, toolContext);
            // 成功后截断结果摘要，留给审计和前端工具调用侧边栏使用。
            resultPreview = preview(result);
            return result;
        } catch (Exception ex) {
            // 任何异常都标记为 ERROR，再重新抛出，让上层模型调用链按失败处理。
            status = "ERROR";
            // 反射调用常把真实异常包在 cause 里；取根因消息更利于排查。
            Throwable root = ex.getCause() == null ? ex : ex.getCause();
            errorMessage = root.getMessage();
            // RuntimeException 直接保留类型抛出；受检异常包装成 IllegalStateException。
            throw ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Tool call failed: " + registration.toolQualifiedName(), root);
        } finally {
            // 必须清理 ThreadLocal；Web 容器线程会复用，不清理会把上个用户上下文泄漏给下个请求。
            ToolExecutionContextHolder.clear();
            // 无论 SUCCESS、DENIED 还是 ERROR 都写审计日志，这是工具系统的安全闭环。
            logRepository.save(ToolInvocationLog.builder()
                    // 上下文可能为空，所以每个字段都做空值保护。
                    .userId(executionContext == null ? null : executionContext.userId())
                    .sessionId(executionContext == null ? null : executionContext.sessionId())
                    .messageId(executionContext == null ? null : executionContext.messageId())
                    // skillId 与 toolQualifiedName 用于按技能/工具统计和排查。
                    .skillId(registration.skillId())
                    .toolQualifiedName(registration.toolQualifiedName())
                    // arguments 保存的是规范化后的 JSON，保证数据库 jsonb 字段能接受。
                    .arguments(normalizedArguments)
                    // resultPreview 只保存短结果，不保存完整大对象。
                    .resultPreview(resultPreview)
                    // 纳秒耗时转换为 int 毫秒，过大时饱和到 Integer.MAX_VALUE。
                    .durationMs(toIntDuration(System.nanoTime() - start))
                    .status(status)
                    .errorMessage(errorMessage)
                    .build());
        }
    }

    /**
     * 构造结构化 DENIED 响应，让模型能读懂“工具被策略拒绝”。
     *
     * @param reason 守卫给出的拒绝原因
     * @return JSON 字符串；极端情况下退化为最小 DENIED JSON
     */
    private String deniedJson(String reason) {
        try {
            // 返回 JSON 而不是抛异常，是为了让模型可以继续解释“需要审批”这件事。
            return objectMapper.writeValueAsString(Map.of(
                    "status", "DENIED",
                    "error", reason == null ? "Tool call denied by policy." : reason));
        } catch (Exception ignored) {
            // 如果 ObjectMapper 异常，仍然返回可解析的最小 JSON，避免把拒绝结果变成空字符串。
            return "{\"status\":\"DENIED\"}";
        }
    }

    /**
     * 从 Spring AI ToolContext 中提取平台可信上下文。
     *
     * <p>这些值由后端在聊天请求入口写入，不应该由模型通过工具参数传入。</p>
     */
    private ToolExecutionContext fromToolContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            // 没有上下文时返回字段全空的对象，后续代码可以统一判空字段而不是判空对象。
            return new ToolExecutionContext(null, null, null, null);
        }
        // Spring AI 的上下文是 Map，这里只读取本平台约定的几个 key。
        Map<String, Object> values = context.getContext();
        return new ToolExecutionContext(
                // userId：当前登录用户或 guest。
                stringValue(values.get("userId")),
                // sessionId：当前会话，用于会话技能开关和工具审批。
                stringValue(values.get("sessionId")),
                // messageId：触发本次工具调用的用户消息 id。
                longValue(values.get("messageId")),
                // query：本轮原始用户问题，知识库工具留空 query 时会回退使用它。
                stringValue(values.get("query"))
        );
    }

    /**
     * 把上下文 Map 中的任意对象转成字符串。
     */
    private String stringValue(Object value) {
        // null 保持 null，避免写成字符串 "null" 污染审计字段。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 宽松读取 Long 类型上下文字段。
     */
    private Long longValue(Object value) {
        if (value == null) {
            // messageId 不是所有调用路径都有，允许为空。
            return null;
        }
        if (value instanceof Number number) {
            // 如果上游已经放入数字类型，直接取 longValue。
            return number.longValue();
        }
        try {
            // 如果上游以字符串形式放入，也尝试解析。
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            // 无法解析时按缺失处理，不让坏 messageId 影响工具执行。
            return null;
        }
    }

    /**
     * 把模型传来的参数规范成合法 JSON。
     *
     * <p>审计表的 arguments 是 jsonb 字段，非法 JSON 会导致落库失败；这里先兜底为 {}。</p>
     */
    private String normalizeArguments(String input) {
        if (input == null || input.isBlank()) {
            // 空参数按无参工具处理。
            return "{}";
        }
        try {
            // 只校验能否被 Jackson 解析，不改变原始字符串，方便日志保留模型实际参数。
            objectMapper.readTree(input);
            return input;
        } catch (Exception ignored) {
            // 非法 JSON 不继续传给工具，避免 provider 解析失败或 jsonb 落库失败。
            return "{}";
        }
    }

    /**
     * 截断工具结果用于审计预览。
     */
    private String preview(String text) {
        if (text == null) {
            // 工具没有返回内容时，预览字段也保持为空。
            return null;
        }
        // 只保留前 2KB 左右，避免把大段 RAG/MCP 响应写入审计表。
        return text.length() > 2048 ? text.substring(0, 2048) : text;
    }

    /**
     * 将纳秒耗时转换为数据库字段使用的 int 毫秒。
     */
    private int toIntDuration(long nanos) {
        // 审计表只需要毫秒级耗时，纳秒差值除以一百万即可。
        long millis = nanos / 1_000_000L;
        // 极端长任务不让 long 强转 int 溢出，而是饱和到最大值。
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }
}
