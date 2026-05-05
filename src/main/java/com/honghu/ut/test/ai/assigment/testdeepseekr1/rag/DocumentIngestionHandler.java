package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.ChunkCandidate;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.IngestionResult;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface DocumentIngestionHandler {

    /**
     * 这个 handler 支持哪些文件类型
     */
    Set<String> supportedTypes();

    /**
     * 处理一份文件：解析 → 分块 → 向量化 → 入库
     */
    IngestionResult handleFileMessage(S3UploadReceivedMessage message, String messageId) throws IOException;

    String extractText(String fileType, byte[] fileBytes) throws IOException;

    List<ChunkCandidate> chunk(String text);
}