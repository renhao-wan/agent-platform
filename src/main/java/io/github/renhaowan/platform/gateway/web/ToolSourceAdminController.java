package io.github.renhaowan.platform.gateway.web;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.gateway.registry.ContractSyncService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约来源的可观测性与手工对账入口。整个 /admin/** 由 JwtAuthFilter 要求 ADMIN 角色。
 * <p>
 * 定时对账默认每 5 分钟一次；这里用于排障与"改完契约立刻生效"的演示。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/admin/sources")
public class ToolSourceAdminController {

    private final ContractSyncService contractSyncService;

    @GetMapping
    public ApiResponse<List<ContractSyncService.SourceView>> sources() {
        return ApiResponse.ok(contractSyncService.sources());
    }

    @PostMapping("/sync")
    public ApiResponse<List<ContractSyncService.SyncReport>> syncAll() {
        return ApiResponse.ok(contractSyncService.syncConfiguredSources());
    }

    @PostMapping("/{sourceKey}/sync")
    public ResponseEntity<ApiResponse<ContractSyncService.SyncReport>> syncOne(
            @PathVariable String sourceKey) {
        return contractSyncService.syncByKey(sourceKey)
                .map(report -> ResponseEntity.ok(ApiResponse.ok(report)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ApiResponse.error(404, "来源未配置：" + sourceKey)));
    }
}
