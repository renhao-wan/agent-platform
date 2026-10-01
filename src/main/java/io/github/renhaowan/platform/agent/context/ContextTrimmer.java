package io.github.renhaowan.platform.agent.context;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 混合上下文裁剪：粗估 token（chars/2）超阈值时，
 * 保留 system 前缀 + 最近 keepRecent 条消息，更早内容压缩为一条摘要 SystemMessage。
 * 确定性规则实现（不调模型），单测友好；摘要同时由调用方落 checkpoint 表。
 * 装配见 AgentConfig（阈值来自 platform.agent.context-threshold）。
 */
public class ContextTrimmer {

    public static final int KEEP_RECENT = 6;

    private final int threshold;

    public ContextTrimmer(int threshold) {
        this.threshold = threshold;
    }

    public record TrimResult(List<Message> messages, boolean trimmed, String summary) {
    }

    /**
     * 裁剪入口：粗估 ≤ 阈值时原样返回（零成本快路径）；
     * 超阈值时按"system 前缀 + 摘要 + 最近 KEEP_RECENT 条"重建，摘要内容由调用方落 checkpoint 表留痕。
     *
     * @param history 完整消息历史（含 system 前缀）
     * @return TrimResult：裁剪后的消息列表、是否发生裁剪、压缩摘要（未裁剪为 null）
     */
    public TrimResult trim(List<Message> history) {
        if (estimate(history) <= threshold) {
            return new TrimResult(history, false, null);
        }
        int historySize = history.size();
        int systemCount = 0;
        while (systemCount < historySize && history.get(systemCount) instanceof SystemMessage) {
            systemCount++;
        }
        int keepFrom = Math.max(systemCount, historySize - KEEP_RECENT);

        List<Message> head = history.subList(0, systemCount);
        List<Message> recent = history.subList(keepFrom, historySize);

        long toolCalls = history.subList(systemCount, keepFrom).stream()
                .filter(m -> m instanceof AssistantMessage am && am.hasToolCalls())
                .count();
        String summary = "【历史压缩】早期对话共 " + (keepFrom - systemCount)
                + " 条消息（含 " + toolCalls + " 次工具调用）已被压缩，关键结论以里程碑形式保留。";

        List<Message> result = new java.util.ArrayList<>(head);
        result.add(new SystemMessage(summary));
        result.addAll(recent);
        return new TrimResult(List.copyOf(result), true, summary);
    }

    static int estimate(List<Message> messages) {
        int chars = 0;
        for (Message message : messages) {
            if (message.getText() != null) {
                chars += message.getText().length();
            }
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    chars += call.arguments() == null ? 0 : call.arguments().length();
                    chars += call.name() == null ? 0 : call.name().length();
                }
            }
        }
        return chars / 2;
    }
}
