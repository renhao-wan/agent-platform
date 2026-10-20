package io.github.renhaowan.platform.gateway.registry.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 工具定义（一条 = 一个可被模型调用的业务接口，来源 OpenAPI 导入）。
 * <p>
 * 可空字段一律标 {@code updateStrategy = ALWAYS}：这些列全由导入结果决定，
 * 而 MyBatis-Plus 默认跳过 null 字段，会让"业务系统删掉的声明"永远留在库里——
 * 例如接口不再要求 ADMIN 后重新导入，旧值不覆盖就等于声明改不动。
 */
@Data
@TableName("tool_definition")
public class ToolDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String gatewayId;

    private String name;

    /** 契约来源标识；工具只能由来源对账写入，因此非空 */
    private String sourceKey;

    /** 契约字段的指纹：同值表示这次同步无需重写该行 */
    private String contractHash;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    private String httpMethod;

    private String urlTemplate;

    /** 工具参数 schema（JSON 字符串，OpenAPI 导入产物） */
    private String inputSchema;

    private Integer requireConfirm;

    /** 角色闸门：NULL=全员可用；ADMIN|STAFF=仅该角色可见可调 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String requiredRole;

    private Integer enabled;

    /** 契约中是否仍声明该工具：同步负责置 0/1，人工禁用用 enabled，两者互不覆盖 */
    private Integer present;

    /** 转发时附带的服务凭证 header 名（如 X-Service-Key），导入时配置 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String authHeaderName;

    /** 转发时附带的服务凭证 header 值 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String authHeaderValue;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
