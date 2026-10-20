package io.github.renhaowan.platform.gateway;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具层的接入来源种子（application.yml 的 platform.gateway.sources）。
 * <p>
 * 每项是一个 OpenAPI 契约地址。启动时写入来源注册表（tool_source）：已存在的 CONFIG 来源按
 * 配置刷新，从配置移除的 CONFIG 来源软下线。管理员可在运行时通过 /admin/sources 增删改，
 * 编辑过的来源归管理端所有，配置不再覆盖。
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
