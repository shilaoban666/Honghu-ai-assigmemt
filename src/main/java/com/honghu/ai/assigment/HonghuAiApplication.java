package com.honghu.ai.assigment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * Spring Boot 应用启动类。
 *
 * 这里是整个后端服务的入口：main 方法只负责把 Spring 容器拉起来，
 * 具体的 Web、RAG、技能商店、模型网关等能力都会在容器启动时按 Bean 自动装配。
 */
@SpringBootApplication(exclude = {
        /*
         * OpenAI Chat 自动配置先排除掉。
         *
         * 原因：本项目不是只接官方 OpenAI，而是有 DeepSeek、Ollama、DashScope 等多 provider。
         * 如果让 Spring AI 自动创建默认 OpenAiChatModel，容易和我们自己的 AiChatModelGatewayService
         * 抢 Bean，最终出现“配置的是 deepseek，实际调用却走默认 OpenAI Bean”的问题。
         */
        OpenAiChatAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
        /*
         * OpenAI Embedding 自动配置也排除掉。
         *
         * 原因：RAG embedding 需要使用 OpenAI-compatible 接口，但 baseUrl/apiKey/path 需要按项目配置手动兜底。
         * 具体装配逻辑放在 RagEmbeddingModelConfig，那里会明确检查 baseUrl 和 apiKey 是否存在。
         */
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        /*
         * Milvus VectorStore 自动配置排除掉。
         *
         * 原因：升级 Spring AI 1.0.0 后，Milvus builder 参数发生变化；
         * 项目同时还有自己直连 Milvus SDK 的写入仓储，所以统一交给 RagMilvusClientConfig 手动创建，
         * 避免自动配置和手写配置生成两套连接或两套 collection 配置。
         */
        MilvusVectorStoreAutoConfiguration.class
})
/*
 * 开启 Spring 定时任务。
 *
 * RAG、MCP 市场同步、未来的技能统计刷新等后台任务都可以复用这个能力；
 * 当前类只声明能力开关，不直接写任何定时逻辑。
 */
@EnableScheduling
public class HonghuAiApplication {

    public static void main(String[] args) {
        // 启动 Spring Boot，并把命令行参数继续交给 Spring，用于读取 profile、端口等启动配置。
        SpringApplication.run(HonghuAiApplication.class, args);
    }

}
