package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.GatewayProperties;
import io.github.renhaowan.platform.gateway.registry.ContractFetcher;
import io.github.renhaowan.platform.gateway.registry.ContractSyncService;
import io.github.renhaowan.platform.gateway.registry.OpenApiImportService;
import io.github.renhaowan.platform.gateway.registry.ToolCatalogChangedEvent;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolSourceMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** ContractSyncService 实现：抓取 -> 解析 -> 快照对账 -> 记录来源状态。 */
@Slf4j
@Service
public class ContractSyncServiceImpl implements ContractSyncService {

    private final GatewayProperties gatewayProperties;
    private final AgentProperties agentProperties;
    private final OpenApiImportService importService;
    private final ToolRegistryService registryService;
    private final ToolSourceMapper sourceMapper;
    private final ContractFetcher fetcher;
    private final ApplicationEventPublisher events;

    public ContractSyncServiceImpl(GatewayProperties gatewayProperties, AgentProperties agentProperties,
                                   OpenApiImportService importService, ToolRegistryService registryService,
                                   ToolSourceMapper sourceMapper, ContractFetcher fetcher,
                                   ApplicationEventPublisher events) {
        this.gatewayProperties = gatewayProperties;
        this.agentProperties = agentProperties;
        this.importService = importService;
        this.registryService = registryService;
        this.sourceMapper = sourceMapper;
        this.fetcher = fetcher;
        this.events = events;
    }

    @Override
    public List<SyncReport> syncConfiguredSources() {
        List<SyncReport> reports = new ArrayList<>();
        for (GatewayProperties.Source source : gatewayProperties.getSources()) {
            if (source.getUrl() == null || source.getUrl().isBlank()) {
                continue;
            }
            reports.add(sync(specOf(source)));
        }
        if (reports.isEmpty()) {
            log.info("platform.gateway.sources 为空，跳过契约对账；可用 POST /admin/protocols/import 手工导入");
        }
        return reports;
    }

    @Override
    public SyncReport sync(SourceSpec spec) {
        String contract;
        try {
            contract = fetcher.fetch(spec.contractUrl());
        } catch (Exception e) {
            return failed(spec, ToolSource.STATUS_FAILED, e);
        }

        List<ToolDefinition> tools;
        try {
            tools = importService.parse(agentProperties.getGatewayId(), contract, baseUrlOf(spec));
        } catch (Exception e) {
            return failed(spec, ToolSource.STATUS_FAILED, e);
        }
        tools.forEach(tool -> {
            tool.setAuthHeaderName(spec.serviceKeyName());
            tool.setAuthHeaderValue(spec.serviceKeyValue());
        });

        try {
            ToolRegistryService.SnapshotResult result = registryService.applySnapshot(
                    agentProperties.getGatewayId(), spec.sourceKey(), tools);
            String contractHash = ContractHashes.ofAll(tools);
            recordSuccess(spec, contractHash, tools.size(), result);
            if (result.added() + result.updated() + result.stale() > 0) {
                events.publishEvent(new ToolCatalogChangedEvent(spec.sourceKey()));
            }
            if (!result.breakingChanges().isEmpty()) {
                log.warn("契约 {} 存在 {} 项破坏性变更：{}", spec.sourceKey(),
                        result.breakingChanges().size(), result.breakingChanges());
            }
            log.info("契约 {} 对账完成：新增 {}、更新 {}、下线 {}、未变 {}",
                    spec.sourceKey(), result.added(), result.updated(), result.stale(), result.unchanged());
            return new SyncReport(spec.sourceKey(), ToolSource.STATUS_OK, contractHash,
                    result.added(), result.updated(), result.unchanged(), result.stale(),
                    result.breakingChanges().size(), result.staleNames(), result.breakingChanges(),
                    null, LocalDateTime.now());
        } catch (IllegalStateException e) {
            // 同名工具仍被别的来源声明、或同一契约里工具名重复：整份放弃，保留上一版
            return failed(spec, ToolSource.STATUS_CONFLICT, e);
        } catch (Exception e) {
            return failed(spec, ToolSource.STATUS_FAILED, e);
        }
    }

    @Override
    public Optional<SyncReport> syncByKey(String sourceKey) {
        return gatewayProperties.getSources().stream()
                .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                .map(this::specOf)
                .filter(spec -> spec.sourceKey().equals(sourceKey))
                .findFirst()
                .map(this::sync);
    }

