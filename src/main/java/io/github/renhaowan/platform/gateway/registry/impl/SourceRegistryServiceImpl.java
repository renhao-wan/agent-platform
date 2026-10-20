package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.GatewayProperties;
import io.github.renhaowan.platform.gateway.registry.SourceConflictException;
import io.github.renhaowan.platform.gateway.registry.SourceRegistryService;
import io.github.renhaowan.platform.gateway.registry.ToolCatalogChangedEvent;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolSourceMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 来源注册表实现：配置文件只做种子，数据库是运行时的来源清单。 */
@Slf4j
@Service
public class SourceRegistryServiceImpl implements SourceRegistryService {

    private final GatewayProperties gatewayProperties;
    private final AgentProperties agentProperties;
    private final ToolSourceMapper sourceMapper;
    private final ToolRegistryService registryService;
    private final ApplicationEventPublisher events;

    public SourceRegistryServiceImpl(GatewayProperties gatewayProperties, AgentProperties agentProperties,
                                     ToolSourceMapper sourceMapper, ToolRegistryService registryService,
                                     ApplicationEventPublisher events) {
        this.gatewayProperties = gatewayProperties;
        this.agentProperties = agentProperties;
        this.sourceMapper = sourceMapper;
        this.registryService = registryService;
        this.events = events;
    }

    @Override
    public void seedFromConfig() {
        Map<String, GatewayProperties.Source> configured = validateConfigured();
        List<ToolSource> existing;
        try {
            existing = sourceMapper.selectList(new QueryWrapper<ToolSource>().orderByAsc("source_key"));
        } catch (Exception e) {
            // 数据库不可用不阻断启动：后续对账同样会失败并保留 last-known-good
            log.warn("读取来源注册表失败，跳过本次配置种子：{}", e.toString());
            return;
        }

        Map<String, ToolSource> existingByKey = new LinkedHashMap<>();
        existing.forEach(source -> existingByKey.put(source.getSourceKey(), source));

        for (Map.Entry<String, GatewayProperties.Source> entry : configured.entrySet()) {
            GatewayProperties.Source spec = entry.getValue();
            ToolSource row = existingByKey.get(entry.getKey());
            if (row == null) {
                try {
                    sourceMapper.insert(newSource(spec.getId(), spec.getUrl(), spec.getBaseUrl(),
                            spec.getServiceKeyName(), spec.getServiceKeyValue(), ToolSource.ORIGIN_CONFIG));
                } catch (Exception e) {
                    log.warn("写入配置来源 {} 失败：{}", entry.getKey(), e.toString());
                }
            } else if (ToolSource.ORIGIN_CONFIG.equals(row.getOrigin())) {
                row.setContractUrl(spec.getUrl());
                row.setBaseUrl(spec.getBaseUrl());
                row.setServiceKeyName(spec.getServiceKeyName());
                row.setServiceKeyValue(spec.getServiceKeyValue());
                row.setEnabled(1);
                if (ToolSource.STATUS_RETIRED.equals(row.getStatus())) {
                    row.setStatus(ToolSource.STATUS_PENDING);
                }
                updateQuietly(row);
            }
        }

        for (ToolSource row : existing) {
            if (ToolSource.ORIGIN_CONFIG.equals(row.getOrigin())
                    && !configured.containsKey(row.getSourceKey())
                    && !ToolSource.STATUS_RETIRED.equals(row.getStatus())) {
                retireRow(row);
            }
        }
    }

    @Override
    public List<ToolSource> listAll() {
        return sourceMapper.selectList(new QueryWrapper<ToolSource>().orderByAsc("source_key"));
    }

    @Override
    public List<ToolSource> listActive() {
        return sourceMapper.selectList(new QueryWrapper<ToolSource>()
                .eq("enabled", 1)
                .ne("status", ToolSource.STATUS_RETIRED)
                .orderByAsc("source_key"));
    }

    @Override
    public Optional<ToolSource> find(String sourceKey) {
        return Optional.ofNullable(sourceMapper.selectById(sourceKey));
    }

    @Override
    public ToolSource create(SourceCommand command) {
        validateCommand(command);
        if (sourceMapper.selectById(command.sourceKey()) != null) {
            throw new SourceConflictException("来源已存在：" + command.sourceKey());
        }
        ToolSource source = newSource(command.sourceKey(), command.contractUrl(), command.baseUrl(),
                command.serviceKeyName(), command.serviceKeyValue(), ToolSource.ORIGIN_ADMIN);
        source.setEnabled(command.enabled() == null || command.enabled() ? 1 : 0);
        if (source.getEnabled() == 0) {
            source.setStatus(ToolSource.STATUS_RETIRED);
        }
        try {
            sourceMapper.insert(source);
        } catch (Exception e) {
            throw new SourceConflictException("来源写入失败（可能已被并发创建）：" + command.sourceKey());
        }
        log.info("管理端新增来源 {} -> {}", source.getSourceKey(), source.getContractUrl());
        return source;
    }

