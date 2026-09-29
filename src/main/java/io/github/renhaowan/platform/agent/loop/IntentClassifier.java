package io.github.renhaowan.platform.agent.loop;

/**
 * 意图分类（双通道架构：规则通道优先，LLM 通道为低置信度兜底——M2 先落规则通道）。
 */
public interface IntentClassifier {

    String classify(String text);

    String LABEL_ROOM_BOOKING = "ROOM_BOOKING";
    String LABEL_CANCEL = "CANCEL";
    String LABEL_GENERAL = "GENERAL";
}
