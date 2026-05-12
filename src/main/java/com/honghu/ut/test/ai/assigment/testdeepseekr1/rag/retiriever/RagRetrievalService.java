package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retiriever;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务抽象。
 *
 * <p>定义"针对当前用户、当前会话、当前问题，如何从已入库的文档分块中找出最相关片段"的统一接口。</p>
 *
 * <h3>检索模式</h3>
 * <ul>
 *   <li>{@code keyword}：基于 PostgreSQL chunk 表 + 关键词匹配打分（默认兜底）</li>
 *   <li>{@code vector}：基于 Milvus 向量相似度检索（通过 {@code app.rag.retrieval.mode=vector} 启用）</li>
 * </ul>
 *
 * <h3>数据流转</h3>
 * <pre>
 *   检索命中 → RagSnippet(content, score, ChunkMetadata)
 *            → RagSnippetFormatter.format() → 拼成 prompt 上下文块
 *            → ChatService 注入到 LLM 的 SystemMessage
 * </pre>
 *
 * <p>上层调用方（ChatService）不需要知道底层是 keyword 还是 vector，
 * 只需要说"帮我检索上下文"。</p>
 */
public interface RagRetrievalService {

    /**
     * RAG 上下文块开始标记。
     *
     * <p>与 {@link #RAG_BLOCK_END} 成对包裹所有外部资料，供 prompt 拼装和下游调试识别边界。</p>
     */
    String RAG_BLOCK_BEGIN = "<<<RAG_DOC_BEGIN>>>";

    /**
     * RAG 上下文块结束标记。
     *
     * <p>模型可以通过这对标记区分"系统指令"和"用户上传资料"，是抵御 prompt injection 的第一层防线。</p>
     */
    String RAG_BLOCK_END = "<<<RAG_DOC_END>>>";

    /**
     * 按用户、会话、查询文本返回最相关的片段列表。
     *
     * <p>返回结构化 {@link RagSnippet}，不是最终 prompt 文本。上层可继续排序、裁剪、格式化。</p>
     *
     * @param userId    当前请求用户 ID（用于权限校验）
     * @param sessionId 当前对话会话 ID（用于限定检索范围）
     * @param query     用户当前提问文本
     * @return 命中片段列表（按相关度降序），无结果时返回空列表
     */
    List<RagSnippet> retrieveBySession(String userId, String sessionId, String query);

    /**
     * 构造可直接注入 LLM SystemMessage 的 RAG 上下文块。
     *
     * <p>内部调用 {@link #retrieveBySession} 获取命中结果，
     * 再通过 {@code RagSnippetFormatter} 统一格式化为带安全提示的上下文文本。</p>
     *
     * @param userId    当前请求用户 ID
     * @param sessionId 当前对话会话 ID
     * @param query     用户当前提问文本
     * @return 格式化后的上下文文本，无命中时返回空字符串
     */
    String buildContextBlock(String userId, String sessionId, String query);

    /**
     * 检索命中的标准化片段对象。
     *
     * <p>无论底层来自 PostgreSQL 关键词命中还是 Milvus 向量命中，
     * 最终都统一映射为这个 record。formatter 和上层业务都基于它做统一处理，
     * 不需要感知底层检索实现细节。</p>
     *
     * <h3>数据模型</h3>
     * <ul>
     *     <li>{@code content}：chunk 正文，直接展示给 LLM</li>
     *     <li>{@code score}：相关度分数，越大越相关（keyword 模式为加权分，vector 模式为 1-distance）</li>
     *     <li>{@code metadata}：强类型 {@link ChunkMetadata}，携带 documentId/fileName/fileType/chunkIndex/offset 等</li>
     * </ul>
     *
     * <h3>兼容性</h3>
     * <ul>
     *     <li>3 参主构造器：{@code (content, score, ChunkMetadata)} — 新代码推荐</li>
     *     <li>3 参 Map 兼容构造器：{@code (content, score, Map)} — 从 Milvus/DB Map 直接构造</li>
     *     <li>6 参兼容构造器：{@code (documentId, fileName, fileType, chunkIndex, content, score)} — 旧代码过渡</li>
     * </ul>
     *
     * <h3>使用示例</h3>
     * <pre>{@code
     * // 新代码：直接用 ChunkMetadata
     * ChunkMetadata meta = ChunkMetadata.ofBase("123", "sess-1", "userA", "f1", "报告.pdf", "pdf", "bucket/key");
     * RagSnippet snippet = new RagSnippet("正文内容...", 15.5, meta);
     *
     * // formatter 中可以安全访问
     * String name = snippet.metadata().fileName();   // "报告.pdf"
     * Integer page = snippet.metadata().pageNumber(); // null（本轮未填充）
     *
     * // 旧代码兼容
     * RagSnippet old = new RagSnippet(123L, "报告.pdf", "pdf", 3, "正文...", 15.5);
     * }</pre>
     */
    record RagSnippet(String content, double score, ChunkMetadata metadata) {

