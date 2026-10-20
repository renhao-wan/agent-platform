package io.github.renhaowan.platform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.auth.RbacService;
import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.knowledge.embedding.EmbeddingUsageRepository;
import io.github.renhaowan.platform.knowledge.eval.KnowledgeEvalService;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexJobRepository.Job;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchAuditRepository;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService.SearchHit;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService.SearchOutcome;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

        ApiResponse<?> response = controller(null, service, null, rbacService()).eval(4);

        assertThat(response.code()).isEqualTo(ApiResponse.OK_CODE);
        assertThat(response.data()).isEqualTo(report);
    }

    @Test
    void evalWithoutQuestionFileReturns400() {
        KnowledgeEvalService service = mock(KnowledgeEvalService.class);
        when(service.evaluate(4)).thenThrow(new IllegalStateException("评估集不存在"));

        ApiResponse<?> response = controller(null, service, null, rbacService()).eval(4);

        assertThat(response.code()).isEqualTo(400);
        assertThat(response.message()).contains("评估集不存在");
    }

    @Test
    void searchReturns503WhenKnowledgeDisabled() {
        ApiResponse<?> response = controller(null)
                .search(new KnowledgeAdminController.SearchBody("年假怎么算", "STAFF", 4));

        assertThat(response.code()).isEqualTo(503);
        assertThat(response.message()).contains("EMBEDDING_API_KEY");
    }

    @Test
    void searchRejectsBlankQueryWithoutHittingSearchService() {
        KnowledgeSearchService service = mock(KnowledgeSearchService.class);

        ApiResponse<?> response = controller(null, null, service, rbacService())
                .search(new KnowledgeAdminController.SearchBody("   ", "STAFF", 4));

        assertThat(response.code()).isEqualTo(400);
        verify(service, org.mockito.Mockito.never())
                .searchDetailed(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void searchReturnsHitsAndModeWithRequestedRole() {
        KnowledgeSearchService service = mock(KnowledgeSearchService.class);
        SearchOutcome outcome = new SearchOutcome(
                List.of(new SearchHit("员工手册", "年假", "入职满一年享受 5 天年假", 0.82)),
                "vector", 12);
        when(service.searchDetailed(0L, "年假", 6, Set.of("role:STAFF"), "STAFF")).thenReturn(outcome);

        ApiResponse<?> response = controller(null, null, service, rbacService())
                .search(new KnowledgeAdminController.SearchBody("年假", "STAFF", 6));

        assertThat(response.code()).isEqualTo(ApiResponse.OK_CODE);
        assertThat(response.data()).isEqualTo(outcome);
    }

    private static KnowledgeAdminController controller(KnowledgeIndexService service) {
        return controller(service, null, null, rbacService());
    }

    private static KnowledgeAdminController controller(KnowledgeIndexService indexService,
            KnowledgeEvalService evalService, KnowledgeSearchService searchService, RbacService rbacService) {
        return new KnowledgeAdminController(mockProvider(indexService), mockProvider(null),
                mockProvider(null), mockProvider(evalService), mockProvider(searchService), rbacService);
    }

    private static RbacService rbacService() {
        RbacService rbacService = mock(RbacService.class);
        when(rbacService.permissionsOfRole("STAFF")).thenReturn(Set.of("role:STAFF"));
        return rbacService;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
