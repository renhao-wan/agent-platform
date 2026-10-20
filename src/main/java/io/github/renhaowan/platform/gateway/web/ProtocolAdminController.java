package io.github.renhaowan.platform.gateway.web;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.gateway.registry.ToolCatalogChangedEvent;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具查询与人工启停（管理端）。
 * <p>
 * 工具定义只能由契约对账写入：没有手工导入入口，否则会留下没有来源归属、
 * 不参与对账的幽灵工具。接入新接口请登记来源（{@code /admin/sources}）。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/admin")
public class ProtocolAdminController {

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

    private final ToolRegistryService registryService;
    private final ApplicationEventPublisher events;
    private final AgentProperties agentProperties;

    /** gatewayId 可选：管理台只有一个运行时网关，缺省取当前配置。 */
    @GetMapping("/tools")
    public ApiResponse<List<ToolView>> listTools(
            @RequestParam(required = false) String gatewayId) {
        String scope = gatewayId == null || gatewayId.isBlank()
                ? agentProperties.getGatewayId()
                : gatewayId;
        return ApiResponse.ok(registryService.listAll(scope)
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
