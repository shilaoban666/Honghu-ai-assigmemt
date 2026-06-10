package com.honghu.ai.assigment.skill.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.skill.builtin.claude.SkillMdParser;
import com.honghu.ai.assigment.skill.builtin.claude.SkillPromptResolver;
import com.honghu.ai.assigment.skill.core.SkillSource;
import com.honghu.ai.assigment.skill.entity.Skill;
import com.honghu.ai.assigment.skill.repository.SkillRepository;
import com.honghu.ai.assigment.skill.translation.SkillTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic 官方 Claude Skills 仓库爬虫。
 *
 * <p>从 GitHub <a href="https://github.com/anthropics/skills">anthropics/skills</a> 拉取真实的
 * Claude Skill 目录：用一次 git trees 接口列出所有 {@code SKILL.md} 路径，再逐个抓取 raw 文件，
 * 解析 frontmatter（name/description/license）后写入 {@code skill} 表（source=CLAUDE_SKILL）。</p>
 *
 * <p>Claude Skill 是“提示词运行时能力”，本爬虫只读取并落库目录元信息，不下载/执行任何脚本。
 * 真正的提示词注入仍由 {@link SkillPromptResolver} 在会话启用该技能时完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeSkillCrawler {

    /** 解析 GitHub tree API 响应。 */
    private final ObjectMapper objectMapper;

    /** Claude Skill 目录项最终写入 skill 表。 */
    private final SkillRepository skillRepository;

    /** 解析每个 SKILL.md 的 frontmatter。 */
    private final SkillMdParser skillMdParser;

    /** 爬取后增量翻译英文描述。 */
    private final SkillTranslationService translationService;

    /** 复用项目统一 OkHttpClient，获得统一超时和连接池。 */
    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient httpClient;

    /** 统一爬虫线程池；字段名与 Bean 名一致，按注入点名称匹配规避多 Executor 歧义。 */
    private final java.util.concurrent.Executor skillCrawlerExecutor;

    /** 是否启用 Claude Skills 爬虫。 */
    @Value("${app.skill.crawler.claude.enabled:true}")
    private boolean enabled;

    /** 目标仓库 owner/repo。 */
    @Value("${app.skill.crawler.claude.repo:anthropics/skills}")
    private String repo;

    /** 仓库默认分支。 */
    @Value("${app.skill.crawler.claude.branch:main}")
    private String branch;

    /** 可选 GitHub token，提高 API 速率限制；为空时走匿名访问。 */
    @Value("${app.skill.crawler.claude.github-token:${GITHUB_TOKEN:}}")
    private String githubToken;

    /** 防止启动同步和定时同步并发执行。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 应用启动完成后异步同步一次 Claude Skills 目录。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("Claude Skills 爬虫已禁用（app.skill.crawler.claude.enabled=false），跳过启动同步");
            return;
        }
        // 启动同步提交到统一的有界爬虫线程池（单线程串行），避免启动并发抢占连接池/CPU；
        // GitHub API 慢或不可用也只占用这条后台流水线，不阻塞应用启动。
        skillCrawlerExecutor.execute(this::crawlSafely);
    }

    /** 默认每周一凌晨 4:00 同步一次；Claude Skills 更新频率不高。 */
    @Scheduled(cron = "${app.skill.crawler.claude.cron:0 0 4 * * MON}")
    public void scheduledCrawl() {
        if (!enabled) {
            // 关闭配置时定时任务不做任何网络请求。
            return;
        }
        crawlSafely();
    }

    /**
     * 带并发保护和异常兜底的同步入口。
     */
    public void crawlSafely() {
        if (!running.compareAndSet(false, true)) {
            // 已有同步在跑时跳过，避免并发写同一批 skill。
            log.info("Claude Skills 同步已在进行中，本次触发跳过");
            return;
        }
        try {
            // crawl 返回本次成功 upsert 的技能数量。
            int saved = crawl();
            log.info("Claude Skills 同步完成，本次落库/更新 {} 个 Claude 技能", saved);
            // 同步后把缺中文翻译的描述补齐（增量、尽力而为）。
            translationService.translatePending(SkillSource.CLAUDE_SKILL);
        } catch (Exception ex) {
            // 爬虫失败不能影响主应用；记录 warning 等下一次同步恢复。
            log.warn("Claude Skills 同步失败：{}", ex.getMessage(), ex);
        } finally {
            // 必须释放运行锁，否则后续定时任务会一直跳过。
            running.set(false);
        }
    }

    /**
     * 从 GitHub 仓库递归 tree 中找到所有顶层 skills/<dir>/SKILL.md 并落库。
     */
    public int crawl() throws Exception {
        // 一次 git trees API 获取仓库文件树。
        JsonNode tree = fetchTree();
        // GitHub 响应中的文件节点数组在 tree 字段。
        JsonNode nodes = tree.path("tree");
        if (!nodes.isArray()) {
            // 响应结构异常时不处理任何条目。
            return 0;
        }
        int saved = 0;
        for (JsonNode node : nodes) {
            // path 是仓库内相对路径，例如 skills/foo/SKILL.md。
            String path = node.path("path").asText("");
            // 只处理仓库 skills/ 目录下的真实技能，跳过 template/SKILL.md 等非技能样例。
            if (!path.startsWith("skills/") || !path.endsWith("/SKILL.md")) {
                continue;
            }
            // 从 skills/<dir>/SKILL.md 中抽取 dir 作为 skillKey 的后缀。
            String dir = path.substring("skills/".length(), path.length() - "/SKILL.md".length());
            if (!StringUtils.hasText(dir) || dir.contains("/")) {
                // 只接受 skills 目录下一层技能，跳过更深层路径。
                continue;
            }
            try {
                // 单个技能失败不影响整个爬取批次。
                if (upsertSkill(dir, path)) {
                    saved++;
                }
            } catch (Exception ex) {
                log.debug("跳过一个 Claude Skill：dir={}, error={}", dir, ex.getMessage());
            }
        }
        return saved;
    }

    /**
     * 调用 GitHub git trees API 拉取仓库文件树。
     */
    private JsonNode fetchTree() throws Exception {
        // recursive=1 表示递归返回所有文件路径，避免逐目录请求。
        String url = "https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1";
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get();
        if (StringUtils.hasText(githubToken)) {
            // 有 token 时带上 Authorization，提高 GitHub API 速率限制。
            builder.header("Authorization", "Bearer " + githubToken);
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                // 非 2xx 直接失败，外层 crawlSafely 会记录 warning。
                throw new IllegalStateException("GitHub trees API returned " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("GitHub trees API returned empty body");
            }
            // 响应体只能读取一次，立即解析成 JsonNode。
            return objectMapper.readTree(body.string());
        }
    }

    /**
     * 抓取并 upsert 一个 Claude Skill。
     */
    private boolean upsertSkill(String dir, String path) throws Exception {
        // 拉取 SKILL.md 原文。
        String markdown = fetchRaw(path);
        // 解析 frontmatter 和正文；当前只落库 frontmatter 元数据。
        SkillMdParser.ParsedSkillMd parsed = skillMdParser.parse(markdown);
        Map<String, String> frontmatter = parsed.frontmatter();

        // 统一用 skill:<dir> 作为 Claude Skill 的业务 key。
        String skillKey = "skill:" + dir;
        // name 缺失时回退目录名。
        String displayName = frontmatter.getOrDefault("name", dir);
        // description/license 直接来自 frontmatter。
        String description = frontmatter.get("description");
        String license = frontmatter.get("license");

        // 按 skillKey 幂等更新。
        Skill row = skillRepository.findBySkillKey(skillKey).orElse(null);
        boolean isNew = row == null;
        if (isNew) {
            // 新条目默认出现在市场，但不默认启用，也不是必装。
            row = new Skill();
            row.setSkillKey(skillKey);
            row.setEnabled(true);
            row.setDefaultEnabled(false);
            row.setMandatory(false);
            row.setRequiredRole(User.UserRole.USER);
        }
        // 标记来源为 CLAUDE_SKILL，resolver 后续才会把它当 prompt 能力处理。
        row.setSource(SkillSource.CLAUDE_SKILL);
        row.setDisplayName(truncate(displayName, 200));
        row.setDescription(description);
        row.setCategory("Claude Skills");
        row.setVersion("v1");
        row.setAuthor("Anthropic");
        row.setRepoUrl("https://github.com/" + repo + "/tree/" + branch + "/" + dir(path));
        row.setLicense(truncate(StringUtils.hasText(license) ? license : "see repo", 50));
        if (row.getRequiredRole() == null) {
            // 兼容旧数据缺角色字段的情况。
            row.setRequiredRole(User.UserRole.USER);
        }
        // 保存新建或更新后的 Skill。
        skillRepository.save(row);
        return true;
    }

    /**
     * 拉取某个 SKILL.md 的 raw 内容。
     */
    private String fetchRaw(String path) throws Exception {
        // raw.githubusercontent.com 可直接返回文件原文。
        String url = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                // 404/限流/空响应都作为该技能抓取失败。
                throw new IllegalStateException("Unable to fetch SKILL.md: " + path + " (" + response.code() + ")");
            }
            // 直接返回 markdown 原文。
            return response.body().string();
        }
    }

    /**
     * 把 SKILL.md 文件路径转换为 GitHub tree 目录路径。
     */
    private String dir(String path) {
        // skills/<dir>/SKILL.md -> skills/<dir>
        return path.substring(0, path.length() - "/SKILL.md".length());
    }

    /**
     * 截断字符串到数据库字段长度。
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        // 上游文本可能超过数据库字段长度，保存前先截断。
        return value.length() > max ? value.substring(0, max) : value;
    }
}
