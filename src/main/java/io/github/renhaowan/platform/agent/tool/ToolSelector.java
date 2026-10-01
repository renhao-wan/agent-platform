package io.github.renhaowan.platform.agent.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具前置路由（System One 决策模型扩展点）：
 * 工具集增长后，在进入主模型前先粗筛，避免全量 Schema 倾倒（prompt 膨胀/选择幻觉）。
 * <p>
 * 实现策略可切换（AgentConfig 装配）：NoopToolSelector（默认透传）/
 * WattAiToolSelector（System One 决策模型，见 docs/adr/ADR-004）。
 * 契约：任何实现失败都必须 fail-open 返回全量——路由层故障不得影响对话主流程。
 */
public interface ToolSelector {

    List<ToolCallback> select(String userText, List<ToolCallback> allTools);
}
