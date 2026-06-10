package com.honghu.ai.assigment.rag.retrieval.fallback;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.rag.retrieval.retriever.KeywordRagCandidateRetriever;
import com.honghu.ai.assigment.rag.retrieval.retriever.RagRetrievalService.RagSnippet;
import com.honghu.ai.assigment.rag.retrieval.retriever.RetrievalCandidate;
import com.honghu.ai.assigment.rag.retrieval.scope.EffectiveScope;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索作用域 fallback 控制器。
 *
 * <p>这个类只负责一件事：当主检索结果“不够好”时，自动把检索范围从较窄的作用域扩大到较宽的作用域，
 * 再补充一批兜底候选结果。这里的“不够好”不是靠大模型主观判断，而是靠两个明确的工程指标判断：
 * 候选数量是否太少，以及最高相关性分数是否太低。</p>
 *
 * <h3>它在整个 RAG 检索链路中的位置</h3>
 * <pre>
 *   RagPipeline
 *     1. ScopeResolver       解析本次应该先查哪个范围
 *     2. QueryAnalyzer       可选：生成 query variants
 *     3. CandidateRetrieval  keyword/vector 召回候选
 *     4. ResultFusion        可选：多路候选融合
 *     5. RagScopeFallback    本类：命中不足时扩大 scope 再查一次
 *     6. Rerank              当前预留
 *     7. Sort + Format       排序、截断、格式化为 prompt 上下文
 * </pre>
 *
 * <h3>为什么要从 RagPipeline 中抽出来</h3>
 * <p>fallback 看起来只是几行 if 判断，但它实际上同时牵涉四类知识：</p>
 * <ol>
 *   <li><b>作用域知识</b>：知道 {@code FILE_IDS}、{@code CHAT}、{@code SESSION} 谁更窄、谁更宽；</li>
 *   <li><b>质量判断</b>：知道候选数量和最高分达到什么阈值才算“结果够用”；</li>
 *   <li><b>兜底检索策略</b>：知道 fallback 阶段固定使用 keyword 检索器，而不是重新走完整 hybrid 流程；</li>
 *   <li><b>结果治理</b>：知道 session 结果范围更宽，需要降权，并且需要和主候选按 chunk 去重合并。</li>
 * </ol>
 * <p>把这些规则集中到一个组件里，{@code RagPipeline} 就可以继续保持“主流程编排器”的角色，
 * 以后要给 fallback 增加单元测试、灰度开关、更多日志、更多降级层级时，也不用再把 Pipeline 改得越来越胖。</p>
 *
 * <h3>核心业务规则</h3>
 * <table>
 *   <tr><th>规则</th><th>当前行为</th><th>原因</th></tr>
 *   <tr><td>触发条件</td><td>{@code candidateCount < fallbackMinHits || bestScore < fallbackMinScore}</td><td>候选太少或相关性太差，都说明当前窄作用域不够可靠</td></tr>
 *   <tr><td>降级链</td><td>{@code FILE_IDS -> CHAT -> SESSION}</td><td>先查最相关的附件，再退到当前对话，最后退到整个会话</td></tr>
 *   <tr><td>SESSION 终点</td><td>{@code SESSION} 不再继续 fallback</td><td>session 已经是当前设计里最宽的用户私有检索范围</td></tr>
 *   <tr><td>fallback 检索器</td><td>{@link KeywordRagCandidateRetriever}</td><td>keyword 不依赖 Milvus/Embedding，是最稳定的兜底路径</td></tr>
 *   <tr><td>SESSION 降权</td><td>{@code rawScore * sessionPenaltyFactor}</td><td>session 范围更宽，命中结果可能更泛，所以排序时降低权重</td></tr>
 *   <tr><td>合并策略</td><td>主候选优先，fallback 候选补充</td><td>不让更宽作用域的结果覆盖更精确作用域的结果</td></tr>
 * </table>
 *
 * <h3>一个具体例子</h3>
 * <pre>
 *   用户本轮上传了 A.pdf，并提问：“审批金额上限是多少？”
 *
 *   第一次检索：
 *     scope = FILE_IDS，只查 A.pdf
 *     返回 1 条候选，配置要求 fallbackMinHits = 2
 *
 *   本类判断：
 *     1 < 2，说明本轮附件命中不足，于是 fallback
 *
 *   fallback 检索：
 *     如果当前 chatId 存在，则 scope 从 FILE_IDS 扩大到 CHAT
 *     再用 keywordRetriever 查当前对话下的文档
 *
 *   合并：
 *     A.pdf 的原始候选保留
 *     CHAT 范围新召回的候选补进来
 *     如果同一个 documentId + chunkIndex 重复出现，只保留原始候选
 * </pre>
 *
 * <h3>这个类不负责什么</h3>
 * <p>为了保持职责单一，本类不做 query rewrite、不选择 keyword/vector/hybrid 模式、不做 RRF 融合、
 * 不做 rerank，也不拼接最终 prompt。它只处理“当前 scope 结果不够时，是否需要扩大 scope 再补一批候选”。</p>
 *
 * @see com.honghu.ai.assigment.rag.retrieval.pipeline.RagPipeline
 * @see EffectiveScope
 * @see KeywordRagCandidateRetriever
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagScopeFallback {

    /**
     * RAG 总配置对象。
     *
     * <p>本类只使用其中的 {@code retrieval.scope} 部分：</p>
     * <ul>
     *   <li>{@code fallbackMinHits}：候选数量低于多少时触发 fallback；</li>
     *   <li>{@code fallbackMinScore}：最高分低于多少时触发 fallback；</li>
     *   <li>{@code sessionPenaltyFactor}：fallback 到 SESSION 时的分数降权系数。</li>
     * </ul>
     */
    private final RagProperties ragProperties;

    /**
     * fallback 路径固定使用的关键词检索器。
     *
     * <p>这里有一个有意保留的设计：fallback 不重新执行完整的 hybrid 检索，
     * 也不再调用 vector 检索。这样做是为了保持兜底路径稳定、便宜、依赖少：
     * 即使 Milvus、Embedding 或向量检索暂时不可用，fallback 仍然可以基于数据库里的 chunk 文本做关键词召回。</p>
     */
    private final KeywordRagCandidateRetriever keywordRetriever;

    /**
     * 对已完成 fusion 的候选列表应用 scope fallback。
     *
     * <p>这是本类唯一对外入口。{@code RagPipeline} 在完成主检索和候选融合后，
     * 把当前候选列表、当前作用域、原始问题和 topK 传进来。
     * 本方法会判断是否需要扩大检索范围，并返回处理后的候选列表。</p>
     *
     * <h3>为什么入参使用“已完成 fusion 的 candidates”</h3>
     * <p>fallback 的判断应该基于“主检索阶段最终能提供多少可用候选”，
     * 而不是基于 keyword 或 vector 的某一路中间结果。比如 hybrid 模式下，keyword 命中少但 vector 命中多，
     * fusion 之后整体结果可能已经足够，这时就不应该再 fallback。</p>
     *
     * <h3>为什么 fallback 使用原始 query</h3>
     * <p>主检索阶段可能启用了 query rewrite，产生多个 query variant。
     * 但 fallback 的目标是稳定补召回，而不是继续扩大查询复杂度，所以这里使用用户原始问题。
     * 这样可以避免 fallback 阶段因为多个 query variant 再次放大候选数量和日志噪声。</p>
     *
     * <h3>方法执行顺序</h3>
     * <p>执行顺序与原先 {@code RagPipeline} 内联逻辑一致：</p>
     * <ol>
     *   <li>根据候选数量和最高分判断是否需要 fallback；</li>
     *   <li>需要时构造下一级更宽的 {@link EffectiveScope}；</li>
     *   <li>使用 {@link KeywordRagCandidateRetriever} 在 fallback scope 下重新检索；</li>
     *   <li>如果 fallback 到 SESSION，则按配置系数降低 fallback 候选分数；</li>
     *   <li>把原候选和 fallback 候选按 {@code documentId:chunkIndex} 去重合并，原候选优先保留。</li>
     * </ol>
     *
     * <h3>返回值语义</h3>
     * <ul>
     *   <li>未触发 fallback：返回原始 candidates，{@code triggered=false}；</li>
     *   <li>达到阈值但没有下一级 scope：返回原始 candidates，{@code triggered=false}；</li>
     *   <li>真正执行 fallback：返回合并后的 candidates，{@code triggered=true}，并记录 from/to scope。</li>
     * </ul>
     *
     * @param candidates 当前主检索和 fusion 后的候选列表；通常不为 null，调用方传入 Pipeline 内部构造的列表
     * @param scope 当前生效的检索作用域；决定是否允许 fallback，以及下一步可以退到哪里
     * @param query 原始用户问题；fallback 保持使用原始 query，不使用 query variant
     * @param topK fallback 检索传入的 topK；与主检索 topK 保持一致，避免兜底阶段召回规模失控
     * @return fallback 处理结果；包含候选列表、是否触发、fallback 来源/目标 scope、fallback 候选数量
     */
    public FallbackResult applyFallback(List<RetrievalCandidate> candidates,
                                        EffectiveScope scope,
                                        String query,
                                        int topK) {
        // 第一步：先判断是否有必要 fallback。这里不会直接看“是否有候选”，而是同时看数量和最高分。
        // 如果当前候选已经足够，就直接把原列表返回给 Pipeline，避免做额外数据库查询。
        if (!shouldFallback(candidates, scope)) {
            return FallbackResult.builder()
                    .candidates(candidates)
                    .triggered(false)
                    .fallbackCandidateCount(0)
                    .build();
        }

        // 第二步：按当前 scope 构造“下一层更宽的 scope”。
        // 例如 FILE_IDS 有 chatId 时退到 CHAT；CHAT 有 sessionId 时退到 SESSION。
        EffectiveScope fallbackScope = buildFallbackScope(scope);

        // 如果当前 scope 已经没有下一级，说明虽然命中了 fallback 阈值，但系统没有更宽范围可查。
        // 这种情况不能硬查，否则可能产生越权或无意义查询，所以保持原候选不变。
        if (fallbackScope == null || fallbackScope.getPrimaryType() == EffectiveScope.ScopeType.EMPTY) {
            log.debug("RAG scope fallback skipped: scope={}, reason=no_available_next_scope, candidates={}",
                    scope.getPrimaryType(), candidates.size());
            return FallbackResult.builder()
                    .candidates(candidates)
                    .triggered(false)
                    .fallbackCandidateCount(0)
                    .build();
        }

        // 第三步：记录主候选当前最高分。这个值只用于日志排查，不参与后续计算。
        double bestScore = bestScore(candidates);

        // 第四步：在 fallback scope 下重新检索候选。这里固定走 keywordRetriever，保持兜底路径稳定。
        List<RetrievalCandidate> fallbackCandidates = retrieveFallbackCandidates(fallbackScope, query, topK);

        // 第五步：如果 fallback 到 SESSION，给 fallback 候选降权，避免宽范围结果压过精确范围结果。
        fallbackCandidates = applySessionPenaltyIfNeeded(fallbackScope, fallbackCandidates);

        // 第六步：把主候选和 fallback 候选去重合并。主候选先写入，因此重复 chunk 会保留主候选版本。
        List<RetrievalCandidate> mergedCandidates = dedupAndMerge(candidates, fallbackCandidates);

        // 这里用 info 级别记录真正发生的 fallback，因为这会影响最终注入 prompt 的上下文来源。
        log.info(
                "RAG scope fallback: {} -> {}, primaryCandidates={}, fallbackCandidates={}, mergedCandidates={}, bestScore={}, minHits={}, minScore={}",
                scope.getPrimaryType(),
                fallbackScope.getPrimaryType(),
                candidates.size(),
                fallbackCandidates.size(),
                mergedCandidates.size(),
                bestScore,
                scope.getFallbackMinHits(),
                scope.getFallbackMinScore()
        );

        // 第七步：把合并结果和 fallback 元信息一起返回给 Pipeline。
        // Pipeline 后面会继续统一排序、topK 截断、格式化 contextBlock。
        return FallbackResult.builder()
                .candidates(mergedCandidates)
                .triggered(true)
                .fromScope(scope.getPrimaryType())
                .toScope(fallbackScope.getPrimaryType())
                .fallbackCandidateCount(fallbackCandidates.size())
                .build();
    }

    /**
     * 判断当前候选是否满足 fallback 触发条件。
     *
     * <p>这是 fallback 的“闸门”。只有它返回 true，后续才会构造 fallback scope 并再次检索。
     * 这个方法只负责判断，不做查询、不修改候选列表。</p>
     *
     * <p>两个条件为 OR 关系，任一满足即触发：</p>
     * <ul>
     *   <li>候选数量小于 {@code fallbackMinHits}</li>
     *   <li>最高分数小于 {@code fallbackMinScore}</li>
     * </ul>
     *
     * <h3>为什么是 OR 而不是 AND</h3>
     * <p>如果用 AND，会出现“候选数量够多但全都很低分”时不 fallback，
     * 也会出现“只有 1 条高分候选”时不 fallback。这里使用 OR 更保守：
     * 只要数量不足或质量不足，就尝试扩大范围补充上下文。</p>
     *
     * <h3>终止条件</h3>
     * <p>{@code SESSION} 是最宽作用域，{@code EMPTY} 表示无可检索作用域，
     * 二者都不会继续 fallback，避免无限降级或无意义查询。</p>
     *
     * @param candidates 当前候选列表
     * @param scope 当前作用域，里面携带 allowFallback、fallbackMinHits、fallbackMinScore
     * @return true 表示需要尝试 fallback；false 表示保持当前候选不变
     */
    private boolean shouldFallback(List<RetrievalCandidate> candidates, EffectiveScope scope) {
        // 策略层可能明确禁止 fallback，例如 ATTACHMENT_ONLY / CHAT_ONLY。
        // 这种情况下即使候选为 0，也必须尊重“只查指定范围”的语义。
        if (!scope.isAllowFallback()) {
            log.debug("RAG scope fallback skipped: scope={}, reason=not_allowed, candidates={}",
                    scope.getPrimaryType(), candidates.size());
            return false;
        }

        // SESSION 已经是当前会话内最宽的检索范围；EMPTY 表示根本没有合法检索范围。
        // 二者都没有继续扩大的空间，所以直接结束 fallback 判断。
        if (scope.getPrimaryType() == EffectiveScope.ScopeType.SESSION
                || scope.getPrimaryType() == EffectiveScope.ScopeType.EMPTY) {
            log.debug("RAG scope fallback skipped: scope={}, reason=terminal_scope, candidates={}",
                    scope.getPrimaryType(), candidates.size());
            return false;
        }

        // 当前候选数量。数量太少时，即使分数不错，也可能缺少足够上下文支撑回答。
        int candidateCount = candidates.size();

        // 当前候选最高分。没有候选时 bestScore 返回 0，因此自然会低于大多数 minScore 阈值。
        double bestScore = bestScore(candidates);

        // 只要“数量不足”或“最高分不足”任一成立，就触发 fallback。
        boolean triggered = candidateCount < scope.getFallbackMinHits()
                || bestScore < scope.getFallbackMinScore();

        // 命中阈值时只打 debug 日志，因为后续真正执行 fallback 时还会打一条 info 汇总日志。
        if (triggered) {
            log.debug(
                    "RAG scope fallback threshold matched: scope={}, candidates={}, bestScore={}, minHits={}, minScore={}",
                    scope.getPrimaryType(),
                    candidateCount,
                    bestScore,
                    scope.getFallbackMinHits(),
                    scope.getFallbackMinScore()
            );
        }
        return triggered;
    }

    /**
     * 构造下一级 fallback 作用域。
     *
     * <p>这个方法只负责“算出下一步查哪里”，不真正执行检索。
     * 返回的 {@link EffectiveScope} 会继续交给 {@link KeywordRagCandidateRetriever} 使用。</p>
     *
     * <p>保持原有降级链不变：</p>
     * <pre>
     *   FILE_IDS -> CHAT -> SESSION
     *   CHAT     -> SESSION
     *   SESSION  -> null
     *   EMPTY    -> null
     * </pre>
     *
     * <h3>为什么 FILE_IDS 优先退到 CHAT</h3>
     * <p>本轮附件是最窄、最精确的范围。如果附件命中不足，当前 chat 通常包含用户同一轮或相邻上下文上传的文档，
     * 比整个 session 更接近当前问题，所以优先退到 CHAT。</p>
     *
     * <h3>为什么 SESSION 的 allowFallback=false</h3>
     * <p>SESSION 是当前设计里的终点。构造 SESSION scope 时显式关闭 fallback，
     * 可以防止后续逻辑误以为还能继续扩大范围。</p>
     *
     * @param currentScope 当前作用域
     * @return 下一级作用域；无下一级时返回 null
     */
    private EffectiveScope buildFallbackScope(EffectiveScope currentScope) {
        // 读取 scope 配置，主要是把最新的 fallback 阈值带到下一级 scope 中。
        var cfg = ragProperties.getRetrieval().getScope();
        return switch (currentScope.getPrimaryType()) {
            // 当前只查附件 fileIds：如果有 chatId，就扩大到当前 chat。
            case FILE_IDS -> currentScope.getChatId() != null
                    ? EffectiveScope.builder()
                    .primaryType(EffectiveScope.ScopeType.CHAT)
                    .chatId(currentScope.getChatId())
                    .sessionId(currentScope.getSessionId())
                    .ownerFolder(currentScope.getOwnerFolder())
                    .allowFallback(true)
                    .fallbackMinHits(cfg.getFallbackMinHits())
                    .fallbackMinScore(cfg.getFallbackMinScore())
                    .build()
                    // 如果没有 chatId 但有 sessionId，就直接扩大到 session。
                    : StringUtils.hasText(currentScope.getSessionId())
                    ? EffectiveScope.builder()
                    .primaryType(EffectiveScope.ScopeType.SESSION)
                    .sessionId(currentScope.getSessionId())
                    .ownerFolder(currentScope.getOwnerFolder())
                    .allowFallback(false)
                    .build()
                    : null;
            // 当前只查 chat：如果有 sessionId，就扩大到整个 session。
            case CHAT -> StringUtils.hasText(currentScope.getSessionId())
                    ? EffectiveScope.builder()
                    .primaryType(EffectiveScope.ScopeType.SESSION)
                    .sessionId(currentScope.getSessionId())
                    .ownerFolder(currentScope.getOwnerFolder())
                    .allowFallback(false)
                    .build()
                    : null;
            // SESSION / EMPTY 都没有下一层，返回 null 表示不能再 fallback。
            default -> null;
        };
    }

    /**
     * 安全执行 fallback 关键词检索。
     *
     * <p>原先这一步通过 {@code RagPipeline.retrieveFrom(...)} 捕获异常。
     * 抽成独立类后必须在这里保留异常兜底日志，避免 fallback 检索失败时丢失排查线索。</p>
     *
     * <h3>为什么异常时返回空列表</h3>
     * <p>RAG 检索只是聊天链路的增强能力，不应该因为 fallback 检索失败导致整个聊天请求失败。
     * 返回空列表后，Pipeline 仍然可以继续使用主检索候选，或者最终生成无 RAG 上下文的回答。</p>
     *
     * @param fallbackScope 下一级更宽的作用域
     * @param query 原始用户问题
     * @param topK 检索条数上限
     * @return fallback 候选；异常时返回空列表
     */
    private List<RetrievalCandidate> retrieveFallbackCandidates(EffectiveScope fallbackScope,
                                                                String query,
                                                                int topK) {
        try {
            // fallback 固定使用 keyword 检索器，避免依赖向量库或 embedding 服务。
            return keywordRetriever.retrieve(fallbackScope, query, topK);
        } catch (Exception ex) {
            // fallback 失败不能中断主链路；warn 级别保留排查信息，然后返回空候选。
            log.warn("Fallback retriever '{}' failed: scope={}, err={}",
                    keywordRetriever.strategyName(), fallbackScope.getPrimaryType(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 如果 fallback 目标是 SESSION，则降低 fallback 候选分数。
     *
     * <p>SESSION 的范围最宽，命中内容可能更泛。
     * 通过 {@code sessionPenaltyFactor} 降权，可以让原始精确作用域中的候选优先排序。</p>
     *
     * <h3>为什么只对 SESSION 降权</h3>
     * <p>从 FILE_IDS 退到 CHAT 时，仍然属于当前对话附近的较近上下文，相关性通常还比较强。
     * 从 CHAT 退到 SESSION 后，范围扩大到整个会话，历史文档或历史片段混入的概率更高，
     * 所以只在 SESSION 层做额外惩罚。</p>
     *
     * <h3>为什么要同时改 snippet.score 和 rawScore</h3>
     * <p>{@link RetrievalCandidate#getRawScore()} 用于 Pipeline 排序，
     * {@link RagSnippet#score()} 可能用于后续展示或格式化。
     * 两个分数保持一致，可以避免排序看到的是降权分，而展示看到的是原始分。</p>
     *
     * @param fallbackScope fallback 目标作用域
     * @param candidates fallback 检索出来的候选列表
     * @return 如果目标不是 SESSION，原样返回；如果是 SESSION，返回降权后的新候选列表
     */
    private List<RetrievalCandidate> applySessionPenaltyIfNeeded(EffectiveScope fallbackScope,
                                                                 List<RetrievalCandidate> candidates) {
        // 只有扩大到 SESSION 时才降权；FILE_IDS -> CHAT 不降权。
        if (fallbackScope.getPrimaryType() != EffectiveScope.ScopeType.SESSION) {
            return candidates;
        }

        // 从配置读取降权系数，例如 0.85 表示 fallback 候选保留 85% 的原始分。
        double penalty = ragProperties.getRetrieval().getScope().getSessionPenaltyFactor();
        log.debug("RAG session fallback penalty applied: factor={}, candidates={}", penalty, candidates.size());
        return candidates.stream()
                // RetrievalCandidate 是不可变值对象，所以这里不是原地修改，而是构造一个新候选。
                .map(candidate -> RetrievalCandidate.builder()
                        .snippet(new RagSnippet(
                                candidate.getSnippet().documentId(),
                                candidate.getSnippet().fileName(),
                                candidate.getSnippet().fileType(),
                                candidate.getSnippet().chunkIndex(),
                                candidate.getSnippet().content(),
                                candidate.getRawScore() * penalty))
                        // source 加后缀，方便日志或调试时知道这条候选来自 session fallback。
                        .source(candidate.getSource() + "-fallback-session")
                        .rawScore(candidate.getRawScore() * penalty)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 按 {@link RetrievalCandidate#dedupKey()} 去重合并主候选和 fallback 候选。
     *
     * <p>主候选先写入 map，fallback 候选后写入且使用 {@code putIfAbsent}。
     * 因此同一个 chunk 同时出现在主检索和 fallback 检索中时，保留主检索候选，保持原逻辑不变。</p>
     *
     * <h3>为什么用 LinkedHashMap</h3>
     * <p>{@link LinkedHashMap} 可以保持插入顺序。先插入主候选，再插入 fallback 候选，
     * 最终列表会保持“主候选在前，fallback 补充在后”的基础顺序。
     * 虽然后续 Pipeline 还会按分数排序，但这里保持稳定顺序有利于日志排查和单元测试。</p>
     *
     * @param primaryCandidates 主检索和 fusion 后的候选
     * @param fallbackCandidates fallback 阶段补充出来的候选
     * @return 去重合并后的候选列表
     */
    private List<RetrievalCandidate> dedupAndMerge(List<RetrievalCandidate> primaryCandidates,
                                                   List<RetrievalCandidate> fallbackCandidates) {
        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();

        // 先放主候选：同一 chunk 同时出现在主候选和 fallback 候选中时，主候选优先。
        for (RetrievalCandidate candidate : primaryCandidates) {
            merged.putIfAbsent(candidate.dedupKey(), candidate);
        }

        // 再放 fallback 候选：只补充主候选中没有出现过的 chunk。
        for (RetrievalCandidate candidate : fallbackCandidates) {
            merged.putIfAbsent(candidate.dedupKey(), candidate);
        }

        // 转回 List，交给 Pipeline 后续排序、截断和格式化。
        return new ArrayList<>(merged.values());
    }

    /**
     * 计算当前候选列表中的最高原始分数。
     *
     * <p>没有候选时返回 0，与原先 {@code orElse(0)} 的判断逻辑保持一致。</p>
     *
     * <p>这个分数只用于 fallback 触发判断和日志。
     * 这里不做归一化，因为当前候选已经是 Pipeline 主检索和 fusion 之后的统一候选对象；
     * fallback 阈值由配置控制，允许根据实际检索策略调整。</p>
     *
     * @param candidates 当前候选列表
     * @return 候选中的最高 rawScore；候选为空时返回 0
     */
    private double bestScore(List<RetrievalCandidate> candidates) {
        return candidates.stream()
                .mapToDouble(RetrievalCandidate::getRawScore)
                .max()
                .orElse(0);
    }

    /**
     * fallback 阶段的返回值。
     *
     * <p>这是一个只读值对象，用来把 fallback 阶段的结果和指标一起带回 {@code RagPipeline}。
     * 把它做成独立返回对象，而不是只返回 {@code List<RetrievalCandidate>}，是因为 Pipeline 还需要知道
     * fallback 是否触发、从哪个 scope 退到哪个 scope、兜底候选数量是多少。</p>
     *
     * <p>Pipeline 需要从这里拿到：</p>
     * <ul>
     *   <li>合并后的候选列表，用于后续排序、topK 截断和格式化；</li>
     *   <li>是否触发 fallback，用于 metrics 和最终日志；</li>
     *   <li>fallback 的源 scope，用于排查是哪一级命中不足。</li>
     * </ul>
     *
     * <h3>字段关系</h3>
     * <table>
     *   <tr><th>场景</th><th>triggered</th><th>fromScope</th><th>toScope</th><th>fallbackCandidateCount</th></tr>
     *   <tr><td>未达到阈值</td><td>false</td><td>null</td><td>null</td><td>0</td></tr>
     *   <tr><td>达到阈值但无下一级 scope</td><td>false</td><td>null</td><td>null</td><td>0</td></tr>
     *   <tr><td>真正执行 fallback</td><td>true</td><td>当前 scope</td><td>下一级 scope</td><td>fallback 返回数量</td></tr>
     * </table>
     */
    @Value
    @Builder
    public static class FallbackResult {
        /**
         * fallback 处理后的候选列表。
         *
         * <p>未触发 fallback 时，这里就是传入的原候选列表；
         * 触发 fallback 时，这里是主候选和 fallback 候选去重合并后的列表。</p>
         */
        List<RetrievalCandidate> candidates;

        /**
         * 是否真正执行了 fallback 检索。
         *
         * <p>注意：达到 fallback 阈值但没有可用下一级 scope 时，这里仍然是 false，
         * 因为没有实际执行第二次检索。</p>
         */
        boolean triggered;

        /**
         * fallback 来源 scope，例如 {@code FILE_IDS}。
         *
         * <p>只有 {@link #triggered} 为 true 时才有值。</p>
         */
        EffectiveScope.ScopeType fromScope;

        /**
         * fallback 目标 scope，例如 {@code CHAT} 或 {@code SESSION}。
         *
         * <p>只有 {@link #triggered} 为 true 时才有值。</p>
         */
        EffectiveScope.ScopeType toScope;

        /**
         * fallback 检索返回并完成必要降权后的候选数量。
         *
         * <p>这个数字用于日志观测：它表示 fallback 阶段拿到了多少补充候选，
         * 不等于最终注入 prompt 的片段数，最终片段数还会经过合并、排序和 topK 截断。</p>
         */
        int fallbackCandidateCount;
    }
}
