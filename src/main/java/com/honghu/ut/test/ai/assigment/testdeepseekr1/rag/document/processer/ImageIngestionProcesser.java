package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner.RagGenericTextCleaner;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.splitter.RagTextSplitter;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding.RagVectorIndexingService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.monitor.RagIngestionStateService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser.RagImageOcrParser;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.RagDocumentChunkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 图片文件摄取处理器（占位实现）。
 *
 * <p>这个类的存在说明系统在架构上已经为“图片进入 RAG”留好了位置，
 * 只是目前缺少真正的 OCR 实现。</p>
 *
 * <p>当前装配关系如下：</p>
 * <ul>
 *   <li>解析器：{@link RagImageOcrParser}，未来负责把图片识别成文字；</li>
 *   <li>清洗器：{@link RagGenericTextCleaner}，识别后的文字先做基础清洗；</li>
 *   <li>切分器：句子窗口策略，更适合 OCR 结果这种段落结构较弱的文本。</li>
 * </ul>
 *
 * <p>因为 OCR 还未实现，所以当前类更多是一个“占位装配器”。一旦 OCR parser 补完，
 * 这里就能直接接入现有的模板流程，无需重写整套摄取逻辑。</p>
 */
@Component
public class ImageIngestionProcesser extends AbstractDocumentIngestionProcesser {

    public ImageIngestionProcesser(AwsManager awsManager,
                                   RagProperties ragProperties,
                                   RagIngestionStateService stateService,
                                   AwsProperties awsProperties,
                                   RagVectorIndexingService vectorIndexingService,
                                   RagDocumentChunkRepository chunkRepository,
                                   @Qualifier("ragImageOcrParser") RagImageOcrParser imageOcrParser,
                                   @Qualifier("ragGenericTextCleaner") RagGenericTextCleaner genericTextCleaner,
                                   @Qualifier("ragSentenceWindowTextSplitter") RagTextSplitter splitter) {
        // 先把图片链路需要的策略装配好，真正的 OCR 能力则由 parser 实现类补充。
        super(awsManager, ragProperties, stateService, awsProperties,
                vectorIndexingService, chunkRepository, imageOcrParser, genericTextCleaner, splitter);
    }

    @Override
    public Set<String> supportedTypes() {
        // 这里声明图片扩展名；是否真正可用仍取决于 OCR parser 是否完成实现。
        return Set.of("png", "jpg", "jpeg");
    }
}
