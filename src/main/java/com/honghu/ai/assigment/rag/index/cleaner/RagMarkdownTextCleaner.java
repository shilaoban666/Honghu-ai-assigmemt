package com.honghu.ai.assigment.rag.index.cleaner;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Markdown 专用文本清洗器。
 *
 * <p>Markdown 的特点是：同一份文件里同时混着两类信息：</p>
 * <ul>
 *   <li><strong>真正要给模型理解的正文</strong>；</li>
 *   <li><strong>只服务于排版的语法符号</strong>，例如 {@code ###}、{@code **}、{@code >}、{@code ```}。</li>
 * </ul>
 *
 * <p>如果这些语法符号原封不动进入 RAG，常见副作用包括：</p>
 * <ul>
 *   <li>embedding 额外学习了排版噪音，而不是正文语义；</li>
 *   <li>关键词检索时，chunk 看起来更乱、更难读；</li>
 *   <li>同一段话因为写法不同（粗体、链接、列表）而产生不必要差异。</li>
 * </ul>
 *
 * <p>因此本类的目标不是“完整保留 Markdown 语法树”，而是“尽可能保留可读正文，尽可能去掉
 * 干扰检索和向量化的排版符号”。它先做 Markdown 专属的语法剥离，再复用父类的通用空白清洗。</p>
 */
@Component
public class RagMarkdownTextCleaner extends RagGenericTextCleaner {

    /**
     * 清洗 Markdown 文本。
     *
     * <p>处理思路分两段：</p>
     * <ol>
     *   <li>先把明显属于 Markdown 语法层的标记尽量剥离，但尽量保留人真正要读的文本；</li>
     *   <li>再交给父类执行通用空白归一化、trim 和最大长度截断。</li>
     * </ol>
     *
     * <p>例如链接 {@code [标题](url)} 会尽量保留“标题”，去掉 URL；
     * 行内代码 {@code `name`} 会保留 name；标题前缀 {@code ### } 会被剥离。</p>
     *
     * @param rawText parser 输出的 Markdown 原文
     * @param maxCharacters 最大保留字符数
     * @return 去掉大部分 Markdown 排版噪音后的正文文本
     */
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
