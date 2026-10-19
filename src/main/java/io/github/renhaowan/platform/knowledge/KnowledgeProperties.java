package io.github.renhaowan.platform.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置。嵌入密钥为空或 enabled=false 时整个模块不装配（见 KnowledgeEnabledCondition）——
 * 知识库是可选增强，不是启动前置条件。
 */
@Data
@ConfigurationProperties(prefix = "platform.knowledge")
public class KnowledgeProperties {

    private boolean enabled = true;
    private String directory = "knowledge";

    private String vectorStoreUrl = "jdbc:postgresql://localhost:5433/knowledge";
    private String vectorStoreUsername = "postgres";
    private String vectorStorePassword = "postgres";

    private String embeddingBaseUrl = "https://api.siliconflow.cn";
    private String embeddingApiKey = "";
    private String embeddingModel = "BAAI/bge-m3";
    private int embeddingDimensions = 1024;
    /** 外部 embedding 调用必须有超时，否则供应商无响应会把索引线程和在线检索一起拖死。 */
    private int embeddingConnectTimeoutMs = 3000;
    private int embeddingReadTimeoutMs = 20000;
    private int embeddingMaxAttempts = 3;
    private long embeddingRetryInitialBackoffMs = 500;
    private double embeddingRetryMultiplier = 2.0;
    private long embeddingRetryMaxBackoffMs = 5000;

    private int topK = 4;
    private int topKMax = 8;
    private double scoreThreshold = 0.30;
    /** 向量检索为空时按关键词回查 chunk 表，覆盖制度编号/专有名词这类向量不敏感的查询。 */
    private boolean keywordFallbackEnabled = true;

    private int maxChars = 800;
    private int minChars = 50;
    private int overlapChars = 100;
    /** 文档之间的主动节流，避免首灌大量文档时打爆供应商配额；0 表示不限。 */
    private long indexDelayMs = 0;
    /** 超过该时长仍为 RUNNING 的索引任务视为进程中断，下次启动前清理。 */
    private int jobTimeoutMinutes = 120;

    /** 检索审计留存天数；0 表示永久保留。 */
    private int auditRetentionDays = 180;

    private String evalFile = "knowledge/eval/questions.jsonl";
}
