package io.github.renhaowan.platform.agent.loop;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议规划器（DashScope compatible-mode）。
 * 关键：internalToolExecutionEnabled(false) 关闭框架内隐式工具执行，
 * 每轮只取回模型的 toolCalls 决策，执行权在 AgentRunner（插桩：SSE 事件、确认、步数上限）。
 */
@Component
public class OpenAiPlanner implements Planner {

    private final ChatClient chatClient;

    public OpenAiPlanner(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public ChatResponse decide(List<Message> history, List<ToolCallback> tools) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false)
                .build();
        return chatClient.prompt()
                .messages(history)
                .options(options)
                .call()
                .chatResponse();
    }
}
