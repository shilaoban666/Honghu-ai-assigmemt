package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.SkillSource;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.translation.SkillTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 国内 MCP 爬虫（数据源：PulseMCP 聚合目录）。
 *
 * <p>官方 MCP Registry 偏欧美，几乎没有国内常用服务。PulseMCP 聚合了全网 MCP，并且收录了大量国内服务
 * （高德/Amap、12306、Bilibili、百度、微博、支付宝、抖音、小红书、点评等）。这个爬虫用一组「国内关键词」
 * 去 PulseMCP 搜索，把命中的国内 MCP 归一化写入 {@code skill} 表（source=MCP），让技能商店里也能看到国内 MCP。</p>
 *
 * <p>PulseMCP 上很多国内服务是 stdio 包（无托管 HTTP 端点），所以这里多数条目只进目录、暂不可直接远程执行，
 * 这与官方 Registry 里大量 stdio 条目一致。英文描述会由 {@link SkillTranslationService} 翻成中文。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PulseMcpChineseCrawler {

    /** 含中文字符即认为是国内服务，用于过滤搜索噪声。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 默认的国内关键词集合（编译期常量，供 @Value 默认值使用；PulseMCP 用英文/拼音索引为主）。 */
    private static final String DEFAULT_QUERIES =
            "china,chinese,amap,gaode,baidu,tencent,alibaba,alipay,wechat,weixin,"
            + "12306,railway china,bilibili,weibo,douyin,tiktok china,xiaohongshu,rednote,"
            + "dianping,meituan,taobao,jd.com,zhihu,juejin,qq,feishu,lark,dingtalk,"
            + "qiniu,aliyun,tencent cloud,minimax,moonshot,zhipu,deepseek,qwen";

    private final ObjectMapper objectMapper;
    private final SkillRepository skillRepository;
    private final SkillTranslationService translationService;

    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient httpClient;

    /** 统一爬虫线程池；字段名与 Bean 名一致，按注入点名称匹配规避多 Executor 歧义。 */
    private final java.util.concurrent.Executor skillCrawlerExecutor;

    @Value("${app.skill.crawler.cn-mcp.enabled:true}")
    private boolean enabled;

    @Value("${app.skill.crawler.cn-mcp.base-url:https://api.pulsemcp.com/v0beta/servers}")
    private String baseUrl;

    /** 每个关键词最多取多少条。 */
    @Value("${app.skill.crawler.cn-mcp.per-query:30}")
    private int perQuery;

    /** 本次最多落库多少个国内 MCP。 */
    @Value("${app.skill.crawler.cn-mcp.max-servers:200}")
    private int maxServers;

    @Value("${app.skill.crawler.cn-mcp.queries:" + DEFAULT_QUERIES + "}")
    private String queries;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("国内 MCP 爬虫已禁用（app.skill.crawler.cn-mcp.enabled=false），跳过启动同步");
            return;
        }
        // 启动同步提交到统一的有界爬虫线程池（单线程串行），避免启动并发抢占连接池/CPU。
        skillCrawlerExecutor.execute(this::crawlSafely);
    }

    /** 默认每天凌晨 3:50 同步一次。 */
    @Scheduled(cron = "${app.skill.crawler.cn-mcp.cron:0 50 3 * * *}")
    public void scheduledCrawl() {
        if (enabled) {
            crawlSafely();
        }
    }

    public void crawlSafely() {
        if (!running.compareAndSet(false, true)) {
            log.info("国内 MCP 同步已在进行中，本次触发跳过");
            return;
        }
        try {
            int saved = crawl();
            log.info("国内 MCP（PulseMCP）同步完成，本次落库/更新 {} 个", saved);
            translationService.translatePending(SkillSource.MCP);
        } catch (Exception ex) {
            log.warn("国内 MCP 同步失败：{}", ex.getMessage(), ex);
        } finally {
            running.set(false);
        }
    }

    public int crawl() throws Exception {
        Map<String, Skill> bySkillKey = new LinkedHashMap<>();
        for (String query : queries.split(",")) {
            String term = query.trim();
            if (term.isEmpty() || bySkillKey.size() >= maxServers) {
                continue;
            }
            try {
                JsonNode servers = fetch(term).path("servers");
                if (!servers.isArray()) {
                    continue;
                }
                for (JsonNode node : servers) {
                    if (bySkillKey.size() >= maxServers) {
                        break;
                    }
                    Skill mapped = toSkill(node);
                    if (mapped != null && looksChinese(node, term)) {
                        bySkillKey.put(mapped.getSkillKey(), mapped);
                    }
                }
            } catch (Exception ex) {
                log.debug("国内 MCP 关键词 [{}] 拉取失败：{}", term, ex.getMessage());
            }
        }
        int saved = 0;
        for (Skill candidate : bySkillKey.values()) {
            if (upsert(candidate)) {
                saved++;
            }
        }
        return saved;
    }

    private JsonNode fetch(String query) throws Exception {
        HttpUrl url = HttpUrl.parse(baseUrl).newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("count_per_page", String.valueOf(perQuery))
                .build();
        Request request = new Request.Builder().url(url).header("Accept", "application/json").get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("PulseMCP returned " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("PulseMCP empty body");
            }
            return objectMapper.readTree(body.string());
        }
    }

    /** 含中文字符，或命中明确的国内服务关键词，才认为是国内 MCP，过滤泛词搜索噪声。 */
    private boolean looksChinese(JsonNode node, String term) {
        String text = textOrEmpty(node, "name") + " " + textOrEmpty(node, "short_description");
        if (CJK.matcher(text).find()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("china") || lower.contains("chinese") || lower.contains("amap") || lower.contains("gaode")
                || lower.contains("baidu") || lower.contains("bilibili") || lower.contains("weibo") || lower.contains("12306")
                || lower.contains("alipay") || lower.contains("wechat") || lower.contains("weixin") || lower.contains("douyin")
                || lower.contains("xiaohongshu") || lower.contains("tencent") || lower.contains("alibaba") || lower.contains("aliyun")
                || lower.contains("feishu") || lower.contains("lark") || lower.contains("dingtalk") || lower.contains("meituan")
                || lower.contains("taobao") || lower.contains("zhihu") || lower.contains("juejin")
                // 明确的国内服务关键词作为兜底，避免漏掉描述里没写 china 的条目。
                || isChineseServiceTerm(term);
    }

    private boolean isChineseServiceTerm(String term) {
        String t = term.toLowerCase(Locale.ROOT);
        return t.equals("amap") || t.equals("gaode") || t.equals("baidu") || t.equals("12306") || t.equals("bilibili")
                || t.equals("weibo") || t.equals("douyin") || t.equals("xiaohongshu") || t.equals("rednote")
                || t.equals("dianping") || t.equals("meituan") || t.equals("taobao") || t.equals("zhihu")
                || t.equals("juejin") || t.equals("feishu") || t.equals("dingtalk") || t.equals("alipay") || t.equals("wechat");
    }

    private Skill toSkill(JsonNode node) {
        String name = textOrNull(node, "name");
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String slug = slugFromUrl(textOrNull(node, "url"));
        String key = truncate("mcp:cn:" + (slug != null ? slug : sanitize(name)), 100);
        String description = textOrNull(node, "short_description");
        if (!StringUtils.hasText(description)) {
            description = textOrNull(node, "EXPERIMENTAL_ai_generated_description");
        }
        RemoteEndpoint remote = pickRemote(node.path("remotes"));

        Skill skill = new Skill();
        skill.setSkillKey(key);
        skill.setSource(SkillSource.MCP);
        skill.setDisplayName(truncate(name, 200));
        skill.setDescription(description);
        skill.setCategory("国内");
        skill.setAuthor(truncate(authorOf(slug, name), 100));
        skill.setRepoUrl(truncate(textOrNull(node, "source_code_url"), 500));
        skill.setMcpTransport(remote != null ? truncate(remote.type(), 20) : "stdio");
        skill.setMcpEndpoint(remote != null ? truncate(remote.url(), 500) : null);
        skill.setDownloads(intValue(node, "package_download_count", intValue(node, "github_stars", 0)));
        skill.setRequiredRole(User.UserRole.USER);
        return skill;
    }

    private boolean upsert(Skill candidate) {
        try {
            Skill row = skillRepository.findBySkillKey(candidate.getSkillKey()).orElse(null);
            if (row == null) {
                row = candidate;
                row.setEnabled(true);
                row.setDefaultEnabled(false);
                row.setMandatory(false);
            } else {
                if (!java.util.Objects.equals(row.getDescription(), candidate.getDescription())) {
                    row.setDescriptionZh(null);
                }
                row.setDisplayName(candidate.getDisplayName());
                row.setDescription(candidate.getDescription());
                row.setCategory(candidate.getCategory());
                row.setAuthor(candidate.getAuthor());
                row.setRepoUrl(candidate.getRepoUrl());
                row.setMcpTransport(candidate.getMcpTransport());
                row.setMcpEndpoint(candidate.getMcpEndpoint());
                row.setDownloads(candidate.getDownloads());
                if (row.getRequiredRole() == null) {
                    row.setRequiredRole(User.UserRole.USER);
                }
            }
            skillRepository.save(row);
            return true;
        } catch (Exception ex) {
            log.debug("跳过一个国内 MCP upsert：key={}, error={}", candidate.getSkillKey(), ex.getMessage());
            return false;
        }
    }

    private RemoteEndpoint pickRemote(JsonNode remotes) {
        if (!remotes.isArray()) {
            return null;
        }
        for (JsonNode remote : remotes) {
            String url = remote.path("url").asText("");
            String type = remote.path("type").asText("");
            if (url.regionMatches(true, 0, "http", 0, 4)) {
                return new RemoteEndpoint(StringUtils.hasText(type) ? type : "streamable-http", url);
            }
        }
        return null;
    }

    private String slugFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        int idx = url.indexOf("/servers/");
        return idx >= 0 ? sanitize(url.substring(idx + "/servers/".length())) : null;
    }

    private String authorOf(String slug, String name) {
        if (slug != null && slug.contains("-")) {
            return slug.substring(0, slug.indexOf('-'));
        }
        return name;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && StringUtils.hasText(v.asText()) ? v.asText() : null;
    }

    private String textOrEmpty(JsonNode node, String field) {
        String v = textOrNull(node, field);
        return v == null ? "" : v;
    }

    private int intValue(JsonNode node, String field, int fallback) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asInt() : fallback;
    }

    private String sanitize(String value) {
        return value == null ? "x" : value.replaceAll("[^A-Za-z0-9_.\\-]+", "-");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private record RemoteEndpoint(String type, String url) {
    }
}
