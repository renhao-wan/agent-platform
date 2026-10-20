package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import io.github.renhaowan.platform.gateway.registry.impl.ContractSyncServiceImpl;
import io.github.renhaowan.platform.gateway.registry.impl.OpenApiImportServiceImpl;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolSourceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** 对账主流程：成功收敛、失败保留 last-known-good、冲突整份放弃、并发触发返回 BUSY。 */
class ContractSyncServiceTest {

    private static final String CONTRACT = """
            {
              "openapi": "3.0.0",
              "info": {"title": "demo", "version": "1.0"},
              "servers": [{"url": "http://contract-declared-host:9999"}],
              "paths": {
                "/api/v1/things/{id}/cancel": {
                  "post": {
                    "operationId": "cancel_thing",
                    "summary": "取消指定事项",
                    "x-required-permission": "booking:cancel",
                    "x-require-confirm": true,
                    "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}]
                  }
                }
              }
            }
            """;

    private final ToolRegistryService registryService = mock(ToolRegistryService.class);
    private final ToolSourceMapper sourceMapper = mock(ToolSourceMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SourceRegistryService sourceRegistry = mock(SourceRegistryService.class);
    private final AgentProperties agentProperties = new AgentProperties();

    private ContractFetcher fetcher;

    @BeforeEach
    void setUp() {
        agentProperties.setGatewayId("gw1");
        fetcher = url -> CONTRACT;
        when(sourceRegistry.listAll()).thenReturn(List.of());
    }

    private ContractSyncService service() {
        return new ContractSyncServiceImpl(sourceRegistry, agentProperties,
                new OpenApiImportServiceImpl(), registryService, sourceMapper, fetcher, events,
                new SimpleMeterRegistry(), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private ToolSource source(String id, String url) {
        ToolSource source = new ToolSource();
        source.setSourceKey(id);
        source.setGatewayId("gw1");
        source.setContractUrl(url);
        source.setBaseUrl(null);
        source.setServiceKeyName("X-Service-Key");
        source.setServiceKeyValue("demo-key");
        source.setOrigin(ToolSource.ORIGIN_CONFIG);
        source.setEnabled(1);
        source.setStatus(ToolSource.STATUS_OK);
        return source;
    }

    private void firstSyncWritesNewSource() {
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any())).thenReturn(
                new ToolRegistryService.SnapshotResult(1, 0, 0, 0, List.of(), List.of()));
    }

    @SuppressWarnings("unchecked")
    private List<ToolDefinition> appliedTools() {
        ArgumentCaptor<List<ToolDefinition>> captor = ArgumentCaptor.forClass(List.class);
        verify(registryService).applySnapshot(any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void successfulSyncParsesContractAndBindsSource() {
        firstSyncWritesNewSource();

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json", null,
                        "X-Service-Key", "demo-key"));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_OK);
        assertThat(report.added()).isEqualTo(1);
        ToolDefinition tool = appliedTools().get(0);
        assertThat(tool.getName()).isEqualTo("cancel_thing");
        assertThat(tool.getRequiredPermission()).isEqualTo("booking:cancel");
        assertThat(tool.getRequireConfirm()).isEqualTo(1);
        assertThat(tool.getAuthHeaderValue()).isEqualTo("demo-key");
        // 契约自称的 host 不可信：既然从 source.local 拉到，就发回 source.local
        assertThat(tool.getUrlTemplate())
                .isEqualTo("http://source.local/api/v1/things/{id}/cancel");
        verify(events).publishEvent(any(ToolCatalogChangedEvent.class));
    }

    @Test
    void explicitBaseUrlOverridesContractOrigin() {
        firstSyncWritesNewSource();

        service().sync(new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json",
                "http://my-gateway:9000", null, null));

