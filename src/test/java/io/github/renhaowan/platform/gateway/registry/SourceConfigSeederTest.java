package io.github.renhaowan.platform.gateway.registry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** 启动时先种子来源（只读写库），再由 Registrar 拉取契约。 */
class SourceConfigSeederTest {

    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);

    @Test
    void startupSeedsSourcesFromConfig() {
        new SourceConfigSeeder(sourceRegistryService).run(null);

        verify(sourceRegistryService).seedFromConfig();
    }
}
