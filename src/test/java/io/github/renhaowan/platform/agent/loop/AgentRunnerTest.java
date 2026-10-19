package io.github.renhaowan.platform.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.confirm.ConfirmManager;
import io.github.renhaowan.platform.agent.confirm.impl.ConfirmManagerImpl;
import io.github.renhaowan.platform.agent.context.impl.ContextTrimmerImpl;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.impl.HistoryRecallTool;
import io.github.renhaowan.platform.agent.memory.mapper.CheckpointMapper;
import io.github.renhaowan.platform.agent.memory.mapper.ChatMessageMapper;
import io.github.renhaowan.platform.agent.memory.TokenRecorder;
import io.github.renhaowan.platform.agent.sse.AgentEventEmitter;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.ToolGateway;
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
    private ToolGateway toolGateway;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private CheckpointMapper checkpointMapper;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private TokenRecorder tokenRecorder;

    @Mock
    private AgentEventEmitter events;

    private final UserPrincipal staff = new UserPrincipal(1L, "STAFF");
    private final ConfirmManager confirmManager = new ConfirmManagerImpl();
    private final AgentProperties properties = new AgentProperties();

    private AgentRunner runner() {
        // 空责任链 + 阈值置最大 = 不路由、全量直通（等价原 NoopToolSelector）
        return new AgentRunner(planner,
                new io.github.renhaowan.platform.agent.tool.impl.ToolSelectorChain(List.of(), Integer.MAX_VALUE),
                toolRegistry, toolGateway, new HistoryRecallTool(chatMessageMapper, checkpointMapper),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                sessionService, new ContextTrimmerImpl(6000), confirmManager, tokenRecorder,
                properties);
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
        when(toolRegistry.requireConfirm(eq("search_rooms"))).thenReturn(false);
        when(toolRegistry.callbacks()).thenReturn(List.of());
        when(toolGateway.callTool(eq("search_rooms"), anyString(), any())).thenReturn("{\"rooms\":3}");

        runner().run("s1", "帮我订周三下午的会议室", events, staff);

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
    void recallToolRunsLocallyAndNeverTouchesBusinessSystems() {
        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        when(planner.decide(anyList(), any()))
                .thenReturn(toolCallResponse("c1", "recallHistory", "{\"keyword\":\"B305\"}"),
                        answerResponse("你之前查过 B305，当时还有空位"));
        when(toolRegistry.callbacks()).thenReturn(List.of());
        ChatMessage hit = new ChatMessage();
        hit.setId(7L);
        hit.setRole(ChatMessage.ROLE_USER);
        hit.setContent("查一下现在 B305 的状态");
        hit.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 1, 10, 0));
        when(chatMessageMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(hit));

        runner().run("s1", "我刚才是不是看过 B305", events, staff);

        // 内部工具本地执行：工具层零调用；工具清单里始终带 recallHistory 广告位
        verify(toolGateway, never()).callTool(anyString(), anyString(), any());
        verify(planner, atLeast(1)).decide(anyList(), argThat(tools -> tools.stream()
                .anyMatch(t -> HistoryRecallTool.TOOL_NAME.equals(t.getToolDefinition().name()))));
        verify(events).toolCall(eq("recallHistory"), contains("B305"));
        verify(sessionService).appendMessage(eq("s1"), eq(ChatMessage.ROLE_TOOL),
                contains("B305"), eq("recallHistory"));
        verify(events).answer("你之前查过 B305，当时还有空位");
    }

    @Test
    void sensitiveToolWaitsForUserApprovalThenExecutes() throws Exception {
        properties.setConfirmTimeoutSeconds(5);
        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        when(planner.decide(anyList(), any()))
                .thenReturn(toolCallResponse("c1", "cancel_booking", "{\"id\":42}"),
                        answerResponse("已取消"));
        when(toolRegistry.requireConfirm(eq("cancel_booking"))).thenReturn(true);
        when(toolRegistry.callbacks()).thenReturn(List.of());
        when(toolGateway.callTool(eq("cancel_booking"), anyString(), any())).thenReturn("{\"status\":\"CANCELLED\"}");

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

        runner().run("s1", "取消刚才那个预订", events, staff);

        verify(toolGateway).callTool(eq("cancel_booking"), contains("42"), any());
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
        when(toolRegistry.requireConfirm(eq("cancel_booking"))).thenReturn(true);
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

        runner().run("s1", "算了别取消", events, staff);

        verify(toolGateway, never()).callTool(anyString(), anyString(), any());
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
        when(toolGateway.callTool(anyString(), anyString(), any())).thenReturn("{}");

        runner().run("s1", "随便查查", events, staff);

        verify(events).error(contains("最大推理步数"));
        verify(events).done();
        verify(toolGateway, times(3)).callTool(anyString(), anyString(), any());
    }
}
