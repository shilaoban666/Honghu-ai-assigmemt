package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

/**
 * 通用文本清洗器，适用于所有格式的默认清洗逻辑。
 *
 * <p>这个类可以理解成“所有文本都会经过的一次基础整理”。</p>
 *
 * <p>它不处理格式专属语义，只处理几乎所有文本都需要的共性问题：</p>
 * <ol>
 *   <li>去掉 NULL 字节，避免奇怪的不可见字符污染文本；</li>
 *   <li>统一换行格式，避免 Windows / Linux / 旧系统的换行差异；</li>
 *   <li>把制表符等不稳定空白归一成普通空格；</li>
 *   <li>压缩连续空格和过多空行；</li>
 *   <li>清理首尾空白；</li>
 *   <li>最后按照配置截断，防止超长文本拖垮后续切分和向量化。</li>
 * </ol>
 *
 * <p>如果某种格式还需要额外处理，例如 PDF 的断词、Markdown 的标记语法，
 * 就由子类先做专属清洗，再调用 {@code super.clean(...)} 复用这里的通用步骤。</p>
 *
 * <p>{@link Primary} 的作用是：当 Spring 发现多个 {@link RagTextCleaner} Bean 时，
 * 默认优先注入本类，避免通用场景出现歧义。</p>
 */
@Primary
@Component
public class RagGenericTextCleaner implements RagTextCleaner {

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
