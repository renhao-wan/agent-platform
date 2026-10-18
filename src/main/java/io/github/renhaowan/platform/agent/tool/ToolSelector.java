package io.github.renhaowan.platform.agent.tool;

import java.util.List;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具前置路由责任链节点（System One 决策模型扩展点，见 docs/adr/ADR-004）：
 * 工具集增长后，在进入主模型前先粗筛，避免全量 Schema 倾倒（prompt 膨胀/选择幻觉）。
 * <p>
 * 责任链语义：节点按装配顺序依次尝试（AgentConfig 按 routing-handlers 装配），
 * 任一节点接管即短路返回；节点弃权或异常则传递下一环，链尾兜底为全量直通——
 * 路由层任何故障都不影响对话主流程。
 * 当前节点：WattAiToolSelector（System One 决策模型）；
 * 扩展位：jev（TypeSafe AI Jev 托管 API）/ 本地语义路由，实现就绪后按优先级追加。
 */
public interface ToolSelector {

    /**
     * @param userText 当前用户话术
     * @param allTools 全量候选工具
     * @return Optional.of(筛选后工具集) = 本环接管；Optional.empty() = 本环弃权
     *         （前置条件不满足 / 决策不可信 / 调用失败），交由责任链下一环处理
     */
    Optional<List<ToolCallback>> select(String userText, List<ToolCallback> allTools);
}
