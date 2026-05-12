package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PDF 专用文本清洗器。
 *
 * <p>PDF 文本提取后最常见的问题不是“没有文字”，而是“文字形式很脏”。</p>
 *
 * <p>例如：</p>
 * <ul>
 *   <li>一个英文单词因为版面换行被拆成 {@code exam-\nple}；</li>
 *   <li>页与页之间夹着换页符；</li>
 *   <li>连字 fi / fl 被编码成特殊字符，看起来像乱码。</li>
 * </ul>
 *
 * <p>这些问题如果不在这里修掉，会直接影响：</p>
 * <ul>
 *   <li>关键词检索命中率；</li>
 *   <li>embedding 语义质量；</li>
 *   <li>最终 chunk 的可读性。</li>
 * </ul>
 *
 * <p>因此本类先做 PDF 特有修复，再把结果交给父类执行通用清洗。</p>
 */
@Component
public class RagPdfTextCleaner extends RagGenericTextCleaner {

    @Override
    public String clean(String rawText, int maxCharacters) {
        // 如果 PDF 提取出来的文本为空，就直接返回空字符串，不做后续专属修复。
        if (!StringUtils.hasText(rawText)) {
            return "";
        }

        // 先做 PDF 特有的预处理，把最常见的排版噪音修正掉。
        String fixed = rawText
                // 修复英文连字符换行（小写字母后接 -\n 再接小写字母）
                .replaceAll("([a-zA-Z])-\n([a-z])", "$1$2")
                // 换页符替换为段落分隔
                .replace("\f", "\n\n")
                // 修复常见 PDF 合字（Unicode Ligature → ASCII）
                .replace("ﬀ", "ff")
                .replace("ﬁ", "fi")
                .replace("ﬂ", "fl")
                .replace("ﬃ", "ffi")
                .replace("ﬄ", "ffl");

        // 再进入父类的通用清洗流程，复用换行统一、空白压缩、截断等标准逻辑。
        return super.clean(fixed, maxCharacters);
    }
}
