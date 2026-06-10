package com.honghu.ai.assigment.rag.retrieval.scope;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.rag.retrieval.pipeline.RagRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.util.List;

/**
 * 默认作用域解析器。
 *
 * <p>根据 {@link RetrievalScope} 策略和 {@link RagRequest} 中的实际数据，
 * 产出 {@link EffectiveScope} 供后续 CandidateRetriever 使用。</p>
 *
 * <h3>优先级链</h3>
 * <pre>
 *   1. attachmentFileIds 非空  →  primaryType = FILE_IDS
 *   2. chatId 非空             →  primaryType = CHAT
 *   3. sessionId 非空          →  primaryType = SESSION
 *   4. 都不满足                 →  primaryType = EMPTY
 * </pre>
 *
 * <h3>Fallback 规则</h3>
 * <p>当策略允许 fallback 时（如 {@code ATTACHMENT_CHAT_FIRST}），按以下链逐级降级：</p>
 * <pre>
 *   FILE_IDS 命中不足  →  尝试 CHAT
 *   CHAT 命中不足      →  尝试 SESSION
 *   SESSION            →  不再 fallback（终点）
 * </pre>
 *
 * <p>"命中不足"的判断条件（OR 关系）：</p>
 * <ul>
 *   <li>候选数量 {@code < fallbackMinHits}</li>
 *   <li>最高分数 {@code < fallbackMinScore}</li>
 * </ul>
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>ownerFolder 由外部传入（Pipeline 从 UserRepository 查 username），不在 resolver 内部查 DB</li>
 *   <li>所有 scope 路径都必须携带 ownerFolder，供 CandidateRetriever 做鉴权过滤</li>
 *   <li>配置值异常时安全降级到 SESSION_ONLY，避免 NPE</li>
 * </ul>
 *
 * @see RagScopeResolver
 * @see EffectiveScope
 * @see RetrievalScope
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRagScopeResolver implements RagScopeResolver {

    private final RagProperties ragProperties;

    /**
     * 解析检索作用域。
     *
     * <p>优先使用 {@link RagRequest#getScopeOverride()}，如果为 null 则使用配置默认值。</p>
     *
     * @param request      用户请求
     * @param ownerFolder  鉴权用的 ownerFolder（username）
     * @param defaultScope 配置默认策略值（如 "attachment_chat_first"），为 null 时 fallback 到 SESSION_ONLY
     * @return 解析后的 EffectiveScope
     */
    @Override
    public EffectiveScope resolve(RagRequest request, String ownerFolder, String defaultScope) {
        if (request.getScopeOverride() != null) {
            return resolveFromOverride(request, ownerFolder, request.getScopeOverride());
        }
        return resolveFromOverride(request, ownerFolder, parseScope(defaultScope));
    }

    /**
     * 根据明确的作用域策略枚举分发到对应解析方法。
     *
     * <p>外层 {@link #resolve(RagRequest, String, String)} 已经决定了使用请求覆盖值还是默认配置。
     * 到这里时，scope 一定是一个具体枚举。本方法只做策略分派，
     * 不直接拼装检索过滤条件，真正的 DB/Milvus 查询由 CandidateRetriever 完成。</p>
     *
     * @param request 当前 RAG 请求
     * @param ownerFolder 当前调用者的 ownerFolder，用于后续检索鉴权过滤
     * @param scope 已解析出的策略枚举
     * @return 可直接交给检索器使用的实际作用域
     */
    private EffectiveScope resolveFromOverride(RagRequest request, String ownerFolder, RetrievalScope scope) {
        RagProperties.ScopeConfig cfg = ragProperties.getRetrieval().getScope();
        return switch (scope) {
            case ATTACHMENT_CHAT_FIRST -> resolveAttachmentChatFirst(request, ownerFolder, cfg);
            case ATTACHMENT_ONLY -> resolveAttachmentOnly(request, ownerFolder);
            case CHAT_FIRST -> resolveChatFirst(request, ownerFolder, cfg);
            case CHAT_ONLY -> resolveChatOnly(request, ownerFolder);
            case SESSION_ONLY -> resolveSessionOnly(request, ownerFolder);
        };
    }

    /**
     * 解析 ATTACHMENT_CHAT_FIRST 策略。
     *
     * <p>优先级：附件 fileIds → chatId → sessionId。
     * 前两级允许 fallback，session 作为终点不再继续。</p>
     */
    private EffectiveScope resolveAttachmentChatFirst(RagRequest req, String owner, RagProperties.ScopeConfig cfg) {
        if (!CollectionUtils.isEmpty(req.getAttachmentFileIds())) {
            return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.FILE_IDS)
                    .fileIds(req.getAttachmentFileIds()).chatId(req.getChatId())
                    .sessionId(req.getSessionId()).ownerFolder(owner)
                    .allowFallback(true).fallbackMinHits(cfg.getFallbackMinHits())
                    .fallbackMinScore(cfg.getFallbackMinScore()).build();
        }
        if (req.getChatId() != null) {
            return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.CHAT)
                    .chatId(req.getChatId()).sessionId(req.getSessionId()).ownerFolder(owner)
                    .allowFallback(true).fallbackMinHits(cfg.getFallbackMinHits())
                    .fallbackMinScore(cfg.getFallbackMinScore()).build();
        }
        return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.SESSION)
                .sessionId(req.getSessionId()).ownerFolder(owner).allowFallback(false).build();
    }

    /**
     * 解析 ATTACHMENT_ONLY 策略。
     *
     * <p>严格只查附件文件，无附件时返回 EMPTY。</p>
     */
    private EffectiveScope resolveAttachmentOnly(RagRequest req, String owner) {
        List<String> fids = req.getAttachmentFileIds();
        if (CollectionUtils.isEmpty(fids)) {
            log.debug("ATTACHMENT_ONLY 但没有附件，返回 EMPTY scope");
            return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.EMPTY)
                    .ownerFolder(owner).allowFallback(false).build();
        }
        return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.FILE_IDS)
                .fileIds(fids).ownerFolder(owner).allowFallback(false).build();
    }

    /**
     * 解析 CHAT_FIRST 策略。
     *
     * <p>优先 chatId，允许 fallback 到 session。</p>
     */
    private EffectiveScope resolveChatFirst(RagRequest req, String owner, RagProperties.ScopeConfig cfg) {
        if (req.getChatId() != null) {
            return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.CHAT)
                    .chatId(req.getChatId()).sessionId(req.getSessionId()).ownerFolder(owner)
                    .allowFallback(true).fallbackMinHits(cfg.getFallbackMinHits())
                    .fallbackMinScore(cfg.getFallbackMinScore()).build();
        }
        return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.SESSION)
                .sessionId(req.getSessionId()).ownerFolder(owner).allowFallback(false).build();
    }

    /**
     * 解析 CHAT_ONLY 策略。
     *
     * <p>严格只查当前 chat，无 chatId 时返回 EMPTY。</p>
     */
    private EffectiveScope resolveChatOnly(RagRequest req, String owner) {
        if (req.getChatId() == null) {
            log.debug("CHAT_ONLY 但没有 chatId，返回 EMPTY scope");
            return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.EMPTY)
                    .ownerFolder(owner).allowFallback(false).build();
        }
        return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.CHAT)
                .chatId(req.getChatId()).ownerFolder(owner).allowFallback(false).build();
    }

    /**
     * 解析 SESSION_ONLY 策略。
     *
     * <p>旧默认行为，仅按 sessionId 检索。</p>
     */
    private EffectiveScope resolveSessionOnly(RagRequest req, String owner) {
        if (!StringUtils.hasText(req.getSessionId())) {
            return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.EMPTY)
                    .ownerFolder(owner).allowFallback(false).build();
        }
        return EffectiveScope.builder().primaryType(EffectiveScope.ScopeType.SESSION)
                .sessionId(req.getSessionId()).ownerFolder(owner).allowFallback(false).build();
    }

    /**
     * 将配置字符串解析为 {@link RetrievalScope} 枚举。
     *
     * <p>非法值时安全降级到 SESSION_ONLY，并记录 warn 日志。</p>
     */
    private static RetrievalScope parseScope(String scope) {
        if (!StringUtils.hasText(scope)) return RetrievalScope.SESSION_ONLY;
        try { return RetrievalScope.valueOf(scope.toUpperCase()); }
        catch (IllegalArgumentException e) {
            log.warn("未知的作用域配置 '{}'，fallback 到 SESSION_ONLY", scope);
            return RetrievalScope.SESSION_ONLY;
        }
    }
}
