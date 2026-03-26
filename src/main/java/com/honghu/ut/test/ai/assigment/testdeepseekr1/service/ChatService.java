package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatRequest;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.ChatResponse;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatSession;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatSessionRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;
// import org.springframework.transaction.annotation.Transactional;  // 暂时注释，解决编译问题
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI聊天服务实现
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final OllamaChatModel ollamaChatModel;
    private final ChatClient.Builder chatClientBuilder;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final AiTaskKeywordService aiTaskKeywordService;

    // 流式响应日志配置（可通过 YAML 控制）
    // 在 application.yml 中设置：chat.streaming.log.enabled=true 开启详细日志
    // @Value("${chat.streaming.log.enabled:false}")
    private boolean streamingLogEnabled = true;  // 临时设置为 true，后期可通过 YAML 配置
    // 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    
    // JTokkit Token 计算器
    // 注意：CL100K_BASE 是 OpenAI GPT-4 的编码器，与 DeepSeek 的实际 tokenizer 可能存在差异
    // 仅用于历史消息 token 数的估算，不应用于精确计费或硬截断
    // 如需 DeepSeek 精确统计，应以 Ollama 返回的 tokenUsage 为准
    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    
    // 默认最大上下文 token 数（可根据模型调整）
    // 注意：这是一个软性限制，用于避免上下文过长，实际 token 数以模型返回为准
    private static final int MAX_CONTEXT_TOKENS = 4096;
    
    // 模型路由配置
    // 简单指令/闲聊阈值：问题长度小于此值使用 8B 模型（常驻 CPU）
    private static final int SIMPLE_QUERY_LENGTH_THRESHOLD = 20;
    // 简单模型：8B 模型，常驻 CPU，用于简单指令和闲聊
    private static final String SIMPLE_MODEL = "deepseek-r1:8b";
    // 复杂模型：32B 模型，GPU 按需加载，用于复杂任务和代码生成
    private static final String COMPLEX_MODEL = "deepseek-r1:32b";

    /**
     * 简单聊天实现（带重试机制）
     *
     * @param message 用户消息
     * @return 聊天响应
     */
    public ChatResponse simpleChat(String message) {
        return simpleChatWithRetry(message, MAX_RETRIES);
    }
    
    /**
     * 带重试机制的简单聊天实现
     */
    private ChatResponse simpleChatWithRetry(String message, int remainingRetries) {
        try {
            log.info("开始处理简单聊天请求: {} (剩余重试次数: {})", message, remainingRetries);
            
            var response = chatClientBuilder.build()
                    .prompt()
                    .user(message)
                    .call()
                    .chatResponse();

            return ChatResponse.success(
                    response.getResult().getOutput().getContent(),
                    response.getMetadata().getModel()
            );
        } catch (Exception e) {
            log.warn("简单聊天处理失败 (剩余重试次数: {}): {}", remainingRetries, e.getMessage());
            
            // 检查是否是连接相关异常
            if (isConnectionException(e) && remainingRetries > 0) {
                log.info("检测到连接异常，{}毫秒后进行第{}次重试", RETRY_DELAY_MS, MAX_RETRIES - remainingRetries + 1);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ChatResponse.error("重试被中断: " + ie.getMessage());
                }
                return simpleChatWithRetry(message, remainingRetries - 1);
            }
            
            log.error("简单聊天处理最终失败", e);
            return ChatResponse.error("聊天服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 结构化聊天实现 - 支持自定义系统提示词和模型参数（带重试机制）
     * 
     * 该方法提供了一个更灵活的聊天接口，允许客户端指定系统角色、温度参数和最大输出长度。
     * 适用于需要精确控制AI行为和输出质量的场景。
     * 1、构建系统提示词
     * 2、模型选配置
     * 3、用户输入消息
     * 4、构建完整对话
     * 5、调用模型进行推理
     *
     * @param request 包含聊天配置的请求对象，包括：
     *                - message: 用户输入的消息内容
     *                - systemMessage: 可选的系统提示词，用于定义AI助手的角色和行为
     *                - temperature: 控制输出随机性的参数，范围0.0-1.0，值越高越随机
     *                - maxTokens: 限制AI回复的最大token数量
     * @return ChatResponse 聊天响应对象，包含：
     *         - content: AI生成的回复内容
     *         - model: 使用的模型名称
     *         - timestamp: 响应时间戳
     *         - success: 请求是否成功
     *         - tokenUsage: 详细的token使用统计信息
     * @throws RuntimeException 当聊天服务不可用或处理过程中发生错误时抛出
     */
    public ChatResponse structuredChat(ChatRequest request) {
        return structuredChatWithRetry(request, MAX_RETRIES);
    }
    
    /**
     * 带重试机制的结构化聊天实现
     */
    private ChatResponse structuredChatWithRetry(ChatRequest request, int remainingRetries) {
        try {



            String routedModel = routeModelByQuestionLength(request.getMessage(), request.getModel());
            request.setModel(routedModel);
            // 记录请求日志，便于监控和调试
            log.info("开始处理结构化聊天请求: {} (剩余重试次数: {})", request, remainingRetries);
            // 构建系统提示词：如果客户端未提供，则使用默认的AI助手角色定义
            String systemPrompt = request.getSystemMessage() != null ? 
                    request.getSystemMessage() : "你是一个 helpful 的 AI 助手";

            // 构建模型选项配置
            // temperature: 控制生成文本的随机性，0.0表示最确定性，1.0表示最随机
            // numPredict: 限制生成文本的最大token数，防止过长回复
            OllamaOptions options = OllamaOptions.create()
                    .withModel(request.getModel())
                    .withTemperature(request.getTemperature())
                    .withNumPredict(request.getMaxTokens());

            // 构建完整的对话提示
            // SystemPromptTemplate: 将系统提示词包装为系统消息
            // UserMessage: 包装用户输入内容
            SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemPrompt);
            Prompt prompt = new Prompt(
                    List.of(
                        systemPromptTemplate.createMessage(),  // 系统角色定义
                        new UserMessage(request.getMessage())   // 用户实际输入
                    ),
                    options  // 模型参数配置
            );

            // 调用Ollama模型进行推理
            var response = ollamaChatModel.call(prompt);

            // 提取并构建token使用统计信息
            // promptTokens: 输入提示消耗的token数
            // completionTokens: AI生成回复消耗的token数  
            // totalTokens: 总共消耗的token数
            ChatResponse.TokenUsage tokenUsage = ChatResponse.TokenUsage.builder()
                    .promptTokens(response.getMetadata().getUsage().getPromptTokens().intValue())
                    .completionTokens(response.getMetadata().getUsage().getGenerationTokens().intValue())
                    .totalTokens(response.getMetadata().getUsage().getTotalTokens().intValue())
                    .build();

            // 构建成功的响应对象
            return ChatResponse.builder()
                    .content(response.getResult().getOutput().getContent())  // AI回复内容
                    .model(response.getMetadata().getModel())               // 使用的模型
                    .timestamp(System.currentTimeMillis())                  // 响应时间
                    .success(true)                                          // 标记成功
                    .tokenUsage(tokenUsage)                                 // token使用详情
                    .build();

        } catch (Exception e) {
            log.warn("结构化聊天处理失败 (剩余重试次数: {}): {}", remainingRetries, e.getMessage());
            
            // 检查是否是连接相关异常
            if (isConnectionException(e) && remainingRetries > 0) {
                log.info("检测到连接异常，{}毫秒后进行第{}次重试", RETRY_DELAY_MS, MAX_RETRIES - remainingRetries + 1);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ChatResponse.error("重试被中断: " + ie.getMessage());
                }
                return structuredChatWithRetry(request, remainingRetries - 1);
            }
            
            // 记录详细错误信息，便于问题排查
            log.error("结构化聊天处理最终失败", e);
            // 返回标准化错误响应
            return ChatResponse.error("聊天服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 结构化流式聊天实现（带重试机制）
     *
     * @param request 聊天请求配置
     * @return 包含 ChatResponse 的 Flux 流
     */
    public Flux<ChatResponse> structuredStreamChat(@Valid ChatRequest request) {
        return structuredStreamChatWithPersistence(request);
    }

    /**
     * 带重试机制的结构化流式聊天实现（支持持久化消息和上下文）
     * <p>
     * 该方法在结构化流式聊天的基础上，增加了对聊天记录的持久化支持。
     * 适用于需要保存聊天记录以便后续查询或继续对话的场景。
     * </p>
     *
     * @param request 包含聊天配置的请求对象，包括：
     *                - message: 用户输入的消息内容
     *                - systemMessage: 可选的系统提示词，用于定义 AI 助手的角色和行为
     *                - temperature: 控制输出随机性的参数，范围 0.0-1.0，值越高越随机
     *                - maxTokens: 限制 AI 回复的最大 token 数量
     * @return 包含 ChatResponse 的 Flux 流
     */
    @NotNull
    @Transactional  // 事务注解，确保会话创建和消息保存的原子性
    public Flux<ChatResponse> structuredStreamChatWithPersistence(ChatRequest request) {
        // 步骤 0: 智能模型路由策略
        // 如果用户未指定模型，系统将根据问题复杂度自动选择合适的模型
        // - 简单问题/闲聊 -> 8B 小模型 (响应快，成本低)
        // - 复杂任务/代码 -> 32B 大模型 (能力强，更精准)
        String originalModel = request.getModel();
        String messageContent = request.getMessage() != null ? request.getMessage() : "";
        
        String routedModel = routeModelByQuestionLength(messageContent, originalModel);
        request.setModel(routedModel);
        
        // 仅在发生模型切换或自动选择时记录 Info 日志
        if (originalModel == null || originalModel.isEmpty() || !originalModel.equals(routedModel)) {
            log.info("模型自动路由生效：输入长度={}, 目标模型={}, 已加载任务分类={}",
                    messageContent.length(), routedModel, aiTaskKeywordService.getKeywordsGroupedByTaskType().keySet());
        }

        // 步骤 1: 确保会话存在，如不存在则创建新会话
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            // 如果请求中未指定会话 ID，则生成新的 UUID 作为会话 ID
            sessionId = UUID.randomUUID().toString();
            request.setSessionId(sessionId);
        }
        String userId = request.getUserId();
        User chatUser = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        final String finalSessionId = sessionId;  // 用于 lambda 表达式中的 final 变量
            
        // 查询会话是否存在，如果不存在则创建新会话
        ChatSession session = chatSessionRepository.findById(finalSessionId)
                .orElseGet(() -> {
                    // 构建新的会话对象
                    ChatSession newSession = ChatSession.builder()
                            .sessionId(finalSessionId)              // 设置会话 ID
                            .userId(chatUser.getUserId())// 设置用户 ID
                            .userName(chatUser.getUsername())
                            .systemRole(request.getSystemMessage()) // 设置系统角色/提示词
                            .sessionName(request.getMessage().substring(0, Math.min(request.getMessage().length(), 64)))                   // 默认会话名称
                            .sessionStatus("active")                // 会话状态设为活跃
                            .build();
                    // 保存新会话到数据库
                    return chatSessionRepository.save(newSession);
                });

        if ((request.getSystemMessage() == null || request.getSystemMessage().isBlank())
                && session.getSystemRole() != null && !session.getSystemRole().isBlank()) {
            request.setSystemMessage(session.getSystemRole());
        } else if (request.getSystemMessage() != null
                && !request.getSystemMessage().isBlank()
                && !request.getSystemMessage().equals(session.getSystemRole())) {
            session.setSystemRole(request.getSystemMessage());
            chatSessionRepository.save(session);
        }

        // 步骤 2: 保存用户消息到数据库
        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(finalSessionId)      // 关联到当前会话
                .chatRole("user")               // 消息角色：用户
                .contentType("text")            // 内容类型：文本
                .content(request.getMessage())  // 用户输入的消息内容
                .status("active")            // 消息状态：活着的对话
                .build();
        chatMessageRepository.save(userMsg);  // 保存用户消息


        // 步骤 3: 获取当前会话的历史消息记录
        // 按时间顺序获取该会话的所有历史消息，用于构建对话上下文
        List<ChatMessage> history = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(finalSessionId);


        // 步骤 4: 执行流式聊天并保存 AI 回复
        // 使用带历史记录和重试机制的流式聊天方法
        Flux<ChatResponse> responseFlux = structuredStreamChatWithRetryAndHistory(request, history, MAX_RETRIES);
        
        // 异步保存 AI 响应到数据库（不阻塞流式传输）
        StringBuilder fullContent = new StringBuilder();
        
        return responseFlux
                // 收集每个响应块的内容
                .doOnNext(response -> {
                    if (response.getContent() != null) {
                        fullContent.append(response.getContent());
                    }
                })
                // 在响应完成后保存完整消息到数据库
                .doOnComplete(() -> {
                    // 异步保存 AI 助手消息到数据库
                    ChatMessage assistantMsg = ChatMessage.builder()
                            .sessionId(finalSessionId)      // 关联到当前会话
                            .chatRole("assistant")          // 消息角色：AI 助手
                            .contentType("text")            // 内容类型：文本
                            .content(fullContent.toString()) // 完整的 AI 回复内容
                            .status("completed")            // 消息状态：已完成
                            .build();
                    chatMessageRepository.save(assistantMsg);  // 保存 AI 消息
                    log.info("AI 响应已保存到数据库，会话 ID={}, 消息长度={}", 
                            finalSessionId, fullContent.length());
                })
                // 在发生错误时记录日志
                .doOnError(error -> {
                    log.error("流式响应过程中出错，会话 ID={}: {}", finalSessionId, error.getMessage());
                });
    }

    private Flux<ChatResponse> structuredStreamChatWithRetryAndHistory(ChatRequest request, List<ChatMessage> history, int remainingRetries) {
        return Flux.defer(() -> {
            log.info("开始处理带历史记录的结构化流式聊天请求：{} (剩余重试次数：{})", request, remainingRetries);

            String systemPrompt = request.getSystemMessage() != null && !request.getSystemMessage().isBlank()
                    ? request.getSystemMessage()
                    : "你是一个 helpful 的 AI 助手";

            OllamaOptions options = OllamaOptions.create()
                    .withModel(request.getModel())
                    .withTemperature(request.getTemperature())
                    .withNumPredict(request.getMaxTokens());

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));


            Prompt prompt = new Prompt(messages, options);
            log.info("开始处理带历史记录的结构化流式聊天请求：{} (剩余重试次数：{})", request, remainingRetries);

            // 使用流式响应日志记录器（可配置）
            return logStreamingResponse(ollamaChatModel.stream(prompt));
        })
        .onErrorResume(e -> {
            log.warn("结构化流式聊天处理失败 (剩余重试次数：{}): {}", remainingRetries, e.getMessage());
            if (isConnectionException((Exception) e) && remainingRetries > 0) {
                return Mono.delay(Duration.ofMillis(RETRY_DELAY_MS))
                        .thenMany(structuredStreamChatWithRetryAndHistory(request, history, remainingRetries - 1));
            }
            return Flux.error(new RuntimeException("聊天服务暂时不可用：" + e.getMessage(), e));
        });
    }

    /**
     * 流式聊天实现（带重试机制）
     *
     * @param message 用户消息
     * @return 流式响应字符串
     */
    public Flux<String> streamChat(String message) {
        return streamChatWithRetry(message, MAX_RETRIES);
    }

    /**
     * 流式聊天实现（带重试机制）
     *
     * @param message 用户消息
     * @return 流式响应字符串
     */
    private Flux<String> streamChatWithRetry(String message, int remainingRetries) {
        try {
            log.info("开始处理简单聊天请求: {} (剩余重试次数: {})", message, remainingRetries);
            String systemPrompt =  "你是一个 helpful 的 AI 助手";
            //
            Flux<String> content = chatClientBuilder.build()
                    .prompt(systemPrompt)
                    .advisors(new SimpleLoggerAdvisor()).user(message)
                    .stream()
                    .content();

            return content;
        } catch (Exception e) {
            log.warn("简单聊天处理失败 (剩余重试次数: {}): {}", remainingRetries, e.getMessage());
//
//            // 检查是否是连接相关异常
//            if (isConnectionException(e) && remainingRetries > 0) {
//                log.info("检测到连接异常，{}毫秒后进行第{}次重试", RETRY_DELAY_MS, MAX_RETRIES - remainingRetries + 1);
//                try {
//                    Thread.sleep( );
//                } catch (InterruptedException ie) {
//                    Thread.currentThread().interrupt();
//                    return Flux<String>.error("重试被中断: " + ie.getMessage());
//                }
//                return simpleChatWithRetry(message, remainingRetries - 1);
//            }
//
//            log.error("简单聊天处理最终失败", e);
            throw new RuntimeException("聊天服务暂时不可用: " + e.getMessage());
//            return Flux<String>.error("聊天服务暂时不可用: " + e.getMessage());
//            return ChatResponse.error("聊天服务暂时不可用: " + e.getMessage());
        }
    }

    /**
     * 判断异常是否为连接相关异常
     */
    private boolean isConnectionException(Exception e) {
        String message = e.getMessage();
        if (message == null) return false;
        
        // 检查常见的连接异常关键词
        String lowerMsg = message.toLowerCase();
        return lowerMsg.contains("closedchannel") ||
               lowerMsg.contains("connection refused") ||
               lowerMsg.contains("connect timed out") ||
               lowerMsg.contains("connection reset") ||
               lowerMsg.contains("broken pipe") ||
               e.getCause() instanceof java.nio.channels.ClosedChannelException ||
               e.getCause() instanceof java.net.ConnectException;
    }

    /**
     * 根据 token 限制筛选历史消息
     * 从新到旧累加历史消息的 token 数，确保不超过最大上下文限制
     * 
     * 注意：
     * - 使用 CL100K_BASE 编码器估算 token 数，与 DeepSeek 实际 tokenizer 可能存在差异
     * - 这是一个软性限制，主要用于避免上下文过长，不应用于精确控制
     * - 实际 token 消耗以 Ollama 返回的 tokenUsage 为准
     *
     * @param history 历史消息列表（按创建时间升序排列）
     * @param maxTokens 最大 token 数限制（软性限制）
     * @return 筛选后的历史消息列表（仍然保持升序排列）
     */
    private List<ChatMessage> selectHistoryMessagesByTokenLimit(List<ChatMessage> history, int maxTokens) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }

        // 首先将历史消息按时间倒序排列（最新的在前）
        List<ChatMessage> reversedHistory = new ArrayList<>(history);
        reversedHistory.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        List<ChatMessage> selected = new ArrayList<>();
        int totalTokens = 0;
        int skippedCount = 0;

        // 从新到旧遍历历史消息，累加 token 数（使用 CL100K_BASE 估算）
        for (ChatMessage msg : reversedHistory) {
            // 计算当前消息的 token 数（估算值）
            int messageTokens = encoding.countTokens(msg.getContent());
            
            // 如果加上这条消息会超过限制，则跳过
            if (totalTokens + messageTokens > maxTokens) {
                skippedCount++;
                log.debug("跳过历史消息（token 超限）：角色={}, token 数={}, 累计 token={}", 
                         msg.getChatRole(), messageTokens, totalTokens);
                continue;
            }
            
            // 否则添加这条消息
            selected.add(msg);
            totalTokens += messageTokens;
            
            log.debug("添加历史消息：角色={}, token 数={}, 累计 token={}", 
                     msg.getChatRole(), messageTokens, totalTokens);
        }

        // 记录筛选统计信息
        log.info("历史消息 token 筛选完成：原始总数={}, 选中={}, 跳过={}, 估算总 token 数={}/{}", 
                history.size(), selected.size(), skippedCount, totalTokens, maxTokens);
        log.warn("注意：token 数为 CL100K_BASE 估算值，实际消耗以 DeepSeek 返回的 tokenUsage 为准");

        // 重新按时间升序排列，保持原有的顺序
        selected.sort(Comparator.comparing(ChatMessage::getCreatedAt));
        
        return selected;
    }

    /**
     * 根据问题内容进行模型路由。
     * <p>
     * 路由优先级：
     * 1. 用户显式指定模型 -> 直接使用
     * 2. 命中数据库中的任务关键词 -> 根据任务分类决定是否升级模型
     * 3. 未命中关键词时 -> 使用长度规则兜底
     * </p>
     *
     * @param question 用户问题
     * @param originalModel 原始请求中指定的模型（如不为空则优先使用）
     * @return 路由后的模型名称
     */
    private String routeModelByQuestionLength(String question, String originalModel) {
        // 1. 优先遵循用户显式指定的模型
        if (originalModel != null && !originalModel.trim().isEmpty()) {
            return originalModel;
        }
        
        if (question == null || question.trim().isEmpty()) {
            return SIMPLE_MODEL;
        }
        
        String trimmedQuestion = question.trim();
        var keywordMatch = aiTaskKeywordService.findFirstMatch(trimmedQuestion);

        // 2. 关键词特征检测：优先使用数据库中的任务类型和关键词
        if (keywordMatch.isPresent()) {
            AiTaskKeywordService.TaskKeywordMatch match = keywordMatch.get();
            if (aiTaskKeywordService.isComplexTaskType(match.taskType())) {
                log.debug("模型路由: 命中复杂任务关键词，任务类型={}, 关键词={}, 升级到 {} 模型",
                        match.taskType(), match.keyword(), COMPLEX_MODEL);
                return COMPLEX_MODEL;
            }

            log.debug("模型路由: 命中文本类关键词，任务类型={}, 关键词={}，继续使用长度规则判断",
                    match.taskType(), match.keyword());
        }
        
        // 3. 长度特征检测：长文本通常意味着复杂语境
        int questionLength = trimmedQuestion.length();
        if (questionLength >= SIMPLE_QUERY_LENGTH_THRESHOLD) {
            log.debug("模型路由: 输入长度 ({}) 超过阈值，升级到 {} 模型", questionLength, COMPLEX_MODEL);
            return COMPLEX_MODEL;
        }

        // 4. 默认为简单模式
        log.debug("模型路由: 简单指令/闲聊，使用 {} 模型", SIMPLE_MODEL);
        return SIMPLE_MODEL;
    }
    
    /**
     * 为流式响应添加详细日志记录（可用于调试）
     * <p>
     * 记录的关键信息：
     * - 第一个响应到达时间（首字延迟）
     * - 每 5 个响应块的进度
     * - 完整响应的统计信息
     * </p>
     * <p>
     * 配置方式：
     * - 当前默认开启（streamingLogEnabled = true）
     * - 后期可通过 YAML 配置：chat.streaming.log.enabled=false 关闭
     * </p>
     *
     * @param responseFlux 原始的流式响应 Flux
     * @return 带有日志记录的流式响应 Flux
     */
    private Flux<ChatResponse> logStreamingResponse(Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux) {
        if (!streamingLogEnabled) {
            // 如果未开启日志，直接返回原始流
            return responseFlux.map(this::convertToChatResponse);
        }
        
        // 记录流式响应的关键时间点
        final long startTime = System.currentTimeMillis();
        final int[] responseCount = {0};
        final long[] firstResponseTime = {0};
        final StringBuilder fullContentLog = new StringBuilder();
        
        return responseFlux
                .doOnSubscribe(subscription -> {
                    log.info("⏰ [{}] 开始订阅 Ollama 流式响应...", formatTimestamp(0));
                })
                .map(response -> {
                    long currentTime = System.currentTimeMillis();
                    long elapsed = currentTime - startTime;
                    responseCount[0]++;
                    
                    var output = response.getResult().getOutput();
                    String content = output.getContent();
                    
                    // 记录第一个响应的时间（关键指标：首字延迟）
                    if (firstResponseTime[0] == 0) {
                        firstResponseTime[0] = elapsed;
                        log.info("⚡ [{}] 收到第一个流式响应块！耗时={}ms, 内容长度={}", 
                                formatTimestamp(elapsed), elapsed, 
                                content != null ? content.length() : 0);
                    }
                    
                    // 每 5 个响应块记录一次进度
                    if (responseCount[0] % 5 == 0) {
                        log.info("📦 [{}] 已接收 {} 个响应块，累计耗时={}ms", 
                                formatTimestamp(elapsed), responseCount[0], elapsed);
                    }
                    
                    // 记录内容片段（DEBUG 级别）
                    if (content != null && !content.isEmpty()) {
                        fullContentLog.append(content);
                        if (log.isDebugEnabled()) {
                            log.debug("💬 [{}] 响应块 #{}: \"{}\"", 
                                    formatTimestamp(elapsed), responseCount[0], 
                                    content.length() > 50 ? content.substring(0, 50) + "..." : content);
                        }
                    }
                    
                    return convertToChatResponse(response);
                })
                .doOnComplete(() -> {
                    long totalTime = System.currentTimeMillis() - startTime;
                    log.info("✅ [{}] 流式响应完成！总耗时={}ms, 总响应块数={}, 首个响应延迟={}ms", 
                            formatTimestamp(totalTime), totalTime, responseCount[0], firstResponseTime[0]);
                    log.info("📝 完整响应内容预览：{}", 
                            fullContentLog.length() > 200 
                                    ? fullContentLog.substring(0, 200) + "..." 
                                    : fullContentLog.toString());
                })
                .doOnError(error -> {
                    long totalTime = System.currentTimeMillis() - startTime;
                    log.error("❌ [{}] 流式响应出错！耗时={}ms, 已接收响应块数={}, 错误：{}", 
                            formatTimestamp(totalTime), totalTime, responseCount[0], error.getMessage());
                });
    }
    
    /**
     * 转换 Spring AI 的 ChatResponse 为项目的 ChatResponse DTO
     *
     * @param response Spring AI 的 ChatResponse
     * @return 项目的 ChatResponse DTO
     */
    private ChatResponse convertToChatResponse(org.springframework.ai.chat.model.ChatResponse response) {
        var output = response.getResult().getOutput();
        String content = output.getContent();
        return ChatResponse.builder()
                .content(content != null ? content : "")
                .model(response.getMetadata().getModel())
                .timestamp(System.currentTimeMillis())
                .success(true)
                .build();
    }
    
    /**
     * 格式化时间戳（相对于起始时间的毫秒数）
     *
     * @param elapsed 经过的毫秒数
     * @return 格式化的时间戳字符串 [HH:mm:ss.SSS]
     */
    private String formatTimestamp(long elapsed) {
        long hours = elapsed / 3600000;
        long minutes = (elapsed % 3600000) / 60000;
        long seconds = (elapsed % 60000) / 1000;
        long millis = elapsed % 1000;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }
}
