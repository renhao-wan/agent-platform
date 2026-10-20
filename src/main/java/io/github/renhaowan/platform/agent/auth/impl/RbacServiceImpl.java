package io.github.renhaowan.platform.agent.auth.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.auth.RbacService;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.auth.entity.AppRole;
import io.github.renhaowan.platform.agent.auth.entity.AppRolePermission;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.mapper.AppRoleMapper;
import io.github.renhaowan.platform.agent.auth.mapper.AppRolePermissionMapper;
import io.github.renhaowan.platform.agent.auth.mapper.AppUserMapper;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RbacServiceImpl implements RbacService {

    private final AppUserMapper userMapper;
    private final AppRoleMapper roleMapper;
    private final AppRolePermissionMapper rolePermissionMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;

    @Override
    public Optional<UserPrincipal> loadPrincipal(long uid) {
        AppUser user = userMapper.selectById(uid);
        if (user == null || user.getStatus() == null || user.getStatus() != AppUser.STATUS_ENABLED
                || user.getRole() == null || user.getRole().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new UserPrincipal(uid, user.getRole(), permissionsOfRole(user.getRole())));
    }

    @Override
    public Set<String> permissionsOfRole(String role) {
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        List<AppRolePermission> rows = rolePermissionMapper.selectList(
                new QueryWrapper<AppRolePermission>().eq("role_code", role));
        Set<String> permissions = new LinkedHashSet<>();
        for (AppRolePermission row : rows) {
            if (row.getPermissionCode() != null && !row.getPermissionCode().isBlank()) {
                permissions.add(row.getPermissionCode());
            }
        }
        return permissions;
    }

    @Override
    public List<RoleView> listRoles() {
        List<AppRole> roles = roleMapper.selectList(new QueryWrapper<AppRole>().orderByAsc("code"));
        List<RoleView> views = new ArrayList<>(roles.size());
        for (AppRole role : roles) {
            views.add(new RoleView(role.getCode(), role.getName(), role.getDescription(),
                    role.getBuiltin() != null && role.getBuiltin() == 1,
                    permissionsOfRole(role.getCode())));
        }
        return views;
    }

    @Override
    public List<String> knownPermissions() {
        Set<String> permissions = new LinkedHashSet<>(BUILTIN_PERMISSIONS);
        List<ToolDefinition> tools = toolDefinitionMapper.selectList(
                new QueryWrapper<ToolDefinition>().isNotNull("required_permission"));
        for (ToolDefinition tool : tools) {
            String permission = tool.getRequiredPermission();
            if (permission != null && !permission.isBlank()) {
                permissions.add(permission);
            }
        }
        return List.copyOf(permissions);
    }

    @Override
    @Transactional
    public void replacePermissions(String roleCode, Set<String> permissions) {
        if (roleCode == null || roleMapper.selectById(roleCode) == null) {
            throw new IllegalArgumentException("角色不存在：" + roleCode);
        }
        rolePermissionMapper.delete(new QueryWrapper<AppRolePermission>().eq("role_code", roleCode));
        if (permissions == null) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                unique.add(permission.strip());
            }
        }
        for (String permission : unique) {
            AppRolePermission row = new AppRolePermission();
            row.setRoleCode(roleCode);
            row.setPermissionCode(permission);
            rolePermissionMapper.insert(row);
        }
    }
}
