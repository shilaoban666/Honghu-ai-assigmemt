package com.honghu.ai.assigment.rag.index.document.processer;

import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.manager.AwsManager;
import com.honghu.ai.assigment.rag.index.cleaner.RagGenericTextCleaner;
import com.honghu.ai.assigment.rag.index.splitter.RagTextSplitter;
import com.honghu.ai.assigment.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ai.assigment.rag.monitor.RagIngestionStateService;
import com.honghu.ai.assigment.rag.index.parser.RagImageOcrParser;
import com.honghu.ai.assigment.repository.RagDocumentChunkRepository;
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

    /**
     * 构造图片文件摄取处理器。
     *
     * <p>当前它主要承担“把未来图片 OCR 链路的各个策略接口先装好”的作用：
     * parser 位置预留给 OCR，cleaner 继续复用通用文本清洗，而 splitter 则采用更适合短句碎片的句子窗口策略。</p>
     */
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

    /**
     * 声明当前处理器预留支持的图片扩展名集合。
     *
     * <p>注意：这里“声明支持”只意味着路由上会进入本处理器；
     * 真正是否可用，还要看 {@link RagImageOcrParser} 是否已经接入实际 OCR 能力。</p>
     */
    @Override
    public Set<String> supportedTypes() {
        // 这里声明图片扩展名；是否真正可用仍取决于 OCR parser 是否完成实现。
        return Set.of("png", "jpg", "jpeg");
    }
}
