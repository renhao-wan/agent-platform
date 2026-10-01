package io.github.renhaowan.platform.agent.tool.impl;

import io.github.renhaowan.platform.agent.tool.ToolSelector;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 透传实现（默认）：工具全量交给主模型。
 * 当前工具规模（个位数）下，路由层收益为负（额外一跳网络 + 决策模型质量不足），
 * 详见 docs/adr/ADR-004；工具规模增长后切换 WattAiToolSelector。
 */
public class NoopToolSelector implements ToolSelector {

    @Override
    public List<ToolCallback> select(String userText, List<ToolCallback> allTools) {
        return allTools;
    }
}
