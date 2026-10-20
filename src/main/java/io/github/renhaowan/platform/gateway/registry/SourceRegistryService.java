package io.github.renhaowan.platform.gateway.registry;

import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import java.util.List;
import java.util.Optional;

/**
 * 契约来源注册表。
 * <p>
 * 来源可由配置文件种子创建，也可由管理员在运行时新增/编辑/下线。配置文件只负责首次种子
 * （以及未被子接管时的刷新），一旦管理员编辑过某来源，它就归管理端所有，配置不再覆盖。
 * <p>
 * 管理员能编辑的只有"来源"——契约地址、调用基地址、服务凭证、启停；工具定义永远由拉到的
 * 契约推导，管理端只读（只能启停），否则"契约即工具"就不再成立。
 */
public interface SourceRegistryService {

    /**
     * 新建/编辑来源的参数。
     * <p>
     * 编辑时 {@code serviceKeyName}/{@code serviceKeyValue} 为 {@code null} 表示保持原值，
     * 空字符串表示显式清空——凭证一旦入库就不回显，客户端无法用"原值"覆盖。
     */
    record SourceCommand(String sourceKey, String contractUrl, String baseUrl,
                         String serviceKeyName, String serviceKeyValue, Boolean enabled) {
    }

    /**
     * 以配置为种子对来源表收敛：配置新增则插入、配置变化则刷新（仅 CONFIG 来源）、
     * 配置移除则软下线（仅 CONFIG 来源）。id 缺失或重复视为配置错误，直接抛异常阻止启动。
     */
    void seedFromConfig();

    /** 全部来源（含已下线），供管理端查看。 */
    List<ToolSource> listAll();

    /** 参与对账的来源：enabled=1 且未 RETIRED。 */
    List<ToolSource> listActive();

    Optional<ToolSource> find(String sourceKey);

    /** 管理端新建来源；同键已存在时抛 {@link SourceConflictException}。 */
    ToolSource create(SourceCommand command);

    /** 管理端编辑来源；不存在返回 empty。编辑会把来源提升为 ADMIN，之后配置不再覆盖。 */
    Optional<ToolSource> update(String sourceKey, SourceCommand command);

    /** 管理端下线来源：状态置 RETIRED，名下工具立即软下线。配置管理的来源拒绝下线。 */
    boolean retire(String sourceKey);
}
