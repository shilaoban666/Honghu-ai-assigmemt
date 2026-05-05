package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.RagIngestionStateService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 纯文本类文件摄取处理器。
 *
 * <p>当前把 txt / md / json / xml / csv 统一按 UTF-8 文本读取。
 * 这些类型的文本抽取逻辑完全相同，因此复用父类默认实现即可。</p>
 */
@Component
public class PlainTextDocumentIngestionProcesser extends AbstractDocumentIngestionProcesser {

    public PlainTextDocumentIngestionProcesser(AwsManager awsManager,
                                               RagProperties ragProperties,
                                               RagIngestionStateService stateService,
                                               AwsProperties awsProperties) {
        super(awsManager, ragProperties, stateService, awsProperties);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("txt", "md", "json", "xml", "csv");
    }
}

