package io.github.renhaowan.platform.agent.context;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 混合上下文裁剪：粗估 token（chars/2）超阈值时，
 * 保留 system 前缀 + 最近 keepRecent 条消息，更早内容压缩为一条摘要 SystemMessage。
 * 确定性规则实现（不调模型），单测友好；摘要同时由调用方落 checkpoint 表。
 * 装配见 AgentConfig（阈值来自 platform.agent.context-threshold）。
 */
public interface ContextTrimmer {

    int KEEP_RECENT = 6;

    record TrimResult(List<Message> messages, boolean trimmed, String summary) {
    }

    /**
     * 裁剪入口：粗估 ≤ 阈值时原样返回（零成本快路径）；
     * 超阈值时按"system 前缀 + 摘要 + 最近 KEEP_RECENT 条"重建，摘要内容由调用方落 checkpoint 表留痕。
     *
     * @param history 完整消息历史（含 system 前缀）
     * @return TrimResult：裁剪后的消息列表、是否发生裁剪、压缩摘要（未裁剪为 null）
     */
    TrimResult trim(List<Message> history);
}
