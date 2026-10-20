package io.github.renhaowan.platform.gateway.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性契约对账。首次执行也延迟一个周期，避免启动时的 ApplicationRunner 与定时任务重复拉取。
 * 关闭方式：platform.gateway.sync-enabled=false（只保留启动对账与手工触发）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "platform.gateway", name = "sync-enabled",
        havingValue = "true", matchIfMissing = true)
public class ContractSyncScheduler {

    private final ContractSyncService contractSyncService;

    public ContractSyncScheduler(ContractSyncService contractSyncService) {
        this.contractSyncService = contractSyncService;
    }

    @Scheduled(fixedDelayString = "${platform.gateway.sync-interval-ms:300000}",
            initialDelayString = "${platform.gateway.sync-interval-ms:300000}")
    public void sync() {
        log.debug("定时契约对账开始");
        contractSyncService.syncAll();
    }
}
