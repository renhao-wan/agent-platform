package io.github.renhaowan.platform.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.memory.entity.Checkpoint;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.impl.HistoryRecallTool;
import io.github.renhaowan.platform.agent.memory.mapper.CheckpointMapper;
import io.github.renhaowan.platform.agent.memory.mapper.ChatMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

/**
 * 会话记忆调回内部工具契约测试：schema 广告位、跨表合并时序、参数防御、超限截断、故障降级。
 */
@ExtendWith(MockitoExtension.class)
class HistoryRecallToolTest {

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private CheckpointMapper checkpointMapper;

    private HistoryRecallTool tool() {
        return new HistoryRecallTool(chatMessageMapper, checkpointMapper);
    }

    private static ChatMessage message(long id, String role, String content, LocalDateTime at) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(at);
        return m;
    }

    private static Checkpoint checkpoint(long id, String summary, LocalDateTime at) {
        Checkpoint c = new Checkpoint();
        c.setId(id);
        c.setSummary(summary);
        c.setCreatedAt(at);
        return c;
    }

    @Test
    void callbackAdvertisesRecallHistoryWithSchema() {
        ToolCallback callback = tool().callback();

        assertThat(callback.getToolDefinition().name()).isEqualTo("recallHistory");
        assertThat(callback.getToolDefinition().description()).contains("检索");
        assertThat(callback.getToolDefinition().inputSchema()).contains("keyword");
        assertThat(tool().handles("recallHistory")).isTrue();
        assertThat(tool().handles("create")).isFalse();
    }

    @Test
    void mergesMessagesAndCheckpointsInChronologicalOrder() {
        when(chatMessageMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                message(9L, ChatMessage.ROLE_USER, "查一下现在 B305 的状态", LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(checkpointMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                checkpoint(3L, "早期对话共 18 条消息（含 5 次工具调用）已被压缩", LocalDateTime.of(2026, 10, 1, 9, 0))));

        String out = tool().execute("s1", "{\"keyword\":\"B305\"}");

        assertThat(out).contains("检索结果").contains("[里程碑 #3]").contains("[消息 #9][USER]");
        assertThat(out.indexOf("[里程碑 #3]")).isLessThan(out.indexOf("[消息 #9]"));
    }

    @Test
    void noMatchReturnsRetryHint() {
        String out = tool().execute("s1", "{\"keyword\":\"不存在的东西\"}");

        assertThat(out).contains("未在当前会话历史中检索到");
    }

    @Test
    void invalidArgumentsReturnUsageHint() {
        assertThat(tool().execute("s1", "not-json")).contains("参数无效");
        assertThat(tool().execute("s1", "{\"keyword\":\"   \"}")).contains("参数无效");
        assertThat(tool().execute("s1", null)).contains("参数无效");
    }

    @Test
    void toolRoleMessageRendersToolName() {
        ChatMessage toolMsg = message(11L, ChatMessage.ROLE_TOOL, "{\"id\":12,\"status\":\"OK\"}",
                LocalDateTime.of(2026, 10, 1, 10, 5));
        toolMsg.setToolName("create");
        when(chatMessageMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(toolMsg));

        String out = tool().execute("s1", "{\"keyword\":\"id\"}");

        assertThat(out).contains("[消息 #11][TOOL → create]");
    }

    @Test
    void overLimitResultsAreTruncatedWithHint() {
        List<ChatMessage> hits = IntStream.rangeClosed(1, 9)
                .mapToObj(i -> message(i, ChatMessage.ROLE_USER, "包含关键词的记录 " + i,
                        LocalDateTime.of(2026, 10, 1, 10, i)))
                .toList();
        when(chatMessageMapper.selectList(any(QueryWrapper.class))).thenReturn(hits);

        String out = tool().execute("s1", "{\"keyword\":\"关键词\"}");

        assertThat(out).contains("记录 8").doesNotContain("记录 9").contains("另有 1 条未展示");
    }

    @Test
    void mapperFailureDegradesToSoftHint() {
        when(chatMessageMapper.selectList(any(QueryWrapper.class))).thenThrow(new RuntimeException("db down"));

        String out = tool().execute("s1", "{\"keyword\":\"B305\"}");

        assertThat(out).contains("暂不可用");
    }
}
