package io.github.renhaowan.platform.agent;

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
    public void setConfirmTimeoutSeconds(int confirmTimeoutSeconds) { this.confirmTimeoutSeconds = confirmTimeoutSeconds; }
}
