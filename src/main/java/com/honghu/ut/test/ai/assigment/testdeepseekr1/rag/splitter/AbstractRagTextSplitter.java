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
 */
public abstract class AbstractRagTextSplitter implements RagTextSplitter {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    protected final RagProperties ragProperties;

    protected AbstractRagTextSplitter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public final List<ChunkCandidate> chunk(String text) {
        return chunk(text, ChunkMetadata.EMPTY);
    }

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

    protected abstract List<ChunkCandidate> doChunk(
            String text, SplitterSettings settings, ChunkMetadata baseMetadata);

    protected SplitterSettings settings() {
        RagProperties.Ingestion ig = ragProperties.getIngestion();
        return new SplitterSettings(
                Math.max(1, ig.getChunkSize()),
                Math.max(0, Math.min(ig.getChunkSize() - 1, ig.getChunkOverlap())),
                Math.max(1, ig.getMinChunkLength()),
                Math.max(1, ig.getMaxChunksPerDocument()));
    }

    protected int estimateTokens(String text) {
        return Math.max(1, ENCODING.countTokens(text));
    }

    /** 构造 ChunkCandidate（无 offset）。 */
    protected ChunkCandidate buildChunk(int index, String content, ChunkMetadata base) {
        return buildChunk(index, content, base, -1, -1);
    }

    /** 构造 ChunkCandidate（带 offset）。 */
    protected ChunkCandidate buildChunk(int index, String content, ChunkMetadata base,
                                        int startOffset, int endOffset) {
        ChunkMetadata meta = (base == null ? ChunkMetadata.EMPTY : base)
                .withOffsets(startOffset, endOffset);
        return new ChunkCandidate(index, content, content.length(), estimateTokens(content), meta);
    }

    /** 旧版兼容：无 metadata 的 buildChunk。 */
    protected ChunkCandidate buildChunk(int index, String content) {
        return buildChunk(index, content, ChunkMetadata.EMPTY);
    }

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

    protected int chunkSize() { return settings().chunkSize(); }
    protected int overlap()   { return settings().overlap(); }
    protected int minChunkLength() { return settings().minChunkLength(); }
    protected int maxChunks() { return settings().maxChunks(); }

    protected record SplitterSettings(
            int chunkSize, int overlap, int minChunkLength, int maxChunks) {}
}
