package io.github.renhaowan.platform.gateway.web;

import io.github.renhaowan.platform.gateway.registry.OpenApiImportService;
import io.github.renhaowan.platform.gateway.registry.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协议导入与工具查询（管理端）。
 */
@RestController
@RequestMapping("/admin")
public class ProtocolAdminController {

    public record ImportRequest(@NotBlank String gatewayId, @NotBlank String openapi,
                                String baseUrl, String authHeaderName, String authHeaderValue) {
    }

    public record ToolView(Long id, String name, String description, String httpMethod,
                           String urlTemplate, boolean requireConfirm) {

        public static ToolView of(ToolDefinition t) {
            return new ToolView(t.getId(), t.getName(), t.getDescription(),
                    t.getHttpMethod(), t.getUrlTemplate(), t.getRequireConfirm() != null && t.getRequireConfirm() == 1);
        }
    }

    public record ImportResult(int imported, List<ToolView> tools) {
    }

    private final OpenApiImportService importService;
    private final ToolRegistryService registryService;

    public ProtocolAdminController(OpenApiImportService importService, ToolRegistryService registryService) {
        this.importService = importService;
        this.registryService = registryService;
    }

    @PostMapping("/protocols/import")
    public ImportResult importProtocols(@RequestBody @Valid ImportRequest request) {
        TenantContext.TenantInfo tenant = TenantContext.get();
        List<ToolDefinition> tools = importService.parse(
                request.gatewayId(), tenant.id(), request.openapi(), request.baseUrl());
        // 服务级凭证：该来源服务的所有工具共用（转发时自动附带，解决业务接口自身有鉴权的接入问题）
        tools.forEach(tool -> {
            tool.setAuthHeaderName(request.authHeaderName());
            tool.setAuthHeaderValue(request.authHeaderValue());
        });
        registryService.upsertAll(tools);
        return new ImportResult(tools.size(), tools.stream().map(ToolView::of).toList());
    }

    @GetMapping("/tools")
    public List<ToolView> listTools(@RequestParam String gatewayId) {
        TenantContext.TenantInfo tenant = TenantContext.get();
        return registryService.listEnabled(tenant.id(), gatewayId).stream().map(ToolView::of).toList();
    }
}
