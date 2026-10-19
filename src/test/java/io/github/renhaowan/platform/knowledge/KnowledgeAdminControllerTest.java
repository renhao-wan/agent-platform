package io.github.renhaowan.platform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService.IndexResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeAdminControllerTest {

    @Test
    void disabledReturns503() {
        ObjectProvider<KnowledgeIndexService> provider = mockProvider(null);

        ApiResponse<?> response = new KnowledgeAdminController(provider).reindex(false);

        assertThat(response.code()).isEqualTo(503);
        assertThat(response.message()).contains("EMBEDDING_API_KEY");
    }

    @Test
    void enabledTriggersIndexWithForceFlag() {
        KnowledgeIndexService service = mock(KnowledgeIndexService.class);
        IndexResult result = new IndexResult(5, 2, 3, 0, 0);
        when(service.index(true)).thenReturn(result);

        ApiResponse<?> response = new KnowledgeAdminController(mockProvider(service)).reindex(true);

        assertThat(response.code()).isEqualTo(ApiResponse.OK_CODE);
        assertThat(response.data()).isEqualTo(result);
        verify(service).index(true);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KnowledgeIndexService> mockProvider(KnowledgeIndexService service) {
        ObjectProvider<KnowledgeIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
