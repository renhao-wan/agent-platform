package io.github.renhaowan.platform.gateway.registry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 工具定义（一条 = 一个可被模型调用的业务接口，来源 OpenAPI 导入）。
 */
@Data
@TableName("tool_definition")
public class ToolDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String gatewayId;

    private String name;

    private String description;

    private String httpMethod;

    private String urlTemplate;

    /** 工具参数 schema（JSON 字符串，OpenAPI 导入产物） */
    private String inputSchema;

    private Integer requireConfirm;

    /** 角色闸门：NULL=全员可用；ADMIN|STAFF=仅该角色可见可调 */
    private String requiredRole;

    private Integer enabled;

    /** 转发时附带的服务凭证 header 名（如 X-Service-Key），导入时配置 */
    private String authHeaderName;

    /** 转发时附带的服务凭证 header 值 */
    private String authHeaderValue;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
