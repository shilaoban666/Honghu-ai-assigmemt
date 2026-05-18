package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser;

import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 图片 OCR 解析器（占位实现）。
 *
 * <p>与 txt / pdf / docx 不同，图片文件本身通常并不直接包含可复制的文本，
 * 必须先做 OCR（Optical Character Recognition，光学字符识别）才能得到文字内容。</p>
 *
 * <p>当前这个类先把“图片解析”这个扩展点预留出来，但故意不偷偷返回空字符串，
 * 而是直接抛出异常，原因是：</p>
 * <ol>
 *   <li>如果静默返回空文本，系统会误以为文件内容为空；</li>
 *   <li>这样会把“未实现 OCR”伪装成“图片里没有文字”；</li>
 *   <li>直接抛异常能让摄取状态明确显示为失败或未启用，更容易排查。</li>
 * </ol>
 *
 * <p>后续可接入的实现方向包括：</p>
 * <ul>
 *   <li><b>本地 Tesseract</b>：引入 {@code net.sourceforge.tess4j:tess4j}；</li>
 *   <li><b>AWS Textract</b>：通过 AWS SDK 调用文档识别接口；</li>
 *   <li><b>视觉大模型</b>：把图片交给支持视觉输入的模型提取文字。</li>
 * </ul>
 *
 * <p>实现 OCR 后，还需要同步打开扩展名白名单，否则上游流程仍然不会放行图片文件。</p>
 */
@Component
public class RagImageOcrParser implements RagDocumentParser {

    @Override
    public String parse(byte[] fileBytes) throws IOException {
        // 这里故意不返回空字符串，而是明确告诉调用方：
        // 当前项目尚未具备真正的图片 OCR 能力。
        // 这样状态机会把问题暴露出来，而不会误认为“抽取结果为空”。
        throw new UnsupportedOperationException(
                "图片 OCR 解析尚未实现。请选择 Tesseract / AWS Textract / 视觉模型之一集成后再启用 png/jpg 支持。");
    }
}
