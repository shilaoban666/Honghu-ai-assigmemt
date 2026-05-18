package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.ChunkMetadata;

/**
 * 分块后的轻量结果对象。
 *
 * <p>携带索引、正文、字符数、token 估算值以及强类型 {@link ChunkMetadata}。</p>
 *
 * <h3>兼容性</h3>
 * <ul>
 *     <li>5 参构造器：主构造器，metadata 不可为 null</li>
 *     <li>4 参构造器：兼容旧代码，自动生成 {@link ChunkMetadata#EMPTY}</li>
 * </ul>
 */
public record ChunkCandidate(
        int chunkIndex,
        String content,
        int charCount,
        int tokenEstimate,
        ChunkMetadata metadata
) {
    public ChunkCandidate {
        content = content == null ? "" : content;
        metadata = metadata == null ? ChunkMetadata.EMPTY : metadata;
    }

    /**
     * 兼容旧代码的 4 参构造器。
     */
    public ChunkCandidate(int chunkIndex, String content, int charCount, int tokenEstimate) {
        this(chunkIndex, content, charCount, tokenEstimate, ChunkMetadata.EMPTY);
    }
}
