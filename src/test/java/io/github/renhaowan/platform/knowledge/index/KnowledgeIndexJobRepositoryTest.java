package io.github.renhaowan.platform.knowledge.index;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeIndexJobRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final KnowledgeIndexJobRepository repository = new KnowledgeIndexJobRepository(jdbcTemplate);

    @Test
    void allDocumentsFailedMarksJobFailed() {
        repository.finish(9L, new KnowledgeIndexService.IndexResult(5, 0, 0, 5, 0));

        verify(jdbcTemplate).update(anyString(), eq(KnowledgeIndexJobRepository.STATUS_FAILED),
                eq(5), eq(0), eq(0), eq(5), eq(0), contains("全部 5 份文档索引失败"), eq(9L));
    }

    @Test
    void partialFailureStaysSucceeded() {
        repository.finish(9L, new KnowledgeIndexService.IndexResult(5, 2, 0, 3, 0));

        verify(jdbcTemplate).update(anyString(), eq(KnowledgeIndexJobRepository.STATUS_SUCCEEDED),
                eq(5), eq(2), eq(0), eq(3), eq(0), isNull(), eq(9L));
    }

    @Test
    void allSkippedStaysSucceeded() {
        repository.finish(9L, new KnowledgeIndexService.IndexResult(5, 0, 5, 0, 0));

        verify(jdbcTemplate).update(anyString(), eq(KnowledgeIndexJobRepository.STATUS_SUCCEEDED),
                eq(5), eq(0), eq(5), eq(0), eq(0), isNull(), eq(9L));
    }
}
