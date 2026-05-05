package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.processer;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AwsProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.RagProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.manager.AwsManager;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.RagIngestionStateService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * PDF 文件摄取处理器。
 *
 * <p>PDF 不能像 txt 那样直接按字节转字符串，因此这里使用 PDFBox 做文本抽取；
 * 其余的幂等、状态推进、分块和入库流程全部复用父类。</p>
 */
@Component
public class PdfIngestionProcesser extends AbstractDocumentIngestionProcesser {

    public PdfIngestionProcesser(AwsManager awsManager,
                                 RagProperties ragProperties,
                                 RagIngestionStateService stateService,
                                 AwsProperties awsProperties) {
        super(awsManager, ragProperties, stateService, awsProperties);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("pdf");
    }

    @Override
    public String extractText(String fileType, byte[] fileBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }
}
