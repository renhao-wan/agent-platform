package io.github.renhaowan.platform.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.index.KnowledgeDocRepository;
import io.github.renhaowan.platform.knowledge.index.KnowledgeDocRepository.ChunkRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchServiceTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private KnowledgeDocRepository docRepository;
    @Mock
    private KnowledgeSearchAuditRepository auditRepository;

    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private KnowledgeSearchService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeSearchService(vectorStore, properties, docRepository,
                auditRepository, meterRegistry);
    }

    @Test
    void staffOnlySearchesPublicAndOwnRole() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search(1L, "住宿标准", 4, "STAFF");

        // SearchRequest 把字符串解析为 Expression，断言其语义而非源码文本
        assertThat(capturedRequest().getFilterExpression().toString())
                .contains("visible_role")
                .contains("PUBLIC")
                .contains("STAFF")
                .doesNotContain("ADMIN");
    }

    @Test
    void adminSearchesWithoutRoleFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search(1L, "审批权限", 4, "ADMIN");

        // ADMIN 可读全部：不下发过滤条件，比枚举所有角色更不容易漏
        assertThat(capturedRequest().getFilterExpression()).isNull();
    }

    @Test
    void topKIsClampedToConfiguredMaximum() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search(1L, "制度", 999, "STAFF");

        assertThat(capturedRequest().getTopK()).isEqualTo(properties.getTopKMax());
    }

    @Test
    void blankQueryDoesNotTouchVectorStore() {
        assertThat(service.search(1L, "   ", 4, "STAFF")).isEmpty();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void mapsMetadataAndDistanceToHit() {
        Document document = Document.builder()
                .id("1")
                .text("差旅报销制度 > 住宿标准\n一线城市每晚不超过 600 元。")
                .metadata(Map.of(
                        "doc_title", "差旅报销制度",
                        "section", "住宿标准",
                        "raw_text", "一线城市每晚不超过 600 元。"))
                .score(0.82)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        List<KnowledgeSearchService.SearchHit> hits = service.search(1L, "住宿", 4, "STAFF");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("差旅报销制度");
        assertThat(hits.get(0).section()).isEqualTo("住宿标准");
        assertThat(hits.get(0).text()).isEqualTo("一线城市每晚不超过 600 元。");
        assertThat(hits.get(0).score()).isCloseTo(0.82, within(0.001));
    }

    @Test
    void emptyVectorResultFallsBackToKeywordSearchWithRoleFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(docRepository.searchByKeyword(anyString(), eq("STAFF"), eq(4)))
                .thenReturn(List.of(new ChunkRow("报销制度", "第九条", "制度编号 BX-2024-01 适用于全公司。")));

        List<KnowledgeSearchService.SearchHit> hits = service.search(1L, "BX-2024-01", 4, "STAFF");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("报销制度");
        // 关键词命中没有相似度可言，不能伪造分数
        assertThat(hits.get(0).score()).isNaN();
        verify(docRepository).searchByKeyword("%BX-2024-01%", "STAFF", 4);
    }

    @Test
    void keywordFallbackEscapesLikeWildcards() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(docRepository.searchByKeyword(anyString(), anyString(), anyInt())).thenReturn(List.of());

        service.search(1L, "100%_完成", 4, "STAFF");

        verify(docRepository).searchByKeyword("%100\\%\\_完成%", "STAFF", 4);
    }

    @Test
    void searchIsAuditedWithQueryAndSources() {
        Document document = Document.builder()
                .id("1")
                .text("差旅报销制度 > 住宿标准\n一线城市每晚不超过 600 元。")
                .metadata(Map.of("doc_title", "差旅报销制度", "section", "住宿标准",
                        "raw_text", "一线城市每晚不超过 600 元。"))
                .score(0.82)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        service.search(7L, "住宿标准", 4, "STAFF");

        verify(auditRepository).record(eq(7L), eq("STAFF"), eq("住宿标准"), eq(4), eq(1),
                eq(List.of("差旅报销制度 > 住宿标准/0.820")), anyLong());
        assertThat(meterRegistry.counter("knowledge.search.requests", "role", "STAFF", "mode", "vector")
                .count()).isEqualTo(1.0);
    }

    @Test
    void auditFailureDoesNotBreakSearch() {
        Document document = Document.builder()
                .id("1")
                .text("正文")
                .metadata(Map.of("doc_title", "制度", "section", "条款", "raw_text", "正文"))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        doThrow(new IllegalStateException("audit table down")).when(auditRepository)
                .record(anyLong(), any(), anyString(), anyInt(), anyInt(), anyList(), anyLong());

        List<KnowledgeSearchService.SearchHit> hits = service.search(1L, "制度", 4, "STAFF");

        assertThat(hits).hasSize(1);
    }

    private SearchRequest capturedRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }
}
