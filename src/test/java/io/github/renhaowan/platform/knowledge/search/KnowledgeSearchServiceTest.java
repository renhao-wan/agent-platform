package io.github.renhaowan.platform.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import java.util.List;
import java.util.Map;
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

    private final KnowledgeProperties properties = new KnowledgeProperties();

    @Test
    void staffOnlySearchesPublicChunks() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        KnowledgeSearchService service = new KnowledgeSearchService(vectorStore, properties);

        service.search("住宿标准", 4, "STAFF");

        // SearchRequest 把字符串解析为 Expression，断言其语义而非源码文本
        assertThat(capturedRequest().getFilterExpression().toString())
                .contains("visible_role")
                .contains("PUBLIC")
                .doesNotContain("ADMIN");
    }

    @Test
    void adminSearchesPublicAndAdminChunks() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        KnowledgeSearchService service = new KnowledgeSearchService(vectorStore, properties);

        service.search("审批权限", 4, "ADMIN");

        assertThat(capturedRequest().getFilterExpression().toString())
                .contains("type=OR")
                .contains("PUBLIC")
                .contains("ADMIN");
    }

    @Test
    void topKIsClampedToConfiguredMaximum() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        KnowledgeSearchService service = new KnowledgeSearchService(vectorStore, properties);

        service.search("制度", 999, "STAFF");

        assertThat(capturedRequest().getTopK()).isEqualTo(properties.getTopKMax());
    }

    @Test
    void blankQueryDoesNotTouchVectorStore() {
        KnowledgeSearchService service = new KnowledgeSearchService(vectorStore, properties);

        assertThat(service.search("   ", 4, "STAFF")).isEmpty();
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
                        "raw_text", "一线城市每晚不超过 600 元。",
                        "distance", 0.18))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        KnowledgeSearchService service = new KnowledgeSearchService(vectorStore, properties);

        List<KnowledgeSearchService.SearchHit> hits = service.search("住宿", 4, "STAFF");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("差旅报销制度");
        assertThat(hits.get(0).section()).isEqualTo("住宿标准");
        assertThat(hits.get(0).text()).isEqualTo("一线城市每晚不超过 600 元。");
        assertThat(hits.get(0).score()).isCloseTo(0.82, within(0.001));
    }

    private SearchRequest capturedRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }
}
