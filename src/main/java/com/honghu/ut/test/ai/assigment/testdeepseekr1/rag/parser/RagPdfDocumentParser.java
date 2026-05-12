package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PDF 文件解析器，适配 Apache PDFBox 库（Adapter）。
 *
 * <p>PDF 与普通文本文件不同，它内部保存的通常不是“直接可读的字符串”，
 * 而是排版对象、字体映射、页面结构等内容，所以必须借助 PDFBox 这类专用库来提取文本。</p>
 *
 * <p>本类只做两件事：</p>
 * <ol>
 *   <li>把 PDF 二进制内容加载为 {@link PDDocument}；</li>
 *   <li>调用 {@link PDFTextStripper} 把页面里的文本抽出来。</li>
 * </ol>
 *
 * <p>PDF 特有问题，例如：</p>
 * <ul>
 *   <li>换行连字符把一个单词拆开；</li>
 *   <li>换页符残留；</li>
 *   <li>合字（fi / fl）被提取成特殊 Unicode；</li>
 * </ul>
 * <p>都不在这里处理，而是交给
 * {@link com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner.RagPdfTextCleaner}
 * 统一修复。</p>
 */
@Component
public class RagPdfDocumentParser implements RagDocumentParser {

    @Override
    public String parse(byte[] fileBytes) throws IOException {
        // 第一步：把原始 PDF 字节加载成 PDFBox 的文档对象。
        // 只有进入 PDDocument 之后，后续才可以按页扫描文本内容。
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            // 第二步：创建文本提取器。
            // PDFTextStripper 会遍历页面，把可提取的文字按阅读顺序拼接出来。
            PDFTextStripper stripper = new PDFTextStripper();
            // 第三步：真正执行提取。
            String text = stripper.getText(document);
            // 第四步：对下游统一返回非 null 值，避免 cleaner 和 splitter 额外判空。
            return text == null ? "" : text;
        }
    }
}

