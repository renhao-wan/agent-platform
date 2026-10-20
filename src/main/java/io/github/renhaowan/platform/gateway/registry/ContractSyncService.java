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
 * <p>
 * 来源清单来自 {@link SourceRegistryService}（配置种子 + 管理端注册），不再直接读配置。
 */
public interface ContractSyncService {

    /** 对账入口互斥：抢不到锁表示已有一次对账在跑，本次不排队、不写库。 */
    String STATUS_BUSY = "BUSY";

    /** 一份契约来源的调用参数。 */
    record SourceSpec(String sourceKey, String contractUrl, String baseUrl,
                      String serviceKeyName, String serviceKeyValue) {
    }

    /** 一次对账的结果；status 为 OK / FAILED / CONFLICT / BUSY。 */
    record SyncReport(String sourceKey, String status, String contractHash,
                      int added, int updated, int unchanged, int stale, int breaking,
                      List<String> staleTools,
                      List<ToolRegistryService.BreakingChange> breakingChanges,
                      String error, LocalDateTime finishedAt) {
    }

    /** 管理端展示的来源状态。凭证只披露"是否配置"，不回显任何片段。 */
    record SourceView(String sourceKey, String gatewayId, String contractUrl, String baseUrl,
                      String origin, boolean enabled, boolean serviceKeyConfigured, String status,
                      String contractHash, int toolCount, int lastAdded, int lastUpdated,
                      int lastStale, int lastBreaking,
                      List<String> staleTools, List<ToolRegistryService.BreakingChange> breakingChanges,
                      LocalDateTime lastAttemptAt,
                      LocalDateTime lastSuccessAt, String lastError) {

        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();

        public static SourceView of(ToolSource source) {
            return new SourceView(source.getSourceKey(), source.getGatewayId(), source.getContractUrl(),
                    source.getBaseUrl(), source.getOrigin(),
                    source.getEnabled() == null || source.getEnabled() == 1,
                    source.getServiceKeyValue() != null && !source.getServiceKeyValue().isBlank(),
                    source.getStatus(), source.getContractHash(), intOf(source.getToolCount()),
                    intOf(source.getLastAdded()), intOf(source.getLastUpdated()), intOf(source.getLastStale()),
                    intOf(source.getLastBreaking()),
                    parseList(source.getLastStaleTools(), String.class),
                    parseList(source.getLastBreakingChanges(), ToolRegistryService.BreakingChange.class),
                    source.getLastAttemptAt(), source.getLastSuccessAt(), source.getLastError());
        }

        private static int intOf(Integer value) {
            return value == null ? 0 : value;
        }

        /** 明细列是观测信息：解析失败按"无明细"处理，不能让来源列表整个 500。 */
        private static <T> List<T> parseList(String json, Class<T> type) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            try {
                return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, type));
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    /** 对全部启用来源各做一次对账；单个来源失败不影响其余来源。并发触发时返回单条 BUSY。 */
    List<SyncReport> syncAll();

    /** 对指定来源做一次对账；失败也返回报告，不向调用方抛异常。 */
    SyncReport sync(SourceSpec spec);

    /** 按来源标识触发一次对账；来源不存在或已下线时返回 empty。 */
    Optional<SyncReport> syncByKey(String sourceKey);

    /** 全部来源的最近一次对账状态（含已下线来源）。 */
    List<SourceView> sources();
}
