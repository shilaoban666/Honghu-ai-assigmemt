package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.SkillSource;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 中文技能（提示词）爬虫。
 *
 * <p>「Skills」本质是提示词运行时能力。中文世界没有 Claude Skills 那种 SKILL.md registry，但有成熟的
 * <b>中文提示词聚合库</b>。这里采用社区广泛使用的
 * <a href="https://github.com/PlexPt/awesome-chatgpt-prompts-zh">awesome-chatgpt-prompts-zh</a>
 * （一个 {@code [{act, prompt}]} 的 JSON 数组），把每条中文提示词归一化成一个 CLAUDE_SKILL：
 * {@code act} 作为技能名，{@code prompt} 作为技能内容/描述（启用后会注入系统提示）。</p>
 *
 * <p>描述本身就是中文，因此不需要翻译；展示层直接使用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChinesePromptSkillCrawler {

    private final ObjectMapper objectMapper;
    private final SkillRepository skillRepository;

    @Qualifier("aiGatewayOkHttpClient")
    private final OkHttpClient httpClient;

    /** 统一爬虫线程池；字段名与 Bean 名一致，按注入点名称匹配规避多 Executor 歧义。 */
    private final java.util.concurrent.Executor skillCrawlerExecutor;

    @Value("${app.skill.crawler.cn-skill.enabled:true}")
    private boolean enabled;

    /** 中文提示词聚合库的 raw JSON 地址（{@code [{act, prompt}]}）。 */
    @Value("${app.skill.crawler.cn-skill.url:https://raw.githubusercontent.com/PlexPt/awesome-chatgpt-prompts-zh/main/prompts-zh.json}")
    private String url;

    /** 来源标注，写入 author / repo。 */
    @Value("${app.skill.crawler.cn-skill.repo:https://github.com/PlexPt/awesome-chatgpt-prompts-zh}")
    private String repo;

    @Value("${app.skill.crawler.cn-skill.max:300}")
    private int max;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("中文技能爬虫已禁用（app.skill.crawler.cn-skill.enabled=false），跳过启动同步");
            return;
        }
        // 启动同步提交到统一的有界爬虫线程池（单线程串行），避免启动并发抢占连接池/CPU。
        skillCrawlerExecutor.execute(this::crawlSafely);
    }

    /** 默认每周一凌晨 4:10 同步一次。 */
    @Scheduled(cron = "${app.skill.crawler.cn-skill.cron:0 10 4 * * MON}")
    public void scheduledCrawl() {
        if (enabled) {
            crawlSafely();
        }
    }

    public void crawlSafely() {
        if (!running.compareAndSet(false, true)) {
            log.info("中文技能同步已在进行中，本次触发跳过");
            return;
        }
        try {
            int saved = crawl();
            log.info("中文技能（提示词）同步完成，本次落库/更新 {} 个", saved);
        } catch (Exception ex) {
            log.warn("中文技能同步失败：{}", ex.getMessage(), ex);
        } finally {
            running.set(false);
        }
    }

    public int crawl() throws Exception {
        JsonNode array = fetch();
        if (!array.isArray()) {
            return 0;
        }
        int saved = 0;
        int count = 0;
        for (JsonNode node : array) {
            if (count >= max) {
                break;
            }
            String act = text(node, "act");
            String prompt = text(node, "prompt");
            if (!StringUtils.hasText(act) || !StringUtils.hasText(prompt)) {
                continue;
            }
            count++;
            try {
                if (upsert(act, prompt)) {
                    saved++;
                }
            } catch (Exception ex) {
                log.debug("跳过一个中文技能：act={}, error={}", act, ex.getMessage());
            }
        }
        return saved;
    }

    private JsonNode fetch() throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("中文提示词源返回 " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("中文提示词源返回空 body");
            }
            return objectMapper.readTree(body.string());
        }
    }

    private boolean upsert(String act, String prompt) {
        // 用 act 内容的哈希作为稳定 ASCII key，避免中文 key 在前端/选择器里出问题。
        String key = "skill:cn-" + Integer.toHexString(act.hashCode() & 0x7fffffff);
        Skill row = skillRepository.findBySkillKey(key).orElse(null);
        boolean isNew = row == null;
        if (isNew) {
            row = new Skill();
            row.setSkillKey(key);
            row.setEnabled(true);
            row.setDefaultEnabled(false);
            row.setMandatory(false);
            row.setRequiredRole(User.UserRole.USER);
        }
        row.setSource(SkillSource.CLAUDE_SKILL);
        row.setDisplayName(truncate(cleanName(act), 200));
        // prompt 既是展示描述，也是启用后注入模型的技能内容；过长截断避免卡片/上下文膨胀。
        row.setDescription(truncate(prompt, 600));
        row.setCategory("中文技能");
        row.setVersion("v1");
        row.setAuthor("awesome-chatgpt-prompts-zh");
        row.setRepoUrl(truncate(repo, 500));
        row.setLicense("CC0");
        if (row.getRequiredRole() == null) {
            row.setRequiredRole(User.UserRole.USER);
        }
        skillRepository.save(row);
        return true;
    }

    /** 去掉「担任/充当/扮演/作为/做」等开头动词，让技能名更干净，例如「担任雅思写作考官」→「雅思写作考官」。 */
    private String cleanName(String act) {
        String name = act.trim();
        for (String prefix : new String[]{"担任", "充当", "扮演", "作为", "作為", "做"}) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                name = name.substring(prefix.length());
                break;
            }
        }
        return name;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
