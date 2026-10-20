package io.github.renhaowan.platform.agent.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.agent.auth.JwtService;
import io.github.renhaowan.platform.agent.auth.RbacService;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.loop.AgentRunner;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 对话端点鉴权与会话归属（HTTP 层）：
 * 缺 token / token 解析失败 → 401；续聊他人会话 → SSE 错误事件（403 语义）且不启动循环。
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "platform.rbac.seed-on-startup=false"
})
@AutoConfigureMockMvc
class ChatAuthFilterTest {

    /** 会话 key 由服务端生成 UUID，契约上不接受自造字符串（见 ChatController.ChatRequest）。 */
    private static final String FOREIGN_SESSION = "11111111-1111-4111-8111-111111111111";
    private static final String OWN_SESSION = "22222222-2222-4222-8222-222222222222";
    /** 并发锁测试专用，避免与其它用例共用会话锁。 */
    private static final String LOCK_SESSION = "33333333-3333-4333-8333-333333333333";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AgentRunner agentRunner;

    @MockitoBean
    private AgentSessionService sessionService;

    @MockitoBean
    private RbacService rbacService;

    @BeforeEach
    void authorizeStaff() {
        when(rbacService.loadPrincipal(1L)).thenReturn(Optional.of(new UserPrincipal(1L, "STAFF", Set.of())));
    }

    @Test
    void chatWithoutTokenRejectedWith401() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void chatWithGarbageTokenRejectedWith401() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer not-a-real-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid or expired token"));
    }

    @Test
    void chatWithForeignSecretTokenRejectedWith401() throws Exception {
        // 用别的密钥签出的 token（伪造场景）：验签失败 → 401
        String forged = new JwtService("attacker-secret-0123456789abcdef0123456789abcdef")
                .issue(1L);

        mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + forged)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatContinuationOnForeignSessionRejectedWith403Event() throws Exception {
        when(sessionService.ownsSession(FOREIGN_SESSION, 1L)).thenReturn(false);

        MvcResult result = mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + jwtService.issue(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"" + FOREIGN_SESSION + "\",\"message\":\"继续\"}"))
                .andExpect(status().isOk())
                .andReturn();
        result = settleAsync(result);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:error").contains("403");
        // 归属校验失败：不进入对话循环，也不产生新会话
        verify(agentRunner, never()).run(anyString(), anyString(), any(), any());
        verify(sessionService, never()).createSession(any());
    }

    @Test
    void chatContinuationOnOwnSessionPassesOwnership() throws Exception {
        when(sessionService.ownsSession(OWN_SESSION, 1L)).thenReturn(true);

        MvcResult result = mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + jwtService.issue(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"" + OWN_SESSION + "\",\"message\":\"继续\"}"))
                .andExpect(status().isOk())
                .andReturn();
        result = settleAsync(result);

        assertThat(result.getResponse().getContentAsString()).contains("event:session");
        verify(agentRunner).run(org.mockito.ArgumentMatchers.eq(OWN_SESSION), org.mockito.ArgumentMatchers.eq("继续"),
                any(), org.mockito.ArgumentMatchers.eq(new UserPrincipal(1L, "STAFF", Set.of())));
    }

    @Test
    void concurrentChatOnSameSessionIsRejectedWhileFirstIsRunning() throws Exception {
        when(sessionService.ownsSession(LOCK_SESSION, 1L)).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(agentRunner).run(eq(LOCK_SESSION), anyString(), any(), any());

        MvcResult first = mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + jwtService.issue(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"" + LOCK_SESSION + "\",\"message\":\"第一条\"}"))
                .andExpect(status().isOk())
                .andReturn();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            MvcResult second = mockMvc.perform(post("/api/v1/chat")
                            .header("Authorization", "Bearer " + jwtService.issue(1L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionKey\":\"" + LOCK_SESSION + "\",\"message\":\"第二条\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            // 后来者被拒绝，且没有第二个循环在跑
            assertThat(second.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .contains("正在处理中");
            verify(agentRunner, times(1)).run(anyString(), anyString(), any(), any());
        } finally {
            release.countDown();
            settleAsync(first);
        }
    }

    private MvcResult settleAsync(MvcResult result) throws Exception {
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        }
        return result;
    }
}
