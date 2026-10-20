package io.github.renhaowan.platform.gateway.registry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** 启动入口只负责触发对账；抓取、解析与收敛细节在 ContractSyncService 中测试。 */
class OpenApiSourceRegistrarTest {

    private final ContractSyncService contractSyncService = mock(ContractSyncService.class);

    @Test
    void startupRunsAFullReconciliation() {
        new OpenApiSourceRegistrar(contractSyncService).run(null);

        verify(contractSyncService).syncConfiguredSources();
    }
}
