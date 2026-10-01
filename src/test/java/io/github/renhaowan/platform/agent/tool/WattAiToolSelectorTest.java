package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.renhaowan.platform.agent.tool.impl.WattAiToolSelector;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * wattai 路由器契约测试（本地 HttpServer 模拟决策端点，离线可重复）。
 */
class WattAiToolSelectorTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String responseBody = "{\"results\":[{\"argmax\":\"searchAvailable\","
            + "\"confidence\":0.9,\"options\":[\"searchAvailable\",\"create\"],"
            + "\"probs\":[0.9,0.1]}]}";

    private final ToolCallback search = stub("searchAvailable", "查询可用会议室");
    private final ToolCallback create = stub("create", "创建预订");
    private final ToolCallback cancel = stub("cancel", "取消预订");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/systemone", exchange -> {
            requests.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
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

    private static ToolCallback stub(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description)
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    private WattAiToolSelector selector(int minTools, double threshold) {
        return new WattAiToolSelector("http://localhost:" + port, null, minTools, threshold, 2);
    }

    @Test
    void highConfidenceRoutesToSingleTool() {
        var picked = selector(3, 0.6).select("帮我订会议室", List.of(search, create, cancel));

        assertThat(requests.get()).isEqualTo(1);
        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).getToolDefinition().name()).isEqualTo("searchAvailable");
    }

    @Test
    void lowConfidenceFallsBackToTopK() {
        responseBody = "{\"results\":[{\"argmax\":\"create\",\"confidence\":0.3,"
                + "\"options\":[\"searchAvailable\",\"create\",\"cancel\"],"
                + "\"probs\":[0.3,0.35,0.35]}]}";

        var picked = selector(3, 0.6).select("帮我订会议室", List.of(search, create, cancel));

        assertThat(picked).hasSize(2);
        assertThat(picked).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("cancel", "create");
    }

    @Test
    void decisionApiFailureFailsOpen() {
        // 指向不可达端口，避免污染共享 HttpServer（其他用例还要用）
        WattAiToolSelector deadSelector =
                new WattAiToolSelector("http://localhost:1", null, 3, 0.6, 2);

        var picked = deadSelector.select("帮我订会议室", List.of(search, create, cancel));

        assertThat(picked).hasSize(3); // fail-open 全量
    }

    @Test
    void fewToolsPassThroughWithoutCallingDecisionApi() {
        var picked = selector(4, 0.6).select("帮我订会议室", List.of(search, create));

        assertThat(picked).hasSize(2);
        assertThat(requests.get()).isZero();
    }
}
