package io.github.renhaowan.platform.gateway.registry.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.registry.OpenApiImportService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** OpenApiImportService 接口实现。 */
@Service
public class OpenApiImportServiceImpl implements OpenApiImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)}");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch");

    @Override
    public List<ToolDefinition> parse(String gatewayId, Long tenantId, String openApiJson, String baseUrlOverride) {
        List<ToolDefinition> tools = new ArrayList<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(openApiJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("openapi json invalid: " + e.getMessage());
        }

        String baseUrl = baseUrlOverride != null && !baseUrlOverride.isBlank()
                ? baseUrlOverride
                : root.path("servers").path(0).path("url").asText("");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        JsonNode paths = root.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIter = paths.fields();
        while (pathIter.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIter.next();
            String path = pathEntry.getKey();
            Iterator<Map.Entry<String, JsonNode>> methodIter = pathEntry.getValue().fields();
            while (methodIter.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodIter.next();
                String method = methodEntry.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                tools.add(buildTool(gatewayId, tenantId, baseUrl, path, method, methodEntry.getValue()));
            }
        }
        return tools;
    }

    private ToolDefinition buildTool(String gatewayId, Long tenantId, String baseUrl,
                                     String path, String method, JsonNode operation) {
        ToolDefinition tool = new ToolDefinition();
        tool.setTenantId(tenantId);
        tool.setGatewayId(gatewayId);
        tool.setName(operation.hasNonNull("operationId")
                ? operation.get("operationId").asText()
                : slug(method + "_" + path));
        tool.setDescription(firstText(operation.path("summary"), operation.path("description"),
                method.toUpperCase() + " " + path));
        tool.setHttpMethod(method.toUpperCase());
        tool.setUrlTemplate(join(baseUrl, path));
        tool.setRequireConfirm("DELETE".equalsIgnoreCase(tool.getHttpMethod()) ? 1 : 0);
        // 敏感规则扩展：取消类操作（取消/退订/cancel）即使不是 DELETE 也需人工确认
        String sensitiveText = (operation.path("summary").asText() + " " + operation.path("description").asText())
                .toLowerCase();
        if (sensitiveText.contains("取消") || sensitiveText.contains("退订") || sensitiveText.contains("cancel")) {
            tool.setRequireConfirm(1);
        }
        tool.setEnabled(1);
        tool.setInputSchema(buildInputSchema(operation).toString());
        tool.setCreatedAt(LocalDateTime.now());
        return tool;
    }

    private ObjectNode buildInputSchema(JsonNode operation) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        Set<String> required = new LinkedHashSet<>();

        for (JsonNode parameter : operation.path("parameters")) {
            String name = parameter.path("name").asText();
            if (name.isBlank()) {
                continue;
            }
            ObjectNode property = properties.putObject(name);
            JsonNode paramSchema = parameter.path("schema");
            property.put("type", paramSchema.path("type").asText("string"));
            if (paramSchema.hasNonNull("format")) {
                property.put("format", paramSchema.get("format").asText());
            }
            if (parameter.hasNonNull("description")) {
                property.put("description", parameter.get("description").asText());
            }
            if (parameter.path("required").asBoolean(false)) {
                required.add(name);
            }
        }

        JsonNode requestBody = operation.path("requestBody");
        if (requestBody.isObject()) {
            JsonNode jsonSchema = requestBody.path("content").path("application/json").path("schema");
            if (jsonSchema.isObject()) {
                properties.set("body", jsonSchema.deepCopy());
                if (requestBody.path("required").asBoolean(true)) {
                    required.add("body");
                }
            }
        }

        if (!required.isEmpty()) {
            ArrayNode requiredNode = schema.putArray("required");
            required.forEach(requiredNode::add);
        }
        return schema;
    }

    private String join(String baseUrl, String path) {
        return path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path;
    }

    private String firstText(JsonNode first, JsonNode second, String fallback) {
        if (first.isTextual() && !first.asText().isBlank()) {
            return first.asText();
        }
        if (second.isTextual() && !second.asText().isBlank()) {
            return second.asText();
        }
        return fallback;
    }

    private String slug(String raw) {
        Matcher matcher = PATH_PARAM.matcher(raw);
        String cleaned = matcher.replaceAll("$1");
        return cleaned.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "").toLowerCase();
    }
}
