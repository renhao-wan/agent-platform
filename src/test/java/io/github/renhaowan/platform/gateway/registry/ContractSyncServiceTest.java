package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.GatewayProperties;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import io.github.renhaowan.platform.gateway.registry.impl.ContractSyncServiceImpl;
import io.github.renhaowan.platform.gateway.registry.impl.OpenApiImportServiceImpl;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolSourceMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** 对账主流程：成功收敛、失败保留 last-known-good、冲突整份放弃。 */
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
                    "x-required-role": "ADMIN",
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
    private final AgentProperties agentProperties = new AgentProperties();
    private final GatewayProperties gatewayProperties = new GatewayProperties();

    private ContractFetcher fetcher;

    @BeforeEach
    void setUp() {
        agentProperties.setGatewayId("gw1");
        fetcher = url -> CONTRACT;
    }

    private ContractSyncService service() {
        return new ContractSyncServiceImpl(gatewayProperties, agentProperties,
                new OpenApiImportServiceImpl(), registryService, sourceMapper, fetcher, events);
    }

    private GatewayProperties.Source source(String id, String url) {
        GatewayProperties.Source source = new GatewayProperties.Source();
        source.setId(id);
        source.setUrl(url);
        source.setServiceKeyName("X-Service-Key");
        source.setServiceKeyValue("demo-key");
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
        assertThat(tool.getRequiredRole()).isEqualTo("ADMIN");
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
        fetcher = url -> {
            throw new IllegalStateException("connection refused");
        };

        ContractSyncService.SyncReport report = service().sync(
                new ContractSyncService.SourceSpec("booking", "http://source.local/openapi.json", null, null, null));

        assertThat(report.status()).isEqualTo(ToolSource.STATUS_FAILED);
        verify(registryService, never()).applySnapshot(any(), any(), any());
        ArgumentCaptor<ToolSource> captor = ArgumentCaptor.forClass(ToolSource.class);
        verify(sourceMapper).updateById(captor.capture());
        ToolSource written = captor.getValue();
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
    void configuredSourcesAreReconciledAndKeyedById() {
        gatewayProperties.setSources(List.of(
                source("booking", "http://source.local/openapi.json"),
                source("expense", "http://expense.local/openapi.json")));
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any())).thenReturn(
                new ToolRegistryService.SnapshotResult(0, 0, 1, 0, List.of(), List.of()));

        List<ContractSyncService.SyncReport> reports = service().syncConfiguredSources();

        assertThat(reports).extracting(ContractSyncService.SyncReport::sourceKey)
                .containsExactly("booking", "expense");
    }

    @Test
    void sourceWithoutIdFallsBackToAStableDerivedKey() {
        gatewayProperties.setSources(List.of(source(null, "http://localhost:8090/v3/api-docs")));
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(sourceMapper.selectById(any())).thenReturn(null);
        when(registryService.applySnapshot(any(), any(), any())).thenReturn(
                new ToolRegistryService.SnapshotResult(0, 0, 1, 0, List.of(), List.of()));

        assertThat(service().syncConfiguredSources())
                .extracting(ContractSyncService.SyncReport::sourceKey)
                .containsExactly("localhost-8090");
    }

    @Test
    void unknownSourceKeyIsNotSynced() {
        gatewayProperties.setSources(List.of(source("booking", "http://source.local/openapi.json")));

        Optional<ContractSyncService.SyncReport> report = service().syncByKey("missing");

        assertThat(report).isEmpty();
    }
}
