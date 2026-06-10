package com.honghu.ai.assigment.skill.builtin.tools;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.rag.retrieval.retriever.RagRetrievalService;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeSkill;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeTool;
import com.honghu.ai.assigment.skill.builtin.annotation.ToolParam;
import com.honghu.ai.assigment.skill.core.DangerLevel;
import com.honghu.ai.assigment.skill.core.ToolExecutionContext;
import com.honghu.ai.assigment.skill.core.ToolExecutionContextHolder;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 允许模型显式检索 RAG 上下文的内置技能。
 *
 * <p>{@code ChatService} 在模型调用前已经会注入一段轻量 RAG 上下文。这个工具提供第二条显式检索路径：
 * 当模型读完对话后发现还需要文档证据时，可以主动调用本工具重新检索。用户和会话来自
 * {@link ToolExecutionContextHolder} 的可信上下文，不允许模型通过参数传入 userId/sessionId。</p>
 */
@NativeSkill(
        key = "kb",
        displayName = "知识库检索",
        description = "检索当前会话上传文档和知识库片段",
        icon = "KB",
        category = "业务",
        defaultEnabled = true,
        mandatory = false,
        requiredRole = User.UserRole.USER)
@RequiredArgsConstructor
public class KnowledgeBaseTool {

    /** RAG 检索服务，负责真正按用户和会话构造知识库上下文块。 */
    private final RagRetrievalService ragRetrievalService;

    /**
     * 检索当前用户/当前会话可访问的知识库上下文。
     *
     * @param query 检索问题；为空时回退到本轮用户原始问题
     * @return RAG 上下文块和归属信息
     */
    @NativeTool(
            name = "search",
            description = "检索当前用户当前会话可访问的知识库或上传文档片段。不要传 userId/sessionId，后端会使用可信上下文。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> search(
            @ToolParam(description = "检索问题或关键词。留空时使用用户本轮原始问题。", required = false) String query) {
        // 知识库权限依赖当前用户和会话，必须先拿到可信上下文。
        ToolExecutionContext context = requireContext();
        // 如果模型没有显式传 query，就使用本轮用户原始问题作为检索词。
        String effectiveQuery = query == null || query.isBlank() ? context.query() : query;
        if (effectiveQuery == null || effectiveQuery.isBlank()) {
            // 两边都为空时无法检索，直接报参数错误。
            throw new IllegalArgumentException("query must not be blank");
        }
        // 构造格式化 RAG 上下文块；内部会按 userId/sessionId 做范围限制。
        String block = ragRetrievalService.buildContextBlock(context.userId(), context.sessionId(), effectiveQuery);

        // 使用 LinkedHashMap 保持返回字段顺序，便于模型阅读。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", effectiveQuery);
        // 返回 userId/sessionId 方便调试和审计，不包含任何密钥。
        result.put("userId", context.userId());
        result.put("sessionId", context.sessionId());
        // block 可能为空，统一回空字符串避免 JSON null。
        result.put("contextBlock", block == null ? "" : block);
        // hasResult 让模型快速判断是否检索到内容。
        result.put("hasResult", block != null && !block.isBlank());
        return result;
    }

    /**
     * 读取并校验工具调用上下文。
     */
    private ToolExecutionContext requireContext() {
        // 上下文由 AuditingToolCallback / ToolExecutorService 在执行前写入 ThreadLocal。
        ToolExecutionContext context = ToolExecutionContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()
                || context.sessionId() == null || context.sessionId().isBlank()) {
            // 缺少用户或会话时不能检索，否则可能越权或查不到正确数据。
            throw new IllegalStateException("Knowledge base search requires user and session context");
        }
        return context;
    }
}
