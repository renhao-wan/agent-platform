package io.github.renhaowan.platform.gateway.registry;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 工具注册表：按 (tenantId, gatewayId) 作用域管理工具定义，导入时同名工具更新而非重复插入。
 */
@Service
public class ToolRegistryService {

    private final ToolDefinitionMapper mapper;

    public ToolRegistryService(ToolDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    public List<ToolDefinition> listEnabled(Long tenantId, String gatewayId) {
        return mapper.selectList(new QueryWrapper<ToolDefinition>()
                .eq("tenant_id", tenantId)
                .eq("gateway_id", gatewayId)
                .eq("enabled", 1));
    }

    public Optional<ToolDefinition> find(Long tenantId, String gatewayId, String name) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ToolDefinition>()
                .eq("tenant_id", tenantId)
                .eq("gateway_id", gatewayId)
                .eq("name", name)));
    }

    public void upsertAll(List<ToolDefinition> tools) {
        for (ToolDefinition tool : tools) {
            ToolDefinition existing = mapper.selectOne(new QueryWrapper<ToolDefinition>()
                    .eq("gateway_id", tool.getGatewayId())
                    .eq("name", tool.getName()));
            if (existing == null) {
                mapper.insert(tool);
            } else {
                tool.setId(existing.getId());
                tool.setCreatedAt(existing.getCreatedAt());
                tool.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(tool);
            }
        }
    }
}
