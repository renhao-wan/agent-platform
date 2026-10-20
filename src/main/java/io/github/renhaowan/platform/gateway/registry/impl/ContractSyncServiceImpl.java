package io.github.renhaowan.platform.gateway.registry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.registry.ContractFetcher;
import io.github.renhaowan.platform.gateway.registry.ContractSyncService;
import io.github.renhaowan.platform.gateway.registry.OpenApiImportService;
import io.github.renhaowan.platform.gateway.registry.SourceRegistryService;
import io.github.renhaowan.platform.gateway.registry.ToolCatalogChangedEvent;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolSourceMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** ContractSyncService 实现：抓取 -> 解析 -> 快照对账 -> 记录来源状态。 */
@Slf4j
@Service
public class ContractSyncServiceImpl implements ContractSyncService {

    /**
     * 对账入口互斥。定时任务、手工触发、管理端新建/编辑后的立即对账共用这一把进程内锁：
     * 并发对账会同时写同一份工具集合，后完成者可能撞唯一键并被误记为 FAILED。
     * 抢不到锁直接返回 BUSY，不排队——下一次定时或手工触发会自然重试。
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    private final SourceRegistryService sourceRegistry;
    private final AgentProperties agentProperties;
    private final OpenApiImportService importService;
    private final ToolRegistryService registryService;
    private final ToolSourceMapper sourceMapper;
    private final ContractFetcher fetcher;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;

    public ContractSyncServiceImpl(SourceRegistryService sourceRegistry, AgentProperties agentProperties,
                                   OpenApiImportService importService, ToolRegistryService registryService,
                                   ToolSourceMapper sourceMapper, ContractFetcher fetcher,
                                   ApplicationEventPublisher events, MeterRegistry meterRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.agentProperties = agentProperties;
        this.importService = importService;
        this.registryService = registryService;
        this.sourceMapper = sourceMapper;
        this.fetcher = fetcher;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<SyncReport> syncAll() {
        if (!syncLock.tryLock()) {
            log.warn("已有对账在进行中，跳过本次全量对账");
            recordMetrics("*", STATUS_BUSY, 0);
            return List.of(busyReport("*"));
        }
        try {
            List<ToolSource> sources;
            try {
                sources = sourceRegistry.listActive();
            } catch (Exception e) {
                log.warn("读取来源注册表失败，跳过本次对账：{}", e.toString());
                return List.of();
            }
            List<SyncReport> reports = new ArrayList<>();
            for (ToolSource source : sources) {
                if (source.getContractUrl() == null || source.getContractUrl().isBlank()) {
                    continue;
                }
                reports.add(sync(specOf(source)));
            }
            if (reports.isEmpty()) {
                log.info("没有可对账的来源（tool_source 为空），可用 POST /admin/sources 新增");
            }
            return reports;
        } finally {
            syncLock.unlock();
        }
    }

    @Override
    public SyncReport sync(SourceSpec spec) {
        if (!syncLock.tryLock()) {
            log.warn("已有对账在进行中，跳过来源 {} 的本次对账", spec.sourceKey());
            recordMetrics(spec.sourceKey(), STATUS_BUSY, 0);
            return busyReport(spec.sourceKey());
        }
        long start = System.nanoTime();
        String[] result = {ToolSource.STATUS_FAILED};
        try {
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
                ToolRegistryService.SnapshotResult applied = registryService.applySnapshot(
                        agentProperties.getGatewayId(), spec.sourceKey(), tools);
                String contractHash = ContractHashes.ofAll(tools);
                recordSuccess(spec, contractHash, tools.size(), applied);
                if (applied.added() + applied.updated() + applied.stale() > 0) {
                    publishChanged(spec);
                }
                if (!applied.breakingChanges().isEmpty()) {
                    applied.breakingChanges().forEach(change -> Counter.builder("platform.contract.breaking")
                            .description("契约破坏性变更（只告警，不阻断对账）")
                            .tag("source", spec.sourceKey())
                            .tag("type", change.type())
                            .register(meterRegistry)
                            .increment());
                    log.warn("契约 {} 存在 {} 项破坏性变更：{}", spec.sourceKey(),
                            applied.breakingChanges().size(), applied.breakingChanges());
                }
                log.info("契约 {} 对账完成：新增 {}、更新 {}、下线 {}、未变 {}",
                        spec.sourceKey(), applied.added(), applied.updated(), applied.stale(), applied.unchanged());
                result[0] = ToolSource.STATUS_OK;
                return new SyncReport(spec.sourceKey(), ToolSource.STATUS_OK, contractHash,
                        applied.added(), applied.updated(), applied.unchanged(), applied.stale(),
                        applied.breakingChanges().size(), applied.staleNames(), applied.breakingChanges(),
                        null, LocalDateTime.now());
            } catch (IllegalStateException e) {
                // 同名工具仍被别的来源声明、或同一契约里工具名重复：整份放弃，保留上一版
                result[0] = ToolSource.STATUS_CONFLICT;
                return failed(spec, ToolSource.STATUS_CONFLICT, e);
            } catch (Exception e) {
                return failed(spec, ToolSource.STATUS_FAILED, e);
            }
        } finally {
            // syncAll 会以可重入方式嵌套调用本方法；这里只释放本次 tryLock 的配额
            syncLock.unlock();
            recordMetrics(spec.sourceKey(), result[0], System.nanoTime() - start);
        }
    }

    @Override
    public Optional<SyncReport> syncByKey(String sourceKey) {
        return sourceRegistry.find(sourceKey)
                .filter(source -> source.getEnabled() == null || source.getEnabled() == 1)
                .filter(source -> !ToolSource.STATUS_RETIRED.equals(source.getStatus()))
                .map(this::specOf)
                .map(this::sync);
    }

    @Override
    public List<SourceView> sources() {
        return sourceRegistry.listAll().stream()
                .map(SourceView::of)
                .toList();
    }

    /** 缓存刷新是尽力而为：监听器失败不能让一次已经落库成功的对账变成 FAILED。 */
    private void publishChanged(SourceSpec spec) {
        try {
            events.publishEvent(new ToolCatalogChangedEvent(spec.sourceKey()));
        } catch (Exception e) {
            log.warn("发布工具目录变更事件失败，等待下一次刷新：{}", e.toString());
        }
    }

