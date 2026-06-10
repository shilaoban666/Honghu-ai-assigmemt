package com.honghu.ai.assigment.rag.retrieval.Augmentation.analyzer;

import com.honghu.ai.assigment.config.properties.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 轻量级规则 Query Analyzer。
 *
 * <p>第一期不做代词消解和复杂 NLP。仅基于规则生成 query variant：</p>
 * <ol>
 *   <li>原查询（始终保留）</li>
 *   <li>关键词变体：提取关键词用空格连接</li>
 *   <li>短变体：只取前 2-3 个关键词</li>
 * </ol>
 *
 * <h3>为什么不做代词消解</h3>
 * <p>没有中文分词和实体识别时，"它/这个/那个"靠高频词回填容易误导检索。
 * 保留接口但默认关闭（{@code query-rewrite.pronoun-resolve-enabled=false}）。
 * 等后续接入分词器或 LLM rewrite 后再开启。</p>
 *
 * <h3>停用词过滤</h3>
 * <p>内置中英文常见停用词集合，过滤"的/了/是/the/a/an"等无意义高频词。
 * 停用词列表随版本迭代持续完善。</p>
 *
 * @see RagQueryAnalyzer
 * @see RagKeywordExtractor
 */
@Component
@RequiredArgsConstructor
public class LightweightRuleQueryAnalyzer implements RagQueryAnalyzer {

    private final RagProperties ragProperties;

    /**
     * 分析查询，返回 query variant 列表。
     *
     * <p>当 query-rewrite.enabled=false 时，仅返回原查询。</p>
     */
    @Override
    public List<String> analyze(String originalQuery) {
        // 空问题没有任何可分析价值，直接返回空列表，避免后续生成无意义变体。
        if (!StringUtils.hasText(originalQuery)) return List.of();

        // 读取 query rewrite 配置，决定本次是否真正启用规则改写，以及最多保留多少个变体。
        var qrCfg = ragProperties.getQueryRewrite();
        if (!qrCfg.isEnabled()) {
            // 改写关闭时保留用户原句即可，既保证行为最保守，也避免引入额外噪音。
            return List.of(originalQuery.trim());
        }

        // 构造一个轻量关键词抽取器：
        // 它负责把长句拆成更有检索价值的 token，并过滤常见无意义停用词。
        RagKeywordExtractor extractor = new RagKeywordExtractor(
                ragProperties.getRetrieval().getMinKeywordLength(),
                Set.of("的", "了", "是", "在", "和", "与", "或", "这", "那", "它", "他", "她",
                       "the", "a", "an", "is", "are", "was", "were", "of", "in", "on", "to", "for"));

        // variants 按顺序保存所有可供后续检索使用的查询表达。
        List<String> variants = new ArrayList<>();

        // 先把原始问题修剪首尾空白，作为最稳定、最忠于用户意图的第一候选变体。
        String trimmed = originalQuery.trim();
        variants.add(trimmed);

        // 抽取关键词，为后续构造“更短、更聚焦”的检索表达做准备。
        List<String> keywords = extractor.extract(trimmed);

        // Variant 1: 仅关键词（空格连接）
        if (!keywords.isEmpty() && keywords.size() < trimmed.length() / 2) {
            // 把关键词重新拼成一个更紧凑的查询，适合在原句过长时提高召回聚焦度。
            String kwVariant = String.join(" ", keywords);
            // 只有当新变体确实不同于原句且不为空时才加入，避免制造重复检索。
            if (!kwVariant.equals(trimmed) && !kwVariant.isBlank()) {
                variants.add(kwVariant);
            }
        }

        // Variant 2: 前几个关键词（更短的 query）
        if (keywords.size() > 2) {
            // 再进一步只取前 2~3 个关键词，得到一个更短的“激进召回”版本。
            String shortVariant = String.join(" ", keywords.subList(0, 3));
            if (!shortVariant.equals(trimmed) && !shortVariant.isBlank()) {
                variants.add(shortVariant);
            }
        }

        // 控制 maxVariants
        int maxV = Math.max(1, qrCfg.getMaxVariants());
        if (variants.size() > maxV) {
            // 如果规则生成的变体过多，就只保留前几个优先级最高的，控制检索成本与噪音。
            variants = variants.subList(0, maxV);
        }

        // 返回给 Pipeline；后续每个 variant 都可能参与一次独立召回。
        return variants;
    }
}
