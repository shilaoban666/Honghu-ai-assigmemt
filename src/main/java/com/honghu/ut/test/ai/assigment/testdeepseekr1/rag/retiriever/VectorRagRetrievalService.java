package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retiriever;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.generator.RagSnippetFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 DashScope embeddings + Milvus 的向量检索实现。
 *
 * <p>这个类的定位不是“替代一切旧逻辑”，而是在保留 keyword 模式作为回滚路径的前提下，
 * 为 RAG 提供一个可配置启用的语义召回实现。</p>
 *
 * <p><b>设计关���点：</b></p>
 * <ul>
 *     <li>检索前仍然做和 keyword 版同等级别的会话归属校验，不能因为接入向量库就放松权限边界。</li>
 *     <li>鉴权条件（sessionId / ownerFolder / status）会下推到 Milvus filter，避免把别人的数据召回回来再在应用层丢弃。</li>
 *     <li>任何向量检索异常都降级为空上下文，而不是抛 5xx；聊天主链路始终优先保证可用。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rag.retrieval.mode", havingValue = "vector")
public class VectorRagRetrievalService implements RagRetrievalService {

    private final VectorStore vectorStore;
    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;
    private final RagProperties ragProperties;
    private final RagSnippetFormatter formatter;

    @Override
    @Transactional(readOnly = true)
    public List<RagSnippet> retrieveBySession(String userId, String sessionId, String query) {
        // 第一层总开关：允许用配置快速关闭整套 RAG 检索，而不用改代码或移除 Bean。
        if (!ragProperties.isEnabled() || !ragProperties.getRetrieval().isEnabled()) {
            return List.of();
        }

        // 空参直接返回空结果，避免无意义地调用 Milvus / Embedding 服务。
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(query)) {
            return List.of();
        }

        // 第一层鉴权：sessionId 必须归属当前 userId。
        // 如果攻击者仅凭猜到的 sessionId 就能发起检索，将直接越权读到他人资料。
        Optional<ChatSession> session = chatSessionRepository.findById(sessionId);
        if (session.isEmpty() || !Objects.equals(session.get().getUserId(), userId)) {
            log.warn("拒绝跨归属 RAG 检索: userId={}, sessionId={}", userId, sessionId);
            return List.of();
        }

        // 第二层鉴权：ownerFolder 使用的是上传时落到对象路径第一段的 username，
        // 因此这里必须把 userId 翻译成 username 再参与过滤，不能只信 sessionId。
        String ownerFolder = userRepository.findById(userId).map(User::getUsername).orElse(null);
        if (!StringUtils.hasText(ownerFolder)) {
            return List.of();
        }

        final String safeSession;
        final String safeOwner;
        try {
            safeSession = strictEscape(sessionId);
            safeOwner = strictEscape(ownerFolder);
        } catch (IllegalArgumentException ex) {
            // 这里明确返回空，而不是继续尝试“转义后执行”。
            // 原因是这些字段本身是业务主键/目录名，不应包含危险字符；直接拒绝比自制转义更安全。
            log.warn("拒绝非法的向量检索过滤条件: userId={}, sessionId={}, error={}", userId, sessionId, ex.getMessage());
            return List.of();
        }

        // 第三层鉴权下推：让向量库只在“本会话 + 本用户目录 + 已完成索引”的数据里做相似度搜索。
        // 这样不仅更安全，也能缩小搜索空间，减少误召回。
        SearchRequest request = SearchRequest.query(query)
                .withTopK(Math.max(1, ragProperties.getRetrieval().getTopK()))
                .withSimilarityThreshold(ragProperties.getRetrieval().getSimilarityThreshold())
                .withFilterExpression(
                        "sessionId == '" + safeSession + "' && " +
                                "ownerFolder == '" + safeOwner + "' && " +
                                "status == 'INDEXED'");

