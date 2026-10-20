package io.github.renhaowan.platform.agent.memory;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.agent.auth.JwtService;
import io.github.renhaowan.platform.agent.auth.RbacService;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 历史会话端点（HTTP 层）：鉴权范围、只列当前用户的会话、他人会话 403、
 * 只回放用户与最终回答（工具轨迹不进入历史）。
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "platform.rbac.seed-on-startup=false"
})
@AutoConfigureMockMvc
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AgentSessionService sessionService;

    @MockitoBean
    private RbacService rbacService;

    @BeforeEach
    void authorizeStaff() {
        when(rbacService.loadPrincipal(7L)).thenReturn(Optional.of(new UserPrincipal(7L, "STAFF", Set.of())));
    }

    @Test
    void sessionsWithoutTokenRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionsAreListedForCurrentUserOnly() throws Exception {
        when(sessionService.listSessions(7L)).thenReturn(List.of(
                new AgentSessionService.SessionSummary("sk-1", "帮我订明天下午的会议室", LocalDateTime.of(2026, 10, 20, 10, 0))));

        mockMvc.perform(get("/api/v1/sessions")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionKey").value("sk-1"))
                .andExpect(jsonPath("$[0].title").value("帮我订明天下午的会议室"))
                .andExpect(jsonPath("$[0].createdAt").exists());

        verify(sessionService).listSessions(7L);
    }

    @Test
    void messagesOfForeignSessionRejectedWith403() throws Exception {
        when(sessionService.ownsSession("sk-owned-by-other", 7L)).thenReturn(false);

        mockMvc.perform(get("/api/v1/sessions/sk-owned-by-other/messages")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isForbidden());

        verify(sessionService, never()).loadHistory("sk-owned-by-other");
    }

    @Test
    void messagesOnlyContainUserAndAssistantTurns() throws Exception {
        when(sessionService.ownsSession("sk-mine", 7L)).thenReturn(true);
        when(sessionService.loadHistory("sk-mine")).thenReturn(List.of(
                message(ChatMessage.ROLE_USER, "帮我查一下我的报销单"),
                message(ChatMessage.ROLE_TOOL, "{\"code\":0}"),
                message(ChatMessage.ROLE_ASSISTANT, "你有一笔待提交的报销单。")));

        mockMvc.perform(get("/api/v1/sessions/sk-mine/messages")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value(ChatMessage.ROLE_USER))
                .andExpect(jsonPath("$[1].role").value(ChatMessage.ROLE_ASSISTANT))
                .andExpect(jsonPath("$[1].content").value("你有一笔待提交的报销单。"))
                .andExpect(jsonPath("$[1].createdAt").exists());
    }

    @Test
    void usageWithoutTokenRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/sk-mine/usage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usageOfForeignSessionRejectedWith403() throws Exception {
        when(sessionService.ownsSession("sk-other", 7L)).thenReturn(false);

        mockMvc.perform(get("/api/v1/sessions/sk-other/usage")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void usageReturnsSessionTotals() throws Exception {
        when(sessionService.ownsSession("sk-mine", 7L)).thenReturn(true);
        when(sessionService.usageOf("sk-mine"))
                .thenReturn(new AgentSessionService.UsageSummary(320, 110, 430, 2));

        mockMvc.perform(get("/api/v1/sessions/sk-mine/usage")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptTokens").value(320))
                .andExpect(jsonPath("$.completionTokens").value(110))
                .andExpect(jsonPath("$.totalTokens").value(430))
                .andExpect(jsonPath("$.calls").value(2));
    }

    @Test
    void deleteWithoutTokenRejectedWith401() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/sk-mine"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteForeignSessionRejectedWith403() throws Exception {
        when(sessionService.ownsSession("sk-other", 7L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/sessions/sk-other")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isForbidden());

        verify(sessionService, never()).deleteSession("sk-other", 7L);
    }

    @Test
    void deleteMissingSessionReturns404() throws Exception {
        when(sessionService.ownsSession("sk-gone", 7L)).thenReturn(true);
        when(sessionService.deleteSession("sk-gone", 7L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/sessions/sk-gone")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOwnSessionReturns204() throws Exception {
        when(sessionService.ownsSession("sk-mine", 7L)).thenReturn(true);
        when(sessionService.deleteSession("sk-mine", 7L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/sessions/sk-mine")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isNoContent());
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 10, 20, 10, 0));
        return message;
    }
}
