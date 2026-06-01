package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.claude;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claude 风格 {@code SKILL.md} 文件的轻量解析器。
 *
 * <p>完整 frontmatter 解析通常会引入 SnakeYAML。当前项目为了减少依赖，只支持本阶段需要的子集：
 * 文件开头是 {@code ---}，中间是若干 {@code key: value} 行，再用 {@code ---} 结束，后面是 markdown 正文。
 * 解析过程是纯字符串处理，不执行 markdown 里提到的脚本，也不访问任何外部资源。</p>
 */
@Component
public class SkillMdParser {

    /**
     * 从一个 SKILL.md 文档中解析 frontmatter 和正文。
     *
     * @param markdown 原始 markdown 文本
     * @return 解析后的 frontmatter 与正文
     */
    public ParsedSkillMd parse(String markdown) {
        if (markdown == null) {
            // null 输入按空文档处理，调用方不用额外判空。
            return new ParsedSkillMd(Map.of(), "");
        }
        // 统一换行符，避免 Windows CRLF 导致 frontmatter 边界匹配失败。
        String normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            // 没有 frontmatter 时，整个文本都作为正文。
            return new ParsedSkillMd(Map.of(), normalized.trim());
        }
        // 查找第二个 ---，从下标 4 开始是为了跳过开头的 "---\n"。
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            // 起始 --- 没有闭合时，保守地把整份内容当正文。
            return new ParsedSkillMd(Map.of(), normalized.trim());
        }
        // frontmatter 文本不包含两侧 ---。
        String frontmatterText = normalized.substring(4, end);
        // 正文从第二个 --- 后开始；trim 去掉首尾空白。
        String body = normalized.substring(Math.min(normalized.length(), end + 4)).trim();
        // LinkedHashMap 保持 frontmatter 原始顺序，便于调试。
        Map<String, String> frontmatter = new LinkedHashMap<>();
        for (String line : frontmatterText.split("\n")) {
            // 只支持最简单的 key: value，一行里第一个冒号作为分隔符。
            int split = line.indexOf(':');
            if (split <= 0) {
                // 没有冒号或 key 为空的行跳过。
                continue;
            }
            // key/value 都 trim，避免 YAML 常见空格影响读取。
            frontmatter.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        return new ParsedSkillMd(frontmatter, body);
    }

    /**
     * 解析后的 SKILL.md 表示。
     *
     * @param frontmatter 简单 key/value 元数据，例如 name、description、license
     * @param body 技能说明 markdown 正文
     */
    public record ParsedSkillMd(Map<String, String> frontmatter, String body) {
    }
}
