package io.github.renhaowan.platform.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.entity.ChatSession;
import io.github.renhaowan.platform.agent.memory.entity.LlmUsage;
import io.github.renhaowan.platform.agent.memory.impl.AgentSessionServiceImpl;
import io.github.renhaowan.platform.agent.memory.mapper.ChatMessageMapper;
import io.github.renhaowan.platform.agent.memory.mapper.ChatSessionMapper;
import io.github.renhaowan.platform.agent.memory.mapper.CheckpointMapper;
import io.github.renhaowan.platform.agent.memory.mapper.LlmUsageMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 会话列表与归属校验：标题取首条用户消息、空会话兜底、归属规则。 */
@ExtendWith(MockitoExtension.class)
class AgentSessionServiceImplTest {

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private CheckpointMapper checkpointMapper;
    @Mock
    private LlmUsageMapper usageMapper;

    @InjectMocks
    private AgentSessionServiceImpl service;

    @Test
    void listSessionsBuildsTitleFromFirstUserMessage() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session("sk-new", 7L, LocalDateTime.of(2026, 10, 20, 10, 0)),
                session("sk-old", 7L, LocalDateTime.of(2026, 10, 19, 9, 0))));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("sk-new", ChatMessage.ROLE_USER, "帮我订明天下午的会议室"),
                message("sk-old", ChatMessage.ROLE_USER, "查一下我的报销单"),
                message("sk-old", ChatMessage.ROLE_USER, "再看一下第二笔")));

        List<AgentSessionService.SessionSummary> sessions = service.listSessions(7L);

        assertThat(sessions).extracting(AgentSessionService.SessionSummary::sessionKey)
                .containsExactly("sk-new", "sk-old");
        assertThat(sessions).extracting(AgentSessionService.SessionSummary::title)
                .containsExactly("帮我订明天下午的会议室", "查一下我的报销单");
    }

    @Test
    void listSessionsFallsBackToNewChatWhenNoUserMessage() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session("sk-empty", 7L, LocalDateTime.of(2026, 10, 20, 10, 0))));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listSessions(7L))
                .extracting(AgentSessionService.SessionSummary::title)
                .containsExactly("新对话");
    }

    @Test
    void listSessionsTruncatesLongTitle() {
        String longText = "帮我查一下上周提交的那几笔还没有审批通过的报销单分别是哪些";
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session("sk-long", 7L, LocalDateTime.of(2026, 10, 20, 10, 0))));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("sk-long", ChatMessage.ROLE_USER, longText)));

        String title = service.listSessions(7L).get(0).title();

        assertThat(title).hasSize(25).endsWith("…");
        assertThat(longText).startsWith(title.substring(0, 24));
    }

    @Test
    void listSessionsDoesNotTouchStorageForAnonymousUser() {
        assertThat(service.listSessions(null)).isEmpty();
        verifyNoInteractions(sessionMapper, messageMapper);
    }

    @Test
    void ownsSessionAllowsOnlyOwner() {
        when(sessionMapper.selectOne(any())).thenReturn(session("sk-mine", 7L, null));

        assertThat(service.ownsSession("sk-mine", 7L)).isTrue();
        assertThat(service.ownsSession("sk-mine", 8L)).isFalse();
    }

    @Test
    void ownsSessionAllowsLegacyRowWithoutOwner() {
        when(sessionMapper.selectOne(any())).thenReturn(session("sk-legacy", null, null));

        assertThat(service.ownsSession("sk-legacy", 99L)).isTrue();
    }

    @Test
    void ownsSessionAllowsUnknownSessionKey() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        assertThat(service.ownsSession("sk-missing", 7L)).isTrue();
    }

    @Test
    void deleteSessionRemovesMessagesCheckpointAndSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session("sk-mine", 7L, null));

        assertThat(service.deleteSession("sk-mine", 7L)).isTrue();

        verify(messageMapper).delete(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class));
        verify(checkpointMapper).delete(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class));
        verify(sessionMapper).delete(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class));
    }

    @Test
    void deleteSessionRefusesForeignSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session("sk-other", 8L, null));

        assertThat(service.deleteSession("sk-other", 7L)).isFalse();
        verifyNoInteractions(messageMapper, checkpointMapper);
    }

    @Test
    void deleteSessionReturnsFalseWhenMissing() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        assertThat(service.deleteSession("sk-missing", 7L)).isFalse();
        verifyNoInteractions(messageMapper, checkpointMapper);
    }

    @Test
    void usageOfSumsPromptAndCompletionTokens() {
        when(usageMapper.selectList(any())).thenReturn(List.of(
                usage(120, 30), usage(200, 80)));

        AgentSessionService.UsageSummary usage = service.usageOf("sk-mine");

        assertThat(usage.promptTokens()).isEqualTo(320);
        assertThat(usage.completionTokens()).isEqualTo(110);
        assertThat(usage.totalTokens()).isEqualTo(430);
        assertThat(usage.calls()).isEqualTo(2);
    }

    @Test
    void usageOfEmptySessionReturnsZeros() {
        when(usageMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.usageOf("sk-empty"))
                .isEqualTo(new AgentSessionService.UsageSummary(0, 0, 0, 0));
    }

    private LlmUsage usage(int prompt, int completion) {
        LlmUsage usage = new LlmUsage();
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        return usage;
    }

    private ChatSession session(String key, Long userId, LocalDateTime createdAt) {
        ChatSession session = new ChatSession();
        session.setSessionKey(key);
        session.setUserId(userId);
        session.setCreatedAt(createdAt);
        return session;
    }

    private ChatMessage message(String key, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionKey(key);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
