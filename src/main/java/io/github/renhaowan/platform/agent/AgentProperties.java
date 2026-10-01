package io.github.renhaowan.platform.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    /** 工具前置路由：noop（默认透传）| wattai（System One 决策模型） */
    private String toolSelector = "noop";

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

    public String getGatewayBaseUrl() { return gatewayBaseUrl; }
    public void setGatewayBaseUrl(String gatewayBaseUrl) { this.gatewayBaseUrl = gatewayBaseUrl; }
    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
    public String getGatewayApiKey() { return gatewayApiKey; }
    public void setGatewayApiKey(String gatewayApiKey) { this.gatewayApiKey = gatewayApiKey; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public int getContextThreshold() { return contextThreshold; }
    public void setContextThreshold(int contextThreshold) { this.contextThreshold = contextThreshold; }
    public int getConfirmTimeoutSeconds() { return confirmTimeoutSeconds; }
    public String getToolSelector() { return toolSelector; }
    public void setToolSelector(String toolSelector) { this.toolSelector = toolSelector; }
    public Map<String, List<String>> getIntentRules() { return intentRules; }
    public void setIntentRules(Map<String, List<String>> intentRules) { this.intentRules = intentRules; }
    public Wattai getWattai() { return wattai; }
    public void setWattai(Wattai wattai) { this.wattai = wattai; }
    public void setConfirmTimeoutSeconds(int confirmTimeoutSeconds) { this.confirmTimeoutSeconds = confirmTimeoutSeconds; }
}
