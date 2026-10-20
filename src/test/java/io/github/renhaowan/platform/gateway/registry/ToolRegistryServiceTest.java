package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.impl.ContractHashes;
import io.github.renhaowan.platform.gateway.registry.impl.ToolRegistryServiceImpl;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolRegistryServiceTest {

    private final ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
    private final ToolRegistryService service = new ToolRegistryServiceImpl(mapper);

    private ToolDefinition tool(String name) {
        ToolDefinition tool = new ToolDefinition();
        tool.setGatewayId("gw1");
        tool.setName(name);
        tool.setHttpMethod("GET");
        tool.setUrlTemplate("http://h/" + name);
        tool.setDescription("查询 " + name);
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        tool.setRequireConfirm(0);
        tool.setCreatedAt(LocalDateTime.now());
        return tool;
    }

    /** 已落库版本：契约字段与入参一致，可按需覆盖状态字段。 */
    private ToolDefinition stored(ToolDefinition desired, String sourceKey) {
        ToolDefinition old = tool(desired.getName());
        old.setId(42L);
        old.setSourceKey(sourceKey);
        old.setContractHash(ContractHashes.of(desired));
        old.setPresent(1);
        old.setEnabled(1);
        old.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return old;
    }

    private void stubSelects(List<ToolDefinition> existingForSource) {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(existingForSource);
    }

    @Test
    void snapshotInsertsNewToolsWithSourceAndFingerprint() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(), List.of());

        ToolDefinition desired = tool("search_rooms");
        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of(desired));

        assertThat(result.added()).isEqualTo(1);
        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(mapper).insert(captor.capture());
        ToolDefinition inserted = captor.getValue();
        assertThat(inserted.getSourceKey()).isEqualTo("booking");
        assertThat(inserted.getPresent()).isEqualTo(1);
        assertThat(inserted.getEnabled()).isEqualTo(1);
        assertThat(inserted.getContractHash()).isEqualTo(ContractHashes.of(desired));
    }

    @Test
    void identicalContractIsNotRewritten() {
        ToolDefinition desired = tool("search_rooms");
        ToolDefinition old = stored(desired, "booking");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(old), List.of(old));

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of(desired));

        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.added()).isZero();
        verify(mapper, never()).insert(any(ToolDefinition.class));
        verify(mapper, never()).updateById(any(ToolDefinition.class));
    }

    @Test
    void contractChangeUpdatesButKeepsManualDisabledState() {
        ToolDefinition desired = tool("search_rooms");
        desired.setDescription("查询可用会议室（新描述）");
        ToolDefinition old = stored(tool("search_rooms"), "booking");
        old.setEnabled(0);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(old), List.of(old));

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of(desired));

        assertThat(result.updated()).isEqualTo(1);
        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(mapper).updateById(captor.capture());
        ToolDefinition written = captor.getValue();
        assertThat(written.getId()).isEqualTo(42L);
        assertThat(written.getEnabled()).isZero();
        assertThat(written.getDescription()).isEqualTo("查询可用会议室（新描述）");
        assertThat(written.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void toolMissingFromContractIsSoftRetired() {
        ToolDefinition old = stored(tool("legacy_query"), "booking");
        stubSelects(List.of(old));

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of());

        assertThat(result.stale()).isEqualTo(1);
        assertThat(result.staleNames()).containsExactly("legacy_query");
        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getPresent()).isZero();
    }

    @Test
    void alreadyRetiredToolStaysRetired() {
        ToolDefinition old = stored(tool("legacy_query"), "booking");
        old.setPresent(0);
        stubSelects(List.of(old));

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of());

        assertThat(result.stale()).isZero();
        verify(mapper, never()).updateById(any(ToolDefinition.class));
    }

    @Test
    void nameOwnedByAnotherLiveSourceAbortsWholeSnapshot() {
        ToolDefinition desired = tool("cancel_booking");
        ToolDefinition other = stored(desired, "expense");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(), List.of(other));

        assertThatThrownBy(() -> service.applySnapshot("gw1", "booking", List.of(desired)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel_booking")
                .hasMessageContaining("expense");

        verify(mapper, never()).insert(any(ToolDefinition.class));
        verify(mapper, never()).updateById(any(ToolDefinition.class));
    }

    @Test
    void staleToolFromAnotherSourceCanBeTakenOver() {
        ToolDefinition desired = tool("cancel_booking");
        ToolDefinition retired = stored(desired, "expense");
        retired.setPresent(0);
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(retired), List.of(retired));

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of(desired));

        assertThat(result.updated()).isEqualTo(1);
        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getSourceKey()).isEqualTo("booking");
        assertThat(captor.getValue().getPresent()).isEqualTo(1);
    }

    @Test
    void duplicateNameInsideOneContractIsRejectedBeforeAnyWrite() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.applySnapshot("gw1", "booking",
                List.of(tool("search_rooms"), tool("search_rooms"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("契约内工具名重复");

        verify(mapper, never()).insert(any(ToolDefinition.class));
    }

    @Test
    void reservedPlatformToolNameAbortsWholeSnapshot() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.applySnapshot("gw1", "booking",
                List.of(tool("search_knowledge"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search_knowledge")
                .hasMessageContaining("保留名");

        verify(mapper, never()).insert(any(ToolDefinition.class));
    }

    @Test
    void newWriteToolWithoutPermissionIsReportedButStillRegistered() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(), List.of());
        ToolDefinition desired = tool("create_booking");
        desired.setHttpMethod("POST");

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of(desired));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.breakingChanges())
                .extracting(ToolRegistryService.BreakingChange::type)
                .containsExactly("PERMISSION_UNDECLARED");
        verify(mapper).insert(any(ToolDefinition.class));
    }

    @Test
    void newReadToolWithoutPermissionIsNotReported() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(), List.of());

        ToolRegistryService.SnapshotResult result =
                service.applySnapshot("gw1", "booking", List.of(tool("search_rooms")));

        assertThat(result.breakingChanges()).isEmpty();
    }

    @Test
    void setEnabledTogglesOnlyEnabledFlag() {
        ToolDefinition old = tool("search_rooms");
        old.setId(42L);
        old.setEnabled(1);
        when(mapper.selectById(42L)).thenReturn(old);

        assertThat(service.setEnabled(42L, false)).isTrue();

        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getEnabled()).isZero();
        // 必须回写完整实体：description/requiredPermission/authHeader* 是 ALWAYS 策略的列，
        // 只带 id/enabled 的临时对象会把它们置 NULL（权限校验与服务凭证随之失效）
        assertThat(captor.getValue().getName()).isEqualTo("search_rooms");
        assertThat(captor.getValue().getDescription()).isEqualTo("查询 search_rooms");
        assertThat(captor.getValue().getUrlTemplate()).isEqualTo("http://h/search_rooms");
    }

    @Test
    void setEnabledReturnsFalseForUnknownTool() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThat(service.setEnabled(99L, true)).isFalse();
        verify(mapper, never()).updateById(any(ToolDefinition.class));
    }
}
