package com.honghu.ai.assigment.rag.index.document.processer;

import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.manager.AwsManager;
import com.honghu.ai.assigment.rag.index.cleaner.RagPdfTextCleaner;
import com.honghu.ai.assigment.rag.index.splitter.RagTextSplitter;
import com.honghu.ai.assigment.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ai.assigment.rag.monitor.RagIngestionStateService;
import com.honghu.ai.assigment.rag.index.parser.RagPdfDocumentParser;
import com.honghu.ai.assigment.repository.RagDocumentChunkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PDF 文件摄取处理器。
 *
 * <p>它负责把“PDF 这种格式”映射到最合适的一组策略上：</p>
 * <ul>
 *   <li>解析器选择 {@link RagPdfDocumentParser}，负责从 PDF 中抽文字；</li>
 *   <li>清洗器选择 {@link RagPdfTextCleaner}，负责修复 PDF 特有脏数据；</li>
 *   <li>切分器选择段落打包策略，尽量减少长正文被粗暴切断。</li>
 * </ul>
 *
 * <p>由于 PDF 的问题往往集中在“提取后的文本形态很脏”，所以这里必须显式绑定 PDF 专属 cleaner，
 * 否则检索质量通常会明显下降。</p>
 */
@Component
public class PdfIngestionProcesser extends AbstractDocumentIngestionProcesser {

    /**
     * 构造 PDF 专属摄取处理器。
     *
     * <p>PDF 是 RAG 场景里最容易“能读到字，但字很脏”的格式之一，
     * 所以这里必须显式装配：</p>
     * <ul>
     *   <li>PDF 解析器：负责从版面对象中尽量抽出文本；</li>
     *   <li>PDF 清洗器：负责修复断词、合字、换页符等 PDF 特有问题；</li>
     *   <li>段落切分器：尽量沿自然段落组织 chunk。</li>
     * </ul>
     */
    public PdfIngestionProcesser(AwsManager awsManager,
                                 RagProperties ragProperties,
                                 RagIngestionStateService stateService,
                                 AwsProperties awsProperties,
                                 RagVectorIndexingService vectorIndexingService,
                                 RagDocumentChunkRepository chunkRepository,
                                 @Qualifier("ragPdfDocumentParser") RagPdfDocumentParser pdfDocumentParser,
                                 @Qualifier("ragPdfTextCleaner") RagPdfTextCleaner pdfTextCleaner,
                                 @Qualifier("ragParagraphPackingTextSplitter") RagTextSplitter splitter) {
        // 这里把 PDF 专用的三段策略注入父类模板流程。
        // 父类会统一完成剩余通用步骤，例如幂等控制、状态落库和索引替换。
        super(awsManager, ragProperties, stateService, awsProperties,
                vectorIndexingService, chunkRepository, pdfDocumentParser, pdfTextCleaner, splitter);
    }

    /**
     * 声明当前处理器只接管 {@code pdf} 扩展名。
     *
     * <p>这样路由层看到 PDF 文件时，就会把整条摄取流程分发到本处理器，而不会误用普通文本链路。</p>
     */
    @Override
    public Set<String> supportedTypes() {
        // 当前处理器只声明支持 pdf 扩展名。
        return Set.of("pdf");
    }
}
