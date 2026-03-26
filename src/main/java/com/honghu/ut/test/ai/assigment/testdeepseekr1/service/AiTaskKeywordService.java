package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiTaskKeyword;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.AiTaskKeywordRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 任务关键词加载与匹配服务。
 * <p>
 * 该服务负责管理和提供用于 AI 模型路由的关键词库，支持基于关键词的任务分类识别。
 * 主要功能包括：
 * </p>
 * <ul>
 *     <li><strong>数据库优先策略</strong>：优先使用数据库中配置的启用关键词</li>
 *     <li><strong>降级保护</strong>：如果数据库为空或加载失败，自动回退到内置默认关键词</li>
 *     <li><strong>缓存机制</strong>：启动时预加载关键词到内存缓存，避免频繁查询数据库</li>
 *     <li><strong>智能匹配</strong>：支持对用户输入进行关键词匹配，识别任务类型</li>
 *     <li><strong>任务分类</strong>：将任务分为简单类和复杂类，用于模型路由决策</li>
 * </ul>
 * <p>
 * <strong>支持的 5 种任务类型：</strong>
 * </p>
 * <ul>
 *     <li>{@code CODE} - 编程相关（代码生成、调试、优化等）</li>
 *     <li>{@code DESIGN} - 设计相关（架构设计、方案设计等）</li>
 *     <li>{@code TEXT} - 文本处理（翻译、润色、改写等）</li>
 *     <li>{@code DATA_PROCESSING} - 数据处理（ETL、清洗、转换等）</li>
 *     <li>{@code ANALYSIS} - 分析推理（原因分析、原理解释等）</li>
 * </ul>
 * <p>
 * <strong>模型路由规则：</strong>
 * </p>
 * <ul>
 *     <li>命中 {@link #COMPLEX_TASK_TYPES} 中的任务类型 → 路由到 32B 大模型（GPU）</li>
 *     <li>未命中关键词或问题长度 &lt; 20 字 → 路由到 8B 小模型（CPU）</li>
 * </ul>
 *
 * @author shilaoban
 * @since 2026-03-11
 * @see AiTaskKeyword
 * @see AiTaskKeywordRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskKeywordService {

    // ==================== 任务类型常量定义 ====================
    
    /**
     * 编程开发类任务类型标识
     * <p>涵盖：代码生成、调试、重构、优化、SQL 编写等</p>
     */
    public static final String TASK_TYPE_CODE = "CODE";
    /**
     * 设计规划类任务类型标识
     * <p>涵盖：架构设计、方案设计、流程图绘制等</p>
     */
    public static final String TASK_TYPE_DESIGN = "DESIGN";
    /**
     * 文本处理类任务类型标识
     * <p>涵盖：翻译、润色、改写、摘要、总结等</p>
     */
    public static final String TASK_TYPE_TEXT = "TEXT";
    /**
     * 数据处理类任务类型标识
     * <p>涵盖：数据清洗、去重、格式转换、ETL 等</p>
     */
    public static final String TASK_TYPE_DATA_PROCESSING = "DATA_PROCESSING";
    /**
     * 分析推理类任务类型标识
     * <p>涵盖：原因分析、原理解释、对比分析等深度思考任务</p>
     */
    public static final String TASK_TYPE_ANALYSIS = "ANALYSIS";

    // ==================== 复杂任务类型集合 ====================
    
    /**
     * 被认定为复杂任务的类型集合。
     * <p>
     * 当用户输入命中这些类型的关键词时，将路由到 32B 大模型（GPU）进行处理。
     * 注意：{@link #TASK_TYPE_TEXT} 被认为是简单任务，不在此集合中。
     * </p>
     */
    private static final Set<String> COMPLEX_TASK_TYPES = Set.of(
            TASK_TYPE_CODE,
            TASK_TYPE_DESIGN,
            TASK_TYPE_DATA_PROCESSING,
            TASK_TYPE_ANALYSIS
    );

    // ==================== 默认关键词配置 ====================
    
    /**
     * 内置默认关键词映射表（按任务类型分组）。
     * <p>
     * 当数据库中没有配置关键词时，将使用此默认配置作为降级方案。
     * 使用 LinkedHashMap 保证插入顺序，确保优先级稳定。
     * </p>
     */
    private static final Map<String, List<String>> DEFAULT_KEYWORDS_BY_TYPE = new LinkedHashMap<>();

    static {
        // 初始化各类别的默认关键词
        DEFAULT_KEYWORDS_BY_TYPE.put(TASK_TYPE_CODE, List.of(
                "代码", "程序", "java", "python", "javascript", "sql", "bug", "异常", "重构", "优化"
        ));
        DEFAULT_KEYWORDS_BY_TYPE.put(TASK_TYPE_DESIGN, List.of(
                "设计", "架构", "方案", "流程图", "时序图", "模块划分"
        ));
        DEFAULT_KEYWORDS_BY_TYPE.put(TASK_TYPE_TEXT, List.of(
                "润色", "改写", "翻译", "摘要", "总结", "文案"
        ));
        DEFAULT_KEYWORDS_BY_TYPE.put(TASK_TYPE_DATA_PROCESSING, List.of(
                "数据处理", "清洗", "去重", "转换", "etl", "csv"
        ));
        DEFAULT_KEYWORDS_BY_TYPE.put(TASK_TYPE_ANALYSIS, List.of(
                "分析", "原因", "区别", "为什么", "原理", "how", "why"
        ));
    }

    /**
     * AI 任务关键词数据访问层
     * <p>用于从数据库读取启用的关键词配置</p>
     */
    private final AiTaskKeywordRepository aiTaskKeywordRepository;

    /**
     * 关键词缓存（线程安全）。
     * <p>
     * 存储已加载的关键词列表，避免每次请求都查询数据库。
     * 使用 volatile 保证多线程环境下的可见性。
     * </p>
     */
    private volatile List<AiTaskKeyword> cachedKeywords = List.of();

    /**
     * 初始化方法。
     * <p>
     * 在 Spring Bean 创建完成后自动调用，预加载关键词到缓存中。
     * 确保服务启动后即可用，无需等待首次请求。
     * </p>
     */
    @PostConstruct
    public void init() {
        refreshCache();
    }

    /**
     * 刷新关键词缓存。
     * <p>
     * 从数据库重新加载关键词配置，如果加载失败则使用内置默认值。
     * 此方法是同步的，确保缓存更新的原子性。
     * </p>
     * <p>
     * <strong>调用场景：</strong>
     * </p>
     * <ul>
     *     <li>应用启动时自动调用</li>
     *     <li>管理员修改了数据库中的关键词配置后手动调用</li>
     *     <li>缓存为空时自动触发</li>
     * </ul>
     */
    public synchronized void refreshCache() {
        cachedKeywords = loadKeywordsFromDatabaseOrFallback();
        log.info("AI 任务关键词缓存已刷新，当前有效关键词数量={}", cachedKeywords.size());
    }

    /**
     * 获取所有启用的关键词列表。
     * <p>
     * 如果缓存为空，会自动触发一次缓存刷新操作。
     * </p>
     *
     * @return 启用的关键词列表，按优先级排序
     */
    public List<AiTaskKeyword> getEnabledKeywords() {
        if (cachedKeywords.isEmpty()) {
            refreshCache();
        }
        return cachedKeywords;
    }

    /**
     * 获取按任务类型分组的关键词映射表。
     * <p>
     * 返回的 Map 结构为：{@code {任务类型 -> [关键词列表]}}
     * </p>
     *
     * @return 按任务类型分组的关键词映射表（LinkedHashMap，保持插入顺序）
     */
    public Map<String, List<String>> getKeywordsGroupedByTaskType() {
        return getEnabledKeywords().stream()
                .collect(Collectors.groupingBy(
                        AiTaskKeyword::getTaskType,
                        LinkedHashMap::new,
                        Collectors.mapping(AiTaskKeyword::getKeyword, Collectors.toList())
                ));
    }

    /**
     * 查找第一个匹配的关键词。
     * <p>
     * 遍历所有启用的关键词，返回第一个在问题文本中出现的关键词及其对应的任务类型。
     * 匹配时会忽略大小写和首尾空白字符。
     * </p>
     *
     * @param question 用户输入的问题文本
     * @return 如果找到匹配的关键词，返回包含任务类型和关键词的 Optional；否则返回空 Optional
     */
    public Optional<TaskKeywordMatch> findFirstMatch(String question) {
        if (question == null || question.trim().isEmpty()) {
            return Optional.empty();
        }

        String normalizedQuestion = normalize(question);
        for (AiTaskKeyword keyword : getEnabledKeywords()) {
            String normalizedKeyword = normalize(keyword.getKeyword());
            if (!normalizedKeyword.isEmpty() && normalizedQuestion.contains(normalizedKeyword)) {
                return Optional.of(new TaskKeywordMatch(keyword.getTaskType(), keyword.getKeyword()));
            }
        }
        return Optional.empty();
    }

    /**
     * 判断给定的任务类型是否属于复杂任务。
     * <p>
     * 复杂任务将被路由到 32B 大模型（GPU）处理，简单任务则使用 8B 小模型（CPU）。
     * </p>
     *
     * @param taskType 任务类型标识
     * @return 如果是复杂任务类型返回 true，否则返回 false
     * @see #COMPLEX_TASK_TYPES
     */
    public boolean isComplexTaskType(String taskType) {
        return taskType != null && COMPLEX_TASK_TYPES.contains(taskType.trim().toUpperCase());
    }

    /**
     * 从数据库加载关键词或回退到默认配置。
     * <p>
     * 加载策略：
     * </p>
     * <ol>
     *     <li>尝试从数据库查询所有启用的关键词（按优先级和 ID 排序）</li>
     *     <li>如果数据库中有数据，记录日志并返回</li>
     *     <li>如果数据库为空，记录警告并使用内置默认关键词</li>
     *     <li>如果发生异常，记录警告并使用内置默认关键词</li>
     * </ol>
     *
     * @return 关键词列表（来自数据库或内置默认值）
     */
    private List<AiTaskKeyword> loadKeywordsFromDatabaseOrFallback() {
        try {
            List<AiTaskKeyword> dbKeywords = aiTaskKeywordRepository.findByEnabledTrueOrderByPriorityAscIdAsc();
            if (!dbKeywords.isEmpty()) {
                log.info("从数据库加载 AI 路由关键词成功，分类数={}，关键词数={}",
                        dbKeywords.stream().map(AiTaskKeyword::getTaskType).distinct().count(),
                        dbKeywords.size());
                return dbKeywords;
            }
            log.warn("数据库中的 AI 路由关键词为空，将使用内置默认关键词");
        } catch (Exception e) {
            log.warn("加载数据库 AI 路由关键词失败，将回退到内置默认关键词：{}", e.getMessage());
        }
        return buildFallbackKeywords();
    }

    /**
     * 构建降级关键词列表。
     * <p>
     * 当数据库不可用时，使用内置的默认关键词配置构建临时的关键词列表。
     * 优先级从 1 开始递增，确保每个关键词都有唯一的优先级。
     * </p>
     *
     * @return 降级的关键词列表
     */
    private List<AiTaskKeyword> buildFallbackKeywords() {
        List<AiTaskKeyword> fallbackKeywords = new ArrayList<>();
        int priority = 1;

        // 按照默认配置构建关键词列表
        for (Map.Entry<String, List<String>> entry : DEFAULT_KEYWORDS_BY_TYPE.entrySet()) {
            for (String keyword : entry.getValue()) {
                fallbackKeywords.add(AiTaskKeyword.builder()
                        .taskType(entry.getKey())
                        .keyword(keyword)
                        .enabled(true)
                        .priority(priority++)
                        .build());
            }
        }

        return fallbackKeywords;
    }

    /**
     * 标准化文本（忽略大小写和首尾空白）。
     * <p>
     * 用于关键词匹配前的预处理，确保匹配的准确性。
     * </p>
     *
     * @param value 待标准化的文本
     * @return 标准化后的文本（小写、去除首尾空格）
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /**
     * 任务关键词匹配结果记录。
     * <p>
     * 封装了匹配到的任务类型和关键词信息。
     * </p>
     *
     * @param taskType 匹配到的任务类型（如：CODE, DESIGN, TEXT 等）
     * @param keyword  匹配到的关键词
     */
    public record TaskKeywordMatch(String taskType, String keyword) {
    }
}


