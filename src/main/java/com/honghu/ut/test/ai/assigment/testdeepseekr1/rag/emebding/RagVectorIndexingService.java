package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocument;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagDocumentChunk;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding.RagEmbeddingService.ChunkEmbedding;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.vectorstore.MilvusVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * RAG 向量索引写入服务。
 *
 * <p>这个类负责串起“向量索引更新”这条完整链路，是索引侧的总协调者。</p>
 *
 * <p>它负责的步骤包括：</p>
 * <ol>
 *   <li>根据文档 ID 和 chunk 序号推导旧向量 ID；</li>
 *   <li>尽量删除旧的 Milvus 向量记录；</li>
 *   <li>调用 {@link RagEmbeddingService} 为当前 chunk 批量生成向量；</li>
 *   <li>把 chunk 内容、元数据、向量重新组装成 Milvus 可写入记录；</li>
 *   <li>批量插入 Milvus，完成一次新的向量索引替换。</li>
 * </ol>
 *
 * <p>它本身不直接实现 embedding 算法，也不直接处理 Milvus SDK 细节，
 * 而是把两边都委托给更专门的服务，这样职责更清晰。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagVectorIndexingService {

    private final RagEmbeddingService embeddingService;
    private final MilvusVectorRepository milvusVectorRepository;

    public void replaceVectorIndex(Long documentId,
                                   List<RagDocumentChunk> persistedChunks,
                                   Integer previousChunkCount) {
        // 没有文档 ID 或没有 chunk 时，直接跳过，说明当前没有可写入的向量索引。
        if (documentId == null || persistedChunks == null || persistedChunks.isEmpty()) {
            return;
        }

        // 取任意一个 chunk 关联的文档实体，用于后面补齐元数据。
        RagDocument document = persistedChunks.get(0).getDocument();

        // 删除旧向量时，取“旧 chunk 数量”和“当前 chunk 数量”的较大值，
        // 这样即使新文档变短，也能把历史遗留的尾部旧向量一并清掉。
        int deleteCount = Math.max(previousChunkCount == null ? 0 : previousChunkCount, persistedChunks.size());
        List<String> idsToDelete = IntStream.range(0, deleteCount)
                .mapToObj(index -> buildVectorId(documentId, index))
                .toList();

        try {
            // 先删旧数据，保证后面写新数据时不会和上一次索引结果混在一起。
            milvusVectorRepository.deleteByIds(idsToDelete);
        } catch (Exception ex) {
            // 这里删除失败只记 debug，不直接中断。
            // 原因是很多场景下第一次建索引本来就没有旧数据可删。
            log.debug("Milvus old vector delete ignored: documentId={}, err={}", documentId, ex.getMessage());
        }

        // 为最新 chunk 批量生成向量。
        List<ChunkEmbedding> embeddings = embeddingService.embedChunks(documentId, persistedChunks);

        // 把每个 chunk 和它对应的向量打包成 Milvus 可写入的记录。
        List<MilvusVectorRepository.VectorRecord> records = IntStream.range(0, persistedChunks.size())
                .mapToObj(index -> toVectorRecord(document, persistedChunks.get(index), embeddings.get(index)))
                .toList();

        // 一次性写入新的向量索引记录。
        milvusVectorRepository.insert(records);
        log.info("Milvus vector index replaced: documentId={}, chunkCount={}", documentId, records.size());
    }

    private MilvusVectorRepository.VectorRecord toVectorRecord(RagDocument document,
                                                               RagDocumentChunk chunk,
                                                               ChunkEmbedding embedding) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                chunk.getMetadata() == null ? Map.of() : chunk.getMetadata()
        );
        metadata.put(ChunkMetadata.KEY_STATUS, "INDEXED");

        // putIfAbsent 保障旧数据 backfill 不失败
        metadata.putIfAbsent(ChunkMetadata.KEY_DOCUMENT_ID, String.valueOf(document.getDocumentId()));
        metadata.putIfAbsent(ChunkMetadata.KEY_SESSION_ID, nullSafe(document.getSessionId()));
        metadata.putIfAbsent(ChunkMetadata.KEY_OWNER_FOLDER, nullSafe(document.getOwnerFolder()));
        metadata.putIfAbsent(ChunkMetadata.KEY_FILE_ID, nullSafe(document.getFileId()));
        metadata.putIfAbsent(ChunkMetadata.KEY_FILE_TYPE, nullSafe(document.getFileType()));
        metadata.putIfAbsent(ChunkMetadata.KEY_FILE_NAME, nullSafe(document.getFileName()));
        metadata.putIfAbsent(ChunkMetadata.KEY_CHUNK_INDEX, chunk.getChunkIndex());
        metadata.putIfAbsent(ChunkMetadata.KEY_CHAR_COUNT, chunk.getCharCount());
        metadata.putIfAbsent(ChunkMetadata.KEY_TOKEN_ESTIMATE, chunk.getTokenEstimate());

        return new MilvusVectorRepository.VectorRecord(
                buildVectorId(document.getDocumentId(), chunk.getChunkIndex()),
                chunk.getContent(),
                metadata,
                embedding.vector());
    }

    /**
     * 生成稳定的向量记录 ID。
     *
     * <p>这里故意不用数据库里的 chunkId，因为 chunk 表在重建索引时可能整批替换，
     * 而 `documentId + chunkIndex` 才能稳定表示“这篇文档里的第几个逻辑分块”。</p>
     */
    private static String buildVectorId(Long documentId, int chunkIndex) {
        return documentId + ":" + chunkIndex;
    }

    private static String nullSafe(String value) {
        // Milvus metadata 中统一避免 null，减少后续 filter 和反序列化歧义。
        return value == null ? "" : value;
    }
}
