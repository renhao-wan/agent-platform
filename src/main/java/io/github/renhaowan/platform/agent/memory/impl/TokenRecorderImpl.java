package io.github.renhaowan.platform.agent.memory.impl;

import io.github.renhaowan.platform.agent.memory.entity.LlmUsage;
import io.github.renhaowan.platform.agent.memory.mapper.LlmUsageMapper;
import io.github.renhaowan.platform.agent.memory.TokenRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** TokenRecorder 接口实现。 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TokenRecorderImpl implements TokenRecorder {

    private final LlmUsageMapper usageMapper;

    @Override
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
