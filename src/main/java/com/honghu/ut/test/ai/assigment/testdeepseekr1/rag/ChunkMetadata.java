package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG chunk 的结构化元数据。
 *
 * <p>把文档级、文件级、位置追踪等稳定核心字段提升为强类型属性，
 * 仅把未来可能扩展的字段留在 {@link #extra()} Map 中。</p>
 *
 * <h3>与旧版 {@code Map<String,Object>} 的关系</h3>
 * <ul>
 *     <li>{@link #toMap()} 将本 record 序列化为 Map（用于 Milvus 写入、JSONB 持久化）</li>
 *     <li>{@link #fromMap(Map)} 从 Map 反序列化（用于 DB 读取、Milvus 命中回填）</li>
 *     <li>Map 中的 key 与旧版 {@code ChunkMetadata.DOCUMENT_ID} 等常量完全一致，
 *         保证 Milvus filter 表达式不受影响</li>
 * </ul>
 */
public record ChunkMetadata(
        // ── 文档级标识 ────────────────────────────
        String documentId,
        String sessionId,
        String ownerFolder,

        // ── 文件级标识 ────────────────────────────
        String fileId,
        String fileName,
        String fileType,
        String source,

        // ── 结构位置（本轮暂不填充，为后续预留） ──
        Integer pageNumber,
        String sectionPath,

        // ── 文本偏移（cleaner 处理后文本中的位置）──
        Integer cleanedStartOffset,
        Integer cleanedEndOffset,

        // ── 检索状态（仅在 Milvus/检索路径使用） ──
        String status,

        // ── 扩展字段 ──────────────────────────────
        Map<String, Object> extra
) {
    // ── Map key 常量（保持与旧版一致，供 Milvus filter / JSONB 序列化使用） ──

    public static final String KEY_DOCUMENT_ID = "documentId";
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_OWNER_FOLDER = "ownerFolder";
    public static final String KEY_FILE_ID = "fileId";
    public static final String KEY_FILE_NAME = "fileName";
    public static final String KEY_FILE_TYPE = "fileType";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_PAGE_NUMBER = "pageNumber";
    public static final String KEY_SECTION_PATH = "sectionPath";
    public static final String KEY_CHUNK_INDEX = "chunkIndex";
    public static final String KEY_CHAR_COUNT = "charCount";
    public static final String KEY_TOKEN_ESTIMATE = "tokenEstimate";
    public static final String KEY_CLEANED_START_OFFSET = "cleanedStartOffset";
    public static final String KEY_CLEANED_END_OFFSET = "cleanedEndOffset";
    public static final String KEY_STATUS = "status";
    public static final String KEY_DISTANCE = "distance";

    public ChunkMetadata {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    // ── 工厂方法 ──────────────────────────────────

    /**
     * 创建一个包含文档基础信息的 ChunkMetadata（offset/状态为空）。
     */
    public static ChunkMetadata ofBase(
            String documentId, String sessionId, String ownerFolder,
            String fileId, String fileName, String fileType, String source) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                null, null, null, null, null, Map.of());
    }

    /**
     * 从 Map 反序列化（兼容旧 DB / Milvus metadata）。
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

    /** 空 metadata 单例。 */
    public static final ChunkMetadata EMPTY = new ChunkMetadata(
            null, null, null, null, null, null, null,
            null, null, null, null, null, Map.of());

    // ── 序列化 ────────────────────────────────────

    /**
     * 转为 Map（用于 Milvus 写入、JSONB 持久化）。
     *
     * <p>格式与旧版 {@code ChunkMetadata} 常量 key 保持严格一致。</p>
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

    // ─── 便捷构造（immutable 复制） ─────────────────

    /** 复制并替换 documentId。 */
    public ChunkMetadata withDocumentId(String documentId) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                pageNumber, sectionPath, cleanedStartOffset, cleanedEndOffset, status, extra);
    }

    /** 复制并替换 status。 */
    public ChunkMetadata withStatus(String status) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                pageNumber, sectionPath, cleanedStartOffset, cleanedEndOffset, status, extra);
    }

    /** 复制并替换 offset。 */
    public ChunkMetadata withOffsets(Integer start, Integer end) {
        return new ChunkMetadata(documentId, sessionId, ownerFolder,
                fileId, fileName, fileType, source,
                pageNumber, sectionPath, start, end, status, extra);
    }

    // ── 内部辅助 ──────────────────────────────────

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null || "".equals(v) ? null : v.toString();
    }

    private static Integer integer(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isEmpty()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Map<String, Object> extractExtra(Map<String, Object> map) {
        Map<String, Object> extra = new LinkedHashMap<>(map);
        // 移除已知核心字段
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

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
