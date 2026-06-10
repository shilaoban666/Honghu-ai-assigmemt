package com.honghu.ai.assigment.rag.retrieval.retriever;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.entity.ChatSession;
import com.honghu.ai.assigment.rag.retrieval.pipeline.RagPipeline;
import com.honghu.ai.assigment.rag.retrieval.pipeline.RagRequest;
import com.honghu.ai.assigment.rag.retrieval.pipeline.RagResult;
import com.honghu.ai.assigment.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 Pipeline 的 RAG 检索服务入口。
 *
 * <p>实现 {@link RagRetrievalService}，是项目中唯一对外暴露的 RAG 检索 Bean。
 * 内部委托给 {@link RagPipeline} 编排检索全流程：
 * ScopeResolver → QueryAnalyzer → CandidateRetrieval → Fusion → Fallback → Formatter。</p>
 *
 * <h3>架构关系</h3>
 * <pre>
 *   ChatService
 *      │ 注入 RagRetrievalService（唯一 Bean）
 *      ▼
 *   PipelineRagRetrievalService  ← 当前类（鉴权 + 委托）
 *      │
 *      ▼
 *   RagPipeline                 ← 编排器
 *      │
 *      ▼ 内部根据 mode（keyword/vector/hybrid）选用
 *   RagCandidateRetriever 实现
 *      ├─ KeywordRagCandidateRetriever
 *      └─ VectorRagCandidateRetriever
 * </pre>
 *
 * <h3>检索模式</h3>
 * <p>{@code app.rag.retrieval.mode} 不再决定"选哪个 Bean"，而是决定
 * "Pipeline 内部启用哪些 CandidateRetriever"：</p>
 * <ul>
 *   <li>{@code keyword}：仅关键词召回</li>
 *   <li>{@code vector}：仅向量召回</li>
 *   <li>{@code hybrid}：两路并行 + RRF 融合（推荐）</li>
 * </ul>
 *
 * <h3>向后兼容</h3>
 * <p>旧三参方法 {@code buildContextBlock(userId, sessionId, query)} 内部
 * 自动构造 {@code scopeOverride=SESSION_ONLY} 的 {@link RagRequest}，
 * 保证非流式 chat 路径的旧行为不变。</p>
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>检索前校验 session 归属（sessionId → userId），防止跨用户读取</li>
 *   <li>Pipeline 内部通过 ownerFolder 做二次鉴权过滤</li>
 *   <li>任何异常都返回空上下文，不中断 chat 主链路</li>
 * </ul>
 *
 * @see RagPipeline
 * @see RagRetrievalService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineRagRetrievalService implements RagRetrievalService {

    private final RagPipeline pipeline;
    private final RagProperties ragProperties;
    private final ChatSessionRepository chatSessionRepository;

    /**
     * 三参检索入口（向后兼容旧聊天调用方式）。
     *
     * <p>这是旧版调用链最常用的入口：上层只给出 {@code userId + sessionId + query}，
     * 不关心更细粒度的 {@code chatId}、附件 fileId 列表、topK 覆盖值等参数。
     * 为了让旧代码不需要改动，本方法会把这三项参数包装成一个
     * {@link RagRequest#sessionOnly(String, String, String)}，也就是“仅按 session 检索”的请求，
     * 然后再统一委托给新的 {@link RagPipeline} 执行。</p>
     *
     * <h3>为什么这里还要做一次 session 归属校验</h3>
     * <p>虽然 {@link RagPipeline} 后续还会根据用户解析 {@code ownerFolder} 并在检索层做二次过滤，
     * 但这里的 session 归属校验属于更前置的“入口鉴权”。它的目标是尽早拒绝明显非法请求，
     * 比如用户 A 试图拿用户 B 的 sessionId 来读取会话文档。这样可以减少不必要的数据库和检索开销，
     * 也让日志更清楚地说明“请求为什么在入口就被拒绝”。</p>
     *
     * @param userId 当前调用用户 ID，用来校验 session 是否属于这个用户
     * @param sessionId 当前会话 ID；兼容路径下它同时也是唯一的检索范围依据
     * @param query 用户当前提问文本；为空时说明没有检索价值，直接返回空列表
     * @return 命中的结构化片段列表；任一前置条件不满足时返回空列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<RagSnippet> retrieveBySession(String userId, String sessionId, String query) {
        // 第一层快速退出：如果当前环境根本没启用 RAG，或者只允许入库不允许在线检索，就直接返回空列表。
        if (!ragProperties.isEnabled() || !ragProperties.getRetrieval().isEnabled()) return List.of();

        // 第二层快速退出：三参路径至少要有 userId、sessionId、query，缺一都无法安全且有意义地检索。
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(query)) return List.of();

        // 鉴权：先从数据库拿到会话，再确认这个 session 的 owner 就是当前 userId。
        // 这样可以把“跨用户拿别人会话做检索”的请求挡在 pipeline 之外。
        Optional<ChatSession> s = chatSessionRepository.findById(sessionId);
        if (s.isEmpty() || !Objects.equals(s.get().getUserId(), userId)) {
            log.warn("拒绝跨归属 RAG 检索: userId={}, sessionId={}", userId, sessionId);
            return List.of();
        }

        // 兼容旧路径：把散落的三个参数封装成强类型 RagRequest，再交给统一编排器执行。
        return pipeline.execute(RagRequest.sessionOnly(userId, sessionId, query)).getSnippets();
    }

    /**
     * 三参上下文块构造入口（向后兼容旧聊天调用方式）。
     *
     * <p>与 {@link #retrieveBySession(String, String, String)} 的区别是：
     * 前者返回结构化片段列表，便于程序继续处理；本方法直接返回已经格式化好的上下文字符串，
     * 供聊天服务拼进 Prompt。也就是说，本方法更偏“给大模型吃的最终文本”，
     * 而不是“给程序继续加工的中间结果”。</p>
     *
     * @param userId 当前调用用户 ID
     * @param sessionId 当前会话 ID
     * @param query 用户当前提问文本
     * @return 已格式化好的 RAG 上下文块；不满足检索条件时返回空字符串
     */
    @Override
    public String buildContextBlock(String userId, String sessionId, String query) {
        // 与 retrieveBySession 一样，先做开关和必要参数校验，避免无意义进入 pipeline。
        if (!ragProperties.isEnabled() || !ragProperties.getRetrieval().isEnabled()) return "";
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(query)) return "";

        // 这里同样先校验 session 归属，保证旧三参路径和增强版强类型路径拥有同等安全级别。
        Optional<ChatSession> chatSession = chatSessionRepository.findById(sessionId);
        if (chatSession.isEmpty() || !Objects.equals(chatSession.get().getUserId(), userId)) return "";

        // 最终真正的上下文拼装逻辑不放在这里实现，而是完全复用统一的 pipeline 输出。
        return pipeline.execute(RagRequest.sessionOnly(userId, sessionId, query)).getContextBlock();
    }

    /**
     * 增强版上下文块构造（强类型 RagRequest）。
     *
     * <p>当 ChatService 传入 {@link RagRequest} 时走此路径，
     * 利用 chatId + attachmentFileIds 做精确作用域检索。
     * 入口处先做 session 归属鉴权，再委托给 Pipeline。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public String buildContextBlock(RagRequest request) {
        // 强类型入口先判断 request 是否为空，避免后续直接取字段产生空指针。
        if (request == null) return "";

        // 即使是增强版请求，也仍然必须具备最核心的 userId、sessionId、query 三要素。
        if (!StringUtils.hasText(request.getUserId())
                || !StringUtils.hasText(request.getSessionId())
                || !StringUtils.hasText(request.getQuery())) return "";

        // 鉴权：session 必须归属当前 userId（与三参方法保持同等安全级别）。
        // 这样无论调用方走旧接口还是新接口，安全规则都一致，不会出现“新接口更松”的漏洞。
        Optional<ChatSession> s = chatSessionRepository.findById(request.getSessionId());
        if (s.isEmpty() || !Objects.equals(s.get().getUserId(), request.getUserId())) {
            log.warn("拒绝跨归属 RAG 检索: userId={}, sessionId={}",
                    request.getUserId(), request.getSessionId());
            return "";
        }

        // 通过入口鉴权后，完整交给 pipeline：后续 attachment/chat/session 作用域选择、融合、fallback 都在那里完成。
        return pipeline.execute(request).getContextBlock();
    }

    /**
     * 完整检索入口（返回结构化结果）。
     *
     * <p>接受完整 RagRequest，返回包含 contextBlock + snippets + metrics 的 RagResult。
     * 上层若需要 snippets 详情或 metrics 可调用此方法；
     * 仅需 prompt 文本时直接用 {@link #buildContextBlock(RagRequest)} 即可。</p>
     *
     * @param request 完整的 RAG 请求
     * @return RagResult，非法或无命中时返回 {@link RagResult#empty()}
     */
    @Transactional(readOnly = true)
    public RagResult retrieve(RagRequest request) {
        // 完整结果接口用于需要 contextBlock + snippets + metrics 的调用方，所以这里返回 RagResult 而不是字符串。
        if (request == null) return RagResult.empty();

        // 鉴权：如果请求里同时带有 userId 与 sessionId，就先校验 session 归属。
        // 这里故意不在缺字段时抛异常，而是交给 pipeline 统一走“空结果兜底”策略。
        if (StringUtils.hasText(request.getUserId()) && StringUtils.hasText(request.getSessionId())) {
            Optional<ChatSession> s = chatSessionRepository.findById(request.getSessionId());
            if (s.isEmpty() || !Objects.equals(s.get().getUserId(), request.getUserId())) {
                log.warn("拒绝跨归属 RAG 检索: userId={}, sessionId={}",
                        request.getUserId(), request.getSessionId());
                return RagResult.empty();
            }
        }

        // 入口层只负责鉴权与委托；真正的检索执行、容错、日志与 metrics 都由 pipeline 负责。
        return pipeline.execute(request);
    }
}
