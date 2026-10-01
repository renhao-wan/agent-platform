package io.github.renhaowan.platform.agent;

import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import io.github.renhaowan.platform.agent.tool.NoopToolSelector;
import io.github.renhaowan.platform.agent.tool.ToolSelector;
import io.github.renhaowan.platform.agent.tool.WattAiToolSelector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 模块装配：需要原始值参数的组件在此显式构造。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ContextTrimmer contextTrimmer(AgentProperties properties) {
        return new ContextTrimmer(properties.getContextThreshold());
    }

    /** 工具前置路由按配置切换；wattai 失败自动 fail-open，不影响对话主流程。 */
    @Bean
    public ToolSelector toolSelector(AgentProperties properties) {
        return switch (properties.getToolSelector()) {
            case "wattai" -> new WattAiToolSelector(
                    properties.getWattai().getBaseUrl(),
                    System.getenv("WATTAI_API_KEY"),
                    properties.getWattai().getMinTools(),
                    properties.getWattai().getConfidenceThreshold(),
                    properties.getWattai().getTopK());
            default -> new NoopToolSelector();
        };
    }
}
