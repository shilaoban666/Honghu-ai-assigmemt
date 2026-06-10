package com.honghu.ai.assigment.rag.index.document.processer;

import com.honghu.ai.assigment.dto.record.ChunkCandidate;
import com.honghu.ai.assigment.dto.record.IngestionResult;
import com.honghu.ai.assigment.dto.record.S3UploadReceivedMessage;
import com.honghu.ai.assigment.rag.index.cleaner.RagTextCleaner;
import com.honghu.ai.assigment.rag.index.parser.RagDocumentParser;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 文档摄取处理器接口。
 *
 * <p>这个接口定义的是“某一种文件类型如何接入整条 RAG 摄取流程”的统一契约。</p>
 *
 * <p>它本身并不规定 PDF、DOCX、Markdown 的具体处理细节，而是只要求每个处理器回答三个问题：</p>
 * <ol>
 *   <li>你支持哪些扩展名？</li>
 *   <li>收到一个上传事件后，你能不能把文件完整处理到索引里？</li>
 *   <li>你会如何把长文本切成 chunk？</li>
 * </ol>
 *
 * <p>解析与清洗之所以没有直接塞进本接口，是因为它们已经被进一步拆成了独立策略：
 * {@link RagDocumentParser}
 * 和 {@link RagTextCleaner}。
 * 这样处理器更像一个“总装配者”，负责把不同策略组合成完整链路。</p>
 */
public interface DocumentIngestionHandler {

    /**
     * 返回当前处理器支持的文件扩展名集合。
     *
     * <p>约定全部使用小写、且不带点，例如 {@code pdf}、{@code docx}、{@code md}。</p>
     */
    Set<String> supportedTypes();

    /**
     * 处理一份文件的完整摄取流程。
     *
     * <p>通常包括：校验事件、下载文件、解析文本、清洗文本、切分 chunk、写库、可选向量化。</p>
     */
    IngestionResult handleFileMessage(S3UploadReceivedMessage message, String messageId) throws IOException;

    /**
     * 将文档文本切成可检索的 chunk 列表。
     *
     * <p>拆分后的 chunk 会成为后续关键词检索、向量检索和上下文拼装的最小单位。</p>
     */
    List<ChunkCandidate> chunk(String text);
}
