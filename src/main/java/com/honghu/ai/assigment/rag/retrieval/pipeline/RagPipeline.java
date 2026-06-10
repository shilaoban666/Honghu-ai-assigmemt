package com.honghu.ai.assigment.rag.retrieval.pipeline;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.rag.retrieval.Augmentation.analyzer.LightweightRuleQueryAnalyzer;
import com.honghu.ai.assigment.rag.retrieval.Augmentation.fusion.RrfRagResultFusion;
import com.honghu.ai.assigment.rag.retrieval.fallback.RagScopeFallback;
import com.honghu.ai.assigment.rag.retrieval.generator.RagSnippetFormatter;
import com.honghu.ai.assigment.rag.retrieval.retriever.KeywordRagCandidateRetriever;
import com.honghu.ai.assigment.rag.retrieval.retriever.RagCandidateRetriever;
import com.honghu.ai.assigment.rag.retrieval.retriever.RagRetrievalService.RagSnippet;
import com.honghu.ai.assigment.rag.retrieval.retriever.RetrievalCandidate;
import com.honghu.ai.assigment.rag.retrieval.retriever.VectorRagCandidateRetriever;
import com.honghu.ai.assigment.rag.retrieval.scope.DefaultRagScopeResolver;
import com.honghu.ai.assigment.rag.retrieval.scope.EffectiveScope;
import com.honghu.ai.assigment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 在线检索生成链路的核心编排器。
 *
 * <p>这个类是“在线 RAG Serving Pipeline”的中心调度点。
 * 用户在聊天窗口里提问时，聊天服务会构造一个 {@link RagRequest} 交给本类。
 * 本类负责把一次 RAG 检索请求拆成多个阶段依次执行：解析检索范围、分析问题、召回候选、
 * 融合结果、必要时扩大范围 fallback、排序截断、格式化上下文，最后返回 {@link RagResult}。</p>
 *
 * <h3>它不是普通 Service，而是编排层</h3>
 * <p>本类的核心职责不是亲自访问数据库、亲自拼 Milvus filter、亲自计算 RRF，
 * 而是把不同专业组件按正确顺序组织起来。也就是说：</p>
 * <ul>
 *   <li>{@link DefaultRagScopeResolver} 负责“本次应该先查哪个范围”；</li>
 *   <li>{@link LightweightRuleQueryAnalyzer} 负责“是否要把用户问题扩展成多个 query variant”；</li>
 *   <li>{@link KeywordRagCandidateRetriever} 负责“基于 PostgreSQL chunk 表做关键词召回”；</li>
 *   <li>{@link VectorRagCandidateRetriever} 负责“基于 Milvus/VectorStore 做语义召回”；</li>
 *   <li>{@link RrfRagResultFusion} 负责“把多路召回结果融合成一个候选列表”；</li>
 *   <li>{@link RagScopeFallback} 负责“候选不足时扩大检索范围再补一批结果”；</li>
 *   <li>{@link RagSnippetFormatter} 负责“把最终片段格式化成可注入 Prompt 的上下文块”。</li>
 * </ul>
 *
 * <h3>完整执行阶段</h3>
 * <pre>
 *   输入：RagRequest(userId, sessionId, chatId, query, attachmentFileIds, topKOverride)
 *
 *     │
 *     ▼
 *   [0] Fast Guard
 *       - RAG 开关关闭、检索开关关闭、必要参数缺失时，直接返回 RagResult.empty()
 *       - 这样可以保证聊天主链路不会因为 RAG 配置缺失而失败
 *
 *     │
 *     ▼
 *   [1] Owner Resolve + ScopeResolver
 *       - 根据 userId 查询 username，作为 ownerFolder
 *       - ownerFolder 用于限制只能检索当前用户自己的文档
 *       - ScopeResolver 根据 attachmentFileIds/chatId/sessionId 解析 EffectiveScope
 *
 *     │
 *     ▼
 *   [2] QueryAnalyzer
 *       - queryRewrite 开启时，生成多个 query variants
 *       - queryRewrite 关闭时，只使用用户原始问题
 *
 *     │
 *     ▼
 *   [3] Candidate Retrieval
 *       - mode=keyword：只走关键词检索
 *       - mode=vector：只走向量检索
 *       - mode=hybrid：同时走 keyword + vector
 *       - 每个 query variant 都会参与召回
 *
 *     │
 *     ▼
 *   [4] Fusion
 *       - 开启 fusion 且存在多路候选时，使用 RRF 融合
 *       - 未开启 fusion 时，只做简单去重合并
 *
 *     │
 *     ▼
 *   [5] Scope Fallback
 *       - 如果融合后候选数量太少或最高分太低，交给 RagScopeFallback
 *       - fallback 会按 FILE_IDS -> CHAT -> SESSION 扩大作用域
 *       - fallback 结果会和主候选去重合并
 *
 *     │
 *     ▼
 *   [6] Rerank Placeholder
 *       - 当前只是占位，不改变候选
 *       - 后续可以接入 LLM rerank 或 cross-encoder rerank
 *
 *     │
 *     ▼
 *   [7] Sort + Limit + Format
 *       - 按 rawScore 从高到低排序
 *       - 截断 topK
 *       - 格式化成 contextBlock，供后续拼 Prompt 使用
 *
 *     │
 *     ▼
 *   输出：RagResult(contextBlock, snippets, metrics, fallbackTriggered)
 * </pre>
 *
 * <h3>配置驱动</h3>
 * <table>
 *   <tr><th>配置项</th><th>影响阶段</th><th>说明</th></tr>
 *   <tr><td>{@code rag.enabled}</td><td>Fast Guard</td><td>关闭后整条 RAG 链路直接返回空结果</td></tr>
 *   <tr><td>{@code rag.retrieval.enabled}</td><td>Fast Guard</td><td>关闭在线检索，但不一定关闭入库能力</td></tr>
 *   <tr><td>{@code rag.retrieval.mode}</td><td>Candidate Retrieval</td><td>决定 keyword、vector、hybrid 三种召回模式</td></tr>
 *   <tr><td>{@code rag.retrieval.topK}</td><td>Sort + Limit</td><td>决定最终最多注入多少个片段</td></tr>
 *   <tr><td>{@code rag.retrieval.fusion.enabled}</td><td>Fusion</td><td>控制是否启用 RRF 多路融合</td></tr>
 *   <tr><td>{@code rag.queryRewrite.enabled}</td><td>QueryAnalyzer</td><td>控制是否生成多个 query variant</td></tr>
 *   <tr><td>{@code rag.retrieval.scope.defaultScope}</td><td>ScopeResolver</td><td>决定默认从附件、chat 还是 session 开始查</td></tr>
 *   <tr><td>{@code rag.retrieval.maxContextCharacters}</td><td>Format</td><td>限制最终 contextBlock 的最大字符数</td></tr>
 * </table>
 *
 * <h3>容错原则</h3>
 * <p>RAG 是聊天能力的增强模块，不应该让主聊天链路因为检索失败而整体失败。
 * 所以本类采用“失败返回空候选/空上下文”的策略：</p>
 * <ul>
 *   <li>参数不足或用户不存在：返回 {@link RagResult#empty()}；</li>
 *   <li>单个 retriever 异常：记录 warn 日志，并把该路结果当作空列表；</li>
 *   <li>vector 检索失败：不影响 keyword 检索结果；</li>
 *   <li>fallback 检索失败：不影响主检索结果；</li>
 *   <li>最终没有命中片段：返回空 contextBlock，聊天仍可继续。</li>
 * </ul>
 *
 * <h3>安全边界</h3>
 * <p>本类在执行检索前会根据 {@code userId} 查询 {@link User#getUsername()}，并将其作为 ownerFolder。
 * 后续 keyword/vector retriever 都必须携带 ownerFolder 过滤条件。
 * 这样可以保证用户只能检索自己名下入库的文档，避免跨用户召回。</p>
 *
 * @see RagRequest
 * @see RagResult
 * @see RagPipelineMetrics
 * @see RagScopeFallback
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagPipeline {

    private final RagProperties ragProperties;
    private final RagSnippetFormatter formatter;
    private final UserRepository userRepository;
    private final DefaultRagScopeResolver scopeResolver;
    private final KeywordRagCandidateRetriever keywordRetriever;
    private final VectorRagCandidateRetriever vectorRetriever;
    private final LightweightRuleQueryAnalyzer queryAnalyzer;
    private final RrfRagResultFusion resultFusion;
    private final RagScopeFallback fallback;
    /**
     * 执行完整 RAG Pipeline。
     *
     * <p>这是 Pipeline 的唯一对外入口。它接收聊天主链路传来的 {@link RagRequest}，
     * 按固定顺序执行在线检索流程，最后返回一个 {@link RagResult}。
     * 返回结果中最重要的是 {@code contextBlock}：它会被聊天服务拼进 Prompt，
     * 让大模型基于检索到的文档片段回答问题。</p>
     *
     * <h3>快速退出条件</h3>
     * <ul>
     *   <li>RAG 总开关关闭：说明当前环境不启用 RAG，直接返回空结果；</li>
     *   <li>RAG 检索开关关闭：说明可能只允许入库，不允许在线检索，直接返回空结果；</li>
     *   <li>{@code userId/sessionId/query} 缺失：无法做用户隔离、会话过滤或问题检索，直接返回空结果；</li>
     *   <li>根据 {@code userId} 找不到 ownerFolder：无法保证文档归属过滤，直接返回空结果。</li>
     * </ul>
     *
     * <h3>为什么返回空结果而不是抛异常</h3>
     * <p>RAG 在当前系统里是“增强上下文”的能力，不是聊天主链路的硬依赖。
     * 如果 RAG 不可用，系统仍然可以让大模型基于普通聊天上下文回答。
     * 因此本方法倾向于返回 {@link RagResult#empty()}，而不是把异常抛到 Controller。</p>
     *
     * <h3>线程安全说明</h3>
     * <p>本类是 Spring 单例组件，但方法内部使用的 {@code candidates}、{@code queryVariants}、
     * {@code metrics builder} 都是局部变量；注入组件本身由 Spring 管理。
     * 因此一次请求的中间状态不会污染另一次请求。</p>
     *
     * @param request RAG 请求上下文，包含用户、会话、问题、附件文件 ID 和可选 topK 覆盖值
     * @return RagResult，永远不会返回 null；无法检索时返回 {@link RagResult#empty()}
     */
    public RagResult execute(RagRequest request) {
        long start = System.currentTimeMillis();

        // metrics builder 用来收集本次检索的关键指标：生效 scope、keyword/vector 候选数、fusion 后数量、fallback 信息、最终片段数等。
        var mb = RagPipelineMetrics.builder();

        // ── [0] 快速退出：RAG 总开关或检索开关关闭时，不进入任何检索逻辑 ──
        if (!ragProperties.isEnabled() || !ragProperties.getRetrieval().isEnabled()) return RagResult.empty();

        // userId 用于用户隔离，sessionId 用于会话范围过滤，query 是检索文本。缺少任意一个，都无法安全、准确地执行检索。
        if (!StringUtils.hasText(request.getUserId())
                || !StringUtils.hasText(request.getSessionId())
                || !StringUtils.hasText(request.getQuery())) return RagResult.empty();

        // 将 userId 解析成 username/ownerFolder。后续 keyword/vector retriever 都会用 ownerFolder 过滤文档，防止跨用户检索。
        String ownerFolder = userRepository.findById(request.getUserId())
                .map(User::getUsername).orElse(null);

        if (!StringUtils.hasText(ownerFolder)) return RagResult.empty();

        // ── [1] Scope 解析 ──
        // 它描述“默认优先查哪里，以及是否允许 fallback”。
        String defaultScope = ragProperties.getRetrieval().getScope().getDefaultScope();
        EffectiveScope scope = scopeResolver.resolve(request, ownerFolder, defaultScope);

        // 记录生效 scope，方便后续排查“为什么这次查的是附件/当前 chat/整个 session”。
        mb.effectiveScopeType(scope.getPrimaryType());

        // ── [2] Query 分析 → query variants ──
        List<String> queryVariants;
        if (ragProperties.getQueryRewrite().isEnabled()) {
            // 开启 query rewrite 时，一个用户问题可能会生成多个检索表达。例如把长问题拆成关键词、同义词或更短的查询变体，以提高召回率。
            queryVariants = queryAnalyzer.analyze(request.getQuery());
        } else {
            // 默认保守模式：不改写用户问题，只用原始 query 检索。
            queryVariants = List.of(request.getQuery());
        }

        // ── [3] 检索（每个 variant × 每个 retriever） ──
        // topKOverride 允许调用方针对单次请求覆盖默认 topK。没有覆盖时使用配置中的 rag.retrieval.topK。
        int topK = request.getTopKOverride() != null
                ? request.getTopKOverride()
                : ragProperties.getRetrieval().getTopK();

        // mode 决定召回策略：
        String mode = ragProperties.getRetrieval().getMode();

        // allCandidateLists 保存每一路召回结果。
        List<List<RetrievalCandidate>> allCandidateLists = new ArrayList<>();

        // 只有“配置启用 fusion”并且“确实存在多路候选来源”时才做 RRF。
        boolean fusionEnabled = ragProperties.getRetrieval().getFusion().isEnabled()
                && (queryVariants.size() > 1 || "hybrid".equals(mode));

        // 对每一个 query variant 分别召回。
        for (String variant : queryVariants) {
            if ("vector".equals(mode)) {
                // 纯向量模式：只调用 Milvus/VectorStore 召回语义相似片段。
                List<RetrievalCandidate> vec = retrieveFrom(scope, variant, topK, vectorRetriever);
                mb.vectorCandidateCount(mb.build().getVectorCandidateCount() + vec.size());
                allCandidateLists.add(vec);
            } else if ("hybrid".equals(mode)) {
                // 混合模式：同一个 query variant 同时走关键词和向量检索。
                // keyword 偏精确匹配，vector 偏语义匹配，两者互补。
                List<RetrievalCandidate> kw = retrieveFrom(scope, variant, topK, keywordRetriever);
                List<RetrievalCandidate> vec = retrieveFrom(scope, variant, topK, vectorRetriever);
                mb.keywordCandidateCount(mb.build().getKeywordCandidateCount() + kw.size());
                mb.vectorCandidateCount(mb.build().getVectorCandidateCount() + vec.size());
                if (fusionEnabled) {
                    // 开启 RRF 时，先保留各路独立列表，交给 resultFusion 统一按排名融合。
                    allCandidateLists.add(kw);
                    allCandidateLists.add(vec);
                } else {
                    // 未开启 RRF 时，对 keyword/vector 做简单去重合并。
                    // 这样 hybrid 仍能同时利用两路结果，但不改变分数模型。
                    allCandidateLists.add(dedupAndMerge(kw, vec));
                }
            } else {
                // 默认 keyword：配置值不是 vector/hybrid 时，也走 keyword，保证模式配置异常时仍有稳定兜底。
                List<RetrievalCandidate> kw = retrieveFrom(scope, variant, topK, keywordRetriever);
                mb.keywordCandidateCount(mb.build().getKeywordCandidateCount() + kw.size());
                allCandidateLists.add(kw);
            }
        }

        // ── [4] Fusion ──
        List<RetrievalCandidate> candidates;
        if (fusionEnabled && allCandidateLists.size() > 1) {
            // 多路候选列表交给 RRF 融合。
            // RRF 的优势是不同检索器分数尺度不一致时，可以主要根据排名做融合。
            candidates = resultFusion.fuse(allCandidateLists,
                    ragProperties.getRetrieval().getFusion().getRrfK());
            log.debug("Fusion: {} lists → {} candidates", allCandidateLists.size(), candidates.size());
        } else if (allCandidateLists.size() == 1) {
            // 只有一路候选时，不需要融合，直接进入 fallback 判断。
            candidates = allCandidateLists.get(0);
        } else {
            // 理论上较少发生：多路列表存在但 fusion 未启用或条件不满足。
            // 这里把所有候选摊平后做一次稳定去重，避免重复 chunk 进入后续排序。
            candidates = dedupAndMerge(
                    allCandidateLists.stream().flatMap(List::stream).collect(Collectors.toList()),
                    List.of());
        }

        // 记录 fusion 或去重合并后的候选数。
        mb.afterFusionCount(candidates.size());

        // ── [5] Scope Fallback ──
        // fallback 判断基于 fusion 后的整体候选质量，而不是某一路 retriever 的局部结果。如果候选太少或最高分太低，fallback会扩大 scope 并补充候选。
        RagScopeFallback.FallbackResult fallbackResult = fallback.applyFallback(
                candidates, scope, request.getQuery(), topK);

        // 后续排序和格式化使用 fallback 处理后的候选列表。
        candidates = fallbackResult.getCandidates();

        // 这个布尔值会写入 RagResult，调用方可以知道本次回答是否用到了扩大范围的兜底结果。
        boolean fallbackTriggered = fallbackResult.isTriggered();

        // metrics 只记录 fallback 是否触发以及来源 scope；目标 scope 和数量会在日志中输出。
        mb.fallbackTriggered(fallbackTriggered).fallbackFromScope(fallbackResult.getFromScope());

        // ── [6] Rerank（P5 灰度 placeholder） ──
        // 当前还没有真正 rerank，所以 afterRerankCount 等于当前 candidates.size()。
        // 预留这个指标是为了后续接入 LLM rerank/cross-encoder 后可以平滑扩展。
        mb.afterRerankCount(candidates.size());

        // ── [7] Sort + Limit + Format ──
        // 将候选按 rawScore 从高到低排序，并截断到 topK。
        // rawScore 是 Pipeline 内部统一使用的排序分数。
        List<RagSnippet> topSnippets = candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::getRawScore).reversed())
                .limit(Math.max(1, topK))
                .map(RetrievalCandidate::getSnippet)
                .toList();

        // 记录最终真正会注入 prompt 的片段数量。
        mb.finalSnippetCount(topSnippets.size());

        // 没有命中片段时，contextBlock 为空字符串。
        // 有命中片段时，交给 formatter 控制格式和最大字符数。
        String contextBlock = topSnippets.isEmpty() ? ""
                : formatter.format(topSnippets, ragProperties.getRetrieval().getMaxContextCharacters());

        // 当前只记录 total 耗时；保留 stageTimings 结构，是为了后续拆分 retrieval/fusion/fallback/rerank 耗时。
        mb.stageTimings(List.of(RagPipelineMetrics.StageTiming.builder()
                .stage("total").durationMs(System.currentTimeMillis() - start).build()));

        // ── Observability ──
        // 汇总日志只在观测开关开启时输出，避免生产环境日志过量。
        // 这里记录 scope、fallback、各路候选数和最终片段数，是排查 RAG 效果的第一入口。
        if (ragProperties.getObservability().isMetricsEnabled()) {
            log.info("RAG pipeline: scope={}, fallback={}, fallbackFrom={}, fallbackTo={}, fallbackCandidates={}, kw={}, vec={}, final={}, {}ms",
                    scope.getPrimaryType(), fallbackTriggered,
                    fallbackResult.getFromScope(),
                    fallbackResult.getToScope(),
                    fallbackResult.getFallbackCandidateCount(),
                    mb.build().getKeywordCandidateCount(),
                    mb.build().getVectorCandidateCount(),
                    topSnippets.size(), System.currentTimeMillis() - start);
        }

        // 返回给上层的结果包含：
        // 1. contextBlock：真正用于增强 Prompt 的文本；
        // 2. snippets：结构化片段，方便调试或前端展示引用；
        // 3. metrics：本次检索过程指标；
        // 4. fallbackTriggered：调用方可以据此观测是否发生作用域扩大。
        return RagResult.builder()
                .contextBlock(contextBlock)
                .snippets(topSnippets)
                .metrics(mb.build())
                .fallbackTriggered(fallbackTriggered)
                .build();
    }

    /**
     * 安全调用单个 CandidateRetriever。
     *
     * <p>这是 Pipeline 对 retriever 的统一保护层。
     * keyword、vector 都实现了 {@link RagCandidateRetriever}，但它们背后的依赖不同：
     * keyword 依赖数据库，vector 依赖 Embedding 和 Milvus。
     * 任意一路失败时，本方法都会记录 warn 日志并返回空列表，避免整个聊天请求失败。</p>
     *
     * <h3>为什么不把异常继续抛出</h3>
     * <p>RAG 检索失败只意味着“本次无法提供这一路候选”，不应该等价于“用户不能聊天”。
     * 尤其在 hybrid 模式下，vector 失败后 keyword 可能仍然有结果。</p>
     *
     * @param scope 当前检索作用域，限制查附件、chat 或 session
     * @param query 当前用于检索的 query，可能是原始问题，也可能是 query variant
     * @param topK 希望 retriever 返回的候选数量上限
     * @param r 具体检索器，例如 keyword 或 vector
     * @return 检索候选；异常时返回空列表
     */
    private List<RetrievalCandidate> retrieveFrom(EffectiveScope scope, String query,
                                                   int topK, RagCandidateRetriever r) {
        try {
            // 正常路径：交给具体 retriever 执行检索。
            return r.retrieve(scope, query, topK);
        } catch (Exception e) {
            // 异常路径：记录检索器名称和错误信息，然后用空列表兜底。
            log.warn("Retriever '{}' failed: {}", r.strategyName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 dedupKey 去重合并两路候选。
     *
     * <p>这是 Pipeline 内部的轻量合并工具，主要用于两个场景：</p>
     * <ul>
     *   <li>hybrid 模式但未开启 RRF 时，合并 keyword 和 vector 结果；</li>
     *   <li>多路候选不走 fusion 时，把摊平后的候选做一次稳定去重。</li>
     * </ul>
     *
     * <h3>去重规则</h3>
     * <p>每个 {@link RetrievalCandidate} 都有一个 {@link RetrievalCandidate#dedupKey()}，
     * 当前格式是 {@code documentId:chunkIndex}。
     * 同一个文档的同一个 chunk 即使被 keyword 和 vector 同时召回，也只保留一条。</p>
     *
     * <h3>为什么 primary 优先</h3>
     * <p>本方法先写入参数 {@code a}，再写入参数 {@code b}，并使用 {@code putIfAbsent}。
     * 如果同一个 chunk 重复出现，先出现的候选会保留。
     * 这样调用方可以通过参数顺序表达优先级。</p>
     *
     * @param a 第一组候选，重复时优先保留
     * @param b 第二组候选，只补充第一组中没有的 chunk
     * @return 去重合并后的候选列表
     */
    private List<RetrievalCandidate> dedupAndMerge(List<RetrievalCandidate> a,
                                                    List<RetrievalCandidate> b) {
        Map<String, RetrievalCandidate> m = new LinkedHashMap<>();

        // LinkedHashMap 保留插入顺序，有利于在分数相同或日志排查时保持稳定结果。
        for (var c : a) m.putIfAbsent(c.dedupKey(), c);

        // 第二组候选只补充新 chunk，不覆盖第一组已经存在的 chunk。
        for (var c : b) m.putIfAbsent(c.dedupKey(), c);

        // 转回 List，交给后续排序和格式化阶段。
        return new ArrayList<>(m.values());
    }
}
