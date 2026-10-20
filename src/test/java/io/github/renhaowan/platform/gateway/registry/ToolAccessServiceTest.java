package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    private ToolDefinition tool(String name, String requiredPermission) {
        ToolDefinition tool = new ToolDefinition();
        tool.setName(name);
        tool.setDescription("desc " + name);
        tool.setRequiredPermission(requiredPermission);
        return tool;
    }

    private UserPrincipal user(long uid, String... permissions) {
        return new UserPrincipal(uid, "STAFF", Set.of(permissions));
    }

    private void givenEnabled(ToolDefinition... tools) {
        when(registryService.listEnabled(GATEWAY)).thenReturn(List.of(tools));
    }

    // ---------- 闸门 1：清单组装（不做权限裁剪） ----------

    @Test
    void modelSeesEveryEnabledToolRegardlessOfPermission() {
        givenEnabled(tool("search_rooms", null), tool("cancel_booking", "booking:cancel"));

        // 无权工具也下发——在这里裁掉，模型只会答"我没有这个功能"，把权限问题说成能力问题
        assertThat(service().listForModel(GATEWAY))
                .extracting(ToolDefinition::getName)
                .containsExactly("search_rooms", "cancel_booking");
    }

    @Test
    void permissionRequirementTravelsWithTheToolSoDescriptionCanDiscloseIt() {
        givenEnabled(tool("search_rooms", null), tool("cancel_booking", "booking:cancel"));

        assertThat(service().listForModel(GATEWAY))
                .extracting(ToolDefinition::getRequiredPermission)
                .containsExactly(null, "booking:cancel");
    }

    // ---------- 闸门 2：执行点鉴权 ----------

    @Test
    void missingPermissionIsDeniedBeforeForwarding() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "booking:cancel")));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, user(9L), "cancel_booking", "{\"id\":13}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("权限不足").contains("booking:cancel");
        verify(forwarder, never()).forward(any(), any(), any());
    }

    @Test
    void matchingPermissionIsForwarded() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "booking:cancel")));
        when(forwarder.forward(any(ToolDefinition.class), any(), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(
                        true, 200, "{\"status\":\"CANCELLED\"}", null));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, user(9L, "booking:cancel"), "cancel_booking", "{\"id\":13}");

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("CANCELLED");
        verify(forwarder).forward(any(), any(), any());
    }

    @Test
    void wildcardPermissionCoversEveryRequiredPermission() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "booking:cancel")));
        when(forwarder.forward(any(ToolDefinition.class), any(), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "{}", null));

        assertThat(service().invoke(GATEWAY, user(9L, "*"), "cancel_booking", "{}").isError()).isFalse();
    }

    @Test
    void publicToolIsForwardedWithoutPermission() {
        when(registryService.find(GATEWAY, "search_rooms"))
                .thenReturn(Optional.of(tool("search_rooms", null)));
        when(forwarder.forward(any(ToolDefinition.class), any(), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "{\"rooms\":3}", null));

        assertThat(service().invoke(GATEWAY, user(9L), "search_rooms", "{}").isError()).isFalse();
    }

    @Test
    void callerIdTravelsWithTheCallSoBackendCanOwnDataPerUser() {
        when(registryService.find(GATEWAY, "list_bookings"))
                .thenReturn(Optional.of(tool("list_bookings", null)));
        when(forwarder.forward(any(ToolDefinition.class), any(), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "[]", null));

        service().invoke(GATEWAY, user(42L), "list_bookings", "{}");

        // 身份必须显式下穿到转发层——少了它，业务系统会把所有请求都记在系统账号名下
        verify(forwarder).forward(any(ToolDefinition.class), any(), eq(42L));
    }

    // ---------- 弹确认前的只读预判（不能替代闸门 2） ----------

    @Test
    void precheckTellsTheLoopToSkipConfirmForMissingPermission() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "booking:cancel")));

        Optional<String> denial = service().denial(GATEWAY, user(9L), "cancel_booking");

        assertThat(denial).isPresent();
        assertThat(denial.get()).contains("权限不足").contains("booking:cancel");
        verify(forwarder, never()).forward(any(), any(), any());
    }

    @Test
    void precheckPassesForMatchingPermissionAndUnknownToolsAreDenied() {
        when(registryService.find(GATEWAY, "cancel_booking"))
                .thenReturn(Optional.of(tool("cancel_booking", "booking:cancel")));
        when(registryService.find(GATEWAY, "ghost"))
                .thenReturn(Optional.empty());

        assertThat(service().denial(GATEWAY, user(9L, "booking:cancel"), "cancel_booking")).isEmpty();
        assertThat(service().denial(GATEWAY, user(9L, "booking:cancel"), "ghost")).hasValueSatisfying(
                reason -> assertThat(reason).contains("unknown tool"));
    }

    // ---------- 失败一律回喂文本，不抛异常 ----------

    @Test
    void unknownToolIsErrorResultNotException() {
        when(registryService.find(GATEWAY, "nope")).thenReturn(Optional.empty());

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, user(9L, "*"), "nope", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("unknown tool");
        verify(forwarder, never()).forward(any(), any(), any());
    }

    @Test
    void backendFailureIsCarriedBackAsErrorText() {
        when(registryService.find(GATEWAY, "create_booking"))
                .thenReturn(Optional.of(tool("create_booking", null)));
        when(forwarder.forward(any(ToolDefinition.class), any(), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(
                        false, 500, "", "backend responded 500"));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, user(9L), "create_booking", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("500");
    }

    @Test
    void malformedArgumentsAreReportedWithoutForwarding() {
        when(registryService.find(GATEWAY, "search_rooms"))
                .thenReturn(Optional.of(tool("search_rooms", null)));

        ToolAccessService.ToolCallResult result =
                service().invoke(GATEWAY, user(9L), "search_rooms", "{not json");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("JSON");
        verify(forwarder, never()).forward(any(), any(), any());
    }

    @Test
    void blankArgumentsAreTreatedAsNoArguments() {
        when(registryService.find(GATEWAY, "list_rooms"))
                .thenReturn(Optional.of(tool("list_rooms", null)));
        when(forwarder.forward(any(ToolDefinition.class), any(), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "[]", null));

        assertThat(service().invoke(GATEWAY, user(9L), "list_rooms", "").isError()).isFalse();
    }
}
