package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.ChatMemoryConfig;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.config.DefaultSystemPromptProvider;
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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.UUID;

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
    private final ChatMemoryService chatMemoryService;
    private final ChatMemoryConfig chatMemoryConfig;
    private final DefaultSystemPromptProvider defaultSystemPromptProvider;
    private final ChatSummaryService chatSummaryService;

    // 流式响应日志配置（可通过 YAML 控制）
    // 在 application.yml 中设置：chat.streaming.log.enabled=true 开启详细日志
    @Value("${app.chat.streaming.log.enabled:true}")
    private boolean streamingLogEnabled;
    // 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    
    // JTokkit Token 计算器
    // 注意：CL100K_BASE 是 OpenAI GPT-4 的编码器，与 DeepSeek 的实际 tokenizer 可能存在差异
    // 仅用于历史消息 token 数的估算，不应用于精确计费或硬截断
    // 如需 DeepSeek 精确统计，应以 Ollama 返回的 tokenUsage 为准
    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

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
            AiTaskKeywordService.TaskKeywordMatch taskKeywordMatch = resolveTaskKeywordMatch(message).orElse(null);
            String systemPrompt = resolveSystemPrompt(null, taskKeywordMatch);

            var response = chatClientBuilder.build()
                    .prompt(systemPrompt)
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
            AiTaskKeywordService.TaskKeywordMatch taskKeywordMatch = resolveTaskKeywordMatch(request.getMessage()).orElse(null);
            String routedModel = routeModelByQuestionLength(request.getMessage(), request.getModel(), taskKeywordMatch);
            request.setModel(routedModel);
            // 记录请求日志，便于监控和调试
            log.info("开始处理结构化聊天请求: {} (剩余重试次数: {})", request, remainingRetries);
            // 系统提示词在任务分类阶段就确定：
            // request.systemMessage > taskType 对应默认提示词 > 全局默认提示词。
            // 这样提示词选择与后续模型路由解耦，不会出现“根据最终模型反推 prompt”的耦合问题。
            String systemPrompt = resolveSystemPrompt(request.getSystemMessage(), taskKeywordMatch);

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
    @Transactional
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
    // 这里由对外公开入口 structuredStreamChat 开启事务；
    // 这样 controller 调用 public 方法时，Spring 事务代理才能真正生效，
    // 避免“本类内部 self-invocation 导致 @Transactional 失效”。
    public Flux<ChatResponse> structuredStreamChatWithPersistence(ChatRequest request) {


        // 步骤 0: 智能模型路由策略
        // 如果用户未指定模型，系统将根据问题复杂度自动选择合适的模型
        // - 简单问题/闲聊 -> 8B 小模型 (响应快，成本低)
        // - 复杂任务/代码 -> 32B 大模型 (能力强，更精准)
        String originalModel = request.getModel();
        String messageContent = request.getMessage() != null ? request.getMessage() : "";
        
        AiTaskKeywordService.TaskKeywordMatch taskKeywordMatch = resolveTaskKeywordMatch(messageContent).orElse(null);
        String routedModel = routeModelByQuestionLength(messageContent, originalModel, taskKeywordMatch);
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
        userMsg = chatMessageRepository.save(userMsg);  // 先写数据库，保证长期持久化完整
        log.info("用户消息已保存到数据库：{}", userMsg);
        // Redis 写入改为事务提交后再执行，避免数据库回滚但缓存已提前暴露。一致性设计说明：
        // 1. PostgreSQL 是会话与消息的最终真源，所有消息必须先可靠落库。
        // 2. Redis 只保存“短期工作记忆”，它是数据库的派生缓存，而不是唯一事实来源。
        // 3. 因此这里采取“先写数据库，提交后再写 Redis”的策略，避免 DB 回滚但 Redis 已提前暴露。
        chatMemoryService.appendMessageAfterCommit(userMsg);


        // 步骤 3: 获取当前会话的历史消息记录
        // 优先从 Redis 短期记忆中读取，再按 token 预算精确截断；必要时回退数据库并回填 Redis。
        // 这里拿到的是“原始历史全集”：优先 Redis，未命中再回退数据库。
        // 注意：Redis 工作集允许暂时落后于数据库，因为它采用的是 after-commit 同步与读修复策略。
        List<ChatMessage> history = loadSessionHistory(finalSessionId);
        // 由于 Redis 是在事务提交后才异步补写，这里需要把“当前刚落库的用户消息”临时合并进上下文，
        // 否则本轮请求在事务提交前读取 Redis 时，可能看不到刚保存的 userMsg。
        history = mergeHistoryWithMessage(history, userMsg);
        // 这里先统一解析 system prompt，后面 token 预算计算和 Prompt 组装都必须基于同一份最终提示词。
        String systemPrompt = resolveSystemPrompt(request.getSystemMessage(), taskKeywordMatch);
        // 第二层中期记忆：优先从 Redis 读取“用户主体画像 + 当前会话摘要”两个高浓度 SystemMessage。
        // 这里先只读，不立即触发后台压缩。
        // 原因：本轮请求稍后还会落库 assistant 消息，如果现在就触发一次、完成后再触发一次，会造成重复摘要计算。
        List<String> memorySystemPrompts = chatSummaryService.getMemorySystemPrompts(finalSessionId, userId);
        // 根据 system prompt 长度动态计算本次还能留给历史消息多少 token 预算
        int historyTokenLimit = resolveHistoryTokenLimit(systemPrompt, memorySystemPrompts);
        // 从新到旧累计 token，达到阈值立刻停止，拿到最近一段连续上下文
        List<ChatMessage> selectedHistory = selectHistoryMessagesByTokenLimit(history, historyTokenLimit);


        // 步骤 4: 执行流式聊天并保存 AI 回复
        // 使用带历史记录和重试机制的流式聊天方法
        Flux<ChatResponse> responseFlux = structuredStreamChatWithRetryAndHistory(request, systemPrompt, selectedHistory, memorySystemPrompts, MAX_RETRIES);

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
                    assistantMsg = chatMessageRepository.save(assistantMsg);  // 保存 AI 消息到数据库
                    // assistant 消息同样走“提交后同步 Redis”，避免出现库回滚而缓存先可见
                    chatMemoryService.appendMessageAfterCommit(assistantMsg);
                    // 第二层中期记忆异步压缩：不阻塞当前流式响应，只在消息完整落库后后台刷新摘要/画像。
                    chatSummaryService.triggerRefreshSessionSummaryAsync(finalSessionId, chatUser.getUserId());
                    log.info("AI 响应已保存到数据库，会话 ID={}, 消息长度={}",
                            finalSessionId, fullContent.length());
                })
                // 在发生错误时记录日志
                .doOnError(error -> {
                    log.error("流式响应过程中出错，会话 ID={}: {}", finalSessionId, error.getMessage());
                });
    }

    private Flux<ChatResponse> structuredStreamChatWithRetryAndHistory(
            ChatRequest request,
            String resolvedSystemPrompt,
            List<ChatMessage> history,
            List<String> memorySystemPrompts,
            int remainingRetries) {
        return Flux.defer(() -> {
            log.info("开始处理带历史记录的结构化流式聊天请求：{} (剩余重试次数：{})", request, remainingRetries);

            // 这里直接复用调用入口已经确定好的最终 system prompt，
            // 避免重试时再次根据模型或其他后续逻辑重新推导提示词。
            OllamaOptions options = OllamaOptions.create()
                    .withModel(request.getModel())
                    .withTemperature(request.getTemperature())
                    .withNumPredict(request.getMaxTokens());

            // Prompt 的第一条始终是系统角色定义，用于约束模型行为
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(resolvedSystemPrompt));

            // 第二层中期记忆摘要作为额外的 SystemMessage 注入。
            // 顺序上先用户主体画像，后当前 session 摘要，再拼接短期历史，既保留长期偏好，也保留当前会话浓缩上下文。
            for (String memorySystemPrompt : memorySystemPrompts) {
                if (memorySystemPrompt != null && !memorySystemPrompt.isBlank()) {
                    messages.add(new SystemMessage(memorySystemPrompt));
                }
            }

            // 把筛选后的历史消息按原顺序拼回 Prompt，形成完整上下文
            for (ChatMessage historyMessage : history) {
                Message promptMessage = toPromptMessage(historyMessage);
                if (promptMessage != null) {
                    messages.add(promptMessage);
                }
            }


            Prompt prompt = new Prompt(messages, options);
            log.info("Prompt 组装完成：sessionId={}, systemPromptTokens≈{}, memorySystemMessages={}, historyMessages={}",
                    request.getSessionId(),
                    encoding.countTokens(resolvedSystemPrompt),
                    memorySystemPrompts.size(),
                    history.size());

            // 使用流式响应日志记录器（可配置）
            return logStreamingResponse(ollamaChatModel.stream(prompt));
        })
        .onErrorResume(e -> {
            log.warn("结构化流式聊天处理失败 (剩余重试次数：{}): {}", remainingRetries, e.getMessage());
            if (isConnectionException((Exception) e) && remainingRetries > 0) {
                return Mono.delay(Duration.ofMillis(RETRY_DELAY_MS))
                        .thenMany(structuredStreamChatWithRetryAndHistory(request, resolvedSystemPrompt, history, memorySystemPrompts, remainingRetries - 1));
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
        return Flux.defer(() -> {
            log.info("开始处理简单聊天请求: {} (剩余重试次数: {})", message, remainingRetries);
            // 简单流式聊天没有 request 对象，因此直接使用缓存后的默认 system prompt。
            AiTaskKeywordService.TaskKeywordMatch taskKeywordMatch = resolveTaskKeywordMatch(message).orElse(null);
            String systemPrompt = resolveSystemPrompt(null, taskKeywordMatch);
            return chatClientBuilder.build()
                    .prompt(systemPrompt)
                    .advisors(new SimpleLoggerAdvisor()).user(message)
                    .stream()
                    .content();
        }).onErrorResume(e -> {
            log.warn("简单流式聊天处理失败 (剩余重试次数: {}): {}", remainingRetries, e.getMessage());
            if (e instanceof Exception exception && isConnectionException(exception) && remainingRetries > 0) {
                return Mono.delay(Duration.ofMillis(RETRY_DELAY_MS))
                        .thenMany(streamChatWithRetry(message, remainingRetries - 1));
            }
            return Flux.error(new RuntimeException("聊天服务暂时不可用: " + e.getMessage(), e));
        });
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
     * 统一解析本次请求应使用的系统提示词。
     *
     * <p>优先级：</p>
     * <ol>
     *     <li>请求中显式传入的 systemMessage</li>
     *     <li>启动时从 resources 加载并缓存的默认系统提示词</li>
     * </ol>
     *
     * <p>这样可以确保默认提示词只在应用启动时读取一次，运行期不会反复访问磁盘。</p>
     */
    private String resolveSystemPrompt(String requestSystemPrompt, AiTaskKeywordService.TaskKeywordMatch taskKeywordMatch) {
        if (requestSystemPrompt != null && !requestSystemPrompt.isBlank()) {
            return requestSystemPrompt;
        }
        if (taskKeywordMatch != null) {
            String taskPrompt = defaultSystemPromptProvider.getPromptByTaskType(taskKeywordMatch.taskType());
            if (taskPrompt != null && !taskPrompt.isBlank()) {
                return taskPrompt;
            }
        }
        return defaultSystemPromptProvider.getPrompt();
    }

    private java.util.Optional<AiTaskKeywordService.TaskKeywordMatch> resolveTaskKeywordMatch(String question) {
        if (question == null || question.isBlank()) {
            return java.util.Optional.empty();
        }
        return aiTaskKeywordService.findFirstMatch(question);
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
        if (history == null || history.isEmpty() || maxTokens <= 0) {
            return List.of();  // 返回空列表，避免创建新对象
        }

        // 1: 从后向前遍历，意味着优先保留“最近消息”
        //    这是短期记忆窗口最重要的原则：越新的上下文优先级越高
        List<ChatMessage> selected = new ArrayList<>();
        int totalTokens = 0;
        int skippedCount = 0;
        boolean debugEnabled = log.isDebugEnabled();

        // 2: 不创建倒序副本，直接从尾部扫描，减少一次额外拷贝
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            
            // 3: 跳过空消息，避免无效计算
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                skippedCount++;
                continue;
            }
            
            // 计算当前消息的 token 数（估算值）
            // role 也会参与估算，避免只按 content 计算而低估上下文长度
            int messageTokens = estimateMessageTokens(msg);

            // 4: 一旦达到 token 上限，立刻停止继续向前追溯
            //    这里不是“跳过当前长消息继续找更老消息”，而是直接停下：
            //    目的是保留最近的一段连续上下文，而不是把上下文切成碎片
            if (totalTokens + messageTokens > maxTokens) {
                if (selected.isEmpty()) {
                    // 如果连最新一条都超限，仍然强制保留它，避免当前轮最关键的上下文丢失
                    selected.add(msg);
                    totalTokens += messageTokens;
                    log.warn("最新消息单条 token 已超过历史阈值，仍强制保留：角色={}, token 数={}, 阈值={}",
                            msg.getChatRole(), messageTokens, maxTokens);
                } else {
                    skippedCount += (i + 1);
                    if (debugEnabled) {
                        log.debug("达到 token 上限，停止继续追溯：角色={}, token 数={}, 当前累计={}, 阈值={}",
                                msg.getChatRole(), messageTokens, totalTokens, maxTokens);
                    }
                }
                break;
            }

            // 这条消息仍在预算内，纳入本次 Prompt 的短期记忆窗口
            selected.add(msg);
            totalTokens += messageTokens;
            
            if (debugEnabled) {
                log.debug("添加历史消息：角色={}, token 数={}, 累计 token={}",
                         msg.getChatRole(), messageTokens, totalTokens);
            }
        }

        // 5: selected 当前顺序是“从新到旧”，而 Prompt 需要“从旧到新”
        //    因此最后统一反转一次，恢复正常对话阅读顺序
        if (selected.size() > 1) {
            java.util.Collections.reverse(selected);
        }

        // 记录筛选统计信息
        if (skippedCount > 0 || !selected.isEmpty()) {
            log.info("历史消息 token 筛选完成：原始总数={}, 选中={}, 跳过={}, 估算总 token 数={}/{}",
                    history.size(), selected.size(), skippedCount, totalTokens, maxTokens);
        }
        
        return selected;
    }

    private List<ChatMessage> loadSessionHistory(String sessionId) {
        // 优先读取 Redis：它代表当前会话的短期工作记忆，速度更快
        List<ChatMessage> redisHistory = chatMemoryService.getMessages(sessionId);
        if (!redisHistory.isEmpty()) {
            log.info("从 Redis 短期记忆加载历史消息：sessionId={}, 条数={}", sessionId, redisHistory.size());
            return redisHistory;
        }

        // 如果配置关闭数据库兜底，则 Redis miss 时直接返回空历史。
        // 这意味着系统会容忍 Redis 丢失短期记忆，但不会自动从数据库修复。
        if (!chatMemoryConfig.isFallbackToDatabaseOnMiss()) {
            return List.of();
        }

        // Redis 未命中时回退到数据库，再把完整历史回填 Redis，提升下一次命中率。
        // 这一步是本方案的“读修复”机制：即使 Redis 写失败或过期，也能靠数据库自愈。
        List<ChatMessage> databaseHistory = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (!databaseHistory.isEmpty()) {
            chatMemoryService.rebuildSessionMemory(sessionId, databaseHistory);
            log.info("Redis 短期记忆未命中，已回退数据库历史：sessionId={}, 条数={}", sessionId, databaseHistory.size());
        }
        return databaseHistory;
    }

    private List<ChatMessage> mergeHistoryWithMessage(List<ChatMessage> history, ChatMessage message) {
        // 这个方法专门解决“当前请求已经落库，但 Redis 还在等待 after-commit”的时间窗口问题。
        // 它只影响本次内存中的 Prompt 组装，不会反向修改 Redis 或数据库。
        if (message == null) {
            return history != null ? history : List.of();
        }

        List<ChatMessage> merged = new ArrayList<>(history != null ? history : List.of());
        boolean alreadyExists = merged.stream().anyMatch(existing -> isSameMessage(existing, message));
        if (!alreadyExists) {
            merged.add(message);
        }
        merged.sort((left, right) -> {
            if (left.getCreatedAt() == null && right.getCreatedAt() == null) {
                return compareChatId(left, right);
            }
            if (left.getCreatedAt() == null) {
                return 1;
            }
            if (right.getCreatedAt() == null) {
                return -1;
            }
            int compareCreatedAt = left.getCreatedAt().compareTo(right.getCreatedAt());
            return compareCreatedAt != 0 ? compareCreatedAt : compareChatId(left, right);
        });
        return merged;
    }

    private boolean isSameMessage(ChatMessage left, ChatMessage right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getChatId() != null && right.getChatId() != null) {
            return left.getChatId().equals(right.getChatId());
        }
        return java.util.Objects.equals(left.getSessionId(), right.getSessionId())
                && java.util.Objects.equals(left.getChatRole(), right.getChatRole())
                && java.util.Objects.equals(left.getContent(), right.getContent())
                && java.util.Objects.equals(left.getCreatedAt(), right.getCreatedAt());
    }

    private int compareChatId(ChatMessage left, ChatMessage right) {
        long leftId = left.getChatId() == null ? Long.MAX_VALUE : left.getChatId();
        long rightId = right.getChatId() == null ? Long.MAX_VALUE : right.getChatId();
        return Long.compare(leftId, rightId);
    }

    private int resolveHistoryTokenLimit(String systemPrompt, List<String> memorySystemPrompts) {
        int systemPromptTokens = 0;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            // +4 为 system message 的包装开销预留一个粗略缓冲
            systemPromptTokens = encoding.countTokens(systemPrompt) + 4;
        }

        int memorySummaryTokens = 0;
        if (memorySystemPrompts != null) {
            for (String memorySystemPrompt : memorySystemPrompts) {
                if (memorySystemPrompt != null && !memorySystemPrompt.isBlank()) {
                    memorySummaryTokens += encoding.countTokens(memorySystemPrompt) + 4;
                }
            }
        }

        // 总预算先扣除 system prompt，再把剩余额度分配给历史消息窗口
        int availableHistoryTokens = chatMemoryConfig.getPromptTokenLimit() - systemPromptTokens - memorySummaryTokens;
        // 通过最小值兜底，避免 system prompt 过长导致历史上下文完全丢失
        int resolvedLimit = Math.max(chatMemoryConfig.getMinimumHistoryTokens(), availableHistoryTokens);
        log.info("本次短期记忆 token 预算：promptLimit={}, systemPromptTokens≈{}, memorySummaryTokens≈{}, historyLimit={}",
                chatMemoryConfig.getPromptTokenLimit(), systemPromptTokens, memorySummaryTokens, resolvedLimit);
        return resolvedLimit;
    }

    private int estimateMessageTokens(ChatMessage msg) {
        // 统一封装消息 token 估算逻辑，便于以后替换成更贴近目标模型的 tokenizer
        String role = msg.getChatRole() != null ? msg.getChatRole() : "user";
        String content = msg.getContent() != null ? msg.getContent() : "";
        return Math.max(1, encoding.countTokens(role) + encoding.countTokens(content) + 4);
    }

    private Message toPromptMessage(ChatMessage historyMessage) {
        if (historyMessage == null || historyMessage.getContent() == null || historyMessage.getContent().isBlank()) {
            return null;
        }

        // 统一把数据库/Redis 中的聊天角色转换成 Spring AI 的 Prompt Message
        String role = historyMessage.getChatRole() != null ? historyMessage.getChatRole().trim().toLowerCase() : "user";
        return switch (role) {
            case "assistant" -> new AssistantMessage(historyMessage.getContent());
            case "system" -> new SystemMessage(historyMessage.getContent());
            default -> new UserMessage(historyMessage.getContent());
        };
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
    private String routeModelByQuestionLength(String question, String originalModel, AiTaskKeywordService.TaskKeywordMatch taskKeywordMatch) {
        // 1. 优先遵循用户显式指定的模型
        if (originalModel != null && !originalModel.trim().isEmpty()) {
            return originalModel;
        }
        
        if (question == null || question.trim().isEmpty()) {
            return SIMPLE_MODEL;
        }
        
        String trimmedQuestion = question.trim();

        // 2. 模型路由只负责算力选择，使用前面任务匹配阶段的结果，不再参与提示词反推。
        if (taskKeywordMatch != null) {
            if (aiTaskKeywordService.isComplexTaskType(taskKeywordMatch.taskType())) {
                log.debug("模型路由: 命中复杂任务关键词，任务类型={}, 关键词={}, 升级到 {} 模型",
                        taskKeywordMatch.taskType(), taskKeywordMatch.keyword(), COMPLEX_MODEL);
                return COMPLEX_MODEL;
            }

            log.debug("模型路由: 命中文本类关键词，任务类型={}, 关键词={}，继续使用长度规则判断",
                    taskKeywordMatch.taskType(), taskKeywordMatch.keyword());
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
                .doOnSubscribe(subscription -> log.info("⏰ [{}] 开始订阅 Ollama 流式响应...", formatTimestamp(0)))
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
