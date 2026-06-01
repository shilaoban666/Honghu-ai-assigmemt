package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.properties.AiProviderProperties;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiModelDefinition;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.AiModelAccessService;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.service.OpenAiCompatibleChatClient;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.SkillSource;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 技能描述本地化服务。
 *
 * <p>全网爬取的 MCP / Claude Skill 描述是英文原文。这个服务在爬取后把缺少中文翻译的描述批量交给
 * LLM 翻译成简体中文，写入 {@code skill.description_zh}。展示层优先使用中文翻译，没有时回退英文原文。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *     <li><b>增量</b>：只翻译 {@code description_zh} 为空的条目；已翻译的不再重复，控制成本。</li>
 *     <li><b>跳过中文</b>：内置/已是中文的描述不翻译，靠展示回退即可。</li>
 *     <li><b>尽力而为</b>：翻译失败、模型不可用、Key 未配置时静默回退英文原文，绝不影响爬取主流程。</li>
 *     <li><b>低成本</b>：默认走 deepseek-chat 这类便宜模型，并按批量减少调用次数。直接调用
 *         {@link OpenAiCompatibleChatClient}，绕过聊天网关的配额/计费逻辑（这是系统维护任务）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillTranslationService {

    /** 粗略识别中文字符；命中时认为描述已经是中文，不再走翻译。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 解析模型返回 JSON 字符串数组时使用的 Jackson 类型引用。 */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /** 校验翻译模型是否存在且启用。 */
    private final AiModelAccessService aiModelAccessService;

    /** 读取模型所属 provider 的 baseUrl、key、enabled 等配置。 */
    private final AiProviderProperties aiProviderProperties;

    /** 直接调用 OpenAI-compatible 客户端执行系统维护翻译任务。 */
    private final OpenAiCompatibleChatClient openAiCompatibleChatClient;

    /** 序列化输入数组、解析输出数组。 */
    private final ObjectMapper objectMapper;

    /** 读取和更新 skill.description_zh。 */
    private final SkillRepository skillRepository;

    @Value("${app.skill.crawler.translate.enabled:true}")
    private boolean enabled;

    /** 翻译使用的模型编码，默认走便宜的云端基础模型。 */
    @Value("${app.skill.crawler.translate.model:deepseek-chat}")
    private String modelCode;

    /** 每次 LLM 调用翻译多少条描述。 */
    @Value("${app.skill.crawler.translate.batch-size:20}")
    private int batchSize;

    /**
     * 返回当前技能描述翻译功能是否开启。
     *
     * <p>爬虫会在同步 MCP / Claude Skill 后调用翻译服务；这个 getter 让调用方可以先判断配置开关，
     * 也方便测试或管理接口展示当前状态。它只读取配置值，不触发模型调用，也不会修改数据库。</p>
     *
     * @return true 表示允许执行批量翻译，false 表示所有翻译任务都会被跳过
     */
    public boolean isEnabled() {
        // 给测试或管理接口查看翻译开关状态。
        return enabled;
    }

    /**
     * 翻译某个来源下所有「有英文描述但还没有中文翻译」的技能。
     *
     * @param source 技能来源（MCP / CLAUDE_SKILL）
     * @return 实际成功翻译并入库的条目数
     */
    public int translatePending(SkillSource source) {
        if (!enabled) {
            // 翻译总开关关闭时直接跳过。
            return 0;
        }
        // 只筛选“启用中、英文描述非空、中文翻译为空”的技能。
        List<Skill> pending = skillRepository.findBySourceAndEnabledTrueOrderByDisplayNameAsc(source).stream()
                .filter(this::needsTranslation)
                .toList();
        if (pending.isEmpty()) {
            // 没有待翻译条目时无需调用模型。
            return 0;
        }
        int translated = 0;
        // batchSize 至少为 1，避免配置 0 导致死循环。
        int size = Math.max(1, batchSize);
        for (int from = 0; from < pending.size(); from += size) {
            // 按批次截取待翻译技能，降低单次 prompt 长度和模型调用次数。
            List<Skill> batch = pending.subList(from, Math.min(from + size, pending.size()));
            // 按 skill 顺序提取英文描述，后续依赖“输出数组同顺序同长度”写回。
            List<String> english = batch.stream().map(Skill::getDescription).toList();
            // 调用 LLM 翻译；失败时会返回同长度 null 列表。
            List<String> chinese = translateToChinese(english);
            List<Skill> toSave = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                // 防御模型返回长度不足的情况。
                String zh = i < chinese.size() ? chinese.get(i) : null;
                if (StringUtils.hasText(zh)) {
                    // 只写入非空翻译，空翻译保留待下次重试。
                    batch.get(i).setDescriptionZh(zh.trim());
                    toSave.add(batch.get(i));
                    translated++;
                }
            }
            if (!toSave.isEmpty()) {
                // 每批只保存真正有翻译结果的行，减少数据库写入。
                skillRepository.saveAll(toSave);
            }
        }
        log.info("技能描述翻译完成：source={}, 待翻译={}, 成功={}", source, pending.size(), translated);
        return translated;
    }

    /**
     * 把一批英文文本翻译成简体中文。
     *
     * @param texts 英文原文列表
     * @return 与输入等长的中文列表；无法翻译的位置为 null，调用方据此回退英文
     */
    public List<String> translateToChinese(List<String> texts) {
        if (!enabled || texts == null || texts.isEmpty()) {
            // 关闭、null 或空输入时返回同形状的 null 结果，让调用方统一回退英文。
            return nulls(texts);
        }
        try {
            // 从模型配置表中读取并校验翻译模型。
            AiModelDefinition model = aiModelAccessService.requireEnabledModel(modelCode);
            // 根据模型的 providerCode 读取 provider 连接配置。
            AiProviderProperties.Provider provider = aiProviderProperties.getProviders().get(model.getProviderCode());
            if (provider == null || !provider.isEnabled()) {
                log.warn("翻译模型 provider 不可用，跳过翻译：model={}", modelCode);
                return nulls(texts);
            }
            // 输入直接是 JSON 数组，能最大化保证模型按顺序逐条翻译。
            String inputJson = objectMapper.writeValueAsString(texts);
            // 系统提示强约束输出：只返回同长度 JSON 字符串数组，不要 markdown 或解释。
            String system = "You are a professional localization translator. Translate each English string in the input JSON array into natural, concise Simplified Chinese. "
                    + "Keep product names, brand names and code identifiers as-is. Return ONLY a JSON array of strings with the EXACT same length and order as the input. No markdown, no explanation.";
            // 一条系统消息放规则，一条用户消息放 JSON 数组。
            List<Message> messages = List.of(new SystemMessage(system), new UserMessage(inputJson));
            // 低温度减少自由发挥；4096 token 对短描述批量翻译足够。
            ChatResponse response = openAiCompatibleChatClient.chat(model, provider, messages, 0.2, 4096);
            // 解析模型输出，只有长度正确才采用。
            List<String> parsed = parseArray(response == null ? null : response.getContent(), texts.size());
            return parsed != null ? parsed : nulls(texts);
        } catch (Exception ex) {
            // 翻译是维护任务，失败只回退英文，不影响爬虫主流程。
            log.warn("技能描述批量翻译失败，回退英文原文：{}", ex.getMessage());
            return nulls(texts);
        }
    }

    /**
     * 从模型回复中解析 JSON 字符串数组。
     *
     * <p>模型偶尔会用 ```json 包裹或加少量解释，这里截取第一个 '[' 到最后一个 ']' 再解析；
     * 只有长度与输入一致时才采用，否则放弃（返回 null）以保证按位置对应。</p>
     */
    private List<String> parseArray(String content, int expectedSize) {
        if (!StringUtils.hasText(content)) {
            // 空回复无法解析。
            return null;
        }
        // 有些模型会包 markdown 或解释，这里尽量截取第一个 JSON 数组片段。
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            // 找不到数组边界时放弃。
            return null;
        }
        try {
            // 只解析数组片段，不解析数组外的文本。
            List<String> list = objectMapper.readValue(content.substring(start, end + 1), STRING_LIST);
            // 长度必须和输入一致，否则无法按位置写回对应 skill。
            return list != null && list.size() == expectedSize ? list : null;
        } catch (Exception ex) {
            // JSON 格式不合法时放弃本批翻译。
            return null;
        }
    }

    /**
     * 判断一个技能描述是否需要翻译。
     */
    private boolean needsTranslation(Skill skill) {
        // 条件：英文原描述有内容、中文翻译为空、原描述里没有中文字符。
        return StringUtils.hasText(skill.getDescription())
                && !StringUtils.hasText(skill.getDescriptionZh())
                && !CJK.matcher(skill.getDescription()).find();
    }

    /**
     * 返回与输入长度一致、元素全为 null 的列表。
     */
    private List<String> nulls(List<String> texts) {
        List<String> result = new ArrayList<>();
        if (texts != null) {
            for (int i = 0; i < texts.size(); i++) {
                // null 表示该位置没有可用翻译，调用方会回退英文。
                result.add(null);
            }
        }
        return result;
    }
}
