package io.github.renhaowan.platform.gateway.registry;

import java.util.List;
import java.util.Optional;

/**
 * 工具注册表：按 (tenantId, gatewayId) 作用域管理工具定义，导入时同名工具更新而非重复插入。
 */
public interface ToolRegistryService {

    List<ToolDefinition> listEnabled(Long tenantId, String gatewayId);

    Optional<ToolDefinition> find(Long tenantId, String gatewayId, String name);

    void upsertAll(List<ToolDefinition> tools);
}
