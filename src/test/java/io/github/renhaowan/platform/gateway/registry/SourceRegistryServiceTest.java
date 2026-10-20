package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.GatewayProperties;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import io.github.renhaowan.platform.gateway.registry.impl.SourceRegistryServiceImpl;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolSourceMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** 来源注册表：配置种子、所有权规则、软下线与凭证只入不出。 */
class SourceRegistryServiceTest {

    private final GatewayProperties gatewayProperties = new GatewayProperties();
    private final AgentProperties agentProperties = new AgentProperties();
    private final ToolSourceMapper sourceMapper = mock(ToolSourceMapper.class);
    private final ToolRegistryService registryService = mock(ToolRegistryService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    @BeforeEach
    void setUp() {
        agentProperties.setGatewayId("gw1");
        when(sourceMapper.selectList(any())).thenReturn(List.of());
    }

    private SourceRegistryService service() {
        return new SourceRegistryServiceImpl(gatewayProperties, agentProperties,
                sourceMapper, registryService, events);
    }

    private GatewayProperties.Source configSource(String id, String url) {
        GatewayProperties.Source source = new GatewayProperties.Source();
        source.setId(id);
        source.setUrl(url);
        source.setServiceKeyName("X-Service-Key");
        source.setServiceKeyValue("config-key");
        return source;
    }

    private ToolSource row(String key, String origin) {
        ToolSource row = new ToolSource();
        row.setSourceKey(key);
        row.setGatewayId("gw1");
        row.setContractUrl("http://old.local/openapi.json");
        row.setOrigin(origin);
        row.setEnabled(1);
        row.setStatus(ToolSource.STATUS_OK);
        row.setServiceKeyName("X-Service-Key");
        row.setServiceKeyValue("old-key");
        return row;
    }

    private java.util.List<ToolSource> captureUpdates() {
        java.util.List<ToolSource> updated = new java.util.ArrayList<>();
        when(sourceMapper.updateById(any(ToolSource.class))).thenAnswer(invocation -> {
            updated.add(invocation.getArgument(0));
            return 1;
        });
        return updated;
    }

    @Test
    void seedingInsertsConfiguredSources() {
        gatewayProperties.setSources(List.of(configSource("booking", "http://booking.local/openapi.json")));
        java.util.List<ToolSource> inserted = new java.util.ArrayList<>();
        when(sourceMapper.insert(any(ToolSource.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        service().seedFromConfig();

        assertThat(inserted).hasSize(1);
        ToolSource source = inserted.get(0);
        assertThat(source.getSourceKey()).isEqualTo("booking");
        assertThat(source.getOrigin()).isEqualTo(ToolSource.ORIGIN_CONFIG);
        assertThat(source.getEnabled()).isEqualTo(1);
        assertThat(source.getServiceKeyValue()).isEqualTo("config-key");
    }

    @Test
    void seedingRefreshesConfigOwnedSources() {
        gatewayProperties.setSources(List.of(configSource("booking", "http://new.local/openapi.json")));
        when(sourceMapper.selectList(any())).thenReturn(List.of(row("booking", ToolSource.ORIGIN_CONFIG)));
        java.util.List<ToolSource> updated = captureUpdates();

        service().seedFromConfig();

        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).getContractUrl()).isEqualTo("http://new.local/openapi.json");
        verify(sourceMapper, never()).insert(any(ToolSource.class));
    }

    @Test
    void seedingNeverOverwritesAdminOwnedSources() {
        gatewayProperties.setSources(List.of(configSource("booking", "http://new.local/openapi.json")));
        when(sourceMapper.selectList(any())).thenReturn(List.of(row("booking", ToolSource.ORIGIN_ADMIN)));

        service().seedFromConfig();

        verify(sourceMapper, never()).insert(any(ToolSource.class));
        verify(sourceMapper, never()).updateById(any(ToolSource.class));
    }

    @Test
    void sourceRemovedFromConfigIsRetiredAndItsToolsOfflined() {
        gatewayProperties.setSources(List.of());
        ToolSource removed = row("expense", ToolSource.ORIGIN_CONFIG);
        removed.setStatus(ToolSource.STATUS_OK);
        when(sourceMapper.selectList(any())).thenReturn(List.of(removed));
        when(registryService.retireSource("gw1", "expense")).thenReturn(3);
        java.util.List<ToolSource> updated = captureUpdates();

        service().seedFromConfig();

        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).getStatus()).isEqualTo(ToolSource.STATUS_RETIRED);
        assertThat(updated.get(0).getEnabled()).isZero();
        verify(registryService).retireSource("gw1", "expense");
        verify(events).publishEvent(any(ToolCatalogChangedEvent.class));
    }

