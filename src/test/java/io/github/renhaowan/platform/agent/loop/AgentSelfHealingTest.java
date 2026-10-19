package io.github.renhaowan.platform.agent.loop;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.confirm.ConfirmManager;
import io.github.renhaowan.platform.agent.confirm.impl.ConfirmManagerImpl;
import io.github.renhaowan.platform.agent.context.impl.ContextTrimmerImpl;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.impl.HistoryRecallTool;
import io.github.renhaowan.platform.agent.memory.TokenRecorder;
import io.github.renhaowan.platform.agent.memory.mapper.CheckpointMapper;
import io.github.renhaowan.platform.agent.memory.mapper.ChatMessageMapper;
import io.github.renhaowan.platform.agent.sse.AgentEventEmitter;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.impl.InProcessToolGateway;
import io.github.renhaowan.platform.agent.tool.impl.ToolSelectorChain;
import io.github.renhaowan.platform.gateway.forward.ForwardProperties;
import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.registry.ToolAccessService;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 亮点③「ReAct 显式循环与自愈」的贯通证据：整条工具链路（AgentRunner → ToolGateway →
 * 闸门 2 → GenericHttpForwarder → 真实 HTTP 后端）跑在一个 WireMock 业务系统上，
 * 后端首次返回 500、第二次成功——断言循环没有中断，模型的两次调用都拿到了观察。
 */
@ExtendWith(MockitoExtension.class)
class AgentSelfHealingTest {

    private static WireMockServer backend;
    private static int port;

    @Mock
    private Planner planner;

    @Mock
    private DynamicToolRegistry toolRegistry;

    @Mock
    private ToolRegistryService registryService;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private CheckpointMapper checkpointMapper;

    @Mock
    private TokenRecorder tokenRecorder;

    @Mock
    private AgentEventEmitter events;

    private final ConfirmManager confirmManager = new ConfirmManagerImpl();
    private final AgentProperties properties = new AgentProperties();
    private final UserPrincipal staff = new UserPrincipal(1L, "STAFF");

    @BeforeAll
    static void startBackend() {
        backend = new WireMockServer(options().dynamicPort());
        backend.start();
        port = backend.port();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @BeforeEach
    void resetBackend() {
        backend.resetAll();
    }

    private AgentRunner runner() {
        var forwarder = new GenericHttpForwarder(new ForwardProperties());
        var toolAccess = new ToolAccessService(registryService, forwarder);
        var toolGateway = new InProcessToolGateway(toolAccess, properties);
        return new AgentRunner(planner, new ToolSelectorChain(List.of(), Integer.MAX_VALUE),
                toolRegistry, toolGateway,
                new HistoryRecallTool(chatMessageMapper, checkpointMapper),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                sessionService, new ContextTrimmerImpl(6000), confirmManager, tokenRecorder, properties);
    }

    private ToolDefinition createBookingTool() {
        ToolDefinition tool = new ToolDefinition();
        tool.setGatewayId("gw1");
        tool.setName("create_booking");
        tool.setHttpMethod("POST");
        tool.setUrlTemplate("http://localhost:" + port + "/api/v1/bookings");
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"roomId\":{\"type\":\"integer\"}}}");
        tool.setRequireConfirm(0);
        tool.setEnabled(1);
        return tool;
    }

    private static ChatResponse toolCall(String id, String args) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "create_booking", args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    @Test
    void backendFailureIsFedBackAsObservationAndLoopRecovers() {
        // 首次 500（业务时段冲突），二回 200 —— WireMock 场景状态机
        backend.stubFor(post(urlEqualTo("/api/v1/bookings"))
                .inScenario("create-booking")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500).withBody("{\"code\":1002,\"message\":\"时段冲突\"}"))
                .willSetStateTo("retry-ok"));
        backend.stubFor(post(urlEqualTo("/api/v1/bookings"))
                .inScenario("create-booking")
                .whenScenarioStateIs("retry-ok")
                .willReturn(aResponse().withStatus(200).withBody("{\"code\":0,\"data\":{\"id\":7}}")));

        when(sessionService.loadHistory("s1")).thenReturn(List.of());
        when(toolRegistry.callbacks()).thenReturn(List.of());
        when(toolRegistry.requireConfirm(anyString())).thenReturn(false);
        when(registryService.find("gw1", "create_booking")).thenReturn(Optional.of(createBookingTool()));
        when(planner.decide(anyList(), any()))
                .thenReturn(toolCall("c1", "{\"roomId\":2}"),
                        toolCall("c2", "{\"roomId\":2}"),
                        answer("已改到 15:00，预订成功，编号 7"));

        runner().run("s1", "订个周三下午的会议室", events, staff);

        // 两次工具调用：第一次带回 500 观察，第二次带回成功结果——循环未被单点失败中断
        verify(events, times(2)).toolCall(eq("create_booking"), contains("roomId"));
        verify(events).toolResult(eq("create_booking"), contains("500"));
        verify(events).toolResult(eq("create_booking"), contains("\"id\":7"));
        verify(events, never()).error(anyString());
        verify(events).answer("已改到 15:00，预订成功，编号 7");

        // 两次观察都落库（下一轮模型据此决策）
        verify(sessionService, times(2)).appendMessage(eq("s1"), eq(ChatMessage.ROLE_TOOL),
                anyString(), eq("create_booking"));
        // 后端侧同样确认收到两次请求，第二次请求体与第一次一致（模型重试原参数）
        backend.verify(2, postRequestedFor(urlEqualTo("/api/v1/bookings"))
                .withRequestBody(equalToJson("{\"roomId\":2}")));
    }
}
