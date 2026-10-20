package io.github.renhaowan.platform.gateway.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时对注册表里的每个契约来源做一次全量对账。
 * <p>
 * 工具不是"某次手工导入留下的状态"，而是每轮都从契约推导出的期望状态：新增、修改、删除
 * 都会随对账收敛。单个来源不可用只记日志、保留它上一版工具，不中断启动，也不影响其他来源。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "platform.gateway", name = "sync-on-startup",
        havingValue = "true", matchIfMissing = true)
public class OpenApiSourceRegistrar implements ApplicationRunner {

    private final ContractSyncService contractSyncService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始启动时契约对账");
        contractSyncService.syncAll();
    }
}
