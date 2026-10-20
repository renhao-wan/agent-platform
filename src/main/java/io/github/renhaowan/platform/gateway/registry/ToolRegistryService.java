package io.github.renhaowan.platform.gateway.registry;

import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;

/**
 * 工具注册表：按 gatewayId 作用域管理工具定义。
 * <p>
 * "有效工具" = 契约仍声明（present=1）且未被人工禁用（enabled=1）。两个开关分开存：
 * 契约对账只改 present，人工操作只改 enabled，互不覆盖。
 */
public interface ToolRegistryService {

    /** 可下发给模型的工具：present=1 且 enabled=1。 */
    List<ToolDefinition> listEnabled(String gatewayId);

    /** 可执行工具查询；已下线/已禁用与不存在一样返回 empty。 */
    Optional<ToolDefinition> find(String gatewayId, String name);

    /** 管理端视图：包含已下线与已禁用的工具。 */
    List<ToolDefinition> listAll(String gatewayId);

    /** 手工导入（无来源快照语义）：同名更新，不处理删除。 */
    void upsertAll(List<ToolDefinition> tools);

    /**
     * 以一份完整契约为期望状态做对账：新增、更新、把本来源缺失的工具标记为下线。
     * 只有在成功解析整份契约后调用；任何冲突都在写入前抛出，调用方据此保留上一版数据。
     */
    SnapshotResult applySnapshot(String gatewayId, String sourceKey, List<ToolDefinition> desired);

    /** 人工启停；返回 false 表示工具不存在。 */
    boolean setEnabled(Long id, boolean enabled);

    /** 一次对账的增量结果。 */
    record SnapshotResult(int added, int updated, int unchanged, int stale,
                          List<String> staleNames, List<BreakingChange> breakingChanges) {
    }

    /** 可能影响既有调用的契约变化（只用于告警与审计）。 */
    record BreakingChange(String toolName, String type, String detail) {
    }
}
