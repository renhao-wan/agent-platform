package io.github.renhaowan.platform.gateway.tenant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 租户（apiKey 持有者，MCP 网关的鉴权与配额主体）。
 */
@Data
@TableName("tenant")
public class Tenant {

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String apiKey;

    private Integer status;

    private LocalDateTime createdAt;
}