    @Override
    public List<SourceView> sources() {
        return sourceMapper.selectList(new QueryWrapper<ToolSource>().orderByAsc("source_key")).stream()
                .map(SourceView::of)
                .toList();
    }

    private SyncReport failed(SourceSpec spec, String status, Exception error) {
        log.warn("契约 {} 对账失败（{}）：{}", spec.sourceKey(), status, error.toString());
        recordFailure(spec, status, error);
        return new SyncReport(spec.sourceKey(), status, null, 0, 0, 0, 0, 0,
                List.of(), List.of(), error.toString(), LocalDateTime.now());
    }

    private SourceSpec specOf(GatewayProperties.Source source) {
        String key = source.getId();
        if (key == null || key.isBlank()) {
            key = fallbackKey(source.getUrl());
            log.warn("来源 {} 未配置 id，回退为 {}；建议显式配置稳定 id，避免拓扑变化后变成另一个来源",
                    source.getUrl(), key);
        }
        return new SourceSpec(key, source.getUrl(), source.getBaseUrl(),
                source.getServiceKeyName(), source.getServiceKeyValue());
    }

    /** 从契约地址的 origin 推导，仅作未配置 id 时的兜底；容器内外会得到不同结果，故不推荐依赖。 */
    private static String fallbackKey(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "source" : uri.getHost().replace('.', '-');
            return uri.getPort() > 0 ? host + "-" + uri.getPort() : host;
        } catch (Exception e) {
            return url;
        }
    }

    private String baseUrlOf(SourceSpec spec) {
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            return spec.baseUrl();
        }
        URI uri = URI.create(spec.contractUrl());
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private void recordSuccess(SourceSpec spec, String contractHash, int toolCount,
                               ToolRegistryService.SnapshotResult result) {
        ToolSource source = loadOrNew(spec);
        source.setStatus(ToolSource.STATUS_OK);
        source.setContractHash(contractHash);
        source.setToolCount(toolCount);
        source.setLastAdded(result.added());
        source.setLastUpdated(result.updated());
        source.setLastStale(result.stale());
        source.setLastBreaking(result.breakingChanges().size());
        source.setLastAttemptAt(LocalDateTime.now());
        source.setLastSuccessAt(source.getLastAttemptAt());
        source.setLastError(null);
        persist(source, spec);
    }

    private void recordFailure(SourceSpec spec, String status, Exception error) {
        ToolSource source = loadOrNew(spec);
        // 保留 hash / toolCount / lastSuccessAt：失败时这些仍表示最后一次成功的数据
        source.setStatus(status);
        source.setLastAttemptAt(LocalDateTime.now());
        source.setLastError(truncate(error.toString()));
        persist(source, spec);
    }

    private ToolSource loadOrNew(SourceSpec spec) {
        try {
            ToolSource existing = sourceMapper.selectOne(new QueryWrapper<ToolSource>()
                    .eq("source_key", spec.sourceKey()));
            if (existing != null) {
                return existing;
            }
        } catch (Exception e) {
            // 数据库不可用时也要让启动流程继续：对账状态是观测信息，不是主流程前置条件
            log.warn("读取来源 {} 状态失败：{}", spec.sourceKey(), e.toString());
        }
        ToolSource created = new ToolSource();
        created.setSourceKey(spec.sourceKey());
        created.setGatewayId(agentProperties.getGatewayId());
        created.setContractUrl(spec.contractUrl());
        created.setStatus(ToolSource.STATUS_PENDING);
        created.setToolCount(0);
        created.setLastAdded(0);
        created.setLastUpdated(0);
        created.setLastStale(0);
        created.setLastBreaking(0);
        return created;
    }

    private void persist(ToolSource source, SourceSpec spec) {
        try {
            if (source.getContractUrl() == null) {
                source.setContractUrl(spec.contractUrl());
            }
            boolean exists = sourceMapper.selectById(source.getSourceKey()) != null;
            if (exists) {
                sourceMapper.updateById(source);
            } else {
                sourceMapper.insert(source);
            }
        } catch (Exception e) {
            log.warn("写入来源 {} 的对账状态失败：{}", spec.sourceKey(), e.toString());
        }
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
