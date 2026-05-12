package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retiriever;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务抽象。
 *
 * <p>它定义的是"针对当前用户、当前会话、当前问题，如何从已经入库的文档分块中找出最相关片段"的统一接口。</p>
 *
 * <p>之所以抽象成接口，是为了让上层调用方完全不需要知道底层到底是：</p>
 * <ul>
 *   <li>关键词匹配检索；</li>
 *   <li>向量相似度检索；</li>
 *   <li>未来可能加入的混合检索策略。</li>
 * </ul>
 *
 * <p>这样 `ChatService` 一类上层业务只需要说"帮我检索上下文"，而不需要关心具体实现细节。</p>
 */
public interface RagRetrievalService {

    /** RAG 上下文块开始标记，供提示词拼装和下游调试识别边界使用。 */
    String RAG_BLOCK_BEGIN = "<<<RAG_DOC_BEGIN>>>";

    /** RAG 上下文块结束标记，与 {@link #RAG_BLOCK_BEGIN} 成对出现。 */
    String RAG_BLOCK_END = "<<<RAG_DOC_END>>>";

    /**
     * 按用户、会话、查询文本返回最相关的片段列表。
     *
     * <p>返回的是结构化片段，不是最终 prompt 文本，方便上层继续排序、裁剪、格式化。</p>
     */
    List<RagSnippet> retrieveBySession(String userId, String sessionId, String query);

    /**
     * 构造一个可直接塞进提示词的 RAG 上下文块。
     *
     * <p>通常会在内部先调用 {@link #retrieveBySession(String, String, String)}，
     * 再把结果格式化为统一的上下文文本。</p>
     */
    String buildContextBlock(String userId, String sessionId, String query);

    /**
     * 检索命中的标准化片段对象（metadata-backed）。
     *
     * <p>无论底层来自 PostgreSQL 关键词命中还是 Milvus 向量命中，最终都会被统一映射成这个 record，
     * 方便 formatter 和上层业务做统一处理。</p>
     *
     * <h3>兼容性</h3>
     * <ul>
     *     <li>3 参主构造器：content + score + metadata</li>
     *     <li>6 参兼容构造器：documentId + fileName + fileType + chunkIndex + content + score</li>
     * </ul>
     */
    record RagSnippet(String content, double score, Map<String, Object> metadata) {
        public RagSnippet {
            content = content == null ? "" : content;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        /** 兼容旧代码的 6 参构造器。 */
        public RagSnippet(Long documentId,
                          String fileName,
                          String fileType,
                          Integer chunkIndex,
                          String content,
                          double score) {
            this(content, score, legacyMetadata(documentId, fileName, fileType, chunkIndex));
        }

        /** 从 metadata 中提取 documentId。 */
        public Long documentId() {
            Object v = metadata.get(ChunkMetadata.KEY_DOCUMENT_ID);
            return v == null ? null : Long.valueOf(v.toString());
        }

        /** 从 metadata 中提取 fileName。 */
        public String fileName() {
            Object v = metadata.get(ChunkMetadata.KEY_FILE_NAME);
            return v == null ? "" : v.toString();
        }

        /** 从 metadata 中提取 fileType。 */
        public String fileType() {
            Object v = metadata.get(ChunkMetadata.KEY_FILE_TYPE);
            return v == null ? "" : v.toString();
        }

        /** 从 metadata 中提取 chunkIndex。 */
        public Integer chunkIndex() {
            Object v = metadata.get(ChunkMetadata.KEY_CHUNK_INDEX);
            return v instanceof Number n ? n.intValue() : null;
        }

        private static Map<String, Object> legacyMetadata(Long documentId,
                                                          String fileName,
                                                          String fileType,
                                                          Integer chunkIndex) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (documentId != null) m.put(ChunkMetadata.KEY_DOCUMENT_ID, String.valueOf(documentId));
            if (fileName != null) m.put(ChunkMetadata.KEY_FILE_NAME, fileName);
            if (fileType != null) m.put(ChunkMetadata.KEY_FILE_TYPE, fileType);
            if (chunkIndex != null) m.put(ChunkMetadata.KEY_CHUNK_INDEX, chunkIndex);
            return m;
        }
    }
}
