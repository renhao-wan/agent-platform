package io.github.renhaowan.platform.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行时配置项（application.yml 的 platform.agent.*；
 * @ConfigurationProperties + @ConfigurationPropertiesScan 自动绑定与校验）。
 */
@Data
@ConfigurationProperties(prefix = "platform.agent")
public class AgentProperties {

    /** MCP 网关地址（同进程自环） */
    private String gatewayBaseUrl = "http://localhost:8000";
    private String gatewayId = "gw1";
    /** 平台自身租户的 apiKey（Agent 以 MCP 客户端身份访问网关） */
    private String gatewayApiKey = "";
    private int maxSteps = 8;
    /** 上下文裁剪阈值（粗估 token 数） */
    private int contextThreshold = 6000;
    private int confirmTimeoutSeconds = 300;

    /**
     * 工具路由责任链节点及顺序（逗号分隔、按序尝试）：当前支持 wattai；
     * 置空 = 不路由、全量直通。jev / 本地语义路由实现就绪后按优先级前置。
     */
    private String routingHandlers = "wattai";
    /** 责任链触发阈值：工具数达到该值才启用路由；未达则整链休眠、全量直通（ADR-004） */
    private int routingToolCountThreshold = 20;

    /**
     * 意图分类规则：Map&lt;标签, 关键词列表&gt;，按声明顺序匹配、命中即返回；
     * 默认为空（全部回落 GENERAL）——业务关键词属于配置而非代码，示例见 application.yml。
     */
    private Map<String, List<String>> intentRules = new LinkedHashMap<>();

    private Wattai wattai = new Wattai();

    public static class Wattai {
        private String baseUrl = "https://api.wattai.dev";
        /** 工具数低于该值时不路由（省一次网络跳） */
        private int minTools = 4;
        private double confidenceThreshold = 0.6;
        private int topK = 2;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getMinTools() { return minTools; }
        public void setMinTools(int minTools) { this.minTools = minTools; }
        public double getConfidenceThreshold() { return confidenceThreshold; }
        public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

}
