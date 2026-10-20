package io.github.renhaowan.platform.gateway.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把配置里的来源种进注册表：新增的写入、CONFIG 来源按配置刷新、配置中已消失的软下线。
 * <p>
 * 与对账分开：种子只读写数据库，不访问业务系统，因此即使 sync-on-startup=false
 * （平台先于业务系统启动）也应该执行。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "platform.gateway", name = "seed-on-startup",
        havingValue = "true", matchIfMissing = true)
public class SourceConfigSeeder implements ApplicationRunner {

    private final SourceRegistryService sourceRegistryService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("按配置同步来源注册表");
        sourceRegistryService.seedFromConfig();
    }
}
