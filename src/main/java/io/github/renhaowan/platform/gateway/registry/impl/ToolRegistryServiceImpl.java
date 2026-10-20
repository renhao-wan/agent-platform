package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ToolRegistryService 实现。 */
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
                .eq("present", 1)
                .eq("enabled", 1));
    }

    @Override
    public Optional<ToolDefinition> find(String gatewayId, String name) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ToolDefinition>()
                .eq("gateway_id", gatewayId)
                .eq("name", name)
                .eq("present", 1)
                .eq("enabled", 1)));
    }

    @Override
    public List<ToolDefinition> listAll(String gatewayId) {
        return mapper.selectList(new QueryWrapper<ToolDefinition>()
                .eq("gateway_id", gatewayId)
                .orderByAsc("name"));
    }

    @Override
    public void upsertAll(List<ToolDefinition> tools) {
        for (ToolDefinition tool : tools) {
            ToolDefinition existing = mapper.selectOne(new QueryWrapper<ToolDefinition>()
                    .eq("gateway_id", tool.getGatewayId())
                    .eq("name", tool.getName()));
            if (existing == null) {
                tool.setPresent(1);
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
                tool.setPresent(1);
                mapper.updateById(tool);
            }
        }
    }

    @Override
    @Transactional
    public SnapshotResult applySnapshot(String gatewayId, String sourceKey, List<ToolDefinition> desired) {
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        for (ToolDefinition tool : desired) {
            if (byName.putIfAbsent(tool.getName(), tool) != null) {
                throw new IllegalStateException("契约内工具名重复：" + tool.getName());
            }
        }

        List<ToolDefinition> existingForSource = mapper.selectList(new QueryWrapper<ToolDefinition>()
                .eq("gateway_id", gatewayId)
                .eq("source_key", sourceKey));
        List<ToolDefinition> named = byName.isEmpty() ? List.of()
                : mapper.selectList(new QueryWrapper<ToolDefinition>()
                        .eq("gateway_id", gatewayId)
                        .in("name", byName.keySet()));
        Map<String, ToolDefinition> existingByName = new LinkedHashMap<>();
        named.forEach(tool -> existingByName.put(tool.getName(), tool));

        // 写入前先整体判冲突：同名工具仍由别的来源声明时，本次对账整份放弃，保留上一版状态
        for (ToolDefinition tool : desired) {
            ToolDefinition old = existingByName.get(tool.getName());
            if (old != null && Integer.valueOf(1).equals(old.getPresent())
                    && old.getSourceKey() != null && !old.getSourceKey().equals(sourceKey)) {
                throw new IllegalStateException("工具名冲突：" + tool.getName()
                        + " 已由来源 " + old.getSourceKey() + " 声明；请先从旧契约移除或改名");
            }
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        List<BreakingChange> breaking = new ArrayList<>();

        for (ToolDefinition tool : desired) {
            String hash = ContractHashes.of(tool);
            tool.setSourceKey(sourceKey);
            tool.setContractHash(hash);
            tool.setPresent(1);

            ToolDefinition old = existingByName.get(tool.getName());
            if (old == null) {
                if (tool.getEnabled() == null) {
                    tool.setEnabled(1);
                }
                tool.setCreatedAt(LocalDateTime.now());
                mapper.insert(tool);
                added++;
                continue;
            }

            tool.setId(old.getId());
            tool.setCreatedAt(old.getCreatedAt());
            // 人工启停是注册表里的独立状态：契约同步不得把管理员禁用的工具重新打开
            tool.setEnabled(old.getEnabled() == null ? 1 : old.getEnabled());
            boolean contractChanged = !hash.equals(old.getContractHash());
            boolean authChanged = !Objects.equals(old.getAuthHeaderName(), tool.getAuthHeaderName())
                    || !Objects.equals(old.getAuthHeaderValue(), tool.getAuthHeaderValue());
            boolean wasStale = !Integer.valueOf(1).equals(old.getPresent());

            if (!contractChanged && !authChanged && !wasStale) {
                unchanged++;
                continue;
            }
            if (contractChanged && !wasStale) {
                breaking.addAll(ToolChangeAnalyzer.compare(old, tool));
            }
            tool.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(tool);
            if (contractChanged || wasStale) {
                updated++;
            } else {
                unchanged++;
            }
        }

        Set<String> desiredNames = new HashSet<>(byName.keySet());
        List<String> staleNames = new ArrayList<>();
        for (ToolDefinition old : existingForSource) {
            if (desiredNames.contains(old.getName()) || !Integer.valueOf(1).equals(old.getPresent())) {
                continue;
            }
            old.setPresent(0);
            old.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(old);
            staleNames.add(old.getName());
            breaking.add(new BreakingChange(old.getName(), "REMOVED_ENDPOINT", "契约中已不存在"));
        }

        return new SnapshotResult(added, updated, unchanged, staleNames.size(), staleNames, breaking);
    }

    @Override
    public boolean setEnabled(Long id, boolean enabled) {
        ToolDefinition tool = mapper.selectById(id);
        if (tool == null) {
            return false;
        }
        ToolDefinition update = new ToolDefinition();
        update.setId(id);
        update.setEnabled(enabled ? 1 : 0);
        update.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(update);
        return true;
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
