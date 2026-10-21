package io.github.renhaowan.platform.agent;

import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import io.github.renhaowan.platform.agent.context.impl.ContextTrimmerImpl;
import io.github.renhaowan.platform.agent.tool.ToolSelector;
import io.github.renhaowan.platform.agent.tool.impl.ToolSelectorChain;
import io.github.renhaowan.platform.agent.tool.impl.WattAiToolSelector;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 模块装配：需要原始值参数的组件在此显式构造。
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Bean
    public ContextTrimmer contextTrimmer(AgentProperties properties) {
        return new ContextTrimmerImpl(properties.getContextThreshold());
    }

    /**
     * 工具路由责任链：按 routing-handlers 声明顺序装配节点，逐环尝试、弃权传递、链尾兜底全量。
     * 工具数未达 routing-tool-count-threshold 时整链休眠（个位数工具下路由收益为负，决策 04）。
     */
    @Bean
    public ToolSelector toolSelector(AgentProperties properties) {
        List<ToolSelector> handlers = new ArrayList<>();
        for (String raw : properties.getRoutingHandlers().split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "wattai" -> {
                    String apiKey = properties.getWattai().getApiKey();
                    String baseUrl = properties.getWattai().getBaseUrl();
                    if (apiKey == null || apiKey.isBlank()) {
                        // 未配置凭证视为节点不可用：不装配，避免每轮对话都发一次注定失败的请求
                        log.warn("wattai 路由未装配：platform.agent.wattai.api-key 为空");
                        continue;
                    }
                    if (baseUrl == null || baseUrl.isBlank()) {
                        log.warn("wattai 路由未装配：platform.agent.wattai.base-url 为空");
                        continue;
                    }
                    handlers.add(new WattAiToolSelector(
                            baseUrl,
                            apiKey,
                            properties.getWattai().getMinTools(),
                            properties.getWattai().getConfidenceThreshold(),
                            properties.getWattai().getTopK(),
                            properties.getWattai().getConnectTimeoutMs(),
                            properties.getWattai().getReadTimeoutMs()));
                }
                // 扩展位：jev（TypeSafe AI Jev 托管 API）/ 本地语义路由实现就绪后按优先级追加
                default -> throw new IllegalStateException("未知工具路由节点: " + name + "（当前支持: wattai）");
            }
        }
        return new ToolSelectorChain(handlers, properties.getRoutingToolCountThreshold());
    }

    /**
     * 给 Spring AI 的 OpenAI 客户端加超时。自动配置注入的是容器里的 {@code RestClient.Builder}，
     * 这里通过 Spring Boot 的 customizer 生效——否则供应商挂起时 ReAct 循环会无限期占住线程。
     */
    @Bean
    public org.springframework.boot.web.client.RestClientCustomizer llmHttpTimeouts(AgentProperties properties) {
        return builder -> {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(properties.getLlmConnectTimeoutMs()))
                    .build();
            org.springframework.http.client.JdkClientHttpRequestFactory factory =
                    new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(java.time.Duration.ofMillis(properties.getLlmReadTimeoutMs()));
            builder.requestFactory(factory);
        };
    }
}
