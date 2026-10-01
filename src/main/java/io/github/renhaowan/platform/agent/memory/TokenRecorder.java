package io.github.renhaowan.platform.agent.memory;

/**
 * LLM Token 用量打点（best-effort：打点失败不影响对话主流程，但会留告警日志）。
 * M4 的 Token 成本实验数据源。
 */
public interface TokenRecorder {

    void record(String sessionKey, String phase, String model, Integer promptTokens, Integer completionTokens);
}
