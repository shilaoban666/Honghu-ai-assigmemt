package com.honghu.ai.assigment.rag.index.document.processer;

import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.config.properties.RagProperties;
import com.honghu.ai.assigment.manager.AwsManager;
import com.honghu.ai.assigment.rag.index.cleaner.RagMarkdownTextCleaner;
import com.honghu.ai.assigment.rag.index.splitter.RagTextSplitter;
import com.honghu.ai.assigment.rag.index.embdding.RagVectorIndexingService;
import com.honghu.ai.assigment.rag.monitor.RagIngestionStateService;
import com.honghu.ai.assigment.rag.index.parser.RagMarkdownDocumentParser;
import com.honghu.ai.assigment.repository.RagDocumentChunkRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Markdown 文件摄取处理器。
 *
 * <p>Markdown 虽然在磁盘上也是文本，但在 RAG 语义处理上它和普通 txt 明显不同：</p>
 * <ul>
 *   <li>它包含标题、链接、代码块、强调符号等标记；</li>
 *   <li>这些标记如果不处理，会污染检索和 embedding；</li>
 *   <li>同时 Markdown 往往带有天然层级结构，适合用更结构化的 splitter。</li>
 * </ul>
 *
 * <p>因此这里单独装配 Markdown 专属 parser、cleaner 和 splitter，
 * 让它走一条与普通文本不同但更贴近语义的处理链。</p>
 */
@Component
public class MarkdownIngestionProcesser extends AbstractDocumentIngestionProcesser {

    /**
     * 构造 Markdown 专属摄取处理器。
     *
     * <p>Markdown 在磁盘上虽然也是文本，但在 RAG 语义处理上和普通 txt 明显不同：
     * 它自带标题层级、链接、强调、代码块等结构噪音，因此需要单独装配 Markdown 专属 cleaner 和
     * 更偏结构化的 splitter。</p>
     */
    public MarkdownIngestionProcesser(AwsManager awsManager,
                                      RagProperties ragProperties,
                                      RagIngestionStateService stateService,
                                      AwsProperties awsProperties,
                                      RagVectorIndexingService vectorIndexingService,
                                      RagDocumentChunkRepository chunkRepository,
                                      @Qualifier("ragMarkdownDocumentParser") RagMarkdownDocumentParser markdownDocumentParser,
                                      @Qualifier("ragMarkdownTextCleaner") RagMarkdownTextCleaner markdownTextCleaner,
                                      @Qualifier("ragRecursiveStructureTextSplitter") RagTextSplitter splitter) {
        // 把 Markdown 专属的处理策略装配到父类模板流程中。
        super(awsManager, ragProperties, stateService, awsProperties,
                vectorIndexingService, chunkRepository, markdownDocumentParser, markdownTextCleaner, splitter);
    }

    /**
     * 声明当前处理器仅接管 {@code md} 扩展名。
     *
     * <p>这样上传链路可以把 Markdown 与普通 txt 明确分流，避免错误复用普通文本的处理策略。</p>
     */
    @Override
    public Set<String> supportedTypes() {
        // 当前处理器只接管 md 文件。
        return Set.of("md");
    }
}
