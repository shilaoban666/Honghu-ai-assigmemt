package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retrieval.Augmentation.analyzer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立关键词抽取器。
 *
 * <p>从旧 {@code KeywordRagRetrievalService} 中提取出的独立组件，
 * 供 {@link LightweightRuleQueryAnalyzer} 和 {@code KeywordRagCandidateRetriever} 复用。</p>
 *
 * <h3>抽取策略</h3>
 * <ol>
 *   <li>正则匹配中英文 token（汉字/字母/数字/下划线/连字符，至少 2 字符）</li>
 *   <li>过滤停用词</li>
 *   <li>对中文 token 做子串展开（如"数据安全治理"→"数据安全"、"安全治理"）</li>
 *   <li>无关键词时退化用整句 query</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>不可变字段，线程安全。Pattern 是线程安全的。</p>
 *
 * @see LightweightRuleQueryAnalyzer
 */
public class RagKeywordExtractor {

    /** 关键词提取正则：匹配汉字、字母、数字、下划线、连字符，至少 2 个字符 */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N}_-]{2,}");

    /** 中文子关键词单 token 最多生成的子串数 */
    private static final int MAX_SUB_KEYWORDS_PER_TOKEN = 8;

    private final int minKeywordLength;
    private final Set<String> stopWords;

    /**
     * @param minKeywordLength 最短关键词长度（不低于 2）
     * @param stopWords        停用词集合（可为 null，等价于空集）
     */
    public RagKeywordExtractor(int minKeywordLength, Set<String> stopWords) {
        // 最短关键词长度强制不低于 2，避免抽出单字造成大量噪音命中。
        this.minKeywordLength = Math.max(2, minKeywordLength);
        // 外部没传停用词时，统一视为空集合，减少后续判空分支。
        this.stopWords = stopWords != null ? stopWords : Set.of();
    }

    /**
     * 从查询文本中提取关键词。
     *
     * @param query 用户查询文本
     * @return 关键词列表，无结果时返回仅含原查询的单元素列表
     */
    public List<String> extract(String query) {
        // 用 LinkedHashSet 去重并保留加入顺序，方便后续把前几个关键词当作更高优先级信号使用。
        Set<String> keywords = new LinkedHashSet<>();

        // 空查询没有可抽取内容，直接返回空列表。
        if (query == null || query.isBlank()) return List.of();

        // 去掉首尾空白，避免把空格干扰带进正则匹配流程。
        String trimmed = query.trim();

        // 用统一正则逐个提取可能有意义的 token。
        Matcher matcher = KEYWORD_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            // 当前命中的 token 可能是中文词、英文词、数字串或混合标识符。
            String token = matcher.group();

            // 长度达标且不在停用词中时，才把它视为真正有检索价值的关键词。
            if (token.length() >= minKeywordLength && !stopWords.contains(token.toLowerCase())) {
                // 先保留原 token，确保最直接的检索词不会丢失。
                keywords.add(token);
                // 对中文 token 进一步展开若干子关键词，以提高更细粒度短语的召回率。
                keywords.addAll(buildChineseSubKeywords(token));
            }
        }

        // 如果一个关键词都没抽出来，就退化成把整句 query 当成唯一关键词，避免完全无检索输入。
        if (keywords.isEmpty()) keywords.add(trimmed);

        // 最终转成 List，供调用方按顺序进一步组装 query variant 或执行关键词检索。
        return new ArrayList<>(keywords);
    }

    /**
     * 对中文 token 生成子关键词。
     *
     * <p>仅对包含汉字的 token 做子串展开，英文词不拆。
     * 子关键词最长 8 字符，最多收集 {@value #MAX_SUB_KEYWORDS_PER_TOKEN} 个。</p>
     */
    private List<String> buildChineseSubKeywords(String token) {
        // 用 ArrayList 保存展开结果，保持生成顺序稳定。
        List<String> sub = new ArrayList<>();

        // 只有包含汉字的 token 才做中文子串展开；纯英文词拆开通常只会制造噪音。
        if (token.codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            return sub;
        }

        // 最长只展开到 8 个字符，避免对超长中文串做过度切分。
        int limit = Math.min(token.length(), 8);
        for (int len = Math.min(4, limit); len >= minKeywordLength; len--) {
            for (int i = 0; i + len <= token.length() && sub.size() < MAX_SUB_KEYWORDS_PER_TOKEN; i++) {
                // 依次截取连续子串，例如“数据安全治理”可产生“数据安全”“安全治理”等短语。
                sub.add(token.substring(i, i + len));
            }
        }
        return sub;
    }
}
