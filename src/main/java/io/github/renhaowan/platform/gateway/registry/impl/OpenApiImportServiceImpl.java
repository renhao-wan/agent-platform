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
    public List<ToolDefinition> parse(String gatewayId, String openApiJson, String baseUrlOverride) {
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
                tools.add(buildTool(gatewayId, baseUrl, path, method, methodEntry.getValue(), root));
            }
        }
        return tools;
    }

    private ToolDefinition buildTool(String gatewayId, String baseUrl,
                                     String path, String method, JsonNode operation, JsonNode root) {
        ToolDefinition tool = new ToolDefinition();
        tool.setGatewayId(gatewayId);
        tool.setName(operation.hasNonNull("operationId")
                ? operation.get("operationId").asText()
                : slug(method + "_" + path));
        tool.setDescription(firstText(operation.path("summary"), operation.path("description"),
                method.toUpperCase() + " " + path));
        tool.setHttpMethod(method.toUpperCase());
        tool.setUrlTemplate(join(baseUrl, path));
        // 敏感与权限要求一律由业务系统在契约里声明，平台不猜测接口语义（业务词不落平台代码）。
        tool.setRequireConfirm("DELETE".equalsIgnoreCase(tool.getHttpMethod())
                || extensionFlag(operation, "x-require-confirm") ? 1 : 0);
        tool.setRequiredPermission(extensionText(operation, "x-required-permission"));
        tool.setEnabled(1);
        tool.setPresent(1);
        tool.setInputSchema(buildInputSchema(operation, root).toString());
        tool.setCreatedAt(LocalDateTime.now());
        return tool;
    }

    /**
     * 工具 schema 是脱离原文档、单独发给模型的片段，因此文档内引用必须就地展开：
     * {@code {"$ref": "#/components/schemas/CreateBookingRequest"}} 这样的写法在模型侧
     * 没有 components 可查，OpenAI 兼容接口会以 "Pointer ... does not exist" 拒绝整轮请求。
     */
    private JsonNode resolveRefs(JsonNode node, JsonNode root, Set<String> visiting) {
        if (node == null || node.isMissingNode()) {
            return MAPPER.createObjectNode();
        }
        if (node.isObject() && node.hasNonNull("$ref")) {
            String ref = node.get("$ref").asText();
            JsonNode target = dereference(ref, root);
            if (target == null || visiting.contains(ref)) {
                // 指不到或绕回自身：留一个宽松的 object 骨架，模型仍能构造参数，且不会无限展开
                ObjectNode fallback = MAPPER.createObjectNode();
                fallback.put("type", "object");
                return fallback;
            }
            Set<String> next = new LinkedHashSet<>(visiting);
            next.add(ref);
            return resolveRefs(target, root, next);
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), resolveRefs(field.getValue(), root, visiting));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                copy.add(resolveRefs(element, root, visiting));
            }
            return copy;
        }
        return node.deepCopy();
    }

    /** 只处理文档内引用（{@code #/components/...}）；指向外部文件的引用不在单份契约的范围内，按解析不到处理。 */
    private JsonNode dereference(String ref, JsonNode root) {
        if (!ref.startsWith("#/")) {
            return null;
        }
        JsonNode current = root;
        for (String segment : ref.substring(2).split("/")) {
            current = current.path(segment.replace("~1", "/").replace("~0", "~"));
        }
        return current.isMissingNode() ? null : current;
    }

    /**
     * 读 OpenAPI 扩展字段的文本值，兼容两种写法：{@code "x-required-permission": "booking:cancel"}，
     * 以及 springdoc {@code @Extension} 生成的 {@code {"value": "booking:cancel"}}。
     */
    private static String extensionText(JsonNode operation, String name) {
        JsonNode node = operation.path(name);
        if (node.isTextual()) {
            return node.asText().isBlank() ? null : node.asText();
        }
        if (node.isObject() && node.hasNonNull("value")) {
            String value = node.get("value").asText();
            return value.isBlank() ? null : value;
        }
        return null;
    }

    /** 读 OpenAPI 扩展字段的布尔值，兼容 {@code true}、{@code "true"} 与 {@code {"value": "true"}}。 */
    private static boolean extensionFlag(JsonNode operation, String name) {
        JsonNode node = operation.path(name);
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return Boolean.parseBoolean(node.asText());
        }
        return node.isObject() && node.hasNonNull("value")
                && Boolean.parseBoolean(node.get("value").asText());
    }

    private ObjectNode buildInputSchema(JsonNode operation, JsonNode root) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        Set<String> required = new LinkedHashSet<>();

        for (JsonNode rawParameter : operation.path("parameters")) {
            // parameter 本身也可能写成 $ref，先展开再取字段
            JsonNode parameter = resolveRefs(rawParameter, root, Set.of());
            String name = parameter.path("name").asText();
            if (name.isBlank()) {
                continue;
            }
            ObjectNode property = properties.putObject(name);
            JsonNode paramSchema = resolveRefs(parameter.path("schema"), root, Set.of());
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
                properties.set("body", resolveRefs(jsonSchema, root, Set.of()));
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
