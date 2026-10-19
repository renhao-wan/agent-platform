package io.github.renhaowan.platform.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.GatewayProperties;
import io.github.renhaowan.platform.gateway.forward.ForwardProperties;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.impl.OpenApiImportServiceImpl;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenApiSourceRegistrarTest {

    private static final String CONTRACT = """
            {
              "openapi": "3.0.0",
              "info": {"title": "demo", "version": "1.0"},
              "servers": [{"url": "http://contract-declared-host:9999"}],
              "paths": {
                "/api/v1/things/{id}/cancel": {
                  "post": {
                    "operationId": "cancel_thing",
                    "summary": "取消指定事项",
                    "x-required-role": "ADMIN",
                    "x-require-confirm": true,
                    "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}}]
                  }
                }
              }
            }
            """;

    private HttpServer server;
    private int port;
    private final ToolRegistryService registryService = mock(ToolRegistryService.class);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/openapi.json", exchange -> {
            byte[] body = CONTRACT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenApiSourceRegistrar registrarFor(String url) {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.Source source = new GatewayProperties.Source();
        source.setUrl(url);
        source.setServiceKeyName("X-Service-Key");
        source.setServiceKeyValue("demo-key");
        properties.setSources(List.of(source));
        return new OpenApiSourceRegistrar(properties, new AgentProperties(),
                new OpenApiImportServiceImpl(), registryService, new ForwardProperties());
    }

    @SuppressWarnings("unchecked")
    private List<ToolDefinition> registeredTools() {
        ArgumentCaptor<List<ToolDefinition>> captor = ArgumentCaptor.forClass(List.class);
        verify(registryService).upsertAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void registersToolsDeclaredByTheContractAtStartup() {
        registrarFor("http://localhost:" + port + "/openapi.json").run(null);

        List<ToolDefinition> tools = registeredTools();
        assertThat(tools).hasSize(1);
        ToolDefinition tool = tools.get(0);
        assertThat(tool.getName()).isEqualTo("cancel_thing");
        assertThat(tool.getGatewayId()).isEqualTo("gw1");
        assertThat(tool.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(tool.getRequireConfirm()).isEqualTo(1);
        assertThat(tool.getAuthHeaderName()).isEqualTo("X-Service-Key");
        assertThat(tool.getAuthHeaderValue()).isEqualTo("demo-key");
    }

    @Test
    void toolBaseUrlFollowsWhereTheContractWasFetchedNotTheHostItDeclares() {
        registrarFor("http://localhost:" + port + "/openapi.json").run(null);

        // 契约自称 http://contract-declared-host:9999，但既然是从本机端口拉到的，
        // 工具就该发回本机端口——否则容器内外两种拓扑下必然有一边打不通
        assertThat(registeredTools().get(0).getUrlTemplate())
                .isEqualTo("http://localhost:" + port + "/api/v1/things/{id}/cancel");
    }

    @Test
    void contractDeclaredBaseUrlIsOverriddenWhenExplicitlyConfigured() {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.Source source = new GatewayProperties.Source();
        source.setUrl("http://localhost:" + port + "/openapi.json");
        source.setBaseUrl("http://my-gateway:9000");
        properties.setSources(List.of(source));
        new OpenApiSourceRegistrar(properties, new AgentProperties(), new OpenApiImportServiceImpl(),
                registryService, new ForwardProperties()).run(null);

        assertThat(registeredTools().get(0).getUrlTemplate())
                .isEqualTo("http://my-gateway:9000/api/v1/things/{id}/cancel");
    }

    @Test
    void unreachableSourceIsSkippedWithoutBreakingStartup() {
        assertThatCode(() -> registrarFor("http://localhost:1/openapi.json").run(null))
                .doesNotThrowAnyException();
        verifyNoInteractions(registryService);
    }

    @Test
    void emptySourceListSkipsAutoRegistration() {
        new OpenApiSourceRegistrar(new GatewayProperties(), new AgentProperties(),
                new OpenApiImportServiceImpl(), registryService, new ForwardProperties()).run(null);
        verifyNoInteractions(registryService);
    }
}
