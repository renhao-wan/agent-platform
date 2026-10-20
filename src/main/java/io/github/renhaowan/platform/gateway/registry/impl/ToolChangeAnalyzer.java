package io.github.renhaowan.platform.gateway.registry.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 新旧工具定义的破坏性变更识别。
 * <p>
 * 不求覆盖 OpenAPI 全量规则（那是 oasdiff 的职责），只回答与本平台相关的几类：
 * 调用路径变了、新增必填参数、参数类型变了、权限被收紧或撤销。识别结果只用于告警与审计，
 * 不阻断同步——阻断会让上游一次发布把整个来源的工具卡在旧版本。
 */
final class ToolChangeAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolChangeAnalyzer() {
    }

    static List<ToolRegistryService.BreakingChange> compare(ToolDefinition old, ToolDefinition now) {
        List<ToolRegistryService.BreakingChange> changes = new ArrayList<>();
        if (!Objects.equals(old.getHttpMethod(), now.getHttpMethod())
                || !Objects.equals(old.getUrlTemplate(), now.getUrlTemplate())) {
            changes.add(new ToolRegistryService.BreakingChange(now.getName(), "ENDPOINT_CHANGED",
                    old.getHttpMethod() + " " + old.getUrlTemplate() + " -> "
                            + now.getHttpMethod() + " " + now.getUrlTemplate()));
        }

        JsonNode oldSchema = schemaOf(old);
        JsonNode newSchema = schemaOf(now);
        Set<String> oldRequired = requiredOf(oldSchema);
        Set<String> newRequired = requiredOf(newSchema);
        for (String name : newRequired) {
            if (!oldRequired.contains(name)) {
                changes.add(new ToolRegistryService.BreakingChange(now.getName(), "REQUIRED_PARAM_ADDED", name));
            }
        }
        JsonNode oldProps = oldSchema.path("properties");
        JsonNode newProps = newSchema.path("properties");
        for (String name : newRequired) {
            String before = oldProps.path(name).path("type").asText("");
            String after = newProps.path(name).path("type").asText("");
            if (!before.isEmpty() && !after.isEmpty() && !before.equals(after)) {
                changes.add(new ToolRegistryService.BreakingChange(now.getName(), "PARAM_TYPE_CHANGED",
                        name + ": " + before + " -> " + after));
            }
        }

        String oldPermission = effectivePermission(old);
        String newPermission = effectivePermission(now);
        if (isBlank(oldPermission) && !isBlank(newPermission)) {
            changes.add(new ToolRegistryService.BreakingChange(now.getName(), "PERMISSION_RESTRICTED",
                    "null -> " + newPermission));
        } else if (!isBlank(oldPermission) && !isBlank(newPermission)
                && !oldPermission.equals(newPermission)) {
            changes.add(new ToolRegistryService.BreakingChange(now.getName(), "PERMISSION_CHANGED",
                    oldPermission + " -> " + newPermission));
        } else if (!isBlank(oldPermission) && isBlank(newPermission)) {
            // 声明被撤销通常意味着注解在重构中丢失，接口会静默放开给全员
            changes.add(new ToolRegistryService.BreakingChange(now.getName(), "PERMISSION_REMOVED",
                    oldPermission + " -> null"));
        }
        return changes;
    }

    private static String effectivePermission(ToolDefinition tool) {
        return isBlank(tool.getRequiredPermission()) ? null : tool.getRequiredPermission();
    }

    private static JsonNode schemaOf(ToolDefinition tool) {
        try {
            return tool.getInputSchema() == null ? MAPPER.createObjectNode()
                    : MAPPER.readTree(tool.getInputSchema());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static Set<String> requiredOf(JsonNode schema) {
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode item : schema.path("required")) {
            required.add(item.asText());
        }
        return required;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
