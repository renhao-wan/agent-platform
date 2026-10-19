package io.github.renhaowan.platform.gateway.registry;

import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;

/**
 * OpenAPI 3.0 JSON → 工具定义解析。
 * <p>
 * 映射规则：operationId→工具名（缺失则 method_path slug）；summary/description→描述；
 * path 参数填充 url_template 占位符；query 参数与 requestBody(application/json)
 * 合并生成 inputSchema。
 * <p>
 * 角色要求与敏感标记一律取自业务系统自带的扩展字段——{@code x-required-role} 与
 * {@code x-require-confirm}；DELETE 方法默认视为敏感。平台不猜测接口语义，
 * 因此「哪些接口要管理员」「哪些接口要二次确认」是各业务系统的契约，不是平台的业务知识。
 */
public interface OpenApiImportService {

    List<ToolDefinition> parse(String gatewayId, String openApiJson, String baseUrlOverride);
}