    private SyncReport failed(SourceSpec spec, String status, Exception error) {
        log.warn("契约 {} 对账失败（{}）：{}", spec.sourceKey(), status, error.toString());
        recordFailure(spec, status, error);
        return new SyncReport(spec.sourceKey(), status, null, 0, 0, 0, 0, 0,
                List.of(), List.of(), error.toString(), LocalDateTime.now());
    }

    private SyncReport busyReport(String sourceKey) {
        return new SyncReport(sourceKey, STATUS_BUSY, null, 0, 0, 0, 0, 0,
                List.of(), List.of(), "已有对账在进行中，本次跳过", LocalDateTime.now());
    }

    private void recordMetrics(String sourceKey, String result, long elapsedNanos) {
        Counter.builder("platform.contract.sync")
                .description("契约对账次数")
                .tag("source", sourceKey)
                .tag("result", result)
                .register(meterRegistry)
                .increment();
        if (elapsedNanos > 0) {
            Timer.builder("platform.contract.sync.duration")
                    .description("契约对账耗时")
                    .tag("source", sourceKey)
                    .tag("result", result)
                    .register(meterRegistry)
                    .record(java.time.Duration.ofNanos(elapsedNanos));
        }
    }

    private SourceSpec specOf(ToolSource source) {
        return new SourceSpec(source.getSourceKey(), source.getContractUrl(), source.getBaseUrl(),
                source.getServiceKeyName(), source.getServiceKeyValue());
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
        created.setBaseUrl(spec.baseUrl());
        created.setServiceKeyName(spec.serviceKeyName());
        created.setServiceKeyValue(spec.serviceKeyValue());
        created.setOrigin(ToolSource.ORIGIN_ADMIN);
        created.setEnabled(1);
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
