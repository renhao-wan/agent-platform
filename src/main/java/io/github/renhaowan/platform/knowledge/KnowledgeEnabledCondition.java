package io.github.renhaowan.platform.knowledge;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 知识库开关：enabled=true 且配置了 embedding key 才装配。
 * 未配置 key 时连 Postgres 都不连——保证克隆仓库后不填任何新变量也能启动（优雅降级）。
 */
public class KnowledgeEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        boolean enabled = env.getProperty("platform.knowledge.enabled", Boolean.class, true);
        String apiKey = env.getProperty("platform.knowledge.embedding-api-key", "");
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
