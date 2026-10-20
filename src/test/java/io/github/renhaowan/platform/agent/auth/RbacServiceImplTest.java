package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.auth.entity.AppRole;
import io.github.renhaowan.platform.agent.auth.entity.AppRolePermission;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.impl.RbacServiceImpl;
import io.github.renhaowan.platform.agent.auth.mapper.AppRoleMapper;
import io.github.renhaowan.platform.agent.auth.mapper.AppRolePermissionMapper;
import io.github.renhaowan.platform.agent.auth.mapper.AppUserMapper;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 角色/权限的读取与覆盖式保存；授权数据是可变状态，必须每次请求重读。 */
@ExtendWith(MockitoExtension.class)
class RbacServiceImplTest {

    @Mock
    private AppUserMapper userMapper;
    @Mock
    private AppRoleMapper roleMapper;
    @Mock
    private AppRolePermissionMapper rolePermissionMapper;
    @Mock
    private ToolDefinitionMapper toolDefinitionMapper;

    private RbacServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RbacServiceImpl(userMapper, roleMapper, rolePermissionMapper, toolDefinitionMapper);
    }

    @Test
    void loadPrincipalCarriesRoleAndItsPermissions() {
        when(userMapper.selectById(7L)).thenReturn(user("STAFF", AppUser.STATUS_ENABLED));
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(permission("booking:cancel"), permission("expense:approve")));

        Optional<UserPrincipal> principal = service.loadPrincipal(7L);

        assertThat(principal).isPresent();
        assertThat(principal.get().uid()).isEqualTo(7L);
        assertThat(principal.get().role()).isEqualTo("STAFF");
        assertThat(principal.get().permissions()).containsExactlyInAnyOrder("booking:cancel", "expense:approve");
    }

    @Test
    void disabledOrRemovedOrRolelessAccountsLoadNothing() {
        when(userMapper.selectById(1L)).thenReturn(user("ADMIN", 0));
        when(userMapper.selectById(2L)).thenReturn(null);
        when(userMapper.selectById(3L)).thenReturn(user("  ", AppUser.STATUS_ENABLED));

        assertThat(service.loadPrincipal(1L)).isEmpty();
        assertThat(service.loadPrincipal(2L)).isEmpty();
        assertThat(service.loadPrincipal(3L)).isEmpty();
    }

    @Test
    void permissionsOfRoleSkipsBlankRows() {
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                permission("booking:cancel"), permission("  "), permission(null)));

        assertThat(service.permissionsOfRole("STAFF")).containsExactly("booking:cancel");
    }

    @Test
    void replacePermissionsDeletesThenInsertsTrimmedUniqueValues() {
        when(roleMapper.selectById("STAFF")).thenReturn(role("STAFF", 0));

        LinkedHashSet<String> input = new LinkedHashSet<>();
        input.add(" booking:cancel ");
        input.add("");
        input.add("   ");
        service.replacePermissions("STAFF", input);

        verify(rolePermissionMapper).delete(any());
        ArgumentCaptor<AppRolePermission> inserted = ArgumentCaptor.forClass(AppRolePermission.class);
        verify(rolePermissionMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRoleCode()).isEqualTo("STAFF");
        assertThat(inserted.getValue().getPermissionCode()).isEqualTo("booking:cancel");
    }

    @Test
    void replacePermissionsRejectsUnknownRole() {
        when(roleMapper.selectById("GHOST")).thenReturn(null);

        assertThatThrownBy(() -> service.replacePermissions("GHOST", Set.of("admin:tool")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("角色不存在");
    }

    @Test
    void knownPermissionsCombinesBuiltinsAndContractDeclarations() {
        ToolDefinition declared = new ToolDefinition();
        declared.setRequiredPermission("booking:cancel");
        ToolDefinition blank = new ToolDefinition();
        blank.setRequiredPermission(" ");
        when(toolDefinitionMapper.selectList(any())).thenReturn(List.of(declared, blank));

        assertThat(service.knownPermissions())
                .contains(RbacService.ADMIN_CONSOLE, RbacService.ADMIN_SOURCE, RbacService.ADMIN_TOOL,
                        RbacService.ADMIN_KNOWLEDGE, RbacService.ADMIN_RBAC, "booking:cancel");
    }

    @Test
    void listRolesIncludesPermissionsPerRole() {
        when(roleMapper.selectList(any())).thenReturn(List.of(role("ADMIN", 1), role("STAFF", 1)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(permission("*")));

        List<RbacService.RoleView> roles = service.listRoles();

        assertThat(roles).hasSize(2);
        assertThat(roles).allSatisfy(view -> assertThat(view.permissions()).containsExactly("*"));
    }

    private static AppUser user(String role, int status) {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private static AppRole role(String code, int seeded) {
        AppRole role = new AppRole();
        role.setCode(code);
        role.setName(code);
        role.setBuiltin(1);
        role.setSeeded(seeded);
        return role;
    }

    private static AppRolePermission permission(String code) {
        AppRolePermission permission = new AppRolePermission();
        permission.setRoleCode("STAFF");
        permission.setPermissionCode(code);
        return permission;
    }
}
