package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.gateway.registry.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.ToolDefinitionMapper;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** ToolRegistryService 接口实现。 */
@Service
public class ToolRegistryServiceImpl implements ToolRegistryService {

    private final ToolDefinitionMapper mapper;

    public ToolRegistryServiceImpl(ToolDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ToolDefinition> listEnabled(Long tenantId, String gatewayId) {
        return mapper.selectList(new QueryWrapper<ToolDefinition>()
                .eq("tenant_id", tenantId)
                .eq("gateway_id", gatewayId)
                .eq("enabled", 1));
    }

    @Override
    public Optional<ToolDefinition> find(Long tenantId, String gatewayId, String name) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ToolDefinition>()
                .eq("tenant_id", tenantId)
                .eq("gateway_id", gatewayId)
                .eq("name", name)));
    }

    @Override
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
