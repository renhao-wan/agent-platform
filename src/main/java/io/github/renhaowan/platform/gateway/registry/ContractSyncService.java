package io.github.renhaowan.platform.gateway.registry;

import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 契约对账：把每份 OpenAPI 契约当作该来源的期望状态，与库里的工具集合对齐。
 * <p>
 * 参照 Kubernetes controller 的 reconciliation 模型：拉取失败时保留 last-known-good，
 * 成功拉取后才允许把"契约里已不存在的工具"下线。各来源独立对账，互不牵连。
 */
public interface ContractSyncService {

    /** 一份契约来源的调用参数。 */
    record SourceSpec(String sourceKey, String contractUrl, String baseUrl,
                      String serviceKeyName, String serviceKeyValue) {
    }

    /** 一次对账的结果；status 为 OK / FAILED / CONFLICT。 */
    record SyncReport(String sourceKey, String status, String contractHash,
                      int added, int updated, int unchanged, int stale, int breaking,
                      List<String> staleTools,
                      List<ToolRegistryService.BreakingChange> breakingChanges,
                      String error, LocalDateTime finishedAt) {
    }

    /** 管理端展示的来源状态。 */
    record SourceView(String sourceKey, String gatewayId, String contractUrl, String status,
                      String contractHash, int toolCount, int lastAdded, int lastUpdated,
                      int lastStale, int lastBreaking, LocalDateTime lastAttemptAt,
                      LocalDateTime lastSuccessAt, String lastError) {

        public static SourceView of(ToolSource source) {
            return new SourceView(source.getSourceKey(), source.getGatewayId(), source.getContractUrl(),
                    source.getStatus(), source.getContractHash(), intOf(source.getToolCount()),
                    intOf(source.getLastAdded()), intOf(source.getLastUpdated()), intOf(source.getLastStale()),
                    intOf(source.getLastBreaking()), source.getLastAttemptAt(), source.getLastSuccessAt(),
                    source.getLastError());
        }

        private static int intOf(Integer value) {
            return value == null ? 0 : value;
        }
    }

    /** 对配置里的全部来源各做一次对账；单个来源失败不影响其余来源。 */
    List<SyncReport> syncConfiguredSources();

    /** 对指定来源做一次对账；失败也返回报告，不向调用方抛异常。 */
    SyncReport sync(SourceSpec spec);

    /** 按来源标识触发一次对账；未配置该来源时返回 empty。 */
    Optional<SyncReport> syncByKey(String sourceKey);

    /** 全部来源的最近一次对账状态（含未配置但历史上出现过的来源）。 */
    List<SourceView> sources();
}
