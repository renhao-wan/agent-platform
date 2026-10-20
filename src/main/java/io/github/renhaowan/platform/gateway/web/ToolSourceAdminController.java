package io.github.renhaowan.platform.gateway.web;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.gateway.registry.ContractSyncService;
import io.github.renhaowan.platform.gateway.registry.SourceRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约来源的注册、启停、对账与可观测性入口。整个 /admin/** 由 JwtAuthFilter 按 URI 映射权限码校验。
 * <p>
 * 这里能编辑的只有"来源"：契约地址、调用基地址、服务凭证、启停。工具定义永远由拉到的契约
 * 推导，管理端只读（启停在 /admin/tools），否则"契约即工具"就不成立了。
 * <p>
 * 凭证只入不出：查询接口只披露"是否已配置"，不回显任何片段。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/admin/sources")
public class ToolSourceAdminController {

    public record CreateSourceRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,128}",
                    message = "只允许字母、数字、点、下划线和连字符，最长 128") String sourceKey,
            @NotBlank String contractUrl, String baseUrl,
            String serviceKeyName, String serviceKeyValue, Boolean enabled) {

        SourceRegistryService.SourceCommand toCommand() {
            return new SourceRegistryService.SourceCommand(sourceKey, contractUrl, baseUrl,
                    serviceKeyName, serviceKeyValue, enabled);
        }
    }

    public record UpdateSourceRequest(
            @NotBlank String contractUrl, String baseUrl,
            String serviceKeyName, String serviceKeyValue, Boolean enabled) {

        SourceRegistryService.SourceCommand toCommand(String sourceKey) {
            return new SourceRegistryService.SourceCommand(sourceKey, contractUrl, baseUrl,
                    serviceKeyName, serviceKeyValue, enabled);
        }
    }

    private final SourceRegistryService sourceRegistryService;
    private final ContractSyncService contractSyncService;

    @GetMapping
    public ApiResponse<List<ContractSyncService.SourceView>> sources() {
        return ApiResponse.ok(contractSyncService.sources());
    }

    /** 新建来源并立即对账；抓取失败不回滚来源，状态会记为 FAILED 供管理端观察。 */
    @PostMapping
    public ApiResponse<ContractSyncService.SourceView> create(
            @RequestBody @Valid CreateSourceRequest request) {
        ToolSource source = sourceRegistryService.create(request.toCommand());
        contractSyncService.syncByKey(source.getSourceKey());
        return ApiResponse.ok(viewOf(source.getSourceKey()));
    }

    /** 编辑来源。编辑会把来源提升为 ADMIN，此后配置文件的种子不再覆盖它。 */
    @PutMapping("/{sourceKey}")
    public ResponseEntity<ApiResponse<ContractSyncService.SourceView>> update(
            @PathVariable String sourceKey,
            @RequestBody @Valid UpdateSourceRequest request) {
        return sourceRegistryService.update(sourceKey, request.toCommand(sourceKey))
                .map(source -> {
                    contractSyncService.syncByKey(sourceKey);
                    return ResponseEntity.ok(ApiResponse.ok(viewOf(sourceKey)));
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ApiResponse.error(404, "来源不存在：" + sourceKey)));
    }

    /** 下线来源：来源置 RETIRED，名下工具立即软下线（不删行，来源复活后可自动恢复）。 */
    @DeleteMapping("/{sourceKey}")
    public ResponseEntity<ApiResponse<Void>> retire(@PathVariable String sourceKey) {
        if (!sourceRegistryService.retire(sourceKey)) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "来源不存在：" + sourceKey));
        }
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/sync")
    public ApiResponse<List<ContractSyncService.SyncReport>> syncAll() {
        return ApiResponse.ok(contractSyncService.syncAll());
    }

    @PostMapping("/{sourceKey}/sync")
    public ResponseEntity<ApiResponse<ContractSyncService.SyncReport>> syncOne(
            @PathVariable String sourceKey) {
        return contractSyncService.syncByKey(sourceKey)
                .map(report -> ResponseEntity.ok(ApiResponse.ok(report)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ApiResponse.error(404, "来源不存在或已下线：" + sourceKey)));
    }

    private ContractSyncService.SourceView viewOf(String sourceKey) {
        return contractSyncService.sources().stream()
                .filter(view -> view.sourceKey().equals(sourceKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("来源写入后不可见：" + sourceKey));
    }
}
