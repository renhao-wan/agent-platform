package io.github.renhaowan.platform.gateway.web;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.gateway.registry.OpenApiImportService;
import io.github.renhaowan.platform.gateway.registry.ToolCatalogChangedEvent;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协议导入与工具查询（管理端）。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/admin")
public class ProtocolAdminController {

    public record ImportRequest(@NotBlank String gatewayId, @NotBlank String openapi,
                                String baseUrl, String authHeaderName, String authHeaderValue,
                                String sourceKey) {
    }

    public record ToolView(Long id, String name, String description, String httpMethod,
                           String urlTemplate, boolean requireConfirm, String requiredRole,
                           String sourceKey, String contractHash, boolean present, boolean enabled,
                           LocalDateTime updatedAt) {

        public static ToolView of(ToolDefinition t) {
            return new ToolView(t.getId(), t.getName(), t.getDescription(),
                    t.getHttpMethod(), t.getUrlTemplate(),
                    t.getRequireConfirm() != null && t.getRequireConfirm() == 1,
                    t.getRequiredRole(), t.getSourceKey(), t.getContractHash(),
                    t.getPresent() == null || t.getPresent() == 1,
                    t.getEnabled() == null || t.getEnabled() == 1,
                    t.getUpdatedAt());
        }
    }

    public record ImportResult(int imported, List<ToolView> tools) {
    }

    private final OpenApiImportService importService;
    private final ToolRegistryService registryService;
    private final ApplicationEventPublisher events;

    @PostMapping("/protocols/import")
    public ApiResponse<ImportResult> importProtocols(@RequestBody @Valid ImportRequest request) {
        List<ToolDefinition> tools = importService.parse(
                request.gatewayId(), request.openapi(), request.baseUrl());
        // 服务级凭证：该来源服务的所有工具共用（转发时自动附带，解决业务接口自身有鉴权的接入问题）
        tools.forEach(tool -> {
            tool.setAuthHeaderName(request.authHeaderName());
            tool.setAuthHeaderValue(request.authHeaderValue());
        });
        if (request.sourceKey() == null || request.sourceKey().isBlank()) {
            registryService.upsertAll(tools);
        } else {
            // 带 sourceKey 表示"这是一份完整契约快照"：契约里没有的工具会被下线。
            // 与自动来源分开命名空间，避免手工导入误伤同名业务来源。
            registryService.applySnapshot(request.gatewayId(),
                    "manual:" + request.gatewayId() + ":" + request.sourceKey(), tools);
        }
        events.publishEvent(new ToolCatalogChangedEvent("manual-import"));
        return ApiResponse.ok(new ImportResult(tools.size(), tools.stream().map(ToolView::of).toList()));
    }

    /** 管理端视图包含已下线/已禁用的工具，便于观察对账结果。 */
    @GetMapping("/tools")
    public ApiResponse<List<ToolView>> listTools(@RequestParam String gatewayId) {
        return ApiResponse.ok(registryService.listAll(gatewayId)
                .stream().map(ToolView::of).toList());
    }

    /** 人工启停。契约对账只改 present，不会覆盖这里设置的 enabled。 */
    @PutMapping("/tools/{id}/enabled")
    public ResponseEntity<ApiResponse<Void>> setEnabled(@PathVariable Long id,
                                                        @RequestParam boolean enabled) {
        if (!registryService.setEnabled(id, enabled)) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "工具不存在：" + id));
        }
        events.publishEvent(new ToolCatalogChangedEvent("manual-toggle"));
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
