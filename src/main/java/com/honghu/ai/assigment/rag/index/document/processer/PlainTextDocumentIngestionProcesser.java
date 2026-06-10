package com.honghu.ai.assigment.rag.index.document.processer;

import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.manager.AwsManager;
import com.honghu.ai.assigment.rag.index.cleaner.RagGenericTextCleaner;
import com.honghu.ai.assigment.rag.index.splitter.RagTextSplitter;
import com.honghu.ai.assigment.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ai.assigment.rag.monitor.RagIngestionStateService;
import com.honghu.ai.assigment.rag.index.parser.RagPlainTextDocumentParser;
import com.honghu.ai.assigment.repository.RagDocumentChunkRepository;
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

    /**
     * 构造纯文本类文件的摄取处理器。
     *
     * <p>这类文件通常不需要复杂格式解析，因此装配的是一条最朴素的处理链：</p>
     * <ul>
     *   <li>parser：直接做 UTF-8 解码；</li>
     *   <li>cleaner：做通用空白和控制字符清洗；</li>
     *   <li>splitter：用固定窗口 + overlap 进行稳定切块。</li>
     * </ul>
     */
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

    /**
     * 声明本处理器支持的“纯文本类”扩展名集合。
     *
     * <p>这些扩展名虽然内部结构不完全一样，但在当前版本里都统一走“按文本读取 → 通用清洗 →
     * 固定窗口切分”的轻量链路。</p>
     */
    @Override
    public Set<String> supportedTypes() {
        // 这些扩展名都按“纯文本文件”处理。
        return Set.of("txt", "json", "xml", "csv");
    }
}
