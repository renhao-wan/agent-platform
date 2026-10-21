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
 * wattai 路由节点契约测试（本地 HttpServer 模拟决策端点，离线可重复）。
 * 弃权 = Optional.empty（交责任链下一环），接管 = Optional.of(筛选结果)。
 */
class WattAiToolSelectorTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int responseStatus = 200;
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
            exchange.sendResponseHeaders(responseStatus, body.length);
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
        return new WattAiToolSelector("http://localhost:" + port, "test-key",
                minTools, threshold, 2, 1000, 3000);
    }

    @Test
    void highConfidenceTakesOverWithSingleTool() {
        var picked = selector(3, 0.6).select("帮我订会议室", List.of(search, create, cancel)).orElseThrow();

        assertThat(requests.get()).isEqualTo(1);
        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).getToolDefinition().name()).isEqualTo("searchAvailable");
    }

    @Test
    void lowConfidenceDegradesToTopK() {
        responseBody = "{\"results\":[{\"argmax\":\"create\",\"confidence\":0.3,"
                + "\"options\":[\"searchAvailable\",\"create\",\"cancel\"],"
                + "\"probs\":[0.3,0.35,0.35]}]}";

        var picked = selector(3, 0.6).select("帮我订会议室", List.of(search, create, cancel)).orElseThrow();

        assertThat(picked).hasSize(2);
        assertThat(picked).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("cancel", "create");
    }

    @Test
    void decisionApiFailureAbstainsToNextHandler() {
        // 指向不可达端口，避免污染共享 HttpServer（其他用例还要用）
        WattAiToolSelector deadSelector =
                new WattAiToolSelector("http://localhost:1", "test-key", 3, 0.6, 2, 1000, 3000);

        var picked = deadSelector.select("帮我订会议室", List.of(search, create, cancel));

        assertThat(picked).isEmpty(); // 弃权 → 责任链下一环 / 链尾兜底全量
    }

    @Test
    void readTimeoutAbstainsInsteadOfHanging() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress(0), 0);
        slow.createContext("/v1/systemone", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slow.start();
        try {
            WattAiToolSelector slowSelector = new WattAiToolSelector(
                    "http://localhost:" + slow.getAddress().getPort(), "test-key", 3, 0.6, 2, 500, 200);
            long startedAt = System.nanoTime();

            var picked = slowSelector.select("帮我订会议室", List.of(search, create, cancel));

            // 读超时后弃权交下一环，而不是无限期拖住 ReAct 循环
            assertThat(picked).isEmpty();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(java.time.Duration.ofSeconds(2));
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void fewToolsAbstainWithoutCallingDecisionApi() {
        var picked = selector(4, 0.6).select("帮我订会议室", List.of(search, create));

        assertThat(picked).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void argmaxOutsideCandidatesAbstainsInsteadOfEmittingEmptySet() {
        // 潜伏缺陷回归：argmax 指向候选外工具名时，旧实现会交出空工具集（主模型无工具可用）
        responseBody = "{\"results\":[{\"argmax\":\"ghostTool\",\"confidence\":0.9,"
                + "\"options\":[\"searchAvailable\",\"create\"],\"probs\":[0.9,0.1]}]}";

        var picked = selector(3, 0.6).select("帮我订会议室", List.of(search, create, cancel));

        assertThat(picked).isEmpty();
    }

    @Test
    void missingApiKeyMakesNodeUnavailableWithoutCallingApi() {
        WattAiToolSelector noKey = new WattAiToolSelector(
                "http://localhost:" + port, "  ", 3, 0.6, 2, 1000, 3000);

        var picked = noKey.select("帮我订会议室", List.of(search, create, cancel));

        assertThat(picked).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void authFailureDisablesNodeAfterSingleCall() {
        // 401 属于不可恢复错误：停用节点，后续对话不再重复发送注定失败的请求
        responseStatus = 401;
        WattAiToolSelector selector = selector(3, 0.6);

        assertThat(selector.select("帮我订会议室", List.of(search, create, cancel))).isEmpty();
        assertThat(selector.select("再试一次", List.of(search, create, cancel))).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }
}
