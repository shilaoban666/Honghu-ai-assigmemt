package com.honghu.ai.assigment.rag.index.parser;

import com.honghu.ai.assigment.rag.index.cleaner.RagMarkdownTextCleaner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Markdown 文件解析器。
 *
 * <p>Markdown 在磁盘上本质也是纯文本，所以“解析”阶段并不需要识别 AST、标题层级、链接树等复杂结构；
 * 这里只负责把字节转换成字符串。</p>
 *
 * <p>之所以仍然单独保留一个 Markdown parser，而不是直接复用普通文本 parser，
 * 是因为业务语义上它属于一种“独立文件类型”：后续会配套专门的 cleaner 和 splitter，
 * 这样在处理器装配阶段能明确区分 txt 和 md 的处理链路。</p>
 *
 * <p>Markdown 特有的语法标记，例如：</p>
 * <ul>
 *   <li>{@code #} 标题前缀；</li>
 *   <li>{@code **} / {@code _} 强调标记；</li>
 *   <li>{@code [text](url)} 链接语法；</li>
 *   <li>代码块围栏 {@code ```}；</li>
 * </ul>
 * <p>都由
 * {@link RagMarkdownTextCleaner}
 * 在下游清洗阶段处理。</p>
 */
@Component
public class RagMarkdownDocumentParser implements RagDocumentParser {

    /**
     * 将 Markdown 文件字节按 UTF-8 解码成原始文本。
     *
     * <p>这里之所以只做“解码”，不做“解析 Markdown 结构”，是因为在当前项目里：
     * Markdown 的标题、强调、链接、代码块等语法，都统一交给
     * {@code RagMarkdownTextCleaner} 在下游处理。这样 parser 层保持简单、稳定且容易复用。</p>
     *
     * @param fileBytes Markdown 文件原始字节
     * @return 解码后的原始 Markdown 文本；空文件返回空字符串
     */
    @Override
    public String parse(byte[] fileBytes) {
        // 如果文件为空，就直接返回空字符串，避免解码阶段产生无意义对象。
        if (fileBytes == null || fileBytes.length == 0) {
            return "";
        }
        // Markdown 在项目中按 UTF-8 解码。
        // 此处只做“字节 -> 文本”的转换，不做任何 Markdown 标记清理。
        return new String(fileBytes, StandardCharsets.UTF_8);
    }
}
