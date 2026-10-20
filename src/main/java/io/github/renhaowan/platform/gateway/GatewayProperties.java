package io.github.renhaowan.platform.gateway;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具层的接入来源（application.yml 的 platform.gateway.sources）。
 * <p>
 * 每个来源是一个 OpenAPI 契约地址。平台启动时逐个拉取、解析并注册成工具，于是
 * "业务系统的契约可访问" 就等于 "它的工具可用"，不必人工逐次导入。列表留空则跳过
 * 自动注册，完全依赖 {@code POST /admin/protocols/import} 手工导入。
 */
@Data
@ConfigurationProperties(prefix = "platform.gateway")
public class GatewayProperties {

    private List<Source> sources = new ArrayList<>();

    @Data
    public static class Source {

        /**
         * 来源标识，对账时用于判定"哪些工具属于这份契约"。
         * 必须跨部署拓扑保持稳定（不要用 host/端口推导，否则容器内外会被当成两个来源）。
         */
        private String id;

        /** 契约地址（OpenAPI JSON）。其 origin 同时用作该来源工具的调用基地址 */
        private String url;

        /** 覆盖契约里 servers.url 的基地址；留空则取 url 的 origin */
        private String baseUrl;

        /** 调用该来源时附带的系统凭证头名，如 X-Service-Key；留空 = 不带凭证 */
        private String serviceKeyName;

        private String serviceKeyValue;
    }
}
