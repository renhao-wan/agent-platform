package io.github.renhaowan.platform.agent.memory;

import io.github.renhaowan.platform.agent.auth.UserContext;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史会话入口：列出当前用户的会话、按会话取回可渲染的消息。
 * 会话数据以 user_id 归属为边界，校验与续聊共用 {@link AgentSessionService#ownsSession}。
 */
@RequiredArgsConstructor
@RestController
public class SessionController {

    /** 只回 USER / ASSISTANT 两类消息：工具调用与结果属于本轮执行轨迹，不进入历史对话。 */
    public record SessionMessage(String role, String content, LocalDateTime createdAt) {
    }

    private final AgentSessionService sessionService;

    @GetMapping("/api/v1/sessions")
    public List<AgentSessionService.SessionSummary> sessions() {
        UserPrincipal principal = UserContext.get();
        return sessionService.listSessions(principal.uid());
    }

    @GetMapping("/api/v1/sessions/{sessionKey}/messages")
    public ResponseEntity<List<SessionMessage>> messages(@PathVariable String sessionKey) {
        UserPrincipal principal = UserContext.get();
        if (!sessionService.ownsSession(sessionKey, principal.uid())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<SessionMessage> messages = sessionService.loadHistory(sessionKey).stream()
                .filter(message -> ChatMessage.ROLE_USER.equals(message.getRole())
                        || ChatMessage.ROLE_ASSISTANT.equals(message.getRole()))
                .map(message -> new SessionMessage(message.getRole(), message.getContent(), message.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(messages);
    }

    /** 会话累计用量：与历史读取同一套归属校验，不暴露他人会话的成本信息。 */
    @GetMapping("/api/v1/sessions/{sessionKey}/usage")
    public ResponseEntity<AgentSessionService.UsageSummary> usage(@PathVariable String sessionKey) {
        UserPrincipal principal = UserContext.get();
        if (!sessionService.ownsSession(sessionKey, principal.uid())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(sessionService.usageOf(sessionKey));
    }

    @DeleteMapping("/api/v1/sessions/{sessionKey}")
    public ResponseEntity<Void> delete(@PathVariable String sessionKey) {
        UserPrincipal principal = UserContext.get();
        if (!sessionService.ownsSession(sessionKey, principal.uid())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return sessionService.deleteSession(sessionKey, principal.uid())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
