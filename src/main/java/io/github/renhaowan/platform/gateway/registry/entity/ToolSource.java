package io.github.renhaowan.platform.gateway.registry.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 契约来源与最近一次对账结果。
 * <p>
 * 失败时保留上一次成功的数据（last-known-good）：业务系统暂时不可用不应该让工具集体消失。
 */
@Data
@TableName("tool_source")
public class ToolSource {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CONFLICT = "CONFLICT";
    public static final String STATUS_RETIRED = "RETIRED";

    /** 由配置文件种子创建，启动时按配置刷新；从配置移除即软下线。 */
    public static final String ORIGIN_CONFIG = "CONFIG";

    /** 管理端创建或编辑过的来源，配置文件不再覆盖。 */
    public static final String ORIGIN_ADMIN = "ADMIN";

    @TableId(type = IdType.INPUT)
    private String sourceKey;

    private String gatewayId;

    private String contractUrl;

    /** CONFIG | ADMIN：决定配置能否覆盖该来源 */
    private String origin;

    /** 0 = 已下线，不再参与对账，名下工具已软下线 */
    private Integer enabled;

    /** 覆盖契约 servers.url 的调用基地址；空则取契约地址的 origin */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String baseUrl;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String serviceKeyName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String serviceKeyValue;

    private String status;

    /** 本次成功契约的指纹；与上次相同则所有工具都未变化 */
    private String contractHash;

    private Integer toolCount;

    private Integer lastAdded;

    private Integer lastUpdated;

    private Integer lastStale;

    private Integer lastBreaking;

    private LocalDateTime lastAttemptAt;

    private LocalDateTime lastSuccessAt;

    /** 成功后必须显式清空，故绕过 MyBatis-Plus 跳过 null 的默认策略 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
}
