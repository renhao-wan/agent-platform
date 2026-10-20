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
        var tools = service.parse("gw1", OPENAPI, null);

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
        var tools = service.parse("gw1", openapi, null);

        assertThat(tools.get(0).getName()).isEqualTo("get_api_v1_bookings_id");
        assertThat(json(tools.get(0).getInputSchema()).get("required").toString()).contains("id");
    }

    @Test
    void baseUrlOverrideWinsOverServers() {
        var tools = service.parse("gw1", OPENAPI, "http://override:9999");

        assertThat(tools.get(0).getUrlTemplate()).startsWith("http://override:9999/api/v1/rooms");
    }

    @Test
    void invalidJsonThrowsIllegalArgument() {
        assertThatThrownBy(() -> service.parse("gw1", "{not-json", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationWordingAloneNoLongerMarksSensitive() {
        // 回归断言：平台不再读 summary/description 里的业务词判断敏感度，
        // 「取消」二字必须由业务系统用 x-require-confirm 显式声明才生效
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings/{id}/cancel":{"post":{"operationId":"cancel_booking",
                   "summary":"取消指定预订","description":"退订后不可恢复",
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        assertThat(tools.get(0).getHttpMethod()).isEqualTo("POST");
        assertThat(tools.get(0).getRequireConfirm()).isZero();
    }

    @Test
    void postDeclaredSensitiveByExtensionIsMarked() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings/{id}/cancel":{"post":{
                   "x-require-confirm": true,
                   "summary":"取消指定预订",
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        assertThat(tools.get(0).getHttpMethod()).isEqualTo("POST");
        assertThat(tools.get(0).getRequireConfirm()).isEqualTo(1);
    }

    @Test
    void requiredPermissionReadFromExtensionAsPlainString() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings/{id}/cancel":{
                   "post":{"operationId":"cancel_booking","x-required-permission":"booking:cancel","summary":"取消指定预订",
                     "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]},
                   "get":{"operationId":"get_booking","summary":"查询预订详情",
                     "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        assertThat(tools).extracting(ToolDefinition::getName)
                .containsExactlyInAnyOrder("cancel_booking", "get_booking");
        // 权限声明绑在具体 operation 上，不是整份文档的全局属性
        assertThat(tools).filteredOn(t -> "cancel_booking".equals(t.getName()))
                .allSatisfy(t -> assertThat(t.getRequiredPermission()).isEqualTo("booking:cancel"));
        assertThat(tools).filteredOn(t -> "get_booking".equals(t.getName()))
                .allSatisfy(t -> assertThat(t.getRequiredPermission()).isNull());
    }

    @Test
    void requiredPermissionReadFromSpringdocNestedExtension() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/expenses/{id}/approve":{"post":{
                   "operationId":"approve_expense","summary":"审批通过指定的报销单",
                   "x-required-permission":{"value":"expense:approve"},
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}]}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        assertThat(tools.get(0).getRequiredPermission()).isEqualTo("expense:approve");
    }

    @Test
    void blankExtensionsTreatedAsAbsent() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/rooms":{"get":{"operationId":"search_rooms","summary":"查询",
                   "x-required-permission":"   ","x-require-confirm":"false"}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        assertThat(tools.get(0).getRequiredPermission()).isNull();
        assertThat(tools.get(0).getRequireConfirm()).isZero();
    }

    @Test
    void requestBodyRefIsInlinedSoTheModelSeesARealSchema() {
        // 工具 schema 是脱离原文档发给模型的，$ref 指向的 components 在模型侧不存在，
        // 不展开就会被 OpenAI 兼容接口整轮拒绝（"Pointer ... does not exist"）
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "components":{"schemas":{
                   "CreateBookingRequest":{"type":"object","required":["roomId"],
                     "properties":{"roomId":{"type":"integer"},"title":{"type":"string","maxLength":50}}}}},
                 "paths":{"/api/v1/bookings":{"post":{"operationId":"create",
                   "summary":"创建预订","requestBody":{"required":true,"content":{"application/json":
                     {"schema":{"$ref":"#/components/schemas/CreateBookingRequest"}}}}}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        JsonNode body = json(tools.get(0).getInputSchema()).get("properties").get("body");
        assertThat(body.has("$ref")).isFalse();
        assertThat(body.get("type").asText()).isEqualTo("object");
        assertThat(body.get("properties").get("roomId").get("type").asText()).isEqualTo("integer");
        assertThat(body.get("properties").get("title").get("maxLength").asInt()).isEqualTo(50);
        assertThat(body.get("required").toString()).contains("roomId");
    }

    @Test
    void nestedRefIsInlinedRecursively() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "components":{"schemas":{
                   "Booking":{"type":"object","properties":{"room":{"$ref":"#/components/schemas/Room"}}},
                   "Room":{"type":"object","properties":{"id":{"type":"integer"}}}}},
                 "paths":{"/api/v1/bookings":{"post":{"operationId":"create","summary":"创建预订",
                   "requestBody":{"content":{"application/json":{
                     "schema":{"$ref":"#/components/schemas/Booking"}}}}}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        JsonNode room = json(tools.get(0).getInputSchema())
                .get("properties").get("body").get("properties").get("room");
        assertThat(room.has("$ref")).isFalse();
        assertThat(room.get("properties").get("id").get("type").asText()).isEqualTo("integer");
    }

    @Test
    void selfReferencingSchemaStopsInsteadOfLoopingForever() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "components":{"schemas":{
                   "Node":{"type":"object","properties":{"child":{"$ref":"#/components/schemas/Node"}}}}},
                 "paths":{"/api/v1/nodes":{"post":{"operationId":"create_node","summary":"建节点",
                   "requestBody":{"content":{"application/json":{
                     "schema":{"$ref":"#/components/schemas/Node"}}}}}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        JsonNode body = json(tools.get(0).getInputSchema()).get("properties").get("body");
        // 首层展开、再往下绕回自身时截断成宽松的 object，既不报错也不无限展开
        assertThat(body.get("properties").get("child").get("type").asText()).isEqualTo("object");
    }

    @Test
    void parameterRefIsInlinedAndKeepsItsType() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "components":{"schemas":{"PageSize":{"type":"integer","format":"int32"}}},
                 "paths":{"/api/v1/bookings":{"get":{"operationId":"list","summary":"查询预订",
                   "parameters":[{"name":"pageSize","in":"query","schema":{"$ref":"#/components/schemas/PageSize"}}]}}}}
                """;
        var tools = service.parse("gw1", openapi, null);

        JsonNode pageSize = json(tools.get(0).getInputSchema()).get("properties").get("pageSize");
        assertThat(pageSize.get("type").asText()).isEqualTo("integer");
        assertThat(pageSize.get("format").asText()).isEqualTo("int32");
    }

    @Test
    void parameterSchemaKeepsEnumItemsDefaultAndBounds() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings":{"get":{"operationId":"list","summary":"查询预订",
                   "parameters":[
                     {"name":"status","in":"query","description":"预订状态","schema":{
                        "type":"string","enum":["BOOKED","CANCELLED"],"default":"BOOKED"}},
                     {"name":"roomIds","in":"query","schema":{"type":"array","items":{"type":"integer"}}},
                     {"name":"page","in":"query","schema":{"type":"integer","minimum":1,"default":1}}]}}}}
                """;
        JsonNode props = json(service.parse("gw1", openapi, null).get(0).getInputSchema()).get("properties");

        assertThat(props.get("status").get("enum").toString()).contains("BOOKED", "CANCELLED");
        assertThat(props.get("status").get("default").asText()).isEqualTo("BOOKED");
        assertThat(props.get("status").get("description").asText()).isEqualTo("预订状态");
        assertThat(props.get("roomIds").get("items").get("type").asText()).isEqualTo("integer");
        assertThat(props.get("page").get("minimum").asInt()).isEqualTo(1);
    }

    @Test
    void parameterContentSchemaIsSupported() {
        String openapi = """
                {"openapi":"3.0.0","servers":[{"url":"http://h"}],
                 "paths":{"/api/v1/bookings":{"get":{"operationId":"list","summary":"查询预订",
                   "parameters":[{"name":"filter","in":"query",
                     "content":{"application/json":{"schema":{"type":"object",
                       "properties":{"roomId":{"type":"integer"}}}}}}]}}}}
                """;
        JsonNode filter = json(service.parse("gw1", openapi, null).get(0).getInputSchema())
                .get("properties").get("filter");

        assertThat(filter.get("type").asText()).isEqualTo("object");
        assertThat(filter.get("properties").get("roomId").get("type").asText()).isEqualTo("integer");
    }

    private JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
