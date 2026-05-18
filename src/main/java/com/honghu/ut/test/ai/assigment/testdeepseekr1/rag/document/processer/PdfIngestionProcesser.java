package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner.RagPdfTextCleaner;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter.RagTextSplitter;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding.RagVectorIndexingService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser.RagPdfDocumentParser;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
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

    @Override
    public Set<String> supportedTypes() {
        // 当前处理器只声明支持 pdf 扩展名。
        return Set.of("pdf");
    }
}
