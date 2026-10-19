package io.github.renhaowan.platform.gateway.registry;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.gateway.GatewayProperties;
import io.github.renhaowan.platform.gateway.forward.ForwardProperties;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 启动时按配置拉取各业务系统的 OpenAPI 契约并注册成工具。
 * <p>
 * 工具因此不是"某次手工导入留下的状态"，而是每次启动都从契约重新推导出的数据——
 * 改了业务系统的 {@code @Operation} 说明或 {@code x-required-role} 声明，重启平台即生效。
 * <p>
 * 单个来源不可用（业务系统没起来、地址写错）只记日志、不中断启动：缺工具的后果是模型
 * 看不到该来源的能力，而不是平台起不来。
 */
@Slf4j
@Component
public class OpenApiSourceRegistrar implements ApplicationRunner {

    private final GatewayProperties gatewayProperties;
    private final AgentProperties agentProperties;
    private final OpenApiImportService importService;
    private final ToolRegistryService registryService;
    private final RestClient restClient;

    public OpenApiSourceRegistrar(GatewayProperties gatewayProperties, AgentProperties agentProperties,
                                  OpenApiImportService importService, ToolRegistryService registryService,
                                  ForwardProperties forwardProperties) {
        this.gatewayProperties = gatewayProperties;
        this.agentProperties = agentProperties;
        this.importService = importService;
        this.registryService = registryService;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(forwardProperties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(forwardProperties.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<GatewayProperties.Source> sources = gatewayProperties.getSources();
        if (sources.isEmpty()) {
            log.info("platform.gateway.sources 为空，跳过契约自动注册；可用 POST /admin/protocols/import 手工导入");
            return;
        }
        for (GatewayProperties.Source source : sources) {
            register(source);
        }
    }

    private void register(GatewayProperties.Source source) {
        try {
            String contract = fetch(source.getUrl());
            List<ToolDefinition> tools = importService.parse(
                    agentProperties.getGatewayId(), contract, baseUrlOf(source));
            // 服务级凭证按来源配置：同一来源的接口共用一份，转发时由 GenericHttpForwarder 附带
            tools.forEach(tool -> {
                tool.setAuthHeaderName(source.getServiceKeyName());
                tool.setAuthHeaderValue(source.getServiceKeyValue());
            });
            registryService.upsertAll(tools);
            log.info("已自动注册 {} 的 {} 个工具", source.getUrl(), tools.size());
        } catch (Exception e) {
            log.warn("契约自动注册失败，来源 {}：{}", source.getUrl(), e.toString());
        }
    }

    /**
     * 从哪拉到契约就往哪发请求：契约地址的 origin 直接作为工具的调用基地址。
     * 这样容器内外两种拓扑（host.docker.internal / localhost）都不用另配 baseUrl，
     * 也不会出现"契约从 A 拉、请求发往 B"的错位。
     */
    private String baseUrlOf(GatewayProperties.Source source) {
        if (source.getBaseUrl() != null && !source.getBaseUrl().isBlank()) {
            return source.getBaseUrl();
        }
        URI uri = URI.create(source.getUrl());
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String fetch(String url) {
        return restClient.get().uri(URI.create(url)).accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("契约地址返回 " + response.getStatusCode().value());
                    }
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
    }
}
