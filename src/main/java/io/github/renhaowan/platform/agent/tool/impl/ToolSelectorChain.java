package io.github.renhaowan.platform.agent.tool.impl;

import io.github.renhaowan.platform.agent.tool.ToolSelector;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具路由责任链（按装配顺序逐环尝试，见 docs/adr/ADR-004）：
 * <ol>
 *   <li>规模闸门：工具数未达 toolCountThreshold 时整条链休眠、全量直通——个位数工具下
 *       路由收益为负（多一跳网络 + 决策模型质量不足），工具规模化后自动武装，无需改代码；</li>
 *   <li>逐环传递：节点弃权（Optional.empty）或抛异常都传给下一环——前者是策略性让位，
 *       后者是故障降级，两态都不阻断主流程；</li>
 *   <li>链尾兜底：所有节点都弃权 → 全量直通，与"不路由"等价。</li>
 * </ol>
 */
@Slf4j
public class ToolSelectorChain implements ToolSelector {

    private final List<ToolSelector> handlers;
    private final int toolCountThreshold;

    public ToolSelectorChain(List<ToolSelector> handlers, int toolCountThreshold) {
        this.handlers = List.copyOf(handlers);
        this.toolCountThreshold = toolCountThreshold;
    }

    @Override
    public Optional<List<ToolCallback>> select(String userText, List<ToolCallback> allTools) {
        if (allTools.size() < toolCountThreshold) {
            return Optional.of(allTools);
        }
        for (ToolSelector handler : handlers) {
            Optional<List<ToolCallback>> routed;
            try {
                routed = handler.select(userText, allTools);
            } catch (Exception e) {
                log.warn("route handler {} threw, pass to next: {}",
                        handler.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            if (routed.isPresent()) {
                log.debug("route handler {} took over: {} -> {} tools",
                        handler.getClass().getSimpleName(), allTools.size(), routed.get().size());
                return routed;
            }
        }
        return Optional.of(allTools);
    }
}
