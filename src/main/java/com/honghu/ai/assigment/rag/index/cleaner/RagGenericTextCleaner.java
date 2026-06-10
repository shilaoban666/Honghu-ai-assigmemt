package com.honghu.ai.assigment.rag.index.cleaner;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

/**
 * 通用文本清洗器，适用于所有格式的默认清洗逻辑。
 *
 * <p>可以把这个类理解成 RAG 文本预处理流水线里的“基础保洁层”：
 * 无论上游输入来自 PDF、DOCX、Markdown 还是普通文本，只要已经被 parser
 * 成功抽成字符串，通常都应该先经过这里做一次统一整理。</p>
 *
 * <p>它<strong>刻意不做</strong>任何强格式语义判断，例如：</p>
 * <ul>
 *   <li>不会识别 Markdown 标题层级；</li>
 *   <li>不会修复 PDF 连字符断词；</li>
 *   <li>不会识别 OCR 置信度或版面坐标。</li>
 * </ul>
 *
 * <p>本类只做几乎所有文本都需要的“通用整理动作”，让后续 splitter、embedding、检索
 * 拿到更稳定、更干净、更可预测的输入：</p>
 * <ol>
 *   <li>移除 NULL 字节等不可见脏字符，避免污染索引和日志；</li>
 *   <li>统一换行表达，减少跨平台文本差异；</li>
 *   <li>归一化制表符等不稳定空白，防止同一内容被表示成很多种形式；</li>
 *   <li>压缩冗余空格和过多空行，降低 embedding 噪音；</li>
 *   <li>trim 首尾空白，避免 chunk 边界处出现无意义字符；</li>
 *   <li>按最大字符数截断，控制后续切分和向量化成本。</li>
 * </ol>
 *
 * <p>如果某种格式还需要额外处理，例如 PDF 的断词、Markdown 的标记语法，
 * 就由子类先做专属清洗，再调用 {@code super.clean(...)} 复用这里的通用步骤。</p>
 *
 * <p>{@link Primary} 的作用是：当 Spring 容器里存在多个 {@link RagTextCleaner}
 * 实现时，默认优先注入本类，避免普通文本场景因 Bean 冲突而出现歧义。</p>
 */
@Primary
@Component
public class RagGenericTextCleaner implements RagTextCleaner {

    /**
     * 对原始文本执行一套“尽量无损”的基础清洗流程。
     *
     * <p>这里的核心原则是：<strong>优先整理表示形式，而不是改变正文语义</strong>。
     * 也就是说，本方法更像是在做“文本规范化”，而不是“内容改写”。</p>
     *
     * <p>执行顺序为：</p>
     * <ol>
     *   <li>空文本快速返回；</li>
     *   <li>做换行、空白、控制字符清理；</li>
     *   <li>校验最大长度配置是否合法；</li>
     *   <li>必要时按上限截断；</li>
     *   <li>返回最终可供 splitter 使用的稳定文本。</li>
     * </ol>
     *
     * @param rawText parser 输出的原始文本，可能包含各种不可见字符、平台换行差异和冗余空白
     * @param maxCharacters 最多允许保留的字符数；小于等于 0 时直接返回空字符串
     * @return 清洗后的文本；如果输入为空白则返回空字符串，不返回 {@code null}
     */
    @Override
    public String clean(String rawText, int maxCharacters) {
        // 第一步：如果原始文本为空或全是空白，直接返回空字符串。
        // 这样可以避免后面做一串正则和替换操作的无效开销。
        if (!StringUtils.hasText(rawText)) {
            return "";
        }

        // 第二步：执行基础规范化。
        // 这里每一步都尽量只做“无损整理”，不主动改变文本语义。
        String sanitized = rawText
                // 去掉 NULL 字节，避免部分解析库或二进制内容残留不可见脏字符。
                .replace("\u0000", "")
                // 统一 Windows 风格换行 CRLF 为 LF。
                .replace("\r\n", "\n")
                // 再把孤立的 CR 也转成 LF，保证系统内部只保留一种换行表示。
                .replace('\r', '\n')
                // 把制表符、垂直制表符、换页符等不稳定空白统一替换成单个空格。
                .replaceAll("[\\t\\x0B\\f]+", " ")
                // 把多个连续空格压成一个，提升可读性，也减少 embedding 噪音。
                .replaceAll("[ ]{2,}", " ")
                // 把 3 个及以上连续空行压缩成 2 个，保留段落感但避免空洞过多。
                .replaceAll("\n{3,}", "\n\n")
                // 清理头尾空白，避免 chunk 首尾出现无意义空格或空行。
                .trim();

        // 第三步：保护性处理最大长度配置。
        // 如果配置值非法（0 或负数），这里选择直接返回空字符串，避免 substring 越界。
        if (maxCharacters <= 0) {
            return "";
        }

        // 第四步：当清洗后的文本仍然过长时，按上限截断。
        // 这样能控制单文档后续切分、向量化与存储的成本。
        if (sanitized.length() > maxCharacters) {
            return sanitized.substring(0, maxCharacters);
        }

        // 第五步：长度在允许范围内，则原样返回清洗结果。
        return sanitized;
    }
}
