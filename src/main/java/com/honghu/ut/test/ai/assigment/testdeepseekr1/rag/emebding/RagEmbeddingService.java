package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 向量生成服务。
 *
 * <p>这个类是“文本 -> embedding 向量”这一步的统一边界。</p>
 *
 * <p>之所以单独抽出来，而不是让别的类直接去调 {@link EmbeddingModel}，是为了集中解决几类问题：</p>
 * <ul>
 *   <li>空文本校验；</li>
 *   <li>返回向量合法性校验；</li>
 *   <li>维度一致性校验；</li>
 *   <li>批量嵌入与 chunk 嵌入的统一封装。</li>
 * </ul>
 *
 * <p>这样做之后，向量索引层只需要关心“我要哪些文本的向量”，而不用关心调用模型时的细节与防御性检查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 缓存当前 embedding 模型的维度。
     *
     * <p>一旦第一次得到向量长度，后续就要求所有结果都保持同一维度，
     * 否则会直接报错，防止不同模型或异常返回混入同一索引集合。</p>
     */
    private volatile Integer cachedDimensions;

    public float[] embedText(String text) {
        // 单条文本 embedding 的最基础前置校验：空白文本不允许送进模型。
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Embedding text must not be blank");
        }

        // 调用底层模型生成向量。
        float[] vector = embeddingModel.embed(text);

        // 校验返回向量不是 null、不是空数组、且内部数值都是有限数。
        validateVector(vector, "text");

        // 记住维度，供后续批量结果一致性校验使用。
        rememberDimensions(vector.length);
        return vector;
    }

    public List<float[]> embedTexts(List<String> texts) {
        // 空列表直接返回空结果，避免无意义调用模型。
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        // 批量调用前逐条检查，明确指出是哪一个索引位置的文本非法。
        for (int i = 0; i < texts.size(); i++) {
            if (!StringUtils.hasText(texts.get(i))) {
                throw new IllegalArgumentException("Embedding text must not be blank, index=" + i);
            }
        }

        // 记录耗时，便于观察 embedding 性能。
        long startedAt = System.currentTimeMillis();

        // 让底层模型一次性处理整批文本，通常比逐条调用效率更高。
        List<float[]> vectors = embeddingModel.embed(texts);

        // 结果条数必须与输入条数一一对应，否则说明模型返回不可信。
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding result size mismatch: expected="
                    + texts.size() + ", actual=" + vectors.size());
        }

        // 统一校验每个向量是否合法、维度是否一致，并把维度缓存下来。
        int dimensions = validateAndRemember(vectors);
        log.info("RAG embeddings generated: count={}, dimensions={}, durationMs={}",
                vectors.size(),
                dimensions,
                System.currentTimeMillis() - startedAt);
        return vectors;
    }

    public List<ChunkEmbedding> embedChunks(Long documentId, List<RagDocumentChunk> chunks) {
        // 没有 chunk 就没有必要生成向量。
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        // documentId 是向量记录的重要归属标识，不允许缺失。
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null when embedding chunks");
        }

        // 从实体 chunk 中抽取正文文本，准备批量送入 embedding 模型。
        List<String> texts = chunks.stream()
                .map(RagDocumentChunk::getContent)
                .toList();
        List<float[]> vectors = embedTexts(texts);

        // 把“原始 chunk + 对应向量”重新组装成更适合索引层消费的结构。
        List<ChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RagDocumentChunk chunk = chunks.get(i);
            embeddings.add(new ChunkEmbedding(
                    documentId,
                    chunk.getChunkId(),
                    chunk.getChunkIndex(),
                    vectors.get(i)));
        }
        return embeddings;
    }

    /**
     * 懒加载获取模型维度。
     *
     * <p>Spring AI 某些实现会通过一次真实 embedding 调用来推断维度，
     * 所以这个方法不适合放在高频热路径里反复调用。</p>
     */
    public int dimensions() {
        Integer dimensions = cachedDimensions;
        if (dimensions != null) {
            return dimensions;
        }

        // 如果缓存还没有值，就向底层模型查询一次真实维度。
        int resolved = embeddingModel.dimensions();
        if (resolved <= 0) {
            throw new IllegalStateException("Embedding dimension must be positive: " + resolved);
        }
        rememberDimensions(resolved);
        return resolved;
    }

    private int validateAndRemember(List<float[]> vectors) {
        Integer dimensions = null;
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            // 逐条检查每一个向量是否合法。
            validateVector(vector, "index=" + i);
            if (dimensions == null) {
                // 第一条向量的长度视为本批次标准维度。
                dimensions = vector.length;
            } else if (dimensions != vector.length) {
                // 同一批次中如果出现不同维度，说明结果异常，必须立即中止。
                throw new IllegalStateException("Embedding dimension mismatch in one batch: expected="
                        + dimensions + ", actual=" + vector.length + ", index=" + i);
            }
        }
        int resolved = dimensions == null ? 0 : dimensions;
        if (resolved > 0) {
            rememberDimensions(resolved);
        }
        return resolved;
    }

    private void validateVector(float[] vector, String label) {
        // 向量为 null 或长度为 0 都属于无效结果。
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Embedding vector is empty: " + label);
        }
        for (float value : vector) {
            // NaN / Infinity 这类非有限值会污染向量库，必须在入口就拦截。
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Embedding vector contains non-finite value: " + label);
            }
        }
    }

    private void rememberDimensions(int dimensions) {
        Integer cached = cachedDimensions;
        // 一旦缓存过维度，后续不允许悄悄变化，否则说明模型或返回结果前后不一致。
        if (cached != null && cached != dimensions) {
            throw new IllegalStateException("Embedding dimension changed: cached="
                    + cached + ", actual=" + dimensions);
        }
        cachedDimensions = dimensions;
    }

    /**
     * chunk 向量结果对象。
     *
     * <p>它把文档归属、chunk 标识、chunk 序号和最终向量打包在一起，供索引写入层直接使用。</p>
     */
    public record ChunkEmbedding(Long documentId, Long chunkId, Integer chunkIndex, float[] vector) {
        /** 返回当前向量的维度长度。 */
        public int dimensions() {
            return vector == null ? 0 : vector.length;
        }
    }
}
