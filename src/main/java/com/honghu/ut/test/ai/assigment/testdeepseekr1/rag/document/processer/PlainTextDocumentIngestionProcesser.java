package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner.RagGenericTextCleaner;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter.RagTextSplitter;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding.RagVectorIndexingService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser.RagPlainTextDocumentParser;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 纯文本类文件摄取处理器，适用于 txt / json / xml / csv。
 *
 * <p>这一类文件没有 PDF / DOCX 那种复杂格式外壳，因此处理链最朴素：</p>
 * <ul>
 *   <li>解析时直接按 UTF-8 把字节转成字符串；</li>
 *   <li>清洗时只做通用空白规范化；</li>
 *   <li>切分时采用固定窗口 + overlap，更适合结构不稳定的普通文本。</li>
 * </ul>
 *
 * <p>需要注意：Markdown 虽然也是文本文件，但因为它带有大量格式语法，
 * 所以被单独分流到 {@link MarkdownIngestionProcesser}，避免错误复用通用文本链路。</p>
 */
@Component
public class PlainTextDocumentIngestionProcesser extends AbstractDocumentIngestionProcesser {

    public PlainTextDocumentIngestionProcesser(AwsManager awsManager,
                                               RagProperties ragProperties,
                                               RagIngestionStateService stateService,
                                               AwsProperties awsProperties,
                                               RagVectorIndexingService vectorIndexingService,
                                               RagDocumentChunkRepository chunkRepository,
                                               @Qualifier("ragPlainTextDocumentParser") RagPlainTextDocumentParser plainTextDocumentParser,
                                               @Qualifier("ragGenericTextCleaner") RagGenericTextCleaner genericTextCleaner,
                                               @Qualifier("ragWindowOverlapTextSplitter") RagTextSplitter splitter) {
        // 将“普通文本”的最佳策略组合交给父类模板流程执行。
        super(awsManager, ragProperties, stateService, awsProperties,
                vectorIndexingService, chunkRepository, plainTextDocumentParser, genericTextCleaner, splitter);
    }

    @Override
    public Set<String> supportedTypes() {
        // 这些扩展名都按“纯文本文件”处理。
        return Set.of("txt", "json", "xml", "csv");
    }
}