        try {
            return vectorStore.similaritySearch(request).stream()
                    .map(this::toSnippet)
                    .toList();
        } catch (Exception ex) {
            // 这是刻意的“读侧降级”：Milvus 抖动、Embedding 调用失败、网络抖动等场景下，
            // 本轮只是不带 RAG ��下文，并不应该拖垮 chat 主流程。
            log.warn("向量检索失败，本轮无 RAG 上下文: userId={}, sessionId={}, err={}",
                    userId, sessionId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public String buildContextBlock(String userId, String sessionId, String query) {
        // Vector 实现与 keyword 实现都统一交给 formatter 生成上下文块，
        // 这样 prompt 包装、安全提示、长度裁剪策略都只有一份真相来源。
        List<RagSnippet> snippets = retrieveBySession(userId, sessionId, query);
        if (snippets.isEmpty()) {
            return "";
        }
        return formatter.format(snippets, ragProperties.getRetrieval().getMaxContextCharacters());
    }

    /**
     * 把向量库返回的 Document 转为业务侧统一的 RagSnippet。
     *
     * <p>Spring AI 的 Milvus 实现会把“距离”注入 metadata 中；对于余弦相似度场景，
     * 这里把距离重新折算成一个更直观的相关度分数（1 - distance）。</p>
     */
    private RagSnippet toSnippet(Document doc) {
        // 从 Spring AI Document 的 metadata 中取出向量库回传的附加字段。
        Map<String, Object> metadata = doc.getMetadata();

        // Milvus / Spring AI 通常会把“距离”放进 metadata；距离越小表示越相似。
        Object distanceValue = metadata.get("distance");
        double distance = distanceValue instanceof Number number ? number.doubleValue() : parseDouble(distanceValue);

        // 对外统一转换成“越大越相关”的分数，方便与 keyword 结果保持一致的认知模型。
        double score = Math.max(0.0, 1.0 - distance);

        // 把向量检索结果映射成业务层统一的 RagSnippet，对上层屏蔽底层向量库细节。
        return new RagSnippet(
                toLong(metadata.get("documentId")),
                String.valueOf(metadata.getOrDefault("fileName", "")),
                String.valueOf(metadata.getOrDefault("fileType", "")),
                toInt(metadata.get("chunkIndex")),
                doc.getContent(),
                score
        );
    }

    /**
     * 严格拒绝危险字符，防止拼接 Milvus filter 表达式时出现注入。
     *
     * <p>这里没有做“宽松转义”，是因为 sessionId / ownerFolder 本来就不应该包含这类字符；
     * 如果出现，直接视为非法输入更符合安全边界。</p>
     */
    private static String strictEscape(String raw) {
        // null 这里统一转为空串，避免后续字符串拼接直接 NPE。
        if (raw == null) {
            return "";
        }
        // 单引号、反斜杠、控制字符都可能干扰 filter 表达式语法，因此直接拒绝。
        if (raw.indexOf('\'') >= 0 || raw.indexOf('\\') >= 0 || raw.matches(".*[\\x00-\\x1f].*")) {
            throw new IllegalArgumentException("非法的 filter 值");
        }
        // 通过校验后原样返回，不做自定义转义，保持规则简单可审计。
        return raw;
    }

    /** 将 metadata 中的 documentId 转回 Long。 */
    private static Long toLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    /** 将 metadata 中的 chunkIndex 转回 Integer。 */
    private static Integer toInt(Object value) {
        return value == null ? null : Integer.valueOf(value.toString());
    }

    /**
     * 尝试��� metadata 中解析 distance；解析失败时返回 1.0，等价于“几乎无相关性”。
     *
     * <p>之所以选择最保守的默认值，而不是 0.0，是为了避免脏数据被误判成高相关命中。</p>
     */
    private static double parseDouble(Object value) {
        if (value == null) {
            return 1.0;
        }
        try {
            // 尝试把 metadata 中的距离值转成 double。
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            // 一旦格式异常，保守地把它视为“距离很远”，避免脏数据伪装成高相关结果。
            return 1.0;
        }
    }
}


