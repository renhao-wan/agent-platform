package io.github.renhaowan.platform.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行时配置项（application.yml 的 platform.agent.*；
 * @ConfigurationProperties + @ConfigurationPropertiesScan 自动绑定与校验）。
 */
@Data
@ConfigurationProperties(prefix = "platform.agent")
public class AgentProperties {

    /** 工具层网关标识（工具定义按 gatewayId 分组，导入时指定） */
    private String gatewayId = "gw1";
    private int maxSteps = 8;
    /** 上下文裁剪阈值（粗估 token 数） */
    private int contextThreshold = 6000;
    private int confirmTimeoutSeconds = 300;

    /** 大模型连接超时：供应商不可达时尽快失败，而不是占住 loop 线程。 */
    private int llmConnectTimeoutMs = 5000;
    /** 大模型读超时：覆盖一次完整生成，过短会截断正常的长回答。 */
    private int llmReadTimeoutMs = 120_000;

    /**
     * 工具路由责任链节点及顺序（逗号分隔、按序尝试）：当前支持 wattai；
     * 置空 = 不路由、全量直通。jev / 本地语义路由实现就绪后按优先级前置。
     */
    private String routingHandlers = "wattai";
    /** 责任链触发阈值：工具数达到该值才启用路由；未达则整链休眠、全量直通（ADR-004） */
    private int routingToolCountThreshold = 20;

    private Wattai wattai = new Wattai();

    public static class Wattai {
        private String baseUrl = "https://api.wattai.dev";
        /** 工具数低于该值时不路由（省一次网络跳） */
        private int minTools = 4;
        private double confidenceThreshold = 0.6;
        private int topK = 2;
        /** 路由是可选优化：超时后弃权交下一环，不能拖慢主流程。 */
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 3000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getMinTools() { return minTools; }
        public void setMinTools(int minTools) { this.minTools = minTools; }
        public double getConfidenceThreshold() { return confidenceThreshold; }
        public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

}
