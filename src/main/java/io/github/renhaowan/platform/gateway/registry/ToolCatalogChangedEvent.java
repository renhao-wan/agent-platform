package io.github.renhaowan.platform.gateway.registry;

/** 工具目录在一次成功对账后发生了变化；Agent 侧据此立即刷新清单缓存。 */
public record ToolCatalogChangedEvent(String sourceKey) {
}
