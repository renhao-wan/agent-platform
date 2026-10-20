package io.github.renhaowan.platform.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 模型密钥缺失时启动不静默：平台能起来，但每次对话都会在供应商侧鉴权失败，
 * 故障暴露得越晚越像"平台坏了"。只提示，不阻断启动——知识库、管理端等仍可用。
 */
@Slf4j
@Component
public class LlmKeyStartupCheck {

    private static final String PLACEHOLDER = "missing-key";

    private final String apiKey;

    public LlmKeyStartupCheck(@Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfMissing() {
        if (apiKey == null || apiKey.isBlank() || PLACEHOLDER.equals(apiKey)) {
            log.warn("LLM_API_KEY 未配置：对话请求会因供应商拒绝鉴权而失败，请在 .env 中填写真实密钥后重启");
        }
    }
}
