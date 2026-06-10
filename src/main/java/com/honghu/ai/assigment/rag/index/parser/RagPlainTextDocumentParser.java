package com.honghu.ai.assigment.rag.index.parser;

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

	/**
	 * 将纯文本类文件字节按 UTF-8 解码成字符串。
	 *
	 * <p>这里的“纯文本类文件”包括 txt、json、xml、csv 等。它们在当前项目里都约定为
	 * UTF-8 编码处理，因此 parser 层不去猜测编码，也不在这里尝试自动探测字符集。</p>
	 *
	 * <p>这样做的好处是实现简单、可预测，但前提是上传链路需要尽量保证源文件编码一致。
	 * 如果未来要支持 GBK / GB2312 / ISO-8859-1 等更多编码，可以在这里再扩展探测逻辑。</p>
	 *
	 * @param fileBytes 文件原始字节；允许为空或 null
	 * @return 解码后的原始文本；空文件返回空字符串
	 */
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

