package io.github.renhaowan.platform.agent.loop;

import org.springframework.stereotype.Component;

/**
 * 规则意图分类器：关键词命中即判定，未命中回落 GENERAL（交给 LLM 通道的扩展点）。
 */
@Component
public class RuleIntentClassifier implements IntentClassifier {

    @Override
    public String classify(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("取消") || lower.contains("退订") || lower.contains("cancel")) {
            return LABEL_CANCEL;
        }
        if (lower.contains("订") || lower.contains("预定") || lower.contains("预约")
                || lower.contains("会议室") || lower.contains("book")) {
            return LABEL_ROOM_BOOKING;
        }
        return LABEL_GENERAL;
    }
}
