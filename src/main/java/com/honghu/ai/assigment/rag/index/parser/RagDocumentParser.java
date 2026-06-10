package com.honghu.ai.assigment.rag.index.parser;

import com.honghu.ai.assigment.rag.index.cleaner.RagTextCleaner;

import java.io.IOException;

/**
 * 文档解析策略接口（Strategy）。
 *
 * <p><strong>它在整条 RAG 摄取链路中的位置：</strong></p>
 * <ol>
 *   <li>先由上游把文件从 S3 下载成 {@code byte[]}；</li>
 *   <li>再由本接口的实现类负责把“二进制文件内容”翻译成“原始文本”；</li>
 *   <li>最后把解析出来的原始文本交给
 *       {@link RagTextCleaner}
 *       继续做清洗、截断、规范化。</li>
 * </ol>
 *
 * <p>因此，这一层只关心“<strong>能不能把文件里的文字取出来</strong>”，不关心：
 * Markdown 标记要不要去掉、PDF 连字符要不要修复、文本是否要截断等问题。
 * 这些都属于 cleaner 的职责，刻意与 parser 分离，便于单独替换和测试。</p>
 *
 * <p>每个实现类通常都在这里承担一个“适配器（Adapter）”角色，
 * 例如把 PDFBox、Apache POI、未来可能接入的 OCR 库等第三方能力，
 * 统一适配成当前项目内部可复用的 {@code parse(byte[])} 约定。</p>
 */
public interface RagDocumentParser {

    /**
     * 将文件原始字节解析为原始文本。
     *
     * <p>这里返回的文本仍然可能带有：</p>
     * <ul>
     *   <li>多余空白；</li>
     *   <li>格式标记；</li>
     *   <li>页眉页脚残留；</li>
     *   <li>PDF / OCR / Markdown 的格式噪音。</li>
     * </ul>
     *
     * <p>这些都允许存在，因为 parser 的目标只是“尽量完整地把文本提取出来”，
     * 而不是在这里过早做业务清洗。</p>
     *
     * @param fileBytes 文件原始字节；通常来自对象存储下载结果
     * @return 原始文本；当文件为空、提取不到正文时返回空字符串，不返回 {@code null}
     * @throws IOException 当底层解析库读取失败、文件内容损坏、流解析失败时抛出
     */
    String parse(byte[] fileBytes) throws IOException;
}
