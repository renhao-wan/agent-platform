package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.auth.entity.AppRole;
import io.github.renhaowan.platform.agent.auth.mapper.AppRoleMapper;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 默认权限只种一次：管理员改过之后，重启不得把人工配置盖回去。 */
@ExtendWith(MockitoExtension.class)
class RbacSeederTest {

    @Mock
    private AppRoleMapper roleMapper;
    @Mock
    private RbacService rbacService;

    @Test
    void missingRolesAreCreatedWithDefaultsAndMarkedSeeded() {
        when(roleMapper.selectById("ADMIN")).thenReturn(null);
        when(roleMapper.selectById("STAFF")).thenReturn(null);

        new RbacSeeder(roleMapper, rbacService).run(null);

        ArgumentCaptor<AppRole> inserted = ArgumentCaptor.forClass(AppRole.class);
        verify(roleMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(AppRole::getCode)
                .containsExactly("ADMIN", "STAFF");
        assertThat(inserted.getAllValues()).allSatisfy(role -> assertThat(role.getSeeded()).isZero());

        verify(rbacService).replacePermissions(eq("ADMIN"), eq(new LinkedHashSet<>(List.of("*"))));
        verify(rbacService).replacePermissions(eq("STAFF"), eq(new LinkedHashSet<>()));
        verify(roleMapper, org.mockito.Mockito.times(2)).updateById(any(AppRole.class));
    }

    @Test
    void alreadySeededRolesAreNeverOverwritten() {
        when(roleMapper.selectById("ADMIN")).thenReturn(role("ADMIN", 1));
        when(roleMapper.selectById("STAFF")).thenReturn(role("STAFF", 1));

        new RbacSeeder(roleMapper, rbacService).run(null);

        verify(roleMapper, never()).insert(any(AppRole.class));
        verify(roleMapper, never()).updateById(any(AppRole.class));
        verify(rbacService, never()).replacePermissions(any(), any());
    }

    @Test
    void existingUnseededRoleGetsDefaults() {
        when(roleMapper.selectById("ADMIN")).thenReturn(role("ADMIN", 0));
        when(roleMapper.selectById("STAFF")).thenReturn(role("STAFF", 1));

        new RbacSeeder(roleMapper, rbacService).run(null);

        verify(rbacService).replacePermissions(eq("ADMIN"), any());
        verify(roleMapper).updateById(any(AppRole.class));
        verify(rbacService, never()).replacePermissions(eq("STAFF"), any());
    }

    private static AppRole role(String code, int seeded) {
        AppRole role = new AppRole();
        role.setCode(code);
        role.setSeeded(seeded);
        return role;
    }
}
