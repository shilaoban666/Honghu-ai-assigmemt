package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG chunk 的结构化元数据。
 *
 * <h2>设计理念</h2>
 * <p>稳定核心字段（documentId、fileName、offset 等）用具体属性类型，
 * 仅在 {@link #extra()} Map 中存放未来可能扩展的字段。
 * 这样既保留了 Spring AI 的扩展性，又不会让业务核心字段散落成字符串字典。</p>
 *
 * <h2>数据边界</h2>
 * <table>
 *   <tr><th>层面</th><th>数据类型</th><th>说明</th></tr>
 *   <tr><td>Java 代码</td><td>{@code ChunkMetadata} (record)</td><td>强类型，编译期可检查</td></tr>
 *   <tr><td>DB 持久化</td><td>{@code metadata JSONB}</td><td>通过 {@link #toMap()} 序列化</td></tr>
 *   <tr><td>Milvus 写入</td><td>{@code Map<String,Object>}</td><td>通过 {@link #toMap()} 转换</td></tr>
 *   <tr><td>检索回读</td><td>{@code ChunkMetadata}</td><td>通过 {@link #fromMap(Map)} 反序列化</td></tr>
 * </table>
 *
 * <h2>Map key 约定</h2>
 * <p>{@code KEY_DOCUMENT_ID}、{@code KEY_SESSION_ID} 等常量必须固定不变，
 * 因为 {@code VectorRagRetrievalService} 的 Milvus filter 表达式依赖这些 key。
 * 修改 key 值会导致向量检索的权限过滤失效。</p>
 *
 * <h2>字段生命周期</h2>
 * <ul>
 *     <li><b>ingestion 阶段</b>：{@link #ofBase} 创建基础 metadata（documentId/sessionId/ownerFolder/fileId/fileName/fileType/source）</li>
 *     <li><b>splitter 阶段</b>：{@link #withOffsets} 补上 cleanedStartOffset/cleanedEndOffset</li>
 *     <li><b>vector indexing 阶段</b>：{@link #withStatus} 补上 status="INDEXED"</li>
 *     <li><b>retrieval 阶段</b>：{@link #fromMap} 从 Milvus/DB 回读完整 metadata</li>
 *     <li><b>pageNumber/sectionPath</b>：本轮暂不填充，为后续 parser 结构化改造预留</li>
 * </ul>
 *
 * @see ChunkCandidate
 * @see com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.retiriever.RagRetrievalService.RagSnippet
 */
public record ChunkMetadata(
        /**
         * 文档主记录 ID（对应 rag_document.document_id）。
         *
         * <p>存储为 String 以保持与 Milvus metadata 值类型一致。</p>
         */
        String documentId,

        /**
         * 关联的会话 ID。
         */
        String sessionId,

        /**
         * S3 路径第一段（上传时按 username 隔离的目录名）。
         *
         * <p>VectorRagRetrievalService 的 Milvus filter 依赖此字段做用户级权限过滤。</p>
         */
        String ownerFolder,

        /**
         * 上传时生成的文件唯一标识（UUID）。
         */
        String fileId,

        /**
         * 原始文件名。
         */
        String fileName,

        /**
         * 文件扩展名（不含点，小写），如 pdf / md / txt。
         */
        String fileType,

        /**
         * 文件完整来源标识。
         *
         * <p>格式：{@code bucketName/objectKey}。不直接展示给 LLM，仅用于调试和审计。</p>
         */
        String source,

        /**
         * 页码（本轮暂不填充，为后续 PDF parser 结构化改造预留）。
         */
        Integer pageNumber,

        /**
         * 章节路径（本轮暂不填充，为后续 Markdown/DOCX 标题层级提取预留）。
         *
         * <p>格式示例：{@code "第3章 > 3.2 数据安全 > 3.2.1 加密算法"}。</p>
         */
        String sectionPath,

        /**
         * cleaner 处理后文本中的起始字符偏移。
         *
         * <p>{@link com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter.RagWindowOverlapTextSplitter}
         * 可以填入真实偏移；其他 splitter 暂时填 -1。</p>
         */
        Integer cleanedStartOffset,

        /**
         * cleaner 处理后文本中的结束字符偏移。
         */
        Integer cleanedEndOffset,

        /**
         * 索引状态（仅在 Milvus / 检索路径使用，不强制存入 DB chunk）。
         *
         * <p>向量写入时注入 {@code "INDEXED"}。</p>
         */
        String status,

        /**
         * 扩展字段，存放以下内容：
         * <ul>
         *     <li>parser 临时字段（OCR 置信度、PDF 坐标等）</li>
         *     <li>cleaner hints</li>
         *     <li>未来实验性字段</li>
         *     <li>Milvus 返回的 distance 等检索元数据</li>
         * </ul>
         *
         * <p>这些字段不适合每次改表、改 record，因此保留在 extra Map 中。</p>
         */
        Map<String, Object> extra
) {
    // ═══════════════════════════════════════════════
    // Map key 常量 —— 必须固定，Milvus filter 依赖
    // ═══════════════════════════════════════════════

    /** documentId 的 Map key（保持与 Milvus metadata 一致） */
    public static final String KEY_DOCUMENT_ID = "documentId";
    /** sessionId 的 Map key */
    public static final String KEY_SESSION_ID = "sessionId";
    /** ownerFolder 的 Map key（VectorRagRetrievalService filter 依赖） */
    public static final String KEY_OWNER_FOLDER = "ownerFolder";
    /** fileId 的 Map key */
    public static final String KEY_FILE_ID = "fileId";
    /** fileName 的 Map key */
    public static final String KEY_FILE_NAME = "fileName";
    /** fileType 的 Map key */
    public static final String KEY_FILE_TYPE = "fileType";
    /** source 的 Map key */
    public static final String KEY_SOURCE = "source";
    /** pageNumber 的 Map key */
    public static final String KEY_PAGE_NUMBER = "pageNumber";
    /** sectionPath 的 Map key */
    public static final String KEY_SECTION_PATH = "sectionPath";
    /** chunkIndex 的 Map key */
    public static final String KEY_CHUNK_INDEX = "chunkIndex";
    /** charCount 的 Map key */
    public static final String KEY_CHAR_COUNT = "charCount";
    /** tokenEstimate 的 Map key */
    public static final String KEY_TOKEN_ESTIMATE = "tokenEstimate";
    /** cleanedStartOffset 的 Map key */
    public static final String KEY_CLEANED_START_OFFSET = "cleanedStartOffset";
    /** cleanedEndOffset 的 Map key */
    public static final String KEY_CLEANED_END_OFFSET = "cleanedEndOffset";
    /** status 的 Map key（Milvus filter 依赖） */
    public static final String KEY_STATUS = "status";
    /** Milvus 返回的向量距离字段 key */
    public static final String KEY_DISTANCE = "distance";

    public ChunkMetadata {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    // ═══════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════

    /**
     * 创建一个仅包含文档基础信息的 ChunkMetadata。
     *
     * <p>用于 ingestion 阶段构造 base metadata，offset 和 status 留空，
     * 后续由 splitter 和 vector indexing 阶段补全。</p>
     *
     * @param documentId  文档主记录 ID（转为 String）
     * @param sessionId   会话 ID
     * @param ownerFolder S3 用户目录名
     * @param fileId      文件 UUID
     * @param fileName    原始文件名
     * @param fileType    文件扩展名
     * @param source      文件来源（bucketName/objectKey）
     */
    public static ChunkMetadata ofBase(
            String documentId, String sessionId, String ownerFolder,
            String fileId, String fileName, String fileType, String source) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                null, null, null, null, null, Map.of());
    }

    /**
     * 从 Map 反序列化。
     *
     * <p>用于 DB JSONB 回读、Milvus 命中结果回填。
     * 对 Map 中不存在或为空的 key，对应 record 字段设为 null。</p>
     *
     * <p>所有非核心 key（不在 {@code KEY_*} 常量中的）自动归入 {@link #extra()}。</p>
     *
     * @param map 源 Map（可为 null，返回 {@link #EMPTY}）
     * @return 反序列化后的 ChunkMetadata
     */
    public static ChunkMetadata fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return EMPTY;
        return new ChunkMetadata(
                str(map, KEY_DOCUMENT_ID),
                str(map, KEY_SESSION_ID),
                str(map, KEY_OWNER_FOLDER),
                str(map, KEY_FILE_ID),
                str(map, KEY_FILE_NAME),
                str(map, KEY_FILE_TYPE),
                str(map, KEY_SOURCE),
                integer(map, KEY_PAGE_NUMBER),
                str(map, KEY_SECTION_PATH),
                integer(map, KEY_CLEANED_START_OFFSET),
                integer(map, KEY_CLEANED_END_OFFSET),
                str(map, KEY_STATUS),
                extractExtra(map)
        );
    }

    /** 空 metadata 单例，避免各处创建空对象。 */
    public static final ChunkMetadata EMPTY = new ChunkMetadata(
            null, null, null, null, null, null, null,
            null, null, null, null, null, Map.of());

    // ═══════════════════════════════════════════════
    // 序列化
    // ═══════════════════════════════════════════════

    /**
     * 转为 {@code Map<String, Object>} 用于 JSONB 持久化和 Milvus 写入。
     *
     * <p>仅包含非 null 的核心字段 + {@link #extra()} 中的所有 key。
     * key 名与 {@code KEY_*} 常量严格一致。</p>
     *
     * @return 可序列化的 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, KEY_DOCUMENT_ID, documentId);
        putIfNotNull(map, KEY_SESSION_ID, sessionId);
        putIfNotNull(map, KEY_OWNER_FOLDER, ownerFolder);
        putIfNotNull(map, KEY_FILE_ID, fileId);
        putIfNotNull(map, KEY_FILE_NAME, fileName);
        putIfNotNull(map, KEY_FILE_TYPE, fileType);
        putIfNotNull(map, KEY_SOURCE, source);
        putIfNotNull(map, KEY_PAGE_NUMBER, pageNumber);
        putIfNotNull(map, KEY_SECTION_PATH, sectionPath);
        putIfNotNull(map, KEY_CLEANED_START_OFFSET, cleanedStartOffset);
        putIfNotNull(map, KEY_CLEANED_END_OFFSET, cleanedEndOffset);
        putIfNotNull(map, KEY_STATUS, status);
        if (!extra.isEmpty()) {
            map.putAll(extra);
        }
        return map;
    }

    // ═══════════════════════════════════════════════
    // 便捷构造（immutable 复制，不修改原对象）
    // ═══════════════════════════════════════════════

    /** 复制并替换 documentId（其他字段不变）。 */
    public ChunkMetadata withDocumentId(String documentId) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                pageNumber, sectionPath, cleanedStartOffset, cleanedEndOffset, status, extra);
    }

    /** 复制并替换 status（用于向量写入时注入 "INDEXED"）。 */
    public ChunkMetadata withStatus(String status) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                pageNumber, sectionPath, cleanedStartOffset, cleanedEndOffset, status, extra);
    }

    /** 复制并替换 cleanedStartOffset 和 cleanedEndOffset。 */
    public ChunkMetadata withOffsets(Integer start, Integer end) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                pageNumber, sectionPath, start, end, status, extra);
    }

    // ═══════════════════════════════════════════════
    // 内部辅助方法
    // ═══════════════════════════════════════════════

    /** 从 Map 取 String 值，null 或空串统一返回 null。 */
    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null || "".equals(v) ? null : v.toString();
    }

    /** 从 Map 取 Integer 值，支持 Number 和 String 两种来源。 */
    private static Integer integer(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isEmpty()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /** 从 Map 中提取非核心字段到 extra。 */
    private static Map<String, Object> extractExtra(Map<String, Object> map) {
        Map<String, Object> extra = new LinkedHashMap<>(map);
        // 移除所有已知核心字段，剩余的归入 extra
        extra.keySet().removeAll(java.util.Set.of(
                KEY_DOCUMENT_ID, KEY_SESSION_ID, KEY_OWNER_FOLDER,
                KEY_FILE_ID, KEY_FILE_NAME, KEY_FILE_TYPE, KEY_SOURCE,
                KEY_PAGE_NUMBER, KEY_SECTION_PATH,
                KEY_CLEANED_START_OFFSET, KEY_CLEANED_END_OFFSET,
                KEY_STATUS, KEY_CHUNK_INDEX, KEY_CHAR_COUNT, KEY_TOKEN_ESTIMATE,
                KEY_DISTANCE
        ));
        return extra.isEmpty() ? Map.of() : Map.copyOf(extra);
    }

    /** 仅当 value 非 null 时才放入 Map。 */
    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
