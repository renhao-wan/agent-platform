package io.github.renhaowan.platform.gateway.registry;

import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.renhaowan.platform.gateway.registry.impl.OpenApiImportServiceImpl;
import org.junit.jupiter.api.Test;

class OpenApiImportServiceTest {

    private final OpenApiImportService service = new OpenApiImportServiceImpl();

    private static final String OPENAPI = """
            {
              "openapi": "3.0.0",
              "servers": [{"url": "http://localhost:8090"}],
              "paths": {
                "/api/v1/rooms": {
                  "get": {
                    "operationId": "search_rooms",
                    "summary": "查询可用会议室",
                    "parameters": [
                      {"name": "date", "in": "query", "required": true, "schema": {"type": "string"}},
                      {"name": "capacity", "in": "query", "schema": {"type": "integer"}}
                    ]
                  }
                },
                "/api/v1/bookings": {
                  "post": {
                    "summary": "创建预订",
                    "requestBody": {"required": true, "content": {"application/json": {"schema": {
                      "type": "object",
                      "properties": {"roomId": {"type": "integer"}, "title": {"type": "string"}}
                    }}}}
                  }
                },
                "/api/v1/bookings/{id}": {
                  "delete": {
                    "summary": "取消预订",
                    "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}]
                  }
                }
              }
            }
            """;

    @Test
    void parsesToolsWithOperationIdAndSchema() {
        var tools = service.parse("gw1", 7L, OPENAPI, null);

        assertThat(tools).hasSize(3);

        ToolDefinition search = tools.get(0);
        assertThat(search.getName()).isEqualTo("search_rooms");
        assertThat(search.getHttpMethod()).isEqualTo("GET");
        assertThat(search.getUrlTemplate()).isEqualTo("http://localhost:8090/api/v1/rooms");
        assertThat(search.getRequireConfirm()).isZero();
        JsonNode schema = json(search.getInputSchema());
        assertThat(schema.get("properties").get("date").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("required").toString()).contains("date");

        ToolDefinition create = tools.get(1);
        assertThat(create.getHttpMethod()).isEqualTo("POST");
        assertThat(create.getUrlTemplate()).isEqualTo("http://localhost:8090/api/v1/bookings");
        JsonNode createSchema = json(create.getInputSchema());
        assertThat(createSchema.get("properties").get("body").get("properties").get("roomId")).isNotNull();
        assertThat(createSchema.get("required").toString()).contains("body");

        ToolDefinition cancel = tools.get(2);
        assertThat(cancel.getHttpMethod()).isEqualTo("DELETE");
        assertThat(cancel.getUrlTemplate()).isEqualTo("http://localhost:8090/api/v1/bookings/{id}");
        assertThat(cancel.getRequireConfirm()).isEqualTo(1);
    }

    @Test
    void slugFallbackWhenOperationIdMissing() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings/{id}":{"get":{"summary":"详情",
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]}}}}
                """;
        var tools = service.parse("gw1", 7L, openapi, null);

        assertThat(tools.get(0).getName()).isEqualTo("get_api_v1_bookings_id");
        assertThat(json(tools.get(0).getInputSchema()).get("required").toString()).contains("id");
    }

    @Test
    void baseUrlOverrideWinsOverServers() {
        var tools = service.parse("gw1", 7L, OPENAPI, "http://override:9999");

        assertThat(tools.get(0).getUrlTemplate()).startsWith("http://override:9999/api/v1/rooms");
    }

    @Test
    void invalidJsonThrowsIllegalArgument() {
        assertThatThrownBy(() -> service.parse("gw1", 7L, "{not-json", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelLikeOperationsMarkedSensitiveEvenIfNotDelete() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings/{id}/cancel":{"post":{"summary":"取消指定预订",
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]}}}}
                """;
        var tools = service.parse("gw1", 7L, openapi, null);

        assertThat(tools.get(0).getHttpMethod()).isEqualTo("POST");
        assertThat(tools.get(0).getRequireConfirm()).isEqualTo(1);
    }

    private JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
