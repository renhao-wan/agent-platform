package io.github.renhaowan.platform.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.chunk.MarkdownChunker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private KnowledgeDocRepository repository;

    @Mock
    private VectorStore vectorStore;

    private KnowledgeProperties properties;
    private KnowledgeIndexService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeProperties();
        properties.setDirectory(tempDir.toString());
        properties.setEmbeddingModel("BAAI/bge-m3");
        executor = Executors.newSingleThreadExecutor();
        service = new KnowledgeIndexService(properties, repository, vectorStore,
                new MarkdownChunker(800, 50, 100), executor);

        lenient().when(repository.find(anyString())).thenReturn(Optional.empty());
        lenient().when(repository.chunkIds(anyString())).thenReturn(List.of());
        lenient().when(repository.allPaths()).thenReturn(Set.of());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void indexesNewDocumentWithMetadata() throws Exception {
        write("leave.md", """
                ---
                visible_role: PUBLIC
                ---
                # 差旅报销制度

                ## 住宿标准
                一线城市每晚不超过 600 元，二线城市不超过 400 元。
                """);

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.indexed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        Document document = documents.getValue().get(0);
        assertThat(document.getText()).startsWith("差旅报销制度 > 住宿标准");
        assertThat(document.getMetadata()).containsEntry("doc_path", "leave.md")
                .containsEntry("visible_role", "PUBLIC")
                .containsEntry("raw_text", "一线城市每晚不超过 600 元，二线城市不超过 400 元。");

        verify(repository).upsertDoc(argThat(doc -> doc.chunkCount() == 1
                && KnowledgeDoc.STATUS_INDEXED.equals(doc.status())
                && "BAAI/bge-m3".equals(doc.embeddingModel())));
    }

    @Test
    void skipsUnchangedDocumentOnSecondScan() throws Exception {
        write("leave.md", "# 制度\n\n## 住宿标准\n一线城市每晚不超过 600 元，二线城市不超过 400 元。");
        service.index(false);

        ArgumentCaptor<KnowledgeDoc> saved = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(repository).upsertDoc(saved.capture());
        when(repository.find("leave.md")).thenReturn(Optional.of(saved.getValue()));

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.indexed()).isZero();
        // 第一次索引的一次 add；第二次未变，不应再调用
        verify(vectorStore).add(anyList());
    }

    @Test
    void forceReindexesEvenWhenHashUnchanged() throws Exception {
        write("leave.md", "# 制度\n\n## 住宿标准\n一线城市每晚不超过 600 元，二线城市不超过 400 元。");
        service.index(false);

        ArgumentCaptor<KnowledgeDoc> saved = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(repository).upsertDoc(saved.capture());
        when(repository.find("leave.md")).thenReturn(Optional.of(saved.getValue()));

        KnowledgeIndexService.IndexResult result = service.index(true);

        assertThat(result.indexed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void removesDocumentThatDisappearedFromDisk() {
        when(repository.allPaths()).thenReturn(Set.of("gone.md"));
        when(repository.chunkIds("gone.md")).thenReturn(List.of("chunk-1"));

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.removed()).isEqualTo(1);
        verify(vectorStore).delete(List.of("chunk-1"));
        verify(repository).deleteDoc("gone.md");
    }

    @Test
    void failedDocumentIsMarkedButDoesNotBlockOthers() throws Exception {
        write("bad.md", "# 制度\n\n## 条款\n这一段内容会被写入向量库。");
        doThrow(new IllegalStateException("embedding down")).when(vectorStore).add(anyList());

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.failed()).isEqualTo(1);
        verify(repository).markFailed("bad.md");
        verify(repository, never()).upsertDoc(any());
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
