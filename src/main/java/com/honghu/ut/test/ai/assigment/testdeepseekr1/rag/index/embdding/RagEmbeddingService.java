package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.embdding;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
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

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * 缓存当前 embedding 模型的维度。
     *
     * <p>一旦第一次得到向量长度，后续就要求所有结果都保持同一维度，
     * 否则会直接报错，防止不同模型或异常返回混入同一索引集合。</p>
     */
    private volatile Integer cachedDimensions;

    /**
     * 为单条文本生成 embedding 向量。
     *
     * <p>这是最小粒度的向量化入口，适合查询语句或少量文本使用。
     * 方法内部会先拒绝空白文本，再调用底层 {@link EmbeddingModel}，
     * 最后校验返回向量非空、数值合法、维度与历史缓存一致。</p>
     *
     * <p>维度校验很重要：同一个 Milvus collection 只能存固定维度的向量。
     * 如果模型配置被切换、服务端返回异常维度，必须在这里尽早失败，
     * 而不是等到写入 Milvus 时才发现 schema 不匹配。</p>
     *
     * @param text 要向量化的文本，不能是 null、空串或纯空白
     * @return embedding 向量数组
     */
    public float[] embedText(String text) {
        // 单条文本 embedding 的最基础前置校验：空白文本不允许送进模型。
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Embedding text must not be blank");
        }

        // 调用底层模型生成向量。
        float[] vector = embeddingModel().embed(text);

        // 校验返回向量不是 null、不是空数组、且内部数值都是有限数。
        validateVector(vector, "text");

        // 记住维度，供后续批量结果一致性校验使用。
        rememberDimensions(vector.length);
        return vector;
    }

    /**
     * 批量生成多条文本的 embedding 向量。
     *
     * <p>文档 chunk 建索引时会走这个方法，因为批量调用通常比逐条调用更省网络往返。
     * 这里保证“输入第 n 条文本”与“输出第 n 条向量”一一对应；
     * 如果底层模型返回条数不一致，说明结果不能信任，方法会直接抛异常。</p>
     *
     * <p>每个向量都会经过合法性校验，并且整批向量必须维度一致。
     * 第一批成功返回的维度会被缓存，后续所有调用都必须保持相同维度。</p>
     *
     * @param texts 待向量化文本列表；null 或空列表返回空列表
     * @return 与输入顺序一致的向量列表
     */
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
        List<float[]> vectors = embeddingModel().embed(texts);

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

    /**
     * 为一篇文档的 chunk 列表生成向量，并保留 chunk 身份信息。
     *
     * <p>单纯的 {@code List<float[]>} 只知道“第几个向量”，不知道它属于哪个 chunk。
     * 本方法把 documentId、chunkId、chunkIndex 和 vector 重新打包成 {@link ChunkEmbedding}，
     * 这样向量索引层可以直接生成稳定主键并写入 Milvus。</p>
     *
     * <p>注意：这里假设 {@code chunks} 的顺序就是后续写入索引时使用的顺序。
     * 先抽取所有 content 批量 embedding，再按相同下标把向量放回对应 chunk。</p>
     *
     * @param documentId 文档主键；不能为 null
     * @param chunks 已持久化的 chunk 实体列表
     * @return 带 chunk 身份信息的向量结果列表
     */
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
        int resolved = embeddingModel().dimensions();
        if (resolved <= 0) {
            throw new IllegalStateException("Embedding dimension must be positive: " + resolved);
        }
        rememberDimensions(resolved);
        return resolved;
    }

    /**
     * 校验一批向量，并把合法维度写入缓存。
     *
     * <p>这个方法做三件事：</p>
     * <ol>
     *     <li>逐条调用 {@link #validateVector(float[], String)}，确认向量不为空且数值有限；</li>
     *     <li>检查同一批结果中每条向量的长度完全一致；</li>
     *     <li>把解析出的维度交给 {@link #rememberDimensions(int)} 做跨批次一致性校验。</li>
     * </ol>
     *
     * @param vectors 底层模型返回的一批向量
     * @return 本批次的向量维度；空批次返回 0
     */
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

    /**
     * 校验单个 embedding 向量是否可写入向量库。
     *
     * <p>Milvus 向量字段不能接受 null、空向量，也不应该写入 NaN 或 Infinity。
     * 这些脏值一旦进入向量库，会导致检索结果不可解释，甚至让后续相似度计算失败。
     * 所以在模型返回后第一时间拦截。</p>
     *
     * @param vector 待校验向量
     * @param label 日志/异常中用于定位是哪条输入的标签
     */
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

    /**
     * 记录并校验 embedding 维度。
     *
     * <p>第一次成功生成向量时，缓存该模型的维度。
     * 之后任何一次调用如果得到不同维度，都会被视为严重配置问题：
     * 可能是 embedding 模型被切换，也可能是服务端返回异常。
     * 这类问题不能静默继续，否则同一个 Milvus collection 会混入不同维度的数据。</p>
     *
     * @param dimensions 当前向量维度
     */
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
     * 从 Spring 容器中懒获取 {@link EmbeddingModel}。
     *
     * <p>这里使用 {@link ObjectProvider} 而不是构造器强依赖，是为了允许项目在
     * {@code spring.ai.openai.embedding.enabled=false} 时仍然正常启动。
     * 只有真正执行向量化时才要求 embedding 模型存在；如果没有配置，就给出明确错误。</p>
     *
     * @return 当前可用的 embedding 模型 Bean
     */
    private EmbeddingModel embeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("RAG vector embedding is not enabled. Set SPRING_AI_OPENAI_EMBEDDING_ENABLED=true and configure ALIYUN_API_KEY.");
        }
        return embeddingModel;
    }

    /**
     * chunk 向量结果对象。
     *
     * <p>它把文档归属、chunk 标识、chunk 序号和最终向量打包在一起，供索引写入层直接使用。</p>
     */
    public record ChunkEmbedding(Long documentId, Long chunkId, Integer chunkIndex, float[] vector) {
        /**
         * 返回当前向量的维度长度。
         *
         * <p>这个方法主要用于日志、校验和测试断言。
         * 当 vector 为 null 时返回 0，避免调用方为了打印维度还要额外判空。</p>
         *
         * @return 向量长度；vector 为 null 时返回 0
         */
        public int dimensions() {
            return vector == null ? 0 : vector.length;
        }
    }
}
