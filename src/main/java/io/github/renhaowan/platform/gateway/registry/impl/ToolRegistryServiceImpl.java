package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper;
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

    @Override
    @Transactional
    public int retireSource(String gatewayId, String sourceKey) {
        List<ToolDefinition> tools = mapper.selectList(new QueryWrapper<ToolDefinition>()
                .eq("gateway_id", gatewayId)
                .eq("source_key", sourceKey)
                .eq("present", 1));
        for (ToolDefinition tool : tools) {
            tool.setPresent(0);
            tool.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(tool);
        }
        return tools.size();
    }
}
