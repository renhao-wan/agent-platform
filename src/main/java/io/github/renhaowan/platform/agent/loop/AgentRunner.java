package io.github.renhaowan.platform.agent.loop;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.confirm.ConfirmManager;
import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.impl.HistoryRecallTool;
import io.github.renhaowan.platform.agent.memory.TokenRecorder;
import io.github.renhaowan.platform.agent.sse.AgentEventEmitter;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.ToolSelector;
import io.github.renhaowan.platform.agent.tool.ToolGateway;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchTool;
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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ToolGateway toolGateway;
    private final HistoryRecallTool historyRecallTool;
    /** 知识库未启用时该 bean 不存在，故用 ObjectProvider 而非直接注入。 */
    private final ObjectProvider<KnowledgeSearchTool> knowledgeSearchTool;
    private final AgentSessionService sessionService;
    private final ContextTrimmer contextTrimmer;
    private final ConfirmManager confirmManager;
    private final TokenRecorder tokenRecorder;
    private final AgentProperties properties;

    /**
     * ReAct 主循环（全项目核心流程）：
     * <ol>
     *   <li>载入历史（裁剪后）-> 追加本轮用户输入；</li>
     *   <li>每轮 Planner.decide 拿模型决策：无 toolCalls 即回答并结束；
     *       有 toolCalls 则逐个执行——敏感工具先经 ConfirmManager 挂起等用户确认；</li>
     *   <li>工具执行经 ToolGateway 进程内调用工具层（工具层再转发到业务系统）；
     *       执行结果与"用户拒绝"都作为观察回填，模型据此决定下一步；</li>
     *   <li>ToolResponseMessage 必须与带 toolCalls 的 AssistantMessage 成对回填，
     *       否则模型无法看到执行结果（Spring AI 的消息配对约定）。</li>
     * </ol>
     * 失败语义：工具报错/用户拒绝/步数超限都不抛异常——或转述或熔断，对话永不因单点失败中断。
     *
     * @param principal 当前会话用户身份（uid/role）——ThreadLocal 不跨线程池，
     *                  由 ChatController 在 HTTP 线程显式取出传入，驱动执行点鉴权
     */
    public void run(String sessionKey, String userText, AgentEventEmitter events, UserPrincipal principal) {

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

        List<ToolCallback> allTools = toolRegistry.callbacks();
        // 路由责任链（ADR-004）：节点弃权/失败都回落全量，orElse 只是契约兜底
        List<ToolCallback> tools = new ArrayList<>(toolSelector.select(userText, allTools).orElse(allTools));
        // 平台自有内部工具不参与业务路由，始终在册（会话记忆调回，领域中立、角色无关，ADR-005）
        tools.add(historyRecallTool.callback());
        KnowledgeSearchTool knowledgeSearch = knowledgeSearchTool.getIfAvailable();
        if (knowledgeSearch != null) {
            tools.add(knowledgeSearch.callback());
        }

        long startedAt = System.nanoTime();
        int steps = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        String model = planner.modelName();
        if (model == null || model.isBlank()) {
            model = "unknown";
        }

        for (int step = 0; step < properties.getMaxSteps(); step++) {
            ChatResponse response = planner.decide(history, tools);
            steps++;
            var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            int prompt = usage == null ? 0 : usage.getPromptTokens();
            int completion = usage == null ? 0 : usage.getCompletionTokens();
            promptTokens += prompt;
            completionTokens += completion;
            tokenRecorder.record(sessionKey, "decide", model, prompt, completion);

            AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
            if (output == null) {
                events.error("model returned empty response");
                events.done(steps, elapsedMs(startedAt), promptTokens, completionTokens);
                return;
            }

            if (!output.hasToolCalls()) {
                String answer = output.getText() == null ? "" : output.getText();
                events.answer(answer);
                sessionService.appendMessage(sessionKey, ChatMessage.ROLE_ASSISTANT, answer, null);
                events.done(steps, elapsedMs(startedAt), promptTokens, completionTokens);
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
                String denial = null;
                if (toolRegistry.requireConfirm(call.name())) {
                    // 先做只读角色预判：无权限用户不该先点一次注定失败的确认。
                    // 预判只决定"是否弹确认"，真正的边界仍是执行点的闸门 2。
                    denial = toolGateway.denial(call.name(), principal).orElse(null);
                    if (denial == null) {
                        String token = confirmManager.request(sessionKey, call.name(), call.arguments());
                        events.confirmRequest(token, "即将执行敏感操作：" + call.name() + "，请确认");
                        approved = Boolean.TRUE.equals(confirmManager.await(token, properties.getConfirmTimeoutSeconds()));
                    }
                }

                String resultText;
                if (denial != null) {
                    events.toolCall(call.name(), call.arguments() == null ? "{}" : call.arguments());
                    events.toolResult(call.name(), summarize(denial));
                    resultText = denial;
                } else if (!approved) {
                    resultText = "用户拒绝了该操作";
                } else if (historyRecallTool.handles(call.name())) {
                    // 平台内部工具本地执行：不进网关、无确认/角色闸门（只读当前会话自己的历史）
                    events.toolCall(call.name(), call.arguments() == null ? "{}" : call.arguments());
                    resultText = historyRecallTool.execute(sessionKey, call.arguments());
                    events.toolResult(call.name(), summarize(resultText));
                } else if (knowledgeSearch != null && knowledgeSearch.handles(call.name())) {
                    // 知识检索同样是平台内部工具；角色过滤发生在检索 SQL 内（docs/KNOWLEDGE-RAG.md §8）
                    events.toolCall(call.name(), call.arguments() == null ? "{}" : call.arguments());
                    resultText = knowledgeSearch.execute(principal, call.arguments());
                    events.toolResult(call.name(), summarize(resultText));
                } else {
                    events.toolCall(call.name(), call.arguments() == null ? "{}" : call.arguments());
                    // 用户身份随调用下穿工具层：闸门 2（执行点鉴权）在那里校验 required_role
                    resultText = toolGateway.callTool(call.name(), call.arguments(), principal);
                    events.toolResult(call.name(), summarize(resultText));
                }
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText));
                sessionService.appendMessage(sessionKey, ChatMessage.ROLE_TOOL, resultText, call.name());
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }

        events.error("已达最大推理步数 " + properties.getMaxSteps() + "，对话终止");
        events.done(steps, elapsedMs(startedAt), promptTokens, completionTokens);
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
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
