package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.retriever;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.scope.EffectiveScope;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.retriever.RagRetrievalService.RagSnippet;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Milvus 向量的候选检索器。
 *
 * <p>通过 DashScope embeddings + Milvus 做语义相似度检索。
 * 与 {@link KeywordRagCandidateRetriever} 互补：
 * keyword 侧重精确匹配，vector 侧重语义相似。</p>
 *
 * <h3>Scope 过滤策略</h3>
 * <p>不同 scope 类型使用不同的 Milvus filter 表达式：</p>
 * <table>
 *   <tr><th>ScopeType</th><th>Filter 方式</th><th>说明</th></tr>
 *   <tr><td>FILE_IDS</td><td>documentId in [...]</td><td>先从 Postgres 查 documentIds，再拼接 filter</td></tr>
 *   <tr><td>CHAT</td><td>documentId in [...]</td><td>同上，不直接依赖 chatId metadata</td></tr>
 *   <tr><td>SESSION</td><td>sessionId == 'xxx'</td><td>直接使用 sessionId filter（旧行为）</td></tr>
 *   <tr><td>EMPTY</td><td>—</td><td>返回空</td></tr>
 * </table>
 *
 * <h3>安全注意事项</h3>
 * <ul>
 *   <li>所有 Milvus filter 值都经过严格转义（{@code strictEscape}），拒绝单引号/反斜杠/控制字符</li>
 *   <li>FILE_IDS / CHAT 作用域先查 Postgres 拿 documentIds，不在 Milvus filter 中拼接原始 fileId/chatId</li>
 *   <li>第一期不依赖 Milvus metadata 中的 chatId 字段（该字段可能不存在）</li>
 * </ul>
 *
 * <h3>容错策略</h3>
 * <ul>
 *   <li>Milvus 不可用 / Embedding 调用失败 → 返回空列表，不中断 Pipeline</li>
 *   <li>distance 解析失败 → 保守取值 1.0（几乎无相关性），避免脏数据伪装高相关</li>
 * </ul>
 *
 * @see KeywordRagCandidateRetriever
 * @see RagCandidateRetriever
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRagCandidateRetriever implements RagCandidateRetriever {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final RagDocumentRepository documentRepository;
    private final RagProperties ragProperties;

    /**
     * 返回当前检索器的策略名称。
     *
     * <p>Pipeline 会用这个名字标记候选来源，也会在日志中记录每种检索策略的表现。
     * 当 hybrid 检索同时启用 keyword 与 vector 时，这个字段能帮助判断最终上下文主要来自哪条路径。</p>
     *
     * @return 固定返回 {@code "vector"}
     */
    @Override
    public String strategyName() {
        return "vector";
    }

    /**
     * 按作用域执行向量检索。
     *
     * @param scope 解析后的作用域
     * @param query 用户查询文本
     * @param topK  期望返回数
     * @return 候选列表，异常时返回空列表
     */
    @Override
    public List<RetrievalCandidate> retrieve(EffectiveScope scope, String query, int topK) {
        // 空 query 无法生成有意义的向量检索请求，直接返回空列表。
        if (!StringUtils.hasText(query)) return List.of();

        // 先根据作用域构造 Milvus filter；如果当前 scope 不足以形成合法过滤条件，则不做检索。
        String filter = buildFilter(scope);
        if (filter == null) return List.of();

        // 构造统一的向量检索请求：包含 query、topK、相似度阈值和过滤表达式。
        SearchRequest req = SearchRequest.query(query)
                .withTopK(Math.max(1, topK))
                .withSimilarityThreshold(ragProperties.getRetrieval().getSimilarityThreshold())
                .withFilterExpression(filter);

        // VectorStore 通过 ObjectProvider 延迟获取，允许在某些环境中完全不启用向量检索相关 Bean。
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("向量检索已请求但 VectorStore 未启用，请设置 SPRING_AI_OPENAI_EMBEDDING_ENABLED=true 并配置 ALIYUN_API_KEY");
            return List.of();
        }
        try {
            // 调用底层向量库做相似度搜索，并把返回的 Document 映射成 Pipeline 统一候选模型。
            return vectorStore.similaritySearch(req).stream()
                    .map(this::toCandidate)
                    .toList();
        } catch (Exception ex) {
            log.warn("向量检索失败: scope={}, err={}", scope.getPrimaryType(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 构建 Milvus filter 表达式。
     *
     * <p>所有 filter 值都经过 strictEscape 转义。
     * FILE_IDS / CHAT 作用域先查 Postgres 获取 documentIds，
     * 避免直接依赖 Milvus metadata 中的 chatId（可能缺失）。</p>
     *
     * @return filter 表达式字符串，无有效过滤条件时返回 null
     */
    private String buildFilter(EffectiveScope scope) {
        // ownerFolder 是所有检索路径都必须携带的安全过滤条件，先统一做严格转义。
        String safeOwner = strictEscape(scope.getOwnerFolder());
        return switch (scope.getPrimaryType()) {
            case FILE_IDS -> {
                if (CollectionUtils.isEmpty(scope.getFileIds())) yield null;
                // FILE_IDS 场景下，先从关系库把 fileId 映射成 documentId，再在向量库里按 documentId 过滤。
                List<Long> docIds = documentRepository.findDocumentIdsByFileIdsAndOwner(
                        scope.getFileIds(), scope.getOwnerFolder(), RagDocument.Status.INDEXED);
                if (docIds.isEmpty()) yield null;
                String ids = docIds.stream()
                        .map(String::valueOf)
                        .map(this::strictEscape)
                        .collect(Collectors.joining("','", "'", "'"));
                yield "documentId in [" + ids + "] && ownerFolder == '" + safeOwner + "' && status == 'INDEXED'";
            }
            case CHAT -> {
                if (scope.getChatId() == null) yield null;
                // CHAT 场景同理，优先依赖关系库确定本 chat 关联的文档，再转成向量检索过滤条件。
                List<Long> docIds = documentRepository.findDocumentIdsByChatIdAndOwner(
                        scope.getChatId(), scope.getOwnerFolder(), RagDocument.Status.INDEXED);
                if (docIds.isEmpty()) yield null;
                String ids = docIds.stream()
                        .map(String::valueOf)
                        .map(this::strictEscape)
                        .collect(Collectors.joining("','", "'", "'"));
                yield "documentId in [" + ids + "] && ownerFolder == '" + safeOwner + "' && status == 'INDEXED'";
            }
            case SESSION -> {
                if (!StringUtils.hasText(scope.getSessionId())) yield null;
                // SESSION 场景可以直接利用元数据中的 sessionId 过滤，是最宽但最稳定的兜底路径。
                yield "sessionId == '" + strictEscape(scope.getSessionId())
                        + "' && ownerFolder == '" + safeOwner + "' && status == 'INDEXED'";
            }
            case EMPTY -> null;
        };
    }

    /**
     * 将 Milvus 返回的 Spring AI Document 转为统一候选。
     *
     * <p>distance 越小表示越相似，这里转换为 score = 1 - distance 的"越大越相关"模型。</p>
     */
    private RetrievalCandidate toCandidate(Document doc) {
        // 取出向量库返回的 metadata；documentId、chunkIndex、distance 等信息都在这里。
        Map<String, Object> meta = doc.getMetadata();
        Object dv = meta.get("distance");

        // distance 越小表示越相似，这里统一转换成“分数越大越相关”的模型，便于 Pipeline 后续排序。
        double dist = dv instanceof Number n ? n.doubleValue() : parseDouble(dv);
        double score = Math.max(0.0, 1.0 - dist);
        RagSnippet snippet = new RagSnippet(
                toLong(meta.get("documentId")),
                String.valueOf(meta.getOrDefault("fileName", "")),
                String.valueOf(meta.getOrDefault("fileType", "")),
                toInt(meta.get("chunkIndex")),
                doc.getContent(),
                score);
        return RetrievalCandidate.builder().snippet(snippet).source("vector").rawScore(score).build();
    }

    /**
     * 严格拒绝危险字符，防止拼接 Milvus filter 表达式时出现注入。
     *
     * <p>sessionId / ownerFolder 本不应包含单引号、反斜杠或控制字符；
     * 一旦发现直接抛出异常，视为非法输入。</p>
     */
    private String strictEscape(String raw) {
        if (raw == null) return "";

        // 单引号和反斜杠会直接影响 Milvus filter 表达式语义；控制字符则可能污染解析。
        // 这里选择“发现即拒绝”，而不是尝试自动修复，避免把非法输入静默带入查询。
        if (raw.indexOf('\'') >= 0 || raw.indexOf('\\') >= 0
                || raw.matches(".*[\\x00-\\x1f].*")) {
            throw new IllegalArgumentException("非法 filter 值: " + raw);
        }
        return raw;
    }

    /**
     * 将 metadata 中的 documentId 转回 Long。
     *
     * <p>Milvus JSON metadata 回读后，documentId 可能是数字，也可能是字符串。
     * 这里统一转成 Long，供 {@link RagSnippet} 保存文档主键。
     * null 表示命中结果缺少该元数据，继续返回候选但文档 ID 为空。</p>
     *
     * @param v metadata 原始值
     * @return Long 类型 documentId；原值为 null 时返回 null
     */
    private static Long toLong(Object v) {
        // null 表示 metadata 缺失该字段；否则统一按字符串语义转成 Long。
        return v == null ? null : Long.valueOf(v.toString());
    }

    /**
     * 将 metadata 中的 chunkIndex 转回 Integer。
     *
     * <p>chunkIndex 用来告诉后续展示层“命中的是文档第几个分块”。
     * Milvus metadata 回读后类型不一定稳定，因此这里统一通过字符串形式转换。</p>
     *
     * @param v metadata 原始值
     * @return Integer 类型 chunkIndex；原值为 null 时返回 null
     */
    private static Integer toInt(Object v) {
        // 与 toLong 同理，统一通过字符串解析兼容不同来源的 metadata 类型。
        return v == null ? null : Integer.valueOf(v.toString());
    }

    /**
     * 尝试解析 metadata 中的 distance；解析失败时返回 1.0（等价于"几乎无相关性"）。
     *
     * <p>选择最保守的默认值，避免脏数据被误判成高相关命中。</p>
     */
    private static double parseDouble(Object v) {
        // 元数据里没有 distance 时，保守按“最不相似”处理。
        if (v == null) return 1.0;
        try { return Double.parseDouble(v.toString()); }
        // 脏数据同样按最不相似处理，避免错误值抬高候选排序。
        catch (NumberFormatException e) { return 1.0; }
    }
}
