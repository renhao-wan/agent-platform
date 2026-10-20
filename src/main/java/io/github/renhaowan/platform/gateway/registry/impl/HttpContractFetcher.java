package io.github.renhaowan.platform.gateway.registry.impl;

import io.github.renhaowan.platform.gateway.forward.ForwardProperties;
import io.github.renhaowan.platform.gateway.registry.ContractFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 用与工具转发一致的超时设置拉取契约；非 2xx 直接失败，交给上层记 FAILED 并保留旧数据。 */
@Component
public class HttpContractFetcher implements ContractFetcher {

    private final RestClient restClient;

    public HttpContractFetcher(ForwardProperties forwardProperties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(forwardProperties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(forwardProperties.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String fetch(String url) {
        return restClient.get().uri(URI.create(url)).accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("契约地址返回 " + response.getStatusCode().value());
                    }
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
    }
}
