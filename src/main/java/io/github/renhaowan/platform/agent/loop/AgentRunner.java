package io.github.renhaowan.platform.agent.loop;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.confirm.ConfirmManager;
import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.ChatMessage;
import io.github.renhaowan.platform.agent.memory.TokenRecorder;
import io.github.renhaowan.platform.agent.sse.AgentEventEmitter;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.ToolSelector;
import io.github.renhaowan.platform.agent.tool.McpGatewayClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * ReAct 显式循环（规划 → 工具执行 → 观察 → 反思 → 回答）。
 * 与 OpenAiPlanner 的隐式执行相反，这里每一步都被显式驱动：
 * SSE 分阶段事件、敏感操作确认挂起、步数上限、轨迹落库全部发生在循环内。
 */
@RequiredArgsConstructor
@Service
public class AgentRunner {

    private static final String SYSTEM_PROMPT = loadPrompt();

    private final Planner planner;
    private final ToolSelector toolSelector;
    private final DynamicToolRegistry toolRegistry;
    private final McpGatewayClient gatewayClient;
    private final AgentSessionService sessionService;
    private final ContextTrimmer contextTrimmer;
    private final ConfirmManager confirmManager;
    private final TokenRecorder tokenRecorder;
    private final AgentProperties properties;

    /**
     * ReAct 主循环（全项目核心流程）：
     * <ol>
     *   <li>意图分类（规则通道，仅用于前端事件展示）-> 载入历史（裁剪后）-> 追加本轮用户输入；</li>
     *   <li>每轮 Planner.decide 拿模型决策：无 toolCalls 即回答并结束；
     *       有 toolCalls 则逐个执行——敏感工具先经 ConfirmManager 挂起等用户确认；</li>
     *   <li>工具执行经 McpGatewayClient 自环调用 MCP 网关（网关再转发到业务系统）；
     *       执行结果与"用户拒绝"都作为观察回填，模型据此决定下一步；</li>
     *   <li>ToolResponseMessage 必须与带 toolCalls 的 AssistantMessage 成对回填，
     *       否则模型无法看到执行结果（Spring AI 的消息配对约定）。</li>
     * </ol>
     * 失败语义：工具报错/用户拒绝/步数超限都不抛异常——或转述或熔断，对话永不因单点失败中断。
     */
    public void run(String sessionKey, String userText, AgentEventEmitter events) {

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(resolveSystemPrompt()));
        for (ChatMessage message : sessionService.loadHistory(sessionKey)) {
            history.add(toSpringMessage(message));
        }
        history.add(new UserMessage(userText));
        sessionService.appendMessage(sessionKey, ChatMessage.ROLE_USER, userText, null);

        ContextTrimmer.TrimResult trim = contextTrimmer.trim(history);
        history = new ArrayList<>(trim.messages());
        if (trim.trimmed() && trim.summary() != null) {
            sessionService.saveCheckpoint(sessionKey, trim.summary());
        }

        List<ToolCallback> tools = toolSelector.select(userText, toolRegistry.callbacks());

        for (int step = 0; step < properties.getMaxSteps(); step++) {
            ChatResponse response = planner.decide(history, tools);
            var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            tokenRecorder.record(sessionKey, "decide",
                    response.getMetadata() == null ? "unknown" : "openai-compatible",
                    usage == null ? 0 : usage.getPromptTokens(),
                    usage == null ? 0 : usage.getCompletionTokens());

            AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
            if (output == null) {
                events.error("model returned empty response");
                events.done();
                return;
            }

            if (!output.hasToolCalls()) {
                String answer = output.getText() == null ? "" : output.getText();
                events.answer(answer);
                sessionService.appendMessage(sessionKey, ChatMessage.ROLE_ASSISTANT, answer, null);
                events.done();
                return;
            }

            List<AssistantMessage.ToolCall> calls = output.getToolCalls();
            if (step == 0) {
                events.plan(calls.stream().map(AssistantMessage.ToolCall::name).toList());
            }
            history.add(output);

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                boolean approved = true;
                if (toolRegistry.requireConfirm(call.name())) {
                    String token = confirmManager.request(sessionKey, call.name(), call.arguments());
                    events.confirmRequest(token, "即将执行敏感操作：" + call.name() + "，请确认");
                    approved = Boolean.TRUE.equals(confirmManager.await(token, properties.getConfirmTimeoutSeconds()));
                }

                String resultText;
                if (!approved) {
                    resultText = "用户拒绝了该操作";
                } else {
                    events.toolCall(call.name(), call.arguments() == null ? "{}" : call.arguments());
                    resultText = gatewayClient.callTool(call.name(), call.arguments());
                    events.toolResult(call.name(), summarize(resultText));
                }
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText));
                sessionService.appendMessage(sessionKey, ChatMessage.ROLE_TOOL, resultText, call.name());
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }

        events.error("已达最大推理步数 " + properties.getMaxSteps() + "，对话终止");
        events.done();
    }

    private String resolveSystemPrompt() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String weekday = switch (today.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
        return SYSTEM_PROMPT
                .replace("{{CURRENT_DATE}}", today.toString())
                .replace("{{CURRENT_WEEKDAY}}", weekday);
    }

    private String summarize(String resultText) {
        String flat = resultText.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    private Message toSpringMessage(ChatMessage message) {
        return switch (message.getRole()) {
            case ChatMessage.ROLE_USER -> new UserMessage(message.getContent());
            case ChatMessage.ROLE_ASSISTANT -> new AssistantMessage(message.getContent());
            default -> new SystemMessage("工具 " + message.getToolName() + " 结果：" + message.getContent());
        };
    }

    private static String loadPrompt() {
        try {
            return new String(AgentRunner.class.getResourceAsStream("/prompts/system.md")
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("system prompt missing", e);
        }
    }
}
