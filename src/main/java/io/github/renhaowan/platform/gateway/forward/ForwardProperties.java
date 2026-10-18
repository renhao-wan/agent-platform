package io.github.renhaowan.platform.gateway.forward;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转发层配置项（application.yml 的 platform.gateway.forward.*）。
 * <p>默认值与历史硬编码值一致（connect 2s / read 10s），线上行为零变化；
 * 可调的关键用途是让测试用极短超时验证超时路径。
 */
@Data
@ConfigurationProperties(prefix = "platform.gateway.forward")
public class ForwardProperties {

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 10000;
}
