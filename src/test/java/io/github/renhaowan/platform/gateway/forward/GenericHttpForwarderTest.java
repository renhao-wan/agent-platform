package io.github.renhaowan.platform.gateway.forward;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GenericHttpForwarderTest {

    private HttpServer server;
    private int port;
    private final GenericHttpForwarder forwarder = new GenericHttpForwarder(new ForwardProperties());
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastRawPath = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ping", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"service\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/echo", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = lastBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            byte[] body = ("x".repeat(10000)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/rooms/", exchange -> {
            lastRawPath.set(exchange.getRequestURI().getRawPath());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/broken", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ToolDefinition tool(String method, String template) {
        ToolDefinition tool = new ToolDefinition();
        tool.setHttpMethod(method);
        tool.setUrlTemplate(template);
        return tool;
    }

    private ObjectNode args(String json) throws Exception {
        return (ObjectNode) new ObjectMapper().readTree(json);
    }

    @Test
    void getForwardsRemainingArgsAsQuery() throws Exception {
        var result = forwarder.forward(tool("GET", "http://localhost:" + port + "/ping"),
                args("{\"name\":\"room-a\",\"capacity\":8}"), null);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"service\":\"ok\"");
        assertThat(lastQuery.get()).contains("name=room-a").contains("capacity=8");
    }

    @Test
    void postForwardsRemainingArgsAsJsonBody() throws Exception {
        var result = forwarder.forward(tool("POST", "http://localhost:" + port + "/echo"),
                args("{\"roomId\":1,\"title\":\"周会\"}"), null);

        assertThat(result.success()).isTrue();
        assertThat(result.body()).contains("\"roomId\":1").contains("周会");
        assertThat(lastBody.get()).contains("周会");
    }

    @Test
    void pathParamsFilledAndExcludedFromBody() throws Exception {
        var result = forwarder.forward(tool("POST", "http://localhost:" + port + "/echo/{id}/cancel"),
                args("{\"id\":42,\"reason\":\"日程冲突\"}"), null);

        assertThat(result.success()).isTrue();
        assertThat(lastBody.get()).doesNotContain("\"id\"");
        assertThat(lastBody.get()).contains("日程冲突");
    }

    @Test
    void pathValueIsPercentEncodedNotFormEncoded() throws Exception {
        var result = forwarder.forward(tool("GET", "http://localhost:" + port + "/rooms/{name}"),
                args("{\"name\":\"大 会议室\"}"), null);

        assertThat(result.success()).isTrue();
        // 路径段里的空格必须是 %20；URLEncoder 的 '+' 在 path 中是字面量，会被后端当成不同的名字
        assertThat(lastRawPath.get()).contains("%20").doesNotContain("+");
    }

    @Test
    void oversizedResponseTruncatedTo8KB() {
        var result = forwarder.forward(tool("GET", "http://localhost:" + port + "/big"), null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.body().length()).isEqualTo(GenericHttpForwarder.MAX_BODY_CHARS);
    }

    @Test
    void backendErrorBecomesFailedResult() {
        var result = forwarder.forward(tool("GET", "http://localhost:" + port + "/broken"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("500");
    }

    @Test
    void unreachableBackendBecomesFailedResult() {
        var result = forwarder.forward(tool("GET", "http://localhost:1/ping"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    void authHeaderAttachedWhenConfigured() {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        server.createContext("/secure", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Service-Key"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ToolDefinition secureTool = tool("GET", "http://localhost:" + port + "/secure");
        secureTool.setAuthHeaderName("X-Service-Key");
        secureTool.setAuthHeaderValue("booking-service-key");

        var result = forwarder.forward(secureTool, null, null);

        assertThat(result.success()).isTrue();
        assertThat(receivedKey.get()).isEqualTo("booking-service-key");
    }

    @Test
    void onBehalfOfUserHeaderCarriesCallerId() {
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/whoami", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst(GenericHttpForwarder.ON_BEHALF_OF_HEADER));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var result = forwarder.forward(tool("GET", "http://localhost:" + port + "/whoami"), null, 7L);

        assertThat(result.success()).isTrue();
        assertThat(received.get()).isEqualTo("7");
    }

    @Test
    void onBehalfOfUserHeaderOmittedWhenNoIdentity() {
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/whoami-guest", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst(GenericHttpForwarder.ON_BEHALF_OF_HEADER));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var result = forwarder.forward(tool("GET", "http://localhost:" + port + "/whoami-guest"), null, null);

        assertThat(result.success()).isTrue();
        assertThat(received.get()).isNull();
    }

    @Test
    void bodyWrapperUnwrappedWhenSchemaDeclaresBodyProperty() throws Exception {
        ToolDefinition wrapped = tool("POST", "http://localhost:" + port + "/echo");
        wrapped.setInputSchema("{\"type\":\"object\",\"properties\":{\"body\":{"
                + "\"type\":\"object\",\"properties\":{\"roomId\":{\"type\":\"integer\"}}}}}");

        var result = forwarder.forward(wrapped,
                args("{\"body\":{\"roomId\":2,\"title\":\"周会\"}}"), null);

        assertThat(result.success()).isTrue();
        assertThat(lastBody.get()).contains("\"roomId\":2").contains("周会");
        assertThat(lastBody.get()).doesNotContain("\"body\"");
    }
}
