package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 清单组装与执行点鉴权的契约测试——闸门 2 是平台的安全边界，断言必须直白可见。 */
@ExtendWith(MockitoExtension.class)
class ToolAccessServiceTest {

    private static final String GATEWAY = "gw1";

    @Mock
    private ToolRegistryService registryService;

    @Mock
    private GenericHttpForwarder forwarder;

    private ToolAccessService service() {
        return new ToolAccessService(registryService, forwarder);
    }

    private ToolDefinition tool(String name, String requiredRole) {
        ToolDefinition tool = new ToolDefinition();
        tool.setName(name);
        tool.setDescription("desc " + name);
        tool.setRequiredRole(requiredRole);
        return tool;
    }

    private void givenEnabled(ToolDefinition... tools) {
        when(registryService.listEnabled(GATEWAY)).thenReturn(List.of(tools));
    }

    // ---------- 闸门 1：清单组装（不做角色裁剪） ----------

    @Test
    void modelSeesEveryEnabledToolRegardlessOfRole() {
        givenEnabled(tool("search_rooms", null), tool("cancel_booking", "ADMIN"));

        // 无权工具也下发——在这里裁掉，模型只会答"我没有这个功能"，把权限问题说成能力问题
        assertThat(service().listForModel(GATEWAY))
                .extracting(ToolDefinition::getName)
                .containsExactly("search_rooms", "cancel_booking");
    }

    @Test
    void roleRequirementTravelsWithTheToolSoDescriptionCanDiscloseIt() {
        givenEnabled(tool("search_rooms", null), tool("cancel_booking", "ADMIN"));

        assertThat(service().listForModel(GATEWAY))
                .extracting(ToolDefinition::getRequiredRole)
                .containsExactly(null, "ADMIN");
    }

    // ---------- 闸门 2：执行点鉴权 ----------

    @Test
    void wrongRoleIsDeniedBeforeForwarding() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "ADMIN")));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, "STAFF", "cancel_booking", "{\"id\":13}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("权限不足").contains("ADMIN");
        verify(forwarder, never()).forward(any(), any());
    }

    @Test
    void matchingRoleIsForwarded() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "ADMIN")));
        when(forwarder.forward(any(ToolDefinition.class), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(
                        true, 200, "{\"status\":\"CANCELLED\"}", null));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, "ADMIN", "cancel_booking", "{\"id\":13}");

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("CANCELLED");
        verify(forwarder).forward(any(), any());
    }

    @Test
    void publicToolIsForwardedWithoutRole() {
        when(registryService.find(GATEWAY, "search_rooms"))
                .thenReturn(Optional.of(tool("search_rooms", null)));
        when(forwarder.forward(any(ToolDefinition.class), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "{\"rooms\":3}", null));

        assertThat(service().invoke(GATEWAY, "STAFF", "search_rooms", "{}").isError()).isFalse();
    }

    // ---------- 失败一律回喂文本，不抛异常 ----------

    @Test
    void unknownToolIsErrorResultNotException() {
        when(registryService.find(GATEWAY, "nope")).thenReturn(Optional.empty());

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, "ADMIN", "nope", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("unknown tool");
        verify(forwarder, never()).forward(any(), any());
    }

    @Test
    void backendFailureIsCarriedBackAsErrorText() {
        when(registryService.find(GATEWAY, "create_booking"))
                .thenReturn(Optional.of(tool("create_booking", null)));
        when(forwarder.forward(any(ToolDefinition.class), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(
                        false, 500, "", "backend responded 500"));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, "STAFF", "create_booking", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("500");
    }

    @Test
    void malformedArgumentsAreReportedWithoutForwarding() {
        when(registryService.find(GATEWAY, "search_rooms"))
                .thenReturn(Optional.of(tool("search_rooms", null)));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, "STAFF", "search_rooms", "{not json");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("JSON");
        verify(forwarder, never()).forward(any(), any());
    }

    @Test
    void blankArgumentsAreTreatedAsNoArguments() {
        when(registryService.find(GATEWAY, "list_rooms"))
                .thenReturn(Optional.of(tool("list_rooms", null)));
        when(forwarder.forward(any(ToolDefinition.class), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "[]", null));

        assertThat(service().invoke(GATEWAY, "STAFF", "list_rooms", "").isError()).isFalse();
    }
}
