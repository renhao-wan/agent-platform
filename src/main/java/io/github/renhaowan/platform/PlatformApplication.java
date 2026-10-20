package io.github.renhaowan.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。@ConfigurationPropertiesScan 负责扫描并注册 @ConfigurationProperties 配置类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