        /**
         * 规范化构造器：拒绝 null content 和 null metadata。
         */
        public RagSnippet {
            content = content == null ? "" : content;
            metadata = metadata == null ? ChunkMetadata.EMPTY : metadata;
        }

        /**
         * 从 Map 构造（兼容 Milvus 返回的 {@code Document.getMetadata()} 和 DB JSONB 反序列化）。
         *
         * <p>内部调用 {@link ChunkMetadata#fromMap(Map)} 将 Map 转为强类型。</p>
         */
        public RagSnippet(String content, double score, Map<String, Object> metadataMap) {
            this(content, score, ChunkMetadata.fromMap(metadataMap));
        }

        /**
         * 兼容旧代码的 6 参构造器。
         *
         * <p>自动用 {@code documentId/fileName/fileType/chunkIndex} 构建最小 metadata。
         * 新代码建议直接用 3 参主构造器传入完整的 {@link ChunkMetadata}。</p>
         *
         * @deprecated 新代码请使用 {@code new RagSnippet(content, score, ChunkMetadata.ofBase(...))}
         */
        @Deprecated
        public RagSnippet(Long documentId,
                          String fileName,
                          String fileType,
                          Integer chunkIndex,
                          String content,
                          double score) {
            this(content, score, legacyMetadata(documentId, fileName, fileType, chunkIndex));
        }

        // ─── 便捷访问器（委托给 ChunkMetadata） ──────────────

        /**
         * 从 metadata 中提取 documentId。
         *
         * @return documentId，metadata 中不存在时返回 null
         */
        public Long documentId() {
            String v = metadata.documentId();
            return v == null ? null : Long.valueOf(v);
        }

        /**
         * 从 metadata 中提取 fileName。
         *
         * @return fileName，不存在时返回空字符串
         */
        public String fileName() {
            return metadata.fileName() == null ? "" : metadata.fileName();
        }

        /**
         * 从 metadata 中提取 fileType。
         *
         * @return fileType，不存在时返回空字符串
         */
        public String fileType() {
            return metadata.fileType() == null ? "" : metadata.fileType();
        }

        /**
         * 从 metadata 中提取 chunkIndex。
         *
         * @return chunkIndex，不存在时返回 null
         */
        public Integer chunkIndex() {
            // ChunkMetadata 中没有直接的 chunkIndex 字段，
            // 但在构建 RagSnippet 时 chunkIndex 存在 extra Map 中。
            // 这里从 extra 中回退读取以保持兼容。
            Object v = metadata.extra().get(ChunkMetadata.KEY_CHUNK_INDEX);
            return v instanceof Number n ? n.intValue() : null;
        }

        // ─── 内部辅助 ──────────────────────────────────────

        /**
         * 用零散字段构建最小 metadata Map（供 6 参兼容构造器使用）。
         */
        private static ChunkMetadata legacyMetadata(Long documentId,
                                                     String fileName,
                                                     String fileType,
                                                     Integer chunkIndex) {
            Map<String, Object> extra = new LinkedHashMap<>();
            if (chunkIndex != null) extra.put(ChunkMetadata.KEY_CHUNK_INDEX, chunkIndex);
            return new ChunkMetadata(
                    documentId == null ? null : String.valueOf(documentId),
                    null, null, null,
                    fileName, fileType, null,
                    null, null, null, null, null,
                    extra);
        }
    }
}
