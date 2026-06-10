package com.honghu.ai.assigment.rag.index.parser;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * DOCX 文件解析器，适配 Apache POI 库（Adapter）。
 *
 * <p>这个类专门处理 Office Open XML 格式的 Word 文档，也就是常见的 {@code .docx} 文件。</p>
 *
 * <p><strong>它做的事情很单纯：</strong></p>
 * <ol>
 *   <li>把上传后的 Word 文件字节包装成输入流；</li>
 *   <li>交给 Apache POI 识别 DOCX 的内部结构；</li>
 *   <li>从文档结构中提取可读文本；</li>
 *   <li>把结果原样返回给后续 cleaner 做进一步清洗。</li>
 * </ol>
 *
 * <p>它<strong>不负责</strong>：去空白、去重复换行、截断超长文本、切分 chunk。
 * 这样拆层后，每一层职责都更清晰。</p>
 *
 * <p>当前实现只支持 {@code .docx}，不支持老式二进制 {@code .doc}。
 * 如果后续要支持 {@code .doc}，通常需要额外引入 Apache POI 的 HWPF 相关能力。</p>
 *
 * <p>需要在 `pom.xml` 中引入：</p>
 * <pre>
 *   &lt;dependency&gt;
 *     &lt;groupId&gt;org.apache.poi&lt;/groupId&gt;
 *     &lt;artifactId&gt;poi-ooxml&lt;/artifactId&gt;
 *     &lt;version&gt;5.2.5&lt;/version&gt;
 *   &lt;/dependency&gt;
 * </pre>
 */
@Component
public class RagDocxDocumentParser implements RagDocumentParser {

    /**
     * 使用 Apache POI 从 DOCX 二进制内容中提取纯文本。
     *
     * <p>这里的职责边界非常明确：</p>
     * <ul>
     *   <li>输入是已经完整下载到内存中的 {@code byte[]}；</li>
     *   <li>本方法负责把它交给 POI 解析成 Word 文档对象；</li>
     *   <li>再从段落、表格等结构中尽量抽取出文本；</li>
     *   <li>最后保证对外返回非 {@code null} 字符串。</li>
     * </ul>
     *
     * <p>它不做换行整理、空白压缩、超长截断，这些都交给 cleaner。</p>
     *
     * @param fileBytes DOCX 文件原始字节内容
     * @return Apache POI 提取出的原始文本；提取不到时返回空字符串
     * @throws IOException 当 DOCX 文件损坏、结构非法或 POI 读取失败时抛出
     */
    @Override
    public String parse(byte[] fileBytes) throws IOException {
        // 第一步：把原始字节包装成输入流，让 Apache POI 能按“文档流”的方式读取内容。
        // 这里使用 ByteArrayInputStream，是因为上游已经把文件完整下载到内存中了。
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
             // 第二步：创建文本提取器。
             // XWPFWordExtractor 会从段落、表格等 Word 结构中尽量抽取出纯文本表示。
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            // 第三步：执行真正的文本提取。
            String text = extractor.getText();
            // 第四步：统一保证对外不返回 null，减少下游空指针判断负担。
            return text == null ? "" : text;
        }
    }
}

