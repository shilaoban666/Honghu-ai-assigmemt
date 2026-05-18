package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.document.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.cleaner.RagGenericTextCleaner;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.splitter.RagTextSplitter;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.index.parser.RagDocxDocumentParser;
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

    /**
     * 构造 DOCX 专属摄取处理器。
     *
     * <p>这个构造器本身没有业务逻辑，它的真正作用是把 DOCX 这条链路所需的三种策略装配好：</p>
     * <ul>
     *   <li>parser：负责把 Word 二进制文件抽成原始文本；</li>
     *   <li>cleaner：负责对提取结果做通用清洗；</li>
     *   <li>splitter：负责尽量按段落边界切块。</li>
     * </ul>
     *
     * <p>一旦装配完成，后续真正的处理流程就全部交给父类模板方法统一执行。</p>
     */
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

    /**
     * 声明当前处理器支持的文件扩展名。
     *
     * <p>返回值会在 {@link DocumentIngestionService} 启动时被注册到“扩展名 → handler”映射表中，
     * 从而让上传事件命中 {@code docx} 时自动路由到本处理器。</p>
     */
    @Override
    public Set<String> supportedTypes() {
        // 告诉分发层：当前处理器只接管 docx 扩展名。
        return Set.of("docx");
    }
}
