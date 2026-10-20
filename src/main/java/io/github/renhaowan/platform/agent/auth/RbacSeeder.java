package io.github.renhaowan.platform.agent.auth;

import io.github.renhaowan.platform.agent.auth.entity.AppRole;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import io.github.renhaowan.platform.agent.auth.mapper.AppRoleMapper;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐内置角色与它们的默认权限。
 * <p>{@code app_role.seeded} 是"默认权限只种一次"的标记：管理员在管理端改过之后重启不会复活默认值。
 * 新增内置角色或默认权限时，对已 seeded 的角色不会生效——这正是上限，避免覆盖人工配置。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "platform.rbac", name = "seed-on-startup",
        havingValue = "true", matchIfMissing = true)
public class RbacSeeder implements ApplicationRunner {

    private final AppRoleMapper roleMapper;
    private final RbacService rbacService;

    @Override
    public void run(ApplicationArguments args) {
        seedRole(AppUser.ROLE_ADMIN, "管理员", "平台管理端与全部权限", List.of(RbacService.WILDCARD));
        seedRole(AppUser.ROLE_STAFF, "员工", "仅使用业务工具与公开知识", List.of());
    }

    private void seedRole(String code, String name, String description, List<String> defaults) {
        AppRole role = roleMapper.selectById(code);
        if (role == null) {
            role = new AppRole();
            role.setCode(code);
            role.setName(name);
            role.setDescription(description);
            role.setBuiltin(1);
            role.setSeeded(0);
            roleMapper.insert(role);
        }
        if (role.getSeeded() != null && role.getSeeded() == 1) {
            return;
        }
        rbacService.replacePermissions(code, new LinkedHashSet<>(defaults));
        AppRole seeded = new AppRole();
        seeded.setCode(code);
        seeded.setSeeded(1);
        roleMapper.updateById(seeded);
        log.info("初始化角色 {} 的默认权限 {}", code, defaults);
    }
}
