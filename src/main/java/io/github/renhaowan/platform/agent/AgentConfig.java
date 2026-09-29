package io.github.renhaowan.platform.agent;

import io.github.renhaowan.platform.agent.context.ContextTrimmer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 模块装配：需要原始值参数的组件在此显式构造。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ContextTrimmer contextTrimmer(AgentProperties properties) {
        return new ContextTrimmer(properties.getContextThreshold());
    }
}
