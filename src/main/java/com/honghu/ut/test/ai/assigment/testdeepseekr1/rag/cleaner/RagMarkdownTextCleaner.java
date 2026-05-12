package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.cleaner;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Markdown 专用文本清洗器。
 *
 * <p>Markdown 的核心问题是：文件里既有“内容”，也有“大量帮助排版的语法符号”。</p>
 *
 * <p>如果这些语法符号原封不动进入 RAG，可能会带来几个副作用：</p>
 * <ul>
 *   <li>embedding 关注到了 {@code ###}、{@code **} 之类无意义符号；</li>
 *   <li>检索命中的 chunk 可读性变差；</li>
 *   <li>同一段语义因为不同 Markdown 写法而产生不必要差异。</li>
 * </ul>
 *
 * <p>因此本类的目标不是“还原完整 Markdown 语义树”，而是“尽量保留可读正文，移除排版噪音”。</p>
 */
@Component
public class RagMarkdownTextCleaner extends RagGenericTextCleaner {

    @Override
    public String clean(String rawText, int maxCharacters) {
        // 没有正文时直接返回空字符串，避免执行后面一串正则替换。
        if (!StringUtils.hasText(rawText)) {
            return "";
        }

        // 先剥离 Markdown 语法层，再交给父类做通用空白清洗和截断。
        String stripped = rawText
                // 围栏代码块：去掉 ``` 标记行，保留代码内容
                .replaceAll("(?m)^```[a-zA-Z0-9]*\\s*$", "")
                .replaceAll("(?m)^```\\s*$", "")
                // 行内代码：去掉反引号，保留内容
                .replaceAll("`([^`\n]+)`", "$1")
                // 标题：去掉 # 前缀
                .replaceAll("(?m)^#{1,6}\\s+", "")
                // 加粗 + 斜体（*** 或 ___）
                .replaceAll("\\*{3}([^*\n]+)\\*{3}", "$1")
                .replaceAll("_{3}([^_\n]+)_{3}", "$1")
                // 加粗（** 或 __）
                .replaceAll("\\*{2}([^*\n]+)\\*{2}", "$1")
                .replaceAll("_{2}([^_\n]+)_{2}", "$1")
                // 斜体（* 或 _）
                .replaceAll("\\*([^*\n]+)\\*", "$1")
                .replaceAll("_([^_\n]+)_", "$1")
                // 图片和链接：保留显示文字
                .replaceAll("!?\\[([^]]*)]\\([^)]*\\)", "$1")
                // 水平分割线（整行 --- / *** / ___）
                .replaceAll("(?m)^([-*_])\\1{2,}\\s*$", "")
                // 块引用：去掉 > 前缀
                .replaceAll("(?m)^>+\\s?", "")
                // 无序列表标记
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                // 有序列表标记
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "");

        // 最后复用父类的通用清洗：统一换行、压缩空格、限制最大长度等。
        return super.clean(stripped, maxCharacters);
    }
}
