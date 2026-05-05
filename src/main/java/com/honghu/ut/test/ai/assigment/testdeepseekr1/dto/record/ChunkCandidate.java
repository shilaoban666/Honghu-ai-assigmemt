package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record;

/**
 * @program: testDeepseekR1
 * @description:
 * 分块后的轻量结果对象。
 * <p>这里只保留索引、正文、字符数和 token 估算值，方便后面直接落库。</p>
 * @author: shilaoban
 * @create: 2026-05-02 00:36
 **/
/**

 */
public record ChunkCandidate(int chunkIndex, String content, int charCount, int tokenEstimate) {}