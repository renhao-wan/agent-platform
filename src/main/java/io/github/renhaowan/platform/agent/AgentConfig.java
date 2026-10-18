package io.github.renhaowan.platform.agent;

import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import io.github.renhaowan.platform.agent.context.impl.ContextTrimmerImpl;
import io.github.renhaowan.platform.agent.tool.ToolSelector;
import io.github.renhaowan.platform.agent.tool.impl.ToolSelectorChain;
import io.github.renhaowan.platform.agent.tool.impl.WattAiToolSelector;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 模块装配：需要原始值参数的组件在此显式构造。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ContextTrimmer contextTrimmer(AgentProperties properties) {
        return new ContextTrimmerImpl(properties.getContextThreshold());
    }

    /**
     * 工具路由责任链：按 routing-handlers 声明顺序装配节点，逐环尝试、弃权传递、链尾兜底全量。
     * 工具数未达 routing-tool-count-threshold 时整链休眠（个位数工具下路由收益为负，ADR-004）。
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
                case "wattai" -> handlers.add(new WattAiToolSelector(
                        properties.getWattai().getBaseUrl(),
                        System.getenv("WATTAI_API_KEY"),
                        properties.getWattai().getMinTools(),
                        properties.getWattai().getConfidenceThreshold(),
                        properties.getWattai().getTopK()));
                // 扩展位：jev（TypeSafe AI Jev 托管 API）/ 本地语义路由实现就绪后按优先级追加
                default -> throw new IllegalStateException("未知工具路由节点: " + name + "（当前支持: wattai）");
            }
        }
        return new ToolSelectorChain(handlers, properties.getRoutingToolCountThreshold());
    }
}
