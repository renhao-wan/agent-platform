package io.github.renhaowan.platform.common;

import java.util.Set;

/**
 * 平台内部工具名：由 AgentRunner 本地执行，不经过业务契约注册。
 * 业务契约若占用这些名字，模型会看到两个同名工具，行为不可预测，故对账时整份拒绝。
 */
public final class PlatformToolNames {

    public static final String SEARCH_KNOWLEDGE = "search_knowledge";
    public static final String RECALL_HISTORY = "recallHistory";

    public static final Set<String> RESERVED = Set.of(SEARCH_KNOWLEDGE, RECALL_HISTORY);

    private PlatformToolNames() {
    }
}