    @Override
    public Optional<ToolSource> update(String sourceKey, SourceCommand command) {
        ToolSource source = sourceMapper.selectById(sourceKey);
        if (source == null) {
            return Optional.empty();
        }
        if (command.contractUrl() == null || command.contractUrl().isBlank()) {
            throw new IllegalArgumentException("contractUrl 不能为空");
        }
        validateUrl(command.contractUrl());
        source.setContractUrl(command.contractUrl().trim());
        source.setBaseUrl(command.baseUrl() == null ? source.getBaseUrl() : blankToNull(command.baseUrl()));
        if (command.serviceKeyName() != null) {
            source.setServiceKeyName(blankToNull(command.serviceKeyName()));
        }
        if (command.serviceKeyValue() != null) {
            source.setServiceKeyValue(blankToNull(command.serviceKeyValue()));
        }
        if (command.enabled() != null) {
            source.setEnabled(command.enabled() ? 1 : 0);
            if (command.enabled() && ToolSource.STATUS_RETIRED.equals(source.getStatus())) {
                source.setStatus(ToolSource.STATUS_PENDING);
            }
        }
        // 管理员接管后配置不再覆盖：否则下次重启会悄悄改回配置文件里的地址/凭证
        source.setOrigin(ToolSource.ORIGIN_ADMIN);
        updateQuietly(source);
        log.info("管理端更新来源 {} -> {}", sourceKey, source.getContractUrl());
        return Optional.of(source);
    }

    @Override
    @Transactional
    public boolean retire(String sourceKey) {
        ToolSource source = sourceMapper.selectById(sourceKey);
        if (source == null) {
            return false;
        }
        if (ToolSource.ORIGIN_CONFIG.equals(source.getOrigin())) {
            throw new SourceConflictException(
                    "来源 " + sourceKey + " 由配置文件管理，请在 platform.gateway.sources 中移除");
        }
        retireRow(source);
        return true;
    }

    private void retireRow(ToolSource source) {
        source.setEnabled(0);
        source.setStatus(ToolSource.STATUS_RETIRED);
        updateQuietly(source);
        int offlined = registryService.retireSource(
                source.getGatewayId() == null ? agentProperties.getGatewayId() : source.getGatewayId(),
                source.getSourceKey());
        log.info("来源 {} 已下线，软下线工具 {} 个", source.getSourceKey(), offlined);
        events.publishEvent(new ToolCatalogChangedEvent(source.getSourceKey()));
    }

    private Map<String, GatewayProperties.Source> validateConfigured() {
        Map<String, GatewayProperties.Source> byKey = new LinkedHashMap<>();
        List<GatewayProperties.Source> sources = gatewayProperties.getSources();
        for (int i = 0; i < sources.size(); i++) {
            GatewayProperties.Source source = sources.get(i);
            String path = "platform.gateway.sources[" + i + "]";
            if (source.getUrl() == null || source.getUrl().isBlank()) {
                throw new IllegalStateException(path + ".url 不能为空");
            }
            try {
                validateKey(source.getId(), path + ".id");
                validateUrl(source.getUrl());
            } catch (IllegalArgumentException e) {
                // 配置错误属于启动前置条件不满足，用 IllegalStateException 与请求参数错误区分
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (byKey.putIfAbsent(source.getId(), source) != null) {
                throw new IllegalStateException(path + ".id 重复：" + source.getId());
            }
        }
        return byKey;
    }

    private static void validateCommand(SourceCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        validateKey(command.sourceKey(), "sourceKey");
        if (command.contractUrl() == null || command.contractUrl().isBlank()) {
            throw new IllegalArgumentException("contractUrl 不能为空");
        }
        validateUrl(command.contractUrl());
    }

    private static void validateKey(String key, String field) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空（来源标识必须稳定且唯一）");
        }
        if (!key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException(field + " 只允许字母、数字、点、下划线和连字符，最长 128");
        }
    }

    private static void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("契约地址不合法：" + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("契约地址必须是 http(s) URL：" + url);
        }
    }

    private ToolSource newSource(String key, String contractUrl, String baseUrl,
                                 String serviceKeyName, String serviceKeyValue, String origin) {
        ToolSource source = new ToolSource();
        source.setSourceKey(key);
        source.setGatewayId(agentProperties.getGatewayId());
        source.setContractUrl(contractUrl);
        source.setBaseUrl(blankToNull(baseUrl));
        source.setServiceKeyName(blankToNull(serviceKeyName));
        source.setServiceKeyValue(blankToNull(serviceKeyValue));
        source.setOrigin(origin);
        source.setEnabled(1);
        source.setStatus(ToolSource.STATUS_PENDING);
        source.setToolCount(0);
        source.setLastAdded(0);
        source.setLastUpdated(0);
        source.setLastStale(0);
        source.setLastBreaking(0);
        return source;
    }

    private void updateQuietly(ToolSource source) {
        try {
            sourceMapper.updateById(source);
        } catch (Exception e) {
            log.warn("写入来源 {} 失败：{}", source.getSourceKey(), e.toString());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
