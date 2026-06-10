package com.honghu.ai.assigment.rag.retrieval.retriever;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.entity.RagDocument;
import com.honghu.ai.assigment.entity.RagDocumentChunk;
import com.honghu.ai.assigment.rag.retrieval.scope.EffectiveScope;
import com.honghu.ai.assigment.rag.retrieval.retriever.RagRetrievalService.RagSnippet;
import com.honghu.ai.assigment.repository.RagDocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于关键词的候选检索器。
 *
 * <p>从 PostgreSQL {@code rag_document_chunk} 表按作用域查询候选 chunk，
 * 在应用层做本地关键词匹配打分。这是最稳定、零外部依赖的检索策略，
 * 也是默认模式和 fallback 路径。</p>
 *
 * <h3>检索流程</h3>
 * <ol>
 *   <li>根据 {@link EffectiveScope#getPrimaryType()} 选择查询方法</li>
 *   <li>从 DB 加载候选 chunk（受 {@code candidateLimit} 限制）</li>
 *   <li>提取查询关键词（中文子串展开 + 停用词过滤）</li>
 *   <li>对每个 chunk 做关键词匹配打分</li>
 *   <li>返回所有分数 > 0 的候选</li>
 * </ol>
 *
 * <h3>打分规则</h3>
 * <table>
 *   <tr><th>命中类型</th><th>加分</th><th>说明</th></tr>
 *   <tr><td>正文包含完整 query</td><td>+20</td><td>最高权重，完全匹配</td></tr>
 *   <tr><td>文件名包含完整 query</td><td>+8</td><td>文件名匹配</td></tr>
 *   <tr><td>正文包含关键词</td><td>+5</td><td>每个关键词命中一次</td></tr>
 *   <tr><td>关键词出现在前 120 字符</td><td>+1.5</td><td>前部加权，主题句倾向</td></tr>
 *   <tr><td>关键词出现 2 次以上</td><td>+1</td><td>多次命中加权</td></tr>
 *   <tr><td>文件名包含关键词</td><td>+2</td><td>辅助信号</td></tr>
 * </table>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>✅ 鉴权（通过 ownerFolder）、查询候选 chunk、关键词打分</li>
 *   <li>❌ 不负责 formatter、不拼 prompt、不做 scope fallback</li>
 * </ul>
 *
 * @see VectorRagCandidateRetriever
 * @see RagCandidateRetriever
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRagCandidateRetriever implements RagCandidateRetriever {

    /** 关键词提取正则：匹配汉字、字母、数字、下划线、连字符，至少 2 个字符 */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N}_-]{2,}");

    /** 中文子关键词单 token 最多生成的子串数，控制展开成本 */
    private static final int MAX_SUB_KEYWORDS_PER_TOKEN = 8;

    private final RagDocumentChunkRepository chunkRepository;
    private final RagProperties ragProperties;

    /**
     * 返回当前检索器的策略名称。
     *
     * <p>Pipeline 会把不同检索器的结果放在一起融合、排序。
     * 这个名字用于日志、metrics 和 {@link RetrievalCandidate#getSource()} 标识，
     * 方便后续判断某条候选来自关键词检索还是向量检索。</p>
     *
     * @return 固定返回 {@code "keyword"}
     */
    @Override
    public String strategyName() {
        return "keyword";
    }

    /**
     * 按作用域执行关键词检索。
     *
     * <p>根据 scope 的 primaryType 选择对应的 DB 查询，
     * 然后对候选 chunk 做本地关键词打分。</p>
     *
     * @param scope 解析后的作用域
     * @param query 用户查询文本
     * @param topK  期望返回数（当前实现中候选数由 candidateLimit 控制，topK 在此仅作参考）
     * @return 候选列表
     */
    @Override
    public List<RetrievalCandidate> retrieve(EffectiveScope scope, String query, int topK) {
        // 空 query 不具备关键词检索价值，直接返回空结果。
        if (!StringUtils.hasText(query)) return List.of();

        // 先按作用域从数据库加载一批“可能相关”的 chunk 候选，范围和数量都在这里被初步控制。
        List<RagDocumentChunk> chunks = loadChunks(scope);
        if (chunks.isEmpty()) return List.of();

        // 再从用户问题里抽出关键词，供后续逐 chunk 打分使用。
        List<String> keywords = extractKeywords(query);
        return chunks.stream()
                // 把每个 chunk 转成带分数的 RagSnippet，分数越高表示文本和 query 越相关。
                .map(chunk -> toSnippet(chunk, keywords, query))
                // 分数小于等于 0 说明完全没命中任何有效规则，没有必要进入候选集。
                .filter(s -> s.score() > 0)
                // 最终包装成 Pipeline 统一处理的 RetrievalCandidate，对外标记来源为 keyword。
                .map(s -> RetrievalCandidate.builder().snippet(s).source("keyword").rawScore(s.score()).build())
                .toList();
    }

    /**
     * 根据 scope 类型选择对应的 DB 查询加载候选 chunk。
     *
     * <p>所有查询都携带 ownerFolder 做鉴权，使用 join fetch 避免 N+1。</p>
     */
    private List<RagDocumentChunk> loadChunks(EffectiveScope scope) {
        // candidateLimit 控制“先从数据库拿多少 chunk 来参与本地打分”，避免把整库 chunk 全读出来。
        int limit = Math.max(1, ragProperties.getRetrieval().getCandidateLimit());
        PageRequest page = PageRequest.of(0, limit);
        return switch (scope.getPrimaryType()) {
            // FILE_IDS：最精确，只查本轮附件 fileIds 对应的已索引文档分块。
            case FILE_IDS -> CollectionUtils.isEmpty(scope.getFileIds()) ? List.of()
                    : chunkRepository.findCandidateChunksByFileIdsAndOwner(
                            scope.getFileIds(), scope.getOwnerFolder(), RagDocument.Status.INDEXED, page);
            // CHAT：按当前 chat 关联文档查分块，范围比附件更宽一些。
            case CHAT -> scope.getChatId() == null ? List.of()
                    : chunkRepository.findCandidateChunksByChatIdAndOwner(
                            scope.getChatId(), scope.getOwnerFolder(), RagDocument.Status.INDEXED, page);
            // SESSION：当前设计中的最宽私有检索范围，只按 session 过滤。
            case SESSION -> !StringUtils.hasText(scope.getSessionId()) ? List.of()
                    : chunkRepository.findCandidateChunksBySessionIdAndOwner(
                            scope.getSessionId(), scope.getOwnerFolder(), RagDocument.Status.INDEXED, page);
            // EMPTY：说明没有合法检索范围，直接返回空列表。
            case EMPTY -> List.of();
        };
    }

    /**
     * 从查询文本中提取关键词。
     *
     * <p>对中文 token 做子串展开以提高召回率（如"数据安全治理"→"数据安全"、"安全治理"）。
     * 最短关键词长度由配置控制，但不低于 2。</p>
     */
    private List<String> extractKeywords(String query) {
        // LinkedHashSet 用来同时满足“去重”和“保持提取顺序”两个需求。
        Set<String> keywords = new LinkedHashSet<>();
        if (!StringUtils.hasText(query)) return List.of();

        // 去掉首尾空白，避免空格影响关键词正则匹配结果。
        String trimmed = query.trim();
        Matcher matcher = KEYWORD_PATTERN.matcher(trimmed);

        // 最短关键词长度由配置控制，但最低强制为 2，防止单字造成噪音。
        int minLen = Math.max(2, ragProperties.getRetrieval().getMinKeywordLength());
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= minLen) {
                // 先保留原 token，本身就是最直接的关键词信号。
                keywords.add(token);
                // 如果是中文长词，再额外拆出若干短语子串，提高局部短语召回率。
                keywords.addAll(buildChineseSubKeywords(token, minLen));
            }
        }

        // 若正则完全没抽出词，就退化成整句检索，避免因为抽词失败导致完全无结果。
        if (keywords.isEmpty()) keywords.add(trimmed);
        return new ArrayList<>(keywords);
    }

    /**
     * 对中文 token 生成子关键词。
     *
     * <p>仅对包含汉字的 token 做子串展开，英文词不拆。
     * 子关键词最长 8 字符，最多收集 {@value #MAX_SUB_KEYWORDS_PER_TOKEN} 个。</p>
     */
    private List<String> buildChineseSubKeywords(String token, int minLen) {
        List<String> sub = new ArrayList<>();
        // 纯英文/数字 token 不做中文子串展开，避免产生大量无意义短词。
        if (token.codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            return sub;
        }

        // 最多只对前 8 个字符范围做切分，控制时间成本和结果噪音。
        int lim = Math.min(token.length(), 8);
        for (int len = Math.min(4, lim); len >= minLen; len--) {
            for (int i = 0; i + len <= token.length() && sub.size() < MAX_SUB_KEYWORDS_PER_TOKEN; i++) {
                // 连续子串切分，例如“审批金额上限”可切出“审批金额”“金额上限”等短语。
                sub.add(token.substring(i, i + len));
            }
        }
        return sub;
    }

    /**
     * 对单个 chunk 做关键词匹配打分。
     *
     * <p>统一转小写，减少英文场景下大小写差异影响。</p>
     */
    private RagSnippet toSnippet(RagDocumentChunk chunk, List<String> keywords, String fullQuery) {
        // 拿到当前分块正文与所属文件名，后续打分会同时利用正文和文件名两个信号。
        String content = chunk.getContent();
        String fileName = chunk.getDocument().getFileName();
        if (!StringUtils.hasText(content)) {
            // 空正文直接返回 0 分片段，后续会被外层过滤掉，不再继续做字符串匹配。
            return new RagSnippet(chunk.getDocument().getDocumentId(), fileName,
                    chunk.getDocument().getFileType(), chunk.getChunkIndex(), "", 0);
        }

        // 统一转小写，减少英文大小写差异对 contains/indexOf 命中的影响。
        String lc = content.toLowerCase(Locale.ROOT);
        String lq = fullQuery.toLowerCase(Locale.ROOT);
        String lfn = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        double score = 0;

        // 完整 query 命中
        if (lc.contains(lq)) score += 20;
        if (StringUtils.hasText(lfn) && lfn.contains(lq)) score += 8;

        // 关键词命中
        for (String kw : keywords) {
            if (!StringUtils.hasText(kw)) continue;
            String lk = kw.toLowerCase(Locale.ROOT);
            int fi = lc.indexOf(lk);
            if (fi >= 0) {
                // 命中任意关键词先给一笔基础分。
                score += 5;
                if (fi < 120) score += 1.5;               // 前部加权
                int ns = fi + lk.length();
                if (ns < lc.length() && lc.indexOf(lk, ns) >= 0) score += 1;  // 多次命中
            }
            // 文件名命中通常也是很强的辅助手工信号，所以额外加分。
            if (StringUtils.hasText(lfn) && lfn.contains(lk)) score += 2;
        }

        // 把当前 chunk 最终打分结果封装成 RagSnippet，供 Pipeline 后续统一排序和格式化。
        return new RagSnippet(chunk.getDocument().getDocumentId(), fileName,
                chunk.getDocument().getFileType(), chunk.getChunkIndex(), content, score);
    }
}
