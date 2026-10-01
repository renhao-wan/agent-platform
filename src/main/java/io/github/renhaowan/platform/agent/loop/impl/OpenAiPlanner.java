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
 * OpenAI 兼容协议规划器（DashScope compatible-mode）。
 * 关键：internalToolExecutionEnabled(false) 关闭框架内隐式工具执行，
 * 每轮只取回模型的 toolCalls 决策，执行权在 AgentRunner（插桩：SSE 事件、确认、步数上限）。
 * <p>
 * 兼容性：qwen3 开源思考混合模型在非流式调用下强制要求 enable_thinking=false，
 * 通过 extraBody 透传（其他模型不受影响）——免费档 qwen3-8b 因此可用。
 */
@Component
public class OpenAiPlanner implements Planner {

    private final ChatClient chatClient;
    private final String modelName;

    public OpenAiPlanner(ChatModel chatModel, Environment environment) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.modelName = environment.getProperty("spring.ai.openai.chat.options.model", "");
    }

    @Override
    public ChatResponse decide(List<Message> history, List<ToolCallback> tools) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false);
        if (modelName != null && modelName.startsWith("qwen3")) {
            options.extraBody(Map.of("enable_thinking", false));
        }
        return chatClient.prompt()
                .messages(history)
                .options(options.build())
                .call()
                .chatResponse();
    }
}
