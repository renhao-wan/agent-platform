package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.common.ApiResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class RbacAdminControllerTest {

    private final RbacService rbacService = mock(RbacService.class);
    private final RbacAdminController controller = new RbacAdminController(rbacService);

    @Test
    void rolesAreServedWithTheirPermissions() {
        RbacService.RoleView view = new RbacService.RoleView(
                "STAFF", "员工", "仅业务工具", true, Set.of("booking:cancel"));
        when(rbacService.listRoles()).thenReturn(List.of(view));

        ApiResponse<List<RbacService.RoleView>> response = controller.roles();

        assertThat(response.code()).isEqualTo(ApiResponse.OK_CODE);
        assertThat(response.data()).containsExactly(view);
    }

    @Test
    void knownPermissionsAreExposedForTheMatrix() {
        when(rbacService.knownPermissions()).thenReturn(List.of("admin:tool", "booking:cancel"));

        assertThat(controller.permissions().data()).containsExactly("admin:tool", "booking:cancel");
    }

    @Test
    void permissionsAreReplacedForTheGivenRole() {
        ResponseEntity<ApiResponse<Void>> response = controller.updatePermissions(
                "STAFF", new RbacAdminController.PermissionsBody(Set.of("booking:cancel")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(rbacService).replacePermissions("STAFF", Set.of("booking:cancel"));
    }

    @Test
    void unknownRoleYields404() {
        doThrow(new IllegalArgumentException("角色不存在：GHOST")).when(rbacService)
                .replacePermissions("GHOST", Set.of("admin:tool"));

        ResponseEntity<ApiResponse<Void>> response = controller.updatePermissions(
                "GHOST", new RbacAdminController.PermissionsBody(Set.of("admin:tool")));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().message()).contains("角色不存在");
    }
}
