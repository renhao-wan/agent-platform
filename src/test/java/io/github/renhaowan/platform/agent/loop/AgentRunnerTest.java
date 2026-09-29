package io.github.renhaowan.platform.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.confirm.ConfirmManager;
import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.ChatMessage;
import io.github.renhaowan.platform.agent.memory.TokenRecorder;
import io.github.renhaowan.platform.agent.sse.AgentEventEmitter;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.McpGatewayClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

@ExtendWith(MockitoExtension.class)
class AgentRunnerTest {

    @Mock
    private Planner planner;

    @Mock
    private DynamicToolRegistry toolRegistry;

    @Mock
    private McpGatewayClient gatewayClient;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private TokenRecorder tokenRecorder;

    @Mock
    private AgentEventEmitter events;

    private final ConfirmManager confirmManager = new ConfirmManager();
    private final AgentProperties properties = new AgentProperties();

    private AgentRunner runner() {
        return new AgentRunner(planner, toolRegistry, gatewayClient, sessionService,
                new ContextTrimmer(6000), confirmManager, tokenRecorder,
                new RuleIntentClassifier(), properties);
    }

    private ChatResponse toolCallResponse(String id, String name, String args) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse answerResponse(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    @Test
    void toolCallThenAnswerProducesFullEventSequence() {
        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        when(planner.decide(anyList(), any()))
                .thenReturn(toolCallResponse("c1", "search_rooms", "{\"date\":\"2026-10-01\"}"),
                        answerResponse("已为你找到 3 间可用会议室"));
        when(toolRegistry.requireConfirm("search_rooms")).thenReturn(false);
        when(toolRegistry.callbacks()).thenReturn(List.of());
        when(gatewayClient.callTool(eq("search_rooms"), anyString())).thenReturn("{\"rooms\":3}");

        runner().run("s1", "帮我订周三下午的会议室", events);

        verify(events).intent(IntentClassifier.LABEL_ROOM_BOOKING);
        verify(events).plan(List.of("search_rooms"));
        verify(events).toolCall(eq("search_rooms"), contains("2026-10-01"));
        verify(events).toolResult(eq("search_rooms"), contains("rooms"));
        verify(events).answer("已为你找到 3 间可用会议室");
        verify(events).done();
        verify(sessionService).appendMessage(eq("s1"), eq(ChatMessage.ROLE_USER), anyString(), eq((String) null));
        verify(sessionService).appendMessage(eq("s1"), eq(ChatMessage.ROLE_TOOL), contains("rooms"), eq("search_rooms"));
        verify(sessionService).appendMessage(eq("s1"), eq(ChatMessage.ROLE_ASSISTANT), anyString(), eq((String) null));
        verify(tokenRecorder, times(2)).record(eq("s1"), eq("decide"), anyString(), any(), any());
    }

    @Test
    void sensitiveToolWaitsForUserApprovalThenExecutes() throws Exception {
        properties.setConfirmTimeoutSeconds(5);
        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        when(planner.decide(anyList(), any()))
                .thenReturn(toolCallResponse("c1", "cancel_booking", "{\"id\":42}"),
                        answerResponse("已取消"));
        when(toolRegistry.requireConfirm("cancel_booking")).thenReturn(true);
        when(toolRegistry.callbacks()).thenReturn(List.of());
        when(gatewayClient.callTool(eq("cancel_booking"), anyString())).thenReturn("{\"status\":\"CANCELLED\"}");

        // confirm_request 事件发出后，异步线程模拟用户点击"同意"
        org.mockito.Mockito.doAnswer(invocation -> {
            String token = invocation.getArgument(0);
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    confirmManager.complete(token, true);
                } catch (InterruptedException ignored) {
                }
            }).start();
            return null;
        }).when(events).confirmRequest(anyString(), anyString());

        runner().run("s1", "取消刚才那个预订", events);

        verify(gatewayClient).callTool(eq("cancel_booking"), contains("42"));
        verify(events).answer("已取消");
        verify(events).done();
    }

    @Test
    void userRejectedSensitiveToolSkipsExecution() throws Exception {
        properties.setConfirmTimeoutSeconds(5);
        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        when(planner.decide(anyList(), any()))
                .thenReturn(toolCallResponse("c1", "cancel_booking", "{\"id\":42}"),
                        answerResponse("好的，未取消"));
        when(toolRegistry.requireConfirm("cancel_booking")).thenReturn(true);
        when(toolRegistry.callbacks()).thenReturn(List.of());

        org.mockito.Mockito.doAnswer(invocation -> {
            String token = invocation.getArgument(0);
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    confirmManager.complete(token, false);
                } catch (InterruptedException ignored) {
                }
            }).start();
            return null;
        }).when(events).confirmRequest(anyString(), anyString());

        runner().run("s1", "算了别取消", events);

        verify(gatewayClient, never()).callTool(anyString(), anyString());
        verify(sessionService).appendMessage(eq("s1"), eq(ChatMessage.ROLE_TOOL),
                contains("拒绝"), eq("cancel_booking"));
        verify(events).answer("好的，未取消");
    }

    @Test
    void exceedsMaxStepsTerminatesWithError() {
        properties.setMaxSteps(3);
        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        AtomicInteger counter = new AtomicInteger();
        when(planner.decide(anyList(), any()))
                .thenAnswer(inv -> toolCallResponse("c" + counter.incrementAndGet(), "search_rooms", "{}"));
        when(toolRegistry.requireConfirm(anyString())).thenReturn(false);
        when(toolRegistry.callbacks()).thenReturn(List.of());
        when(gatewayClient.callTool(anyString(), anyString())).thenReturn("{}");

        runner().run("s1", "随便查查", events);

        verify(events).error(contains("最大推理步数"));
        verify(events).done();
        verify(gatewayClient, times(3)).callTool(anyString(), anyString());
    }
}
