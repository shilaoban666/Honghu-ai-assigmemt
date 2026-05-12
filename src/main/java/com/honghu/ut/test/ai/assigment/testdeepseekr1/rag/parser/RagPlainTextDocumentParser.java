package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 纯文本文件解析器，适用于 txt / json / xml / csv。
 *
 * <p>这一类文件的共同点是：文件内容本身就是“文本”，并不需要像 PDF / DOCX 那样先解析复杂格式结构。</p>
 *
 * <p>因此这里的处理非常直接：</p>
 * <ol>
 *   <li>先判断文件是否为空；</li>
 *   <li>如果为空，直接返回空字符串；</li>
 *   <li>如果不为空，就按 UTF-8 把字节数组解码成 Java 字符串。</li>
 * </ol>
 *
 * <p>之所以把它单独做成一个 parser，而不是把“字节转字符串”直接写进处理器里，
 * 是为了保持摄取主流程的统一：所有格式都通过 `parser -> cleaner -> splitter` 三段式处理。</p>
 */
@Component
public class RagPlainTextDocumentParser implements RagDocumentParser {

	@Override
	public String parse(byte[] fileBytes) {
		// 先兜底处理 null 或空数组。
		// 这样上游即使传入空文件，也能得到稳定的空字符串，而不是异常。
		if (fileBytes == null || fileBytes.length == 0) {
			return "";
		}
		// 按 UTF-8 解码文件内容。
		// txt / json / xml / csv 在当前项目约定中都按 UTF-8 处理。
		return new String(fileBytes, StandardCharsets.UTF_8);
	}
}

