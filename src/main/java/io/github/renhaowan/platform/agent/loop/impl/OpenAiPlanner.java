package io.github.renhaowan.platform.agent.loop.impl;

import io.github.renhaowan.platform.agent.loop.Planner;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议规划器（默认供应商 DeepSeek）。
 * 关键：internalToolExecutionEnabled(false) 关闭框架内隐式工具执行，
 * 每轮只取回模型的 toolCalls 决策，执行权在 AgentRunner（插桩：SSE 事件、确认、步数上限）。
 * <p>
 * 兼容性：各家"思考开关"的字段形态不同，统一走 extraBody 透传，互不影响。
 * qwen3 非流式调用强制 enable_thinking=false；DeepSeek 的 thinking 默认开启，必须显式关闭——
 * 它带 tools 时要求把每轮 reasoning_content 回传并拼进上下文，而本循环手动构造
 * ToolResponseMessage，不保留该字段；thinking 模式下 temperature 参数也会被静默忽略。
 */
@Component
public class OpenAiPlanner implements Planner {

    private final ChatClient chatClient;
    private final String modelName;

    public OpenAiPlanner(ChatModel chatModel, Environment environment) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.modelName = environment.getProperty("spring.ai.openai.chat.options.model", "");
    }

    /**
     * 单轮决策：把整段对话历史 + 工具清单发给大模型，只取回"这一步怎么走"，不执行任何工具。
     *
     * <p>【白话 Spring AI】ChatClient 是给 ChatModel（底层 HTTP 客户端）套的门面，
     * 链式 API 把 messages（对话历史）+ options（本次调用参数）组装成一个 /chat/completions 报文。
     * 两个关键参数：
     * <ul>
     *   <li>toolCallbacks：告诉模型"你有哪些工具可用"（只下发展示用的 schema 清单）；</li>
     *   <li>internalToolExecutionEnabled(false)：<b>关闭框架的隐式工具执行</b>。Spring AI 默认会
     *       拿到模型 toolCalls 后自己在服务端执行工具再回填、再调一次模型——整个循环藏在框架里，
     *       我们无法插桩（SSE 事件、人工确认、步数熔断都做不了）。关掉后每轮 decide() 只返回
     *       模型的原始决策（AssistantMessage.toolCalls），执行权完全在 AgentRunner 显式循环手里。</li>
     * </ul>
     *
     * @param history 含 system 前缀、历史消息与本轮用户输入的完整上下文
     * @param tools   本轮允许模型选择的工具（前置路由粗筛后的子集）
     * @return 模型单轮决策：要么带 toolCalls（要调工具），要么带文本（最终回答）
     */
    @Override
    public ChatResponse decide(List<Message> history, List<ToolCallback> tools) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false);
        if (modelName != null && modelName.startsWith("qwen3")) {
            options.extraBody(Map.of("enable_thinking", false));
        } else if (modelName != null && modelName.startsWith("deepseek")) {
            options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        return chatClient.prompt()
                .messages(history)
                .options(options.build())
                .call()
                .chatResponse();
    }
}
