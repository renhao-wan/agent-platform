package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** ToolRegistryService 接口实现。 */
@Slf4j
@Service
public class ToolRegistryServiceImpl implements ToolRegistryService {

    private final ToolDefinitionMapper mapper;

    public ToolRegistryServiceImpl(ToolDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ToolDefinition> listEnabled(String gatewayId) {
        return mapper.selectList(new QueryWrapper<ToolDefinition>()
                .eq("gateway_id", gatewayId)
                .eq("enabled", 1));
    }

    @Override
    public Optional<ToolDefinition> find(String gatewayId, String name) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ToolDefinition>()
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
                // 同一网关内工具名是全局命名空间：多个业务系统共用一个 gatewayId 时，
                // 后来的同名工具会直接顶掉先前的，且执行时没有任何迹象。名字撞车的后果
                // 是"模型调用的接口悄悄换了后端"，比报错难查得多，故留下来源变更记录。
                // 只比对主机与路径：同一后端换个地址（容器内外、改端口）是正常操作，不该告警。
                if (differentEndpoint(existing.getUrlTemplate(), tool.getUrlTemplate())) {
                    log.warn("工具名冲突：{} 原为 {}，本次导入将改指 {}，两者路径不同，请确认是否为同名接口",
                            tool.getName(), existing.getUrlTemplate(), tool.getUrlTemplate());
                }
                tool.setId(existing.getId());
                tool.setCreatedAt(existing.getCreatedAt());
                tool.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(tool);
            }
        }
    }

    private static boolean differentEndpoint(String existingTemplate, String incomingTemplate) {
        return !hostOf(existingTemplate).equals(hostOf(incomingTemplate))
                && !pathOf(existingTemplate).equals(pathOf(incomingTemplate));
    }

    private static String hostOf(String urlTemplate) {
        return partOf(urlTemplate, URI::getHost);
    }

    private static String pathOf(String urlTemplate) {
        return partOf(urlTemplate, URI::getPath);
    }

    private static String partOf(String urlTemplate, java.util.function.Function<URI, String> extractor) {
        if (urlTemplate == null) {
            return "";
        }
        try {
            String value = extractor.apply(URI.create(urlTemplate));
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }
}