        assertThat(appliedTools().get(0).getUrlTemplate())
                .isEqualTo("http://my-gateway:9000/api/v1/things/{id}/cancel");
    }

    @Test
    void fetchFailureKeepsLastKnownGoodAndRecordsFailure() {
        ToolSource previous = new ToolSource();
        previous.setSourceKey("booking");
        previous.setStatus(ToolSource.STATUS_OK);
        previous.setContractHash("hash-of-last-good");
        previous.setToolCount(7);
        previous.setLastSuccessAt(LocalDateTime.of(2026, 10, 19, 10, 0));
        when(sourceMapper.selectOne(any())).thenReturn(previous);
        when(sourceMapper.selectById(any())).thenReturn(previous);
        java.util.List<ToolSource> updates = new java.util.ArrayList<>();
        when(sourceMapper.updateById(any(ToolSource.class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return 1;
        });
        fetcher = url -> {
            throw new IllegalStateException("connection refused");
        };

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json", null, null, null));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_FAILED);
        verify(registryService, never()).applySnapshot(any(), any(), any());
        assertThat(updates).hasSize(1);
        ToolSource written = updates.get(0);
        assertThat(written.getStatus()).isEqualTo(ToolSource.STATUS_FAILED);
        assertThat(written.getContractHash()).isEqualTo("hash-of-last-good");
        assertThat(written.getToolCount()).isEqualTo(7);
        assertThat(written.getLastError()).contains("connection refused");
    }

    @Test
    void malformedContractMarksSourceFailedWithoutTouchingTools() {
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        fetcher = url -> "{not json";

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json", null, null, null));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_FAILED);
        verify(registryService, never()).applySnapshot(any(), any(), any());
    }

    @Test
    void nameConflictMarksSourceConflictAndPublishesNothing() {
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any()))
                .thenThrow(new IllegalStateException("工具名冲突：cancel_thing"));

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json", null, null, null));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_CONFLICT);
        assertThat(report.error()).contains("工具名冲突");
        verify(events, never()).publishEvent(any(ToolCatalogChangedEvent.class));
    }

    @Test
    void nameConflictRecordsToolAndOwningSourceForOperators() {
        ToolSource previous = new ToolSource();
        previous.setSourceKey("demo");
        previous.setStatus(ToolSource.STATUS_OK);
        when(sourceMapper.selectOne(any())).thenReturn(previous);
        when(sourceMapper.selectById(any())).thenReturn(previous);
        java.util.List<ToolSource> updates = new java.util.ArrayList<>();
        when(sourceMapper.updateById(any(ToolSource.class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return 1;
        });
        when(registryService.applySnapshot(any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "工具名冲突：cancel_thing 已由来源 booking 声明；请先从旧契约移除或改名"));

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("demo", "http://source.local/openapi.json", null, null, null));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_CONFLICT);
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getStatus()).isEqualTo(ToolSource.STATUS_CONFLICT);
        // 管理端靠 last_error 定位冲突双方，而不仅仅看到整个来源 CONFLICT
        assertThat(updates.get(0).getLastError())
                .contains("cancel_thing")
                .contains("booking")
                .doesNotContain("java.lang.IllegalStateException");
    }

    @Test
    void listenerFailureDoesNotTurnSuccessfulReconciliationIntoFailure() {
        firstSyncWritesNewSource();
        doThrow(new IllegalStateException("refresh boom"))
                .when(events).publishEvent(any(ToolCatalogChangedEvent.class));

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json", null, null, null));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_OK);
        assertThat(report.added()).isEqualTo(1);
    }

    @Test
    void syncAllReconcilesEveryActiveSourceFromRegistry() {
        when(sourceRegistry.listActive()).thenReturn(List.of(
                source("booking", "http://booking.local/openapi.json"),
                source("expense", "http://expense.local/openapi.json")));
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any())).thenReturn(
                new ToolRegistryService.SnapshotResult(0, 0, 1, 0, List.of(), List.of()));

        List<ContractSyncService.SyncReport> reports = service().syncAll();

        assertThat(reports).extracting(ContractSyncService.SyncReport::sourceKey)
                .containsExactly("booking", "expense");
    }

    @Test
    void syncByKeyOnlyTouchesRegisteredActiveSources() {
        when(sourceRegistry.find("booking"))
                .thenReturn(Optional.of(source("booking", "http://booking.local/openapi.json")));
        when(sourceRegistry.find("missing")).thenReturn(Optional.empty());
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any())).thenReturn(
                new ToolRegistryService.SnapshotResult(0, 0, 1, 0, List.of(), List.of()));

        assertThat(service().syncByKey("booking")).isPresent();
        assertThat(service().syncByKey("missing")).isEmpty();
    }

    @Test
    void syncReleasesTheLockAfterCompletion() throws Exception {
        firstSyncWritesNewSource();
        ContractSyncService service = service();
        ContractSyncService.SourceSpec spec = new ContractSyncService.SourceSpec(
                "booking", "http://source.local/openapi.json", null, null, null);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ContractSyncService.SyncReport first = pool.submit(() -> service.sync(spec))
                    .get(5, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(ToolSource.STATUS_OK);
        } finally {
            pool.shutdownNow();
        }

        // 锁若在第一次对账后泄漏，主线程会拿到 BUSY
        assertThat(service.sync(spec).status()).isEqualTo(ToolSource.STATUS_OK);
    }

    @Test
    void concurrentSyncReturnsBusyInsteadOfRacingTheWriter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fetcher = url -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CONTRACT;
        };
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any())).thenReturn(
                new ToolRegistryService.SnapshotResult(0, 0, 1, 0, List.of(), List.of()));
        when(sourceRegistry.find("booking"))
                .thenReturn(Optional.of(source("booking", "http://booking.local/openapi.json")));
        ContractSyncService service = service();
        ContractSyncService.SourceSpec spec = new ContractSyncService.SourceSpec(
                "booking", "http://booking.local/openapi.json", null, null, null);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> service.sync(spec));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            ContractSyncService.SyncReport second = service.sync(spec);

            assertThat(second.status()).isEqualTo(ContractSyncService.STATUS_BUSY);
            assertThat(second.error()).contains("已有对账");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
