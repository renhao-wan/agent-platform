package io.github.renhaowan.platform.gateway.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * MCP 网关会话。会话体存 Redis（TTL 滑动续期），本表只留审计轨迹；
 * 多实例部署时 instanceId 标记 SSE 连接归属，配合 PubSub 广播路由。
 */
@Data
@TableName("gateway_session")
public class GatewaySession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionKey;

    private Long tenantId;

    private String gatewayId;

    private String transport;

    private String instanceId;

    private LocalDateTime createdAt;

    private LocalDateTime expiredAt;
}
