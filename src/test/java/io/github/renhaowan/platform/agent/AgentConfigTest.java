package io.github.renhaowan.platform.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AgentConfigTest {

    @Test
    void llmRestClientHonorsConfiguredReadTimeout() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress(0), 0);
        slow.createContext("/slow", exchange -> {
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
            AgentProperties properties = new AgentProperties();
            properties.setLlmReadTimeoutMs(200);
            RestClient.Builder builder = RestClient.builder();
            new AgentConfig().llmHttpTimeouts(properties).customize(builder);
            RestClient client = builder.build();
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> client.get()
                    .uri("http://localhost:" + slow.getAddress().getPort() + "/slow")
                    .retrieve().body(String.class))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            slow.stop(0);
        }
    }
}
