package io.github.renhaowan.platform.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.renhaowan.platform.agent.context.impl.ContextTrimmerImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ContextTrimmerTest {

    private final ContextTrimmer trimmer = new ContextTrimmerImpl(100);

    private List<Message> longHistory() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        for (int i = 0; i < 10; i++) {
            messages.add(new UserMessage("用户消息" + i + "：" + "长".repeat(200)));
        }
        return messages;
    }

    @Test
    void keepsHistoryWhenUnderThreshold() {
        List<Message> history = List.of(new SystemMessage("系统"), new UserMessage("你好"));

        var result = trimmer.trim(history);

        assertThat(result.trimmed()).isFalse();
        assertThat(result.messages()).hasSize(2);
    }

    @Test
    void trimsMiddleAndKeepsSystemPrefixPlusRecent() {
        List<Message> history = longHistory();

        var result = trimmer.trim(history);

        assertThat(result.trimmed()).isTrue();
        List<Message> trimmed = result.messages();
        // system 前缀 + 摘要 + 最近 6 条
        assertThat(trimmed.size()).isEqualTo(1 + 1 + ContextTrimmer.KEEP_RECENT);
        assertThat(trimmed.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) trimmed.get(1)).getText()).contains("历史压缩");
        assertThat(trimmed.get(2).getText()).contains("用户消息4"); // 保留最近 6 条：4~9
        assertThat(trimmed.get(trimmed.size() - 1).getText()).contains("用户消息9");
    }

    @Test
    void summaryCountsCompressedToolCalls() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统"));
        messages.add(new UserMessage("开头"));
        for (int i = 0; i < 4; i++) {
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "search_rooms", "{}")))
                    .build());
        }
        for (int i = 0; i < 8; i++) {
            messages.add(new UserMessage("填充" + i + "：" + "长".repeat(200)));
        }

        var result = trimmer.trim(messages);

        assertThat(result.summary()).contains("4 次工具调用");
    }
}
