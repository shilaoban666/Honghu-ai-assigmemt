package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retiriever;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.RagSnippetFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词版 RAG 检索实现。
 *
 * <p>这是当前项目里最朴素、也最稳定的一条召回链路：不依赖向量库、不依赖 embedding，
 * 而是基于数据库里已经落盘的 chunk 文本做候选召回和本地打分。</p>
 *
 * <p>它的整体思路可以概括为：</p>
 * <ol>
 *   <li>先做 RAG 开关、参数、会话归属校验；</li>
 *   <li>从数据库中取出该会话下的候选 chunk；</li>
 *   <li>从用户问题里抽取关键词；</li>
 *   <li>根据“完整命中、关键词命中、文件名命中、前部命中”等规则打分；</li>
 *   <li>返回得分最高的前若干条结果。</li>
 * </ol>
 *
 * <p>它最大的优势是：实现简单、可解释性强、没有额外向量基础设施依赖，
 * 因此也被保留为默认兜底模式。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rag.retrieval.mode", havingValue = "keyword", matchIfMissing = true)
public class KeywordRagRetrievalService implements RagRetrievalService {

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N}_-]{2,}");
    private static final int MAX_SUB_KEYWORDS_PER_TOKEN = 8;

    private final RagProperties ragProperties;
    private final RagDocumentChunkRepository ragDocumentChunkRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;
    private final RagSnippetFormatter formatter;

    @Override
    @Transactional(readOnly = true)
    public List<RagSnippet> retrieveBySession(String userId, String sessionId, String query) {
        // 第一层总开关：允许通过配置整体关闭 RAG 检索能力。
        if (!ragProperties.isEnabled() || !ragProperties.getRetrieval().isEnabled()) {
            return List.of();
        }
        // 必要参数任一为空时，直接返回空结果，避免后续做无意义数据库查询。
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(query)) {
            return List.of();
        }

        // 校验 session 是否真实存在，且必须归属于当前用户。
        // 这是检索侧的重要权限边界，避免跨用户读取他人上传文档。
        Optional<ChatSession> sessionOptional = chatSessionRepository.findById(sessionId);
        if (sessionOptional.isEmpty() || !Objects.equals(sessionOptional.get().getUserId(), userId)) {
            log.warn("拒绝跨归属 RAG 检索: userId={}, sessionId={}", userId, sessionId);
            return List.of();
        }

        // 上传落盘路径是按 username 分目录的，所以这里要把 userId 反查成 username。
        String ownerFolder = userRepository.findById(userId).map(User::getUsername).orElse(null);
        if (!StringUtils.hasText(ownerFolder)) {
            return List.of();
        }

        // 先从数据库取回候选 chunk，而不是全表扫描所有文档。
        // 这里的 candidateLimit 用来限制粗召回规模，控制后续本地打分成本。
        List<RagDocumentChunk> candidates = ragDocumentChunkRepository.findCandidateChunksBySessionIdAndOwner(
                sessionId,
                ownerFolder,
                RagDocument.Status.INDEXED,
                PageRequest.of(0, Math.max(1, ragProperties.getRetrieval().getCandidateLimit()))
        );
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 从用户问题中提取关键词，既保留完整 token，也会对中文词做子串扩展。
        List<String> keywords = extractKeywords(query);

        // 对每个候选 chunk 打分、过滤无分结果、按得分倒序排序，再截取 topK。
        return candidates.stream()
                .map(chunk -> toSnippet(chunk, keywords, query))
                .filter(snippet -> snippet.score() > 0)
                .sorted(Comparator.comparingDouble(RagSnippet::score).reversed())
                .limit(Math.max(1, ragProperties.getRetrieval().getTopK()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String buildContextBlock(String userId, String sessionId, String query) {
        // 先取结构化命中结果。
        List<RagSnippet> snippets = retrieveBySession(userId, sessionId, query);
        if (snippets.isEmpty()) {
            return "";
        }
        // 再交给统一 formatter 拼成上下文块，避免格式逻辑分散在多个检索实现里。
        return formatter.format(snippets, ragProperties.getRetrieval().getMaxContextCharacters());
    }

    private RagSnippet toSnippet(RagDocumentChunk chunk, List<String> keywords, String fullQuery) {
        // 取出正文与文件名，它们是后续打分最核心的两个来源。
        String content = chunk.getContent();
        String fileName = chunk.getDocument().getFileName();

        // 没有正文内容的 chunk 没有检索价值，但仍然返回统一结构，分数记为 0。
        if (!StringUtils.hasText(content)) {
            return new RagSnippet(chunk.getDocument().getDocumentId(), fileName, chunk.getDocument().getFileType(), chunk.getChunkIndex(), "", 0);
        }

        // 统一转小写，减少英文场景下大小写差异对 contains 判断的影响。
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerQuery = fullQuery.toLowerCase(Locale.ROOT);
        String lowerFileName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        double score = 0;

        // 如果正文包含完整 query，给予较高基础分。
        if (lowerContent.contains(lowerQuery)) {
            score += 20;
        }
        // 文件名中命中完整 query 也加分，但权重低于正文命中。
        if (StringUtils.hasText(lowerFileName) && lowerFileName.contains(lowerQuery)) {
            score += 8;
        }

        for (String keyword : keywords) {
            // 跳过空关键词，避免无意义计算。
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);

            // 关键词在正文中首次出现的位置越靠前，通常越可能是主题句，因此额外加权。
            int firstIndex = lowerContent.indexOf(lowerKeyword);
            if (firstIndex >= 0) {
                score += 5;
                if (firstIndex < 120) {
                    score += 1.5;
                }
                int nextSearchFrom = firstIndex + lowerKeyword.length();
                if (nextSearchFrom < lowerContent.length() && lowerContent.indexOf(lowerKeyword, nextSearchFrom) >= 0) {
                    score += 1;
                }
            }

            // 文件名命中关键词也给少量补充分，帮助“按文件名找资料”这种场景。
            if (StringUtils.hasText(lowerFileName) && lowerFileName.contains(lowerKeyword)) {
                score += 2;
            }
        }

        // 最终把数据库实体转换成统一的 RagSnippet，供上层统一格式化。
        return new RagSnippet(
                chunk.getDocument().getDocumentId(),
                fileName,
                chunk.getDocument().getFileType(),
                chunk.getChunkIndex(),
                content,
                score
        );
    }

    private List<String> extractKeywords(String query) {
        // 用 LinkedHashSet 去重并保留插入顺序，避免重复关键词反复加权。
        Set<String> keywords = new LinkedHashSet<>();
        Matcher matcher = KEYWORD_PATTERN.matcher(query);

        // 最短关键词长度通过配置控制，但最低不小于 2，避免 1 个字符噪音太大。
        int minKeywordLength = Math.max(2, ragProperties.getRetrieval().getMinKeywordLength());
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= minKeywordLength) {
                // 保留完整 token。
                keywords.add(token);
                // 对中文 token 再生成若干子关键词，提高中文长词的召回率。
                keywords.addAll(buildChineseSubKeywords(token, minKeywordLength));
            }
        }

        // 如果正则一个关键词都没抓到，就退化使用整句 query，避免结果永远为空。
        if (keywords.isEmpty()) {
            keywords.add(query.trim());
        }
        return new ArrayList<>(keywords);
    }

    private List<String> buildChineseSubKeywords(String token, int minKeywordLength) {
        List<String> subKeywords = new ArrayList<>();
        // 只有包含汉字的 token 才做中文子串展开；英文词通常没必要这样拆。
        if (!token.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            return subKeywords;
        }

        // 子关键词最长只取 8 个字符以内，避免展开过大；同时最多收集固定数量，控制成本。
        int limit = Math.min(token.length(), 8);
        for (int length = Math.min(4, limit); length >= minKeywordLength; length--) {
            for (int i = 0; i + length <= token.length() && subKeywords.size() < MAX_SUB_KEYWORDS_PER_TOKEN; i++) {
                // 逐段截取中文子串，例如“数据安全治理”可拆出“数据安全”“安全治理”等片段。
                subKeywords.add(token.substring(i, i + length));
            }
        }
        return subKeywords;
    }
}

