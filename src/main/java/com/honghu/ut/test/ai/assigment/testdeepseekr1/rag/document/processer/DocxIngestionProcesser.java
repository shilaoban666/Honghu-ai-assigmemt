package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner.RagGenericTextCleaner;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter.RagTextSplitter;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding.RagVectorIndexingService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser.RagDocxDocumentParser;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DOCX 文件摄取处理器。
 *
 * <p>这个类本身几乎不写业务流程代码，因为真正的通用流程已经封装在
 * {@link AbstractDocumentIngestionProcesser} 里。</p>
 *
 * <p>它的核心作用是“装配 DOCX 这条处理链”：</p>
 * <ul>
 *   <li>使用 {@link RagDocxDocumentParser} 把 Word 文件转成原始文本；</li>
 *   <li>使用 {@link RagGenericTextCleaner} 做通用文本清洗；</li>
 *   <li>使用段落打包切分器，让 Word 正文尽量按段落保持语义完整。</li>
 * </ul>
 *
 * <p>换句话说，这个类更像一个“格式路由配置点”，而不是具体算法实现点。</p>
 */
@Component
public class DocxIngestionProcesser extends AbstractDocumentIngestionProcesser {

    public DocxIngestionProcesser(AwsManager awsManager,
                                  RagProperties ragProperties,
                                  RagIngestionStateService stateService,
                                  AwsProperties awsProperties,
                                  RagVectorIndexingService vectorIndexingService,
                                  RagDocumentChunkRepository chunkRepository,
                                  @Qualifier("ragDocxDocumentParser") RagDocxDocumentParser docxDocumentParser,
                                  @Qualifier("ragGenericTextCleaner") RagGenericTextCleaner genericTextCleaner,
                                  @Qualifier("ragParagraphPackingTextSplitter") RagTextSplitter splitter) {
        // 把 DOCX 专属的 parser / cleaner / splitter 组合进父类模板流程。
        // 父类后续会统一执行：事件校验 -> 下载 -> 解析 -> 清洗 -> 分块 -> 索引。
        super(awsManager, ragProperties, stateService, awsProperties,
                vectorIndexingService, chunkRepository, docxDocumentParser, genericTextCleaner, splitter);
    }

    @Override
    public Set<String> supportedTypes() {
        // 告诉分发层：当前处理器只接管 docx 扩展名。
        return Set.of("docx");
    }
}
