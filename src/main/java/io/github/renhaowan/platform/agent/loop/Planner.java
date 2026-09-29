package io.github.renhaowan.platform.agent.loop;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

/**
 * 规划器抽象：单轮决策（不执行工具），由 AgentRunner 驱动 ReAct 循环。
 * 抽象为接口使循环可测（测试用脚本化 Planner，不依赖真实 LLM）。
 */
public interface Planner {

    /**
     * @param history 含 system 前缀、历史消息与本轮用户输入
     * @param tools   网关动态发现的工具（仅作 schema 广告位）
     */
    ChatResponse decide(List<Message> history, List<ToolCallback> tools);
}
