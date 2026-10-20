package io.github.renhaowan.platform.agent.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
        when(sessionService.ownsSession("sk-owned-by-other", 1L)).thenReturn(false);

        MvcResult result = mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + jwtService.issue(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"sk-owned-by-other\",\"message\":\"继续\"}"))
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
        when(sessionService.ownsSession("sk-mine", 1L)).thenReturn(true);

        MvcResult result = mockMvc.perform(post("/api/v1/chat")
                        .header("Authorization", "Bearer " + jwtService.issue(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"sk-mine\",\"message\":\"继续\"}"))
                .andExpect(status().isOk())
                .andReturn();
        result = settleAsync(result);

        assertThat(result.getResponse().getContentAsString()).contains("event:session");
        verify(agentRunner).run(org.mockito.ArgumentMatchers.eq("sk-mine"), org.mockito.ArgumentMatchers.eq("继续"),
                any(), org.mockito.ArgumentMatchers.eq(new UserPrincipal(1L, "STAFF", Set.of())));
    }

    private MvcResult settleAsync(MvcResult result) throws Exception {
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        }
        return result;
    }
}
