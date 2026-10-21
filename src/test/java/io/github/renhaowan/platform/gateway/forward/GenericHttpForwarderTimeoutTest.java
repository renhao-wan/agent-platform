package io.github.renhaowan.platform.gateway.forward;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 转发层的超时与传输故障路径——JDK HttpServer 那套用例覆盖了正常/4xx/5xx/拒连，
 * 唯独无法构造"服务端挂着不回"，故此处用 WireMock。
 * <p>关键：必须用整响应延迟或 NO_RESPONSE，先回 header 不会触发读超时。
 */
class GenericHttpForwarderTimeoutTest {

    private static WireMockServer server;
    private static int port;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        port = server.port();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetStubs() {
        server.resetAll();
    }

    private GenericHttpForwarder forwarderWithTimeouts(int connectMs, int readMs) {
        ForwardProperties properties = new ForwardProperties();
        properties.setConnectTimeoutMs(connectMs);
        properties.setReadTimeoutMs(readMs);
        return new GenericHttpForwarder(properties);
    }

    private ToolDefinition tool(String method, String path) {
        ToolDefinition tool = new ToolDefinition();
        tool.setHttpMethod(method);
        tool.setUrlTemplate("http://localhost:" + port + path);
        return tool;
    }

    @Test
    void readTimeoutOnReadIsUnknownButSafeToRetry() {
        server.stubFor(get(urlEqualTo("/slow"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}").withFixedDelay(1500)));

        var result = forwarderWithTimeouts(2000, 200).forward(tool("GET", "/slow"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.unknown()).isTrue();
        assertThat(result.error()).contains("结果未知").contains("可安全重试");
    }

    @Test
    void readTimeoutOnWriteIsUnknownAndMustNotRetryBlindly() {
        server.stubFor(post(urlEqualTo("/slow-write"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}").withFixedDelay(1500)));

        var result = forwarderWithTimeouts(2000, 200).forward(tool("POST", "/slow-write"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.unknown()).isTrue();
        assertThat(result.error()).contains("结果未知").contains("不要直接重试").contains("先调用查询类工具");
    }

    @Test
    void connectionDroppedAfterRequestIsUnknown() {
        server.stubFor(get(urlEqualTo("/hang"))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        var result = forwarderWithTimeouts(2000, 500).forward(tool("GET", "/hang"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.unknown()).isTrue();
        assertThat(result.error()).contains("结果未知");
    }

    @Test
    void gatewayTimeoutOnWriteIsUnknown() {
        server.stubFor(post(urlEqualTo("/gateway-timeout"))
                .willReturn(aResponse().withStatus(504)));

        var result = forwarderWithTimeouts(2000, 2000).forward(tool("POST", "/gateway-timeout"), null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.unknown()).isTrue();
        assertThat(result.error()).contains("504").contains("不要直接重试");
    }

    @Test
    void nonJsonBodyIsReturnedVerbatimWithoutThrowing() {
        server.stubFor(get(urlEqualTo("/plain"))
                .willReturn(aResponse().withStatus(200).withBody("<html>service up</html>")));

        var result = forwarderWithTimeouts(2000, 2000).forward(tool("GET", "/plain"), null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.body()).isEqualTo("<html>service up</html>");
    }

    @Test
    void oversizedWireMockBodyTruncatedAtBoundary() {
        server.stubFor(get(urlEqualTo("/big"))
                .willReturn(aResponse().withStatus(200).withBody("x".repeat(10000))));

        var result = forwarderWithTimeouts(2000, 2000).forward(tool("GET", "/big"), null, null);

        assertThat(result.body().length()).isEqualTo(GenericHttpForwarder.MAX_BODY_CHARS);
    }
}
