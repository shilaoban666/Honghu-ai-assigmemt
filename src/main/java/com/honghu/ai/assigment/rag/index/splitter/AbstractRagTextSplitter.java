package com.honghu.ai.assigment.rag.index.splitter;

import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.dto.record.ChunkCandidate;
import com.honghu.ai.assigment.rag.ChunkMetadata;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 文本切分器抽象基类。
 *
 * <h2>职责</h2>
 * <ol>
 *     <li>统一从 {@link RagProperties.Ingestion} 读取配置，构造 {@link SplitterSettings} 快照</li>
 *     <li>提供 token 估算（CL100K_BASE）、chunk 构造、尾部合并等公共能力</li>
 *     <li>保证每个输出 chunk 的 {@link ChunkMetadata} 都继承输入的 base metadata</li>
 * </ol>
 *
 * <h2>metadata 继承链</h2>
 * <pre>
 *   ingestion → baseMetadata (documentId, sessionId, fileId, fileName, fileType, source)
 *            → splitter.buildChunk(index, content, base)
 *            → ChunkCandidate.metadata = base.withOffsets(start, end)
 *            → DB: chunk.metadata().toMap() → JSONB
 *            → Milvus: chunk.metadata().toMap() → Map
 *            → retrieval: ChunkMetadata.fromMap(map) → RagSnippet.metadata
 * </pre>
 *
 * <h2>子类实现约定</h2>
 * <ul>
 *     <li>实现 {@link #doChunk(String, SplitterSettings, ChunkMetadata)} 编写切分算法</li>
 *     <li>空/空白文本已在 {@link #chunk(String)} 中统一处理，子类无需重复判断</li>
 *     <li>chunkIndex 必须从 0 连续递增</li>
 *     <li>通过 {@link #buildChunk(int, String, ChunkMetadata, int, int)} 产出 chunk，
 *         确保每个 chunk 都携带完整的 tokenEstimate 和 metadata</li>
 * </ul>
 */
public abstract class AbstractRagTextSplitter implements RagTextSplitter {

    /** CL100K_BASE 编码器，用于估算文本 token 数（与 OpenAI / DashScope 模型对齐）。 */
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    protected final RagProperties ragProperties;

    /**
     * 构造切分器基类。
     *
     * <p>所有具体 splitter 都需要读取 {@link RagProperties} 中的切分配置，
     * 例如 chunkSize、overlap、minChunkLength 和 maxChunksPerDocument。
     * 父类统一保存配置对象，子类只需要关注自己的切分算法。</p>
     *
     * @param ragProperties RAG 配置对象，不能为 null
     */
    protected AbstractRagTextSplitter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    // ═══════════════════════════════════════════════
    // 公共入口（模板方法）
    // ═══════════════════════════════════════════════

    /**
     * 无 metadata 切分（等价于 {@code chunk(text, ChunkMetadata.EMPTY)}）。
     *
     * <p>用于测试和不需要文档上下文的场景。</p>
     */
    @Override
    public final List<ChunkCandidate> chunk(String text) {
        return chunk(text, ChunkMetadata.EMPTY);
    }

    /**
     * 带 base metadata 的切分。
     *
     * <p>执行流程：空文本过滤 → 配置快照 → trim → 调用子类 doChunk →
     * maxChunks 截断 → 尾部短 chunk 合并。</p>
     */
    @Override
    public final List<ChunkCandidate> chunk(String text, ChunkMetadata baseMetadata) {
        if (!StringUtils.hasText(text)) return List.of();
        SplitterSettings settings = settings();
        ChunkMetadata safeBase = baseMetadata == null ? ChunkMetadata.EMPTY : baseMetadata;
        List<ChunkCandidate> chunks = doChunk(text.trim(), settings, safeBase);
        if (chunks == null || chunks.isEmpty()) return List.of();
        if (chunks.size() > settings.maxChunks()) chunks = chunks.subList(0, settings.maxChunks());
        return mergeTrailingShortChunk(chunks, settings.minChunkLength());
    }

    // ═══════════════════════════════════════════════
    // 子类必须实现的切分算法
    // ═══════════════════════════════════════════════

    /**
     * 核心切分算法。
     *
     * @param text     已 trim 的非空文本
     * @param settings 从 RagProperties 解析出的切分配置快照
     * @param baseMetadata 已 sanitize 的基础 metadata，每个输出 chunk 都应继承此 metadata
     * @return chunk 列表（chunkIndex 从 0 连续递增）
     */
    protected abstract List<ChunkCandidate> doChunk(
            String text, SplitterSettings settings, ChunkMetadata baseMetadata);

    // ═══════════════════════════════════════════════
    // 公共工具方法
    // ═══════════════════════════════════════════════

    /**
     * 从 {@link RagProperties} 读取切分配置，并构造一次性的规范化快照。
     *
     * <p>这里做了多层防御：</p>
     * <ul>
     *     <li>{@code chunkSize} 至少为 1，避免后续 substring 或窗口推进出现非法范围；</li>
     *     <li>{@code overlap} 不能小于 0，也不能大于等于 chunkSize，否则窗口可能无法前进；</li>
     *     <li>{@code minChunkLength} 至少为 1，保证尾部短 chunk 合并逻辑有明确阈值；</li>
     *     <li>{@code maxChunks} 至少为 1，避免单文档被配置成完全不产出 chunk。</li>
     * </ul>
     *
     * <p>返回 record 快照，而不是让子类循环中反复读配置，是为了让一次 chunk 过程使用同一组稳定参数。</p>
     *
     * @return 本次切分使用的安全配置快照
     */
    protected SplitterSettings settings() {
        RagProperties.Ingestion ig = ragProperties.getIngestion();
        return new SplitterSettings(
                Math.max(1, ig.getChunkSize()),
                Math.max(0, Math.min(ig.getChunkSize() - 1, ig.getChunkOverlap())),
                Math.max(1, ig.getMinChunkLength()),
                Math.max(1, ig.getMaxChunksPerDocument()));
    }

    /**
     * 使用 CL100K_BASE 编码器估算文本 token 数。
     *
     * <p>这里是估算，不是严格模型计费结果。它的用途主要是写入 chunk 元数据，
     * 让后续检索、展示和调试时能大致知道每个 chunk 的 token 体量。</p>
     *
     * <p>最低返回 1，是为了避免空串或极短文本在指标上表现成 0 token。
     * 进入本方法的 content 通常已经是有效 chunk 文本。</p>
     *
     * @param text 要估算的文本
     * @return 至少为 1 的 token 估算值
     */
    protected int estimateTokens(String text) {
        return Math.max(1, ENCODING.countTokens(text));
    }

    /**
     * 构造 ChunkCandidate（无 offset）。
     *
     * <p>offset 设为 -1，表示当前 splitter 未计算真实偏移。</p>
     */
    protected ChunkCandidate buildChunk(int index, String content, ChunkMetadata base) {
        return buildChunk(index, content, base, -1, -1);
    }

    /**
     * 构造 ChunkCandidate（带真实文本偏移）。
     *
     * <p>调用 {@link ChunkMetadata#withOffsets(Integer, Integer)} 生成新的 metadata copy，
     * 不修改传入的 base metadata 对象。</p>
     *
     * @param index      chunk 序号（从 0 递增）
     * @param content    chunk 正文
     * @param base       基础 metadata（来自 ingestion 阶段）
     * @param startOffset cleaner 处理后文本中的起始偏移
     * @param endOffset   cleaner 处理后文本中的结束偏移
     */
    protected ChunkCandidate buildChunk(int index, String content, ChunkMetadata base,
                                        int startOffset, int endOffset) {
        ChunkMetadata meta = (base == null ? ChunkMetadata.EMPTY : base)
                .withOffsets(startOffset, endOffset);
        return new ChunkCandidate(index, content, content.length(), estimateTokens(content), meta);
    }

    /**
     * 构造不携带业务 metadata 的 chunk。
     *
     * <p>这是旧代码和部分单元测试使用的便捷方法。
     * 它会自动使用 {@link ChunkMetadata#EMPTY} 作为基础 metadata，
     * 因此产出的 chunk 不带 documentId、fileName、ownerFolder 等业务上下文。</p>
     *
     * <p>生产摄取链路更推荐调用 {@link #buildChunk(int, String, ChunkMetadata)} 或
     * {@link #buildChunk(int, String, ChunkMetadata, int, int)}，
     * 以确保每个 chunk 都能在检索结果中追溯到原文档。</p>
     *
     * @param index chunk 序号
     * @param content chunk 正文
     * @return 不带业务 metadata 的 chunk 候选
     */
    protected ChunkCandidate buildChunk(int index, String content) {
        return buildChunk(index, content, ChunkMetadata.EMPTY);
    }

    /**
     * 尾 chunk 过短时合并到前一个。
     *
     * <p>合并时保留 previous 的 base metadata（documentId/fileName 等）和
     * cleanedStartOffset；cleanedEndOffset 优先用 last 的有效值。</p>
     */
    protected List<ChunkCandidate> mergeTrailingShortChunk(List<ChunkCandidate> chunks, int minChunkLength) {
        if (chunks.size() <= 1) return chunks;
        List<ChunkCandidate> mutable = new ArrayList<>(chunks);
        ChunkCandidate last = mutable.get(mutable.size() - 1);
        if (last.charCount() < minChunkLength) {
            ChunkCandidate prev = mutable.get(mutable.size() - 2);
            String merged = prev.content() + "\n" + last.content();
            int lastEnd = last.metadata().cleanedEndOffset() != null && last.metadata().cleanedEndOffset() >= 0
                    ? last.metadata().cleanedEndOffset() : prev.metadata().cleanedEndOffset();
            ChunkMetadata mergedMeta = prev.metadata().withOffsets(
                    prev.metadata().cleanedStartOffset(), lastEnd);
            mutable.set(mutable.size() - 2,
                    new ChunkCandidate(prev.chunkIndex(), merged, merged.length(),
                            estimateTokens(merged), mergedMeta));
            mutable.remove(mutable.size() - 1);
        }
        return Collections.unmodifiableList(mutable);
    }

    /**
     * 尝试把 chunk 结束位置回退到自然文本边界。
     *
     * <p>分隔符优先级：换行 → 中文句末标点（。！？）→ 英文句末标点（. ! ?）→ 分号（；;）。
     * 回退范围不超过 80 个字符。</p>
     */
    protected int adjustChunkEnd(String text, int start, int rawEnd, int minChunkLength) {
        if (rawEnd >= text.length()) return rawEnd;
        int searchStart = Math.max(start + minChunkLength, rawEnd - 80);
        for (int i = rawEnd; i >= searchStart; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？'
                    || c == '.' || c == '!' || c == '?' || c == ';' || c == '；') return i;
        }
        return rawEnd;
    }

    // ─── 便捷访问器 ────────────────────────────────

    /**
     * 读取当前配置下的目标 chunk 字符数。
     *
     * <p>这是给子类使用的便捷访问器，内部仍会经过 {@link #settings()} 的规范化逻辑，
     * 因此返回值至少为 1。</p>
     *
     * @return 单个 chunk 的目标字符数
     */
    protected int chunkSize()     { return settings().chunkSize(); }

    /**
     * 读取当前配置下的相邻 chunk 重叠字符数。
     *
     * <p>重叠的作用是保留跨 chunk 边界的上下文，降低一句话被切断后检索语义变弱的概率。
     * 返回值会被限制在 {@code [0, chunkSize - 1]} 范围内，确保窗口能够向前推进。</p>
     *
     * @return 相邻 chunk 的重叠字符数
     */
    protected int overlap()       { return settings().overlap(); }

    /**
     * 读取尾部短 chunk 的合并阈值。
     *
     * <p>如果最后一个 chunk 太短，父类会把它合并到前一个 chunk，
     * 避免生成几乎没有检索价值的碎片。</p>
     *
     * @return 最小 chunk 字符数阈值
     */
    protected int minChunkLength() { return settings().minChunkLength(); }

    /**
     * 读取单文档最多允许产出的 chunk 数量。
     *
     * <p>这个上限用于防止异常长文档或异常配置导致一次摄取生成过多 chunk，
     * 从而拖垮数据库写入、embedding 调用或向量库写入。</p>
     *
     * @return 单文档最大 chunk 数
     */
    protected int maxChunks()     { return settings().maxChunks(); }

    // ═══════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════

    /**
     * 切分配置快照，由 {@link #settings()} 从 RagProperties 一次性构造。
     *
     * <p>设计为 record 而非直接从 RagProperties 读取，原因是：
     * <ul>
     *     <li>避免在循环中反复访问配置对象</li>
     *     <li>方便子类在 fallback 时用不同参数构造新的 SplitterSettings</li>
     * </ul>
     */
    protected record SplitterSettings(
            int chunkSize,      // 单个 chunk 的目标字符数
            int overlap,        // 相邻 chunk 间保留的重叠字符数
            int minChunkLength, // 过短 chunk 的合并阈值
            int maxChunks       // 单文档最多产出多少个 chunk
    ) {}
}
