package io.github.renhaowan.platform.gateway.registry.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.renhaowan.platform.gateway.registry.ToolRegistryService.BreakingChange;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 破坏性变更识别只做告警，不参与是否落库的判断。 */
class ToolChangeAnalyzerTest {

    private ToolDefinition tool() {
        ToolDefinition tool = new ToolDefinition();
        tool.setName("book_room");
        tool.setHttpMethod("POST");
        tool.setUrlTemplate("http://h/api/rooms/{roomId}/bookings");
        tool.setInputSchema(schema("{\"roomId\":{\"type\":\"integer\"}}", "[\"roomId\"]"));
        return tool;
    }

    private String schema(String properties, String required) {
        return "{\"type\":\"object\",\"properties\":" + properties + ",\"required\":" + required + "}";
    }

    private List<String> types(List<BreakingChange> changes) {
        return changes.stream().map(BreakingChange::type).toList();
    }

    @Test
    void unchangedContractProducesNoWarnings() {
        assertThat(ToolChangeAnalyzer.compare(tool(), tool())).isEmpty();
    }

    @Test
    void newRequiredParameterIsBreaking() {
        ToolDefinition now = tool();
        now.setInputSchema(schema(
                "{\"roomId\":{\"type\":\"integer\"},\"userId\":{\"type\":\"integer\"}}",
                "[\"roomId\",\"userId\"]"));

        List<BreakingChange> changes = ToolChangeAnalyzer.compare(tool(), now);

        assertThat(types(changes)).containsExactly("REQUIRED_PARAM_ADDED");
        assertThat(changes.get(0).detail()).isEqualTo("userId");
    }

    @Test
    void optionalParameterBecomingRequiredIsBreaking() {
        ToolDefinition before = tool();
        before.setInputSchema(schema(
                "{\"roomId\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"}}",
                "[\"roomId\"]"));
        ToolDefinition now = tool();
        now.setInputSchema(schema(
                "{\"roomId\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"}}",
                "[\"roomId\",\"note\"]"));

        assertThat(types(ToolChangeAnalyzer.compare(before, now)))
                .containsExactly("REQUIRED_PARAM_ADDED");
    }

    @Test
    void requiredParameterTypeChangeIsBreaking() {
        ToolDefinition now = tool();
        now.setInputSchema(schema("{\"roomId\":{\"type\":\"string\"}}", "[\"roomId\"]"));

        List<BreakingChange> changes = ToolChangeAnalyzer.compare(tool(), now);

        assertThat(types(changes)).containsExactly("PARAM_TYPE_CHANGED");
        assertThat(changes.get(0).detail()).isEqualTo("roomId: integer -> string");
    }

    @Test
    void endpointChangeIsBreaking() {
        ToolDefinition now = tool();
        now.setUrlTemplate("http://h/api/v2/rooms/{roomId}/bookings");

        List<BreakingChange> changes = ToolChangeAnalyzer.compare(tool(), now);

        assertThat(types(changes)).containsExactly("ENDPOINT_CHANGED");
        assertThat(changes.get(0).detail())
                .contains("POST http://h/api/rooms/{roomId}/bookings")
                .contains("POST http://h/api/v2/rooms/{roomId}/bookings");
    }

    @Test
    void newlyRestrictedPermissionIsBreaking() {
        ToolDefinition now = tool();
        now.setRequiredPermission("booking:cancel");

        List<BreakingChange> changes = ToolChangeAnalyzer.compare(tool(), now);

        assertThat(types(changes)).containsExactly("PERMISSION_RESTRICTED");
        assertThat(changes.get(0).detail()).isEqualTo("null -> booking:cancel");
    }

    @Test
    void changedPermissionIsReportedEvenWhenRelaxed() {
        ToolDefinition before = tool();
        before.setRequiredPermission("admin:policy");
        ToolDefinition now = tool();
        now.setRequiredPermission("public");

        assertThat(types(ToolChangeAnalyzer.compare(before, now)))
                .containsExactly("PERMISSION_CHANGED");
    }

    @Test
    void removedPermissionIsReported() {
        ToolDefinition before = tool();
        before.setRequiredPermission("booking:cancel");

        List<BreakingChange> changes = ToolChangeAnalyzer.compare(before, tool());

        assertThat(types(changes)).containsExactly("PERMISSION_REMOVED");
        assertThat(changes.get(0).detail()).isEqualTo("booking:cancel -> null");
    }

    @Test
    void malformedSchemaIsTreatedAsEmptyInsteadOfFailing() {
        ToolDefinition before = tool();
        before.setInputSchema("{not json");
        ToolDefinition now = tool();
        now.setInputSchema("[also not an object");

        assertThat(ToolChangeAnalyzer.compare(before, now)).isEmpty();
    }
}
