package io.github.renhaowan.platform.gateway.web;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.gateway.tenant.Tenant;
import io.github.renhaowan.platform.gateway.tenant.TenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理（平台管理端，凭据由部署方保管，不走 X-Api-Key 鉴权）。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/admin/tenants")
public class TenantAdminController {

    public record CreateTenantRequest(@NotBlank String name) {
    }

    public record TenantView(Long id, String name, String apiKey) {

        public static TenantView of(Tenant tenant) {
            return new TenantView(tenant.getId(), tenant.getName(), tenant.getApiKey());
        }
    }

    private final TenantService tenantService;

    @PostMapping
    public ApiResponse<TenantView> create(@RequestBody @Valid CreateTenantRequest request) {
        return ApiResponse.ok(TenantView.of(tenantService.create(request.name())));
    }

    @GetMapping
    public ApiResponse<List<TenantView>> list() {
        return ApiResponse.ok(tenantService.list().stream().map(TenantView::of).toList());
    }
}
