package io.github.renhaowan.platform.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LLM Token 用量打点（best-effort：打点失败不影响对话主流程，但会留告警日志）。
 * M4 的 Token 成本实验数据源。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TokenRecorder {

    private final LlmUsageMapper usageMapper;

    public void record(String sessionKey, String phase, String model, Integer promptTokens, Integer completionTokens) {
        try {
            LlmUsage usage = new LlmUsage();
            usage.setSessionKey(sessionKey);
            usage.setPhase(phase);
            usage.setModel(model);
            usage.setPromptTokens(promptTokens == null ? 0 : promptTokens);
            usage.setCompletionTokens(completionTokens == null ? 0 : completionTokens);
            usage.setCreatedAt(java.time.LocalDateTime.now());
            usageMapper.insert(usage);
        } catch (Exception e) {
            log.warn("token usage record failed: {}", e.getMessage());
        }
    }
}
