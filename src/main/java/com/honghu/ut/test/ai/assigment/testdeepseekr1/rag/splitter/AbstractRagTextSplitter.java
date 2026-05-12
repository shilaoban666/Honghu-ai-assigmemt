package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 文本切分器抽象基类（强类型 metadata）。
 *
 * <p>这个类的定位是：把所有 splitter 都会重复写的一批“公共骨架逻辑”集中起来，
 * 让具体实现类只专注于“如何决定边界”本身。</p>
 *
 * <p>它统一解决的事情包括：</p>
 * <ul>
 *   <li>空文本保护；</li>
 *   <li>从配置读取 chunkSize / overlap / minChunkLength / maxChunks；</li>
 *   <li>metadata 空值兜底；</li>
 *   <li>token 估算；</li>
 *   <li>构造 {@link ChunkCandidate}；</li>
 *   <li>尾部过短 chunk 合并；</li>
 *   <li>自然边界微调。</li>
 * </ul>
 *
 * <p>因此，子类通常只需要实现一个方法：
 * {@link #doChunk(String, SplitterSettings, ChunkMetadata)}，
 * 用自己的算法把文本拆成一组 chunk 即可。</p>
 */
public abstract class AbstractRagTextSplitter implements RagTextSplitter {

    /**
     * jtokkit 编码注册表。
     *
     * <p>这里使用和 OpenAI 常见模型接近的 CL100K_BASE 编码方式做 token 粗估，
     * 不是为了绝对精确，而是为了给 chunk 打上一个相对稳定的 tokenEstimate 字段。</p>
     */
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    /** 具体用于 token 估算的编码实例。 */
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /** 全局 RAG 配置，所有 splitter 的参数都从这里读取。 */
    protected final RagProperties ragProperties;

    /**
     * 构造抽象切分器基类。
     *
     * @param ragProperties RAG 全局配置，后续切分参数都从这里读取
     */
    protected AbstractRagTextSplitter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    /**
     * 使用空 metadata 执行切分。
     *
     * <p>这是最常用的入口，适合调用方暂时不关心 chunk 偏移、页码、章节等附加信息的场景。</p>
     *
     * @param text 已完成清洗的文本
     * @return 切分结果列表
     */
    public final List<ChunkCandidate> chunk(String text) {
        // 无 metadata 版本统一转调到带 metadata 的正式入口。
        // 这样所有公共逻辑只有一份实现，不会出现两个入口行为不一致。
        return chunk(text, ChunkMetadata.EMPTY);
    }

    @Override
    /**
     * 统一的正式切分入口。
     *
     * <p>所有具体 splitter 都通过这个模板流程进入：</p>
     * <ol>
     *   <li>判空；</li>
     *   <li>读取配置；</li>
     *   <li>兜底 metadata；</li>
     *   <li>调用子类算法；</li>
     *   <li>裁剪最大 chunk 数；</li>
     *   <li>合并尾部过短分块。</li>
     * </ol>
     *
     * @param text 已清洗文本
     * @param baseMetadata 基础元数据，可为空
     * @return 最终规范化后的 chunk 列表
     */
    public final List<ChunkCandidate> chunk(String text, ChunkMetadata baseMetadata) {
        // 第一步：空文本直接返回空列表。
        // 这样子类就不需要在每个实现里反复写同样的判空逻辑。
        if (!StringUtils.hasText(text)) return List.of();

        // 第二步：从配置生成本次切分要用的参数快照。
        SplitterSettings settings = settings();

        // 第三步：把 metadata 空值兜底成 EMPTY，避免子类到处判 null。
        ChunkMetadata safeBase = baseMetadata == null ? ChunkMetadata.EMPTY : baseMetadata;

        // 第四步：把文本 trim 后交给真正的切分算法实现。
        // trim 的目的，是把首尾纯空白先去掉，避免它们被错误地算进首块/尾块。
        List<ChunkCandidate> chunks = doChunk(text.trim(), settings, safeBase);

        // 第五步：子类若返回 null 或空列表，统一规范为空结果。
        if (chunks == null || chunks.isEmpty()) return List.of();

        // 第六步：应用全局最大 chunk 数限制，防止异常文本切出过多分块。
        if (chunks.size() > settings.maxChunks()) chunks = chunks.subList(0, settings.maxChunks());

        // 第七步：如果最后一块过短，就和前一块合并，减少“碎尾巴”片段。
        return mergeTrailingShortChunk(chunks, settings.minChunkLength());
    }

    /**
     * 具体切分算法扩展点。
     *
     * @param text 已经过 trim 且非空的文本
     * @param settings 规范化后的切分参数
     * @param baseMetadata 基础 metadata，上层已保证非 null
     * @return 切分结果；允许返回空列表，但不建议返回 null
     */
    protected abstract List<ChunkCandidate> doChunk(
            String text, SplitterSettings settings, ChunkMetadata baseMetadata);

    /**
     * 从配置对象生成一次性的切分参数快照。
     *
     * <p>这里顺便做了所有边界保护，保证子类拿到的参数始终可用。</p>
     *
     * @return 规范化后的切分参数
     */
    protected SplitterSettings settings() {
        // 先取出 ingestion 配置对象，避免后面重复链式访问。
        RagProperties.Ingestion ig = ragProperties.getIngestion();

        // chunkSize 至少为 1；否则窗口推进、长度判断等逻辑会全部失效。
        // overlap 同时做上下界限制：不能为负，也不能 >= chunkSize。
        // minChunkLength 和 maxChunks 也统一做最小值保护，避免异常配置拖垮算法。
        return new SplitterSettings(
                Math.max(1, ig.getChunkSize()),
                Math.max(0, Math.min(ig.getChunkSize() - 1, ig.getChunkOverlap())),
                Math.max(1, ig.getMinChunkLength()),
                Math.max(1, ig.getMaxChunksPerDocument()));
    }

    /**
     * 估算一段文本对应的大致 token 数。
     *
     * <p>这个值主要用于统计与调试，不要求绝对精确，但要尽量稳定。</p>
     *
     * @param text 文本内容
     * @return 估算出的 token 数，最少返回 1
     */
    protected int estimateTokens(String text) {
        // token 至少返回 1，避免极短文本出现 0 这种不利于统计的值。
        return Math.max(1, ENCODING.countTokens(text));
    }

    /**
     * 构造一个不显式传 offset 的 chunk。
     *
     * <p>适用于子类已经不关心原始位置，只想快速生成 chunk 的场景。</p>
     */
    protected ChunkCandidate buildChunk(int index, String content, ChunkMetadata base) {
        return buildChunk(index, content, base, -1, -1);
    }

    /**
     * 构造一个显式带 offset 的 chunk。
     *
     * <p>这里会把传入的 base metadata 复制一份并叠加 cleanedStartOffset / cleanedEndOffset，
     * 让后续下游既知道这块来自哪里，也知道它在清洗后文本中的具体位置区间。</p>
     */
    protected ChunkCandidate buildChunk(int index, String content, ChunkMetadata base,
                                        int startOffset, int endOffset) {
        // 先把基础 metadata 做 null 安全兜底，再把当前 chunk 的 offset 写进去。
        ChunkMetadata meta = (base == null ? ChunkMetadata.EMPTY : base)
                .withOffsets(startOffset, endOffset);

        // 统一在这里封装 chunk，保证 charCount 与 tokenEstimate 的计算方式全项目一致。
        return new ChunkCandidate(index, content, content.length(), estimateTokens(content), meta);
    }

    /**
     * 旧版兼容辅助方法。
     *
     * <p>给仍然只传 index + content 的调用点一个默认入口，内部自动附带 EMPTY metadata。</p>
     */
    protected ChunkCandidate buildChunk(int index, String content) {
        return buildChunk(index, content, ChunkMetadata.EMPTY);
    }

    /**
     * 合并尾部过短 chunk。
     *
     * <p>如果最后一个分块太短，单独作为检索片段的价值通常不高，
     * 还会让最后一块看起来像“残片”。因此这里把它并到前一块中。</p>
     *
     * @param chunks 原始切分结果
     * @param minChunkLength 判定“过短”的阈值
     * @return 合并后的结果；如果无需合并，则返回原列表或其只读视图
     */
    protected List<ChunkCandidate> mergeTrailingShortChunk(List<ChunkCandidate> chunks, int minChunkLength) {
        // 只有一个 chunk 或更少时，不存在“尾块合并”问题，直接返回即可。
        if (chunks.size() <= 1) return chunks;

        // 拷贝成可变列表，方便后面做替换与 remove。
        List<ChunkCandidate> mutable = new ArrayList<>(chunks);

        // 取出最后一个 chunk，检查它是否短到不值得单独保存。
        ChunkCandidate last = mutable.get(mutable.size() - 1);
        if (last.charCount() < minChunkLength) {
            // 如果最后一块过短，就把它并到倒数第二块后面。
            ChunkCandidate prev = mutable.get(mutable.size() - 2);
            String merged = prev.content() + "\n" + last.content();

            // 合并后的结束 offset 尽量继承最后一块的结束位置；
            // 如果最后一块没有有效 endOffset，就退回前一块自己的 endOffset。
            int lastEnd = last.metadata().cleanedEndOffset() != null && last.metadata().cleanedEndOffset() >= 0
                    ? last.metadata().cleanedEndOffset() : prev.metadata().cleanedEndOffset();

            // 新 metadata 采用“前一块起点 + 最后一块终点”的组合，表示合并后覆盖的整体范围。
            ChunkMetadata mergedMeta = prev.metadata().withOffsets(
                    prev.metadata().cleanedStartOffset(), lastEnd);

            // 用合并后的新块替换倒数第二块，再删除旧的最后一块。
            mutable.set(mutable.size() - 2,
                    new ChunkCandidate(prev.chunkIndex(), merged, merged.length(),
                            estimateTokens(merged), mergedMeta));
            mutable.remove(mutable.size() - 1);
        }

        // 返回只读视图，防止外部再次无意改写 splitter 的输出结果。
        return Collections.unmodifiableList(mutable);
    }

    /**
     * 在原始结束点附近寻找更自然的结束边界。
     *
     * <p>固定窗口切分常见的问题是会在单词中间、句子中间硬切断。
     * 这个方法会尝试把结束点往前微调到换行或句末标点之后。</p>
     *
     * @param text 完整文本
     * @param start 当前 chunk 起点
     * @param rawEnd 原始结束点
     * @param minChunkLength 当前 chunk 允许的最短长度
     * @return 调整后的结束点；如果找不到更合适边界，则返回 rawEnd
     */
    protected int adjustChunkEnd(String text, int start, int rawEnd, int minChunkLength) {
        // 如果原始结束点已经到达文本尾部，就无需再寻找更自然的边界。
        if (rawEnd >= text.length()) return rawEnd;

        // 搜索范围既不能回退太远，也不能让 chunk 缩短到小于 minChunkLength。
        int searchStart = Math.max(start + minChunkLength, rawEnd - 80);
        for (int i = rawEnd; i >= searchStart; i--) {
            char c = text.charAt(i - 1);
            // 找到换行或常见句末标点时，就把结束点收敛到这里，让 chunk 更像自然语言片段。
            if (c == '\n' || c == '。' || c == '！' || c == '？'
                    || c == '.' || c == '!' || c == '?' || c == ';' || c == '；') return i;
        }

        // 如果附近找不到更好的边界，就保留原始结束点，不强行过度回退。
        return rawEnd;
    }

    /**
     * 便捷访问当前配置下的目标 chunkSize。
     *
     * @return 目标 chunk 字符长度
     */
    protected int chunkSize() { return settings().chunkSize(); }
    /**
     * 便捷访问当前配置下的 overlap。
     *
     * @return 相邻 chunk 的重叠字符数
     */
    protected int overlap()   { return settings().overlap(); }
    /**
     * 便捷访问当前配置下的最短 chunk 长度。
     *
     * @return 尾块合并判断所用的最小长度阈值
     */
    protected int minChunkLength() { return settings().minChunkLength(); }
    /**
     * 便捷访问当前配置下的最大 chunk 数。
     *
     * @return 单文档最多允许产出的 chunk 数量
     */
    protected int maxChunks() { return settings().maxChunks(); }

    /**
     * 切分参数快照。
     *
     * <p>把配置值先整理成一个小 record，后续算法只和这个快照打交道，
     * 可以减少对外部配置对象的耦合，也让方法参数更清晰。</p>
     */
    protected record SplitterSettings(
            int chunkSize, int overlap, int minChunkLength, int maxChunks) {}
}
