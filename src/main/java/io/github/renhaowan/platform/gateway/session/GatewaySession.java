package io.github.renhaowan.platform.gateway.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * MCP 网关会话。会话体存 Redis（TTL 滑动续期），本表只留审计轨迹；
 * 多实例部署时 instanceId 标记 SSE 连接归属，配合 PubSub 广播路由。
 */
@TableName("gateway_session")
public class GatewaySession {

    public static final String TRANSPORT_SSE = "SSE";
    public static final String TRANSPORT_STREAMABLE = "STREAMABLE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionKey;

    private Long tenantId;

    private String gatewayId;

    private String transport;

    private String instanceId;

    private LocalDateTime createdAt;

    private LocalDateTime expiredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiredAt() {
        return expiredAt;
    }

    public void setExpiredAt(LocalDateTime expiredAt) {
        this.expiredAt = expiredAt;
    }
}
