package io.github.renhaowan.platform.agent.loop.impl;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.loop.IntentClassifier;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 配置化规则意图分类器：关键词表来自配置项 platform.agent.intent.rules
 * （Map&lt;标签, 关键词列表&gt;），按声明顺序匹配、命中即返回标签；全部未命中回落 GENERAL。
 * <p>
 * 设计动机：业务关键词是<b>会随接入领域变化的运营数据</b>，放代码里每换一个业务线都要发版；
 * 挪到 yml 后新增/调整意图只改配置。平台代码因此不出现任何业务词。
 * 兜底语义：配置缺失或规则清空时一律 GENERAL——宁可少分类，不可错分类。
 */
@Component
public class ConfigIntentClassifier implements IntentClassifier {

    private final AgentProperties properties;

    public ConfigIntentClassifier(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String classify(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        for (Map.Entry<String, List<String>> rule : properties.getIntentRules().entrySet()) {
            for (String keyword : rule.getValue()) {
                if (lower.contains(keyword.toLowerCase())) {
                    return rule.getKey();
                }
            }
        }
        return LABEL_GENERAL;
    }
}
