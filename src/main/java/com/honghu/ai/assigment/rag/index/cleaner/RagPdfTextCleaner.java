package com.honghu.ai.assigment.rag.index.cleaner;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PDF 专用文本清洗器。
 *
 * <p>PDF 的难点往往不在“能不能抽出字符”，而在于“抽出来的字符长得不像正常正文”。</p>
 *
 * <p>常见脏数据包括：</p>
 * <ul>
 *   <li>一个英文单词因为版面换行被拆成 {@code exam-\nple}；</li>
 *   <li>页与页之间夹着换页符，导致段落被异常切断；</li>
 *   <li>ligature（合字）例如 {@code ﬁ}/{@code ﬂ} 被提成特殊 Unicode，看起来像乱码；</li>
 *   <li>有些 PDF 提取结果保留了版面痕迹，而不是纯阅读顺序文本。</li>
 * </ul>
 *
 * <p>这些问题如果不在这里修掉，会直接影响：</p>
 * <ul>
 *   <li>关键词检索命中率，因为词可能被拆断；</li>
 *   <li>embedding 语义质量，因为异常字符会形成噪音；</li>
 *   <li>最终 chunk 的可读性，进而影响大模型引用效果。</li>
 * </ul>
 *
 * <p>因此本类先做 PDF 特有修复，再把结果交给父类执行通用清洗。</p>
 */
@Component
public class RagPdfTextCleaner extends RagGenericTextCleaner {

    /**
     * 对 PDF 提取出的原始文本做专属修复。
     *
     * <p>当前修复重点放在几类最常见、最影响检索质量的问题上：</p>
     * <ol>
     *   <li>修复英文断词换行；</li>
     *   <li>把换页符转成段落分隔；</li>
     *   <li>把常见 ligature 转回普通 ASCII 文本；</li>
     *   <li>最后再走父类通用清洗。</li>
     * </ol>
     *
     * @param rawText PDFBox 等解析器提取出的原始文本
     * @param maxCharacters 最大保留字符数
     * @return 修复过常见 PDF 噪音后的正文文本
     */
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