    @Test
    void missingOrDuplicateConfigIdFailsFast() {
        gatewayProperties.setSources(List.of(configSource(null, "http://a.local/openapi.json")));
        assertThatThrownBy(() -> service().seedFromConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id 不能为空");

        gatewayProperties.setSources(List.of(
                configSource("dup", "http://a.local/openapi.json"),
                configSource("dup", "http://b.local/openapi.json")));
        assertThatThrownBy(() -> service().seedFromConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    void createRejectsDuplicateKeyAndHidesCredentialsInViews() {
        when(sourceMapper.selectById("booking")).thenReturn(row("booking", ToolSource.ORIGIN_ADMIN));

        assertThatThrownBy(() -> service().create(new SourceRegistryService.SourceCommand(
                "booking", "http://booking.local/openapi.json", null, "X-Service-Key", "secret", true)))
                .isInstanceOf(SourceConflictException.class);

        ContractSyncService.SourceView view = ContractSyncService.SourceView.of(
                row("booking", ToolSource.ORIGIN_ADMIN));
        assertThat(view.serviceKeyConfigured()).isTrue();
        assertThat(view.toString()).doesNotContain("old-key");
    }

    @Test
    void updatePromotesConfigSourceToAdminAndKeepsUnspecifiedSecret() {
        ToolSource existing = row("booking", ToolSource.ORIGIN_CONFIG);
        when(sourceMapper.selectById("booking")).thenReturn(existing);

        Optional<ToolSource> updated = service().update("booking",
                new SourceRegistryService.SourceCommand("booking", "http://new.local/openapi.json",
                        "http://base.local", null, null, true));

        assertThat(updated).isPresent();
        assertThat(updated.get().getOrigin()).isEqualTo(ToolSource.ORIGIN_ADMIN);
        assertThat(updated.get().getServiceKeyValue()).isEqualTo("old-key");
        assertThat(updated.get().getBaseUrl()).isEqualTo("http://base.local");
    }

    @Test
    void updateDisablingSourceRetiresItAndOfflinesItsTools() {
        ToolSource existing = row("expense", ToolSource.ORIGIN_ADMIN);
        when(sourceMapper.selectById("expense")).thenReturn(existing);
        when(registryService.retireSource("gw1", "expense")).thenReturn(2);

        Optional<ToolSource> updated = service().update("expense",
                new SourceRegistryService.SourceCommand("expense", "http://expense.local/openapi.json",
                        null, null, null, false));

        assertThat(updated).isPresent();
        assertThat(updated.get().getEnabled()).isZero();
        assertThat(updated.get().getStatus()).isEqualTo(ToolSource.STATUS_RETIRED);
        verify(registryService).retireSource("gw1", "expense");
        verify(events).publishEvent(any(ToolCatalogChangedEvent.class));
    }

    @Test
    void updateReEnablingRetiredSourceRestoresPendingWithoutRetiringAgain() {
        ToolSource existing = row("expense", ToolSource.ORIGIN_ADMIN);
        existing.setEnabled(0);
        existing.setStatus(ToolSource.STATUS_RETIRED);
        when(sourceMapper.selectById("expense")).thenReturn(existing);

        Optional<ToolSource> updated = service().update("expense",
                new SourceRegistryService.SourceCommand("expense", "http://expense.local/openapi.json",
                        null, null, null, true));

        assertThat(updated).isPresent();
        assertThat(updated.get().getEnabled()).isEqualTo(1);
        assertThat(updated.get().getStatus()).isEqualTo(ToolSource.STATUS_PENDING);
        verify(registryService, never()).retireSource(any(), any());
    }

    @Test
    void retireRejectsConfigManagedSourceButRetiresAdminSource() {
        when(sourceMapper.selectById("booking")).thenReturn(row("booking", ToolSource.ORIGIN_CONFIG));
        when(sourceMapper.selectById("expense")).thenReturn(row("expense", ToolSource.ORIGIN_ADMIN));
        when(registryService.retireSource("gw1", "expense")).thenReturn(2);

        assertThatThrownBy(() -> service().retire("booking"))
                .isInstanceOf(SourceConflictException.class);
        assertThat(service().retire("expense")).isTrue();
        verify(registryService).retireSource("gw1", "expense");
    }
}
