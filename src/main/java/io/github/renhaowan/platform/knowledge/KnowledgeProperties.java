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

    private int topK = 4;
    private int topKMax = 8;
    private double scoreThreshold = 0.30;

    private int maxChars = 800;
    private int minChars = 50;
    private int overlapChars = 100;
}
