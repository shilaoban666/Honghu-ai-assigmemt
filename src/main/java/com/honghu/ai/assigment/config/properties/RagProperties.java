package com.honghu.ai.assigment.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private boolean enabled = true;
    private Listener listener = new Listener();
    private Ingestion ingestion = new Ingestion();
    private Retrieval retrieval = new Retrieval();
    private QueryRewrite queryRewrite = new QueryRewrite();
    private Augmentor augmentor = new Augmentor();
    private Observability observability = new Observability();

    @Data
    public static class Listener {
        private boolean enabled = true;
        private String queueName = "honghu-ai-document-upload-received";
        private List<String> allowedBuckets = new ArrayList<>();
        private int staleProcessingMinutes = 10;
    }

    @Data
    public static class Ingestion {
        private long maxObjectSizeBytes = 20L * 1024 * 1024;
        private List<String> supportedExtensions = new ArrayList<>(List.of("txt", "md", "json", "xml", "csv", "pdf"));
        private int chunkSize = 800;
        private int chunkOverlap = 120;
        private int maxChunksPerDocument = 200;
        private int minChunkLength = 80;
        private int maxExtractedCharacters = 100000;
    }

    @Data
    public static class Retrieval {
        private boolean enabled = true;
        private String mode = "keyword";
        private double similarityThreshold = 0.55;
        private int topK = 4;
        private int candidateLimit = 80;
        private int maxContextCharacters = 3000;
        private int minKeywordLength = 2;
        private ScopeConfig scope = new ScopeConfig();
        private FusionConfig fusion = new FusionConfig();
        private RerankConfig rerank = new RerankConfig();
    }

    @Data
    public static class ScopeConfig {
        private String defaultScope = "attachment_chat_first";
        private int fallbackMinHits = 2;
        private double fallbackMinScore = 0.1;
        private double sessionPenaltyFactor = 0.85;
    }

    @Data
    public static class FusionConfig {
        private boolean enabled = false;
        private String algorithm = "rrf";
        private int rrfK = 60;
        private int candidateLimitAfterFusion = 20;
    }

    @Data
    public static class RerankConfig {
        private boolean enabled = false;
        private String implementation = "noop";
        private int candidateLimit = 10;
        private int timeoutMs = 5000;
    }

    @Data
    public static class QueryRewrite {
        private boolean enabled = false;
        private int maxVariants = 3;
        private boolean pronounResolveEnabled = false;
    }

    @Data
    public static class Augmentor {
        private boolean dedupEnabled = true;
        private boolean compressEnabled = true;
        private boolean citationEnabled = true;
        private boolean budgetEnabled = true;
        private boolean diversityEnabled = false;
        private boolean reorderEnabled = false;
        private int tokenBudget = 1500;
    }

    @Data
    public static class Observability {
        private boolean metricsEnabled = true;
        private String logDetailLevel = "summary";
    }
}
