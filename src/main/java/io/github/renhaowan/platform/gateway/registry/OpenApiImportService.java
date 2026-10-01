package io.github.renhaowan.platform.gateway.registry;

import java.util.List;

/**
 * OpenAPI 3.0 JSON → MCP Tool 定义解析。
 * <p>
 * 映射规则：operationId→工具名（缺失则 method_path slug）；summary/description→描述；
 * path 参数填充 url_template 占位符；query 参数与 requestBody(application/json)
 * 合并生成 inputSchema；DELETE 默认 sensitive=true。
 */
public interface OpenApiImportService {

    List<ToolDefinition> parse(String gatewayId, Long tenantId, String openApiJson, String baseUrlOverride);
}
