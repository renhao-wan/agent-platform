package io.github.renhaowan.platform.agent.auth;

import io.github.renhaowan.platform.common.ApiResponse;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色-权限矩阵（管理端）。只做基本 RBAC：查看角色当前权限、覆盖式保存。
 * 不做角色 CRUD 与用户角色分配——当前是单角色模型，角色来自 app_user.role。
 * <p>权限码全集 = 平台内置 admin:* + 业务契约声明的 x-required-permission。
 */
@RestController
@RequestMapping("/admin/rbac")
@RequiredArgsConstructor
public class RbacAdminController {

    public record PermissionsBody(Set<String> permissions) {
    }

    private final RbacService rbacService;

    @GetMapping("/roles")
    public ApiResponse<List<RbacService.RoleView>> roles() {
        return ApiResponse.ok(rbacService.listRoles());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<String>> permissions() {
        return ApiResponse.ok(rbacService.knownPermissions());
    }

    @PutMapping("/roles/{code}/permissions")
    public ResponseEntity<ApiResponse<Void>> updatePermissions(@PathVariable String code,
                                                               @RequestBody PermissionsBody body) {
        try {
            rbacService.replacePermissions(code, body.permissions());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage()));
        }
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
