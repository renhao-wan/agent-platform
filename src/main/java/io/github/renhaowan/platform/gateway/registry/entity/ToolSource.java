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

    @TableId(type = IdType.INPUT)
    private String sourceKey;

    private String gatewayId;

    private String contractUrl;

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
