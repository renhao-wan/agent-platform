package io.github.renhaowan.platform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.knowledge.embedding.EmbeddingUsageRepository;
import io.github.renhaowan.platform.knowledge.eval.KnowledgeEvalService;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexJobRepository.Job;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchAuditRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeAdminControllerTest {

    @Test
    void disabledReturns503() {
        ApiResponse<?> response = controller(null).reindex(false);

        assertThat(response.code()).isEqualTo(503);
        assertThat(response.message()).contains("EMBEDDING_API_KEY");
    }

    @Test
    void reindexReturnsJobIdInsteadOfBlockingOnIndex() {
        KnowledgeIndexService service = mock(KnowledgeIndexService.class);
        when(service.submitIndex("MANUAL", true)).thenReturn(Optional.of(42L));

        ApiResponse<?> response = controller(service).reindex(true);

        assertThat(response.code()).isEqualTo(ApiResponse.OK_CODE);
        assertThat(response.data()).isEqualTo(Map.of("jobId", 42L));
        verify(service).submitIndex("MANUAL", true);
    }

    @Test
    void reindexReturnsConflictWhenAnotherJobIsRunning() {
        KnowledgeIndexService service = mock(KnowledgeIndexService.class);
        when(service.submitIndex("MANUAL", false)).thenReturn(Optional.empty());

        ApiResponse<?> response = controller(service).reindex(false);

        assertThat(response.code()).isEqualTo(409);
        assertThat(response.message()).contains("/admin/knowledge/jobs");
    }

    @Test
    void jobReturns404WhenMissing() {
        KnowledgeIndexService service = mock(KnowledgeIndexService.class);
        when(service.job(7L)).thenReturn(Optional.empty());

        assertThat(controller(service).job(7L).code()).isEqualTo(404);
    }

    @Test
    void jobAndJobsAreReadable() {
        KnowledgeIndexService service = mock(KnowledgeIndexService.class);
        Job job = new Job(7L, "MANUAL", true, "SUCCEEDED", 10, 2, 8, 0, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(service.job(7L)).thenReturn(Optional.of(job));
        when(service.jobs(20)).thenReturn(List.of(job));

        assertThat(controller(service).job(7L).data()).isEqualTo(job);
        assertThat(controller(service).jobs(20).data()).isEqualTo(List.of(job));
    }

    @Test
    void evalReturnsReport() {
        KnowledgeEvalService service = mock(KnowledgeEvalService.class);
        KnowledgeEvalService.EvalReport report = new KnowledgeEvalService.EvalReport(
                2, 1, 0.5, 0.5, 0.0, 4, List.of());
        when(service.evaluate(4)).thenReturn(report);

        ApiResponse<?> response = new KnowledgeAdminController(mockProvider(null),
                mockProvider(null), mockProvider(null), mockProvider(service)).eval(4);

        assertThat(response.code()).isEqualTo(ApiResponse.OK_CODE);
        assertThat(response.data()).isEqualTo(report);
    }

    @Test
    void evalWithoutQuestionFileReturns400() {
        KnowledgeEvalService service = mock(KnowledgeEvalService.class);
        when(service.evaluate(4)).thenThrow(new IllegalStateException("评估集不存在"));

        ApiResponse<?> response = new KnowledgeAdminController(mockProvider(null),
                mockProvider(null), mockProvider(null), mockProvider(service)).eval(4);

        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("评估集不存在");
    }

    private static KnowledgeAdminController controller(KnowledgeIndexService service) {
        return new KnowledgeAdminController(mockProvider(service),
                mockProvider(null), mockProvider(null), mockProvider(null));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
