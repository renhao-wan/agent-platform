package io.github.renhaowan.platform.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.ai.vectorstore.filter.Filter;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private KnowledgeDocRepository repository;

    @Mock
    private KnowledgeIndexJobRepository jobRepository;

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
        service = new KnowledgeIndexService(properties, repository, jobRepository, vectorStore,
                new MarkdownChunker(800, 50, 100), executor);

        lenient().when(repository.find(anyString())).thenReturn(Optional.empty());
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
                visible_permission: public
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
                .containsEntry("visible_permission", "public")
                .containsEntry("raw_text", "一线城市每晚不超过 600 元，二线城市不超过 400 元。");

        verify(repository).upsertDoc(argThat(doc -> doc.chunkCount() == 1
                && "2-800-50-100".equals(doc.chunkConfig())
                && KnowledgeDoc.STATUS_INDEXED.equals(doc.status())
                && "BAAI/bge-m3".equals(doc.embeddingModel())));
        // 首次索引也按 doc_path 清一遍旧向量：不依赖上次的 chunk 记录
        verify(vectorStore).delete(any(Filter.Expression.class));
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
    void failedDocumentIsRetriedOnNextScan() throws Exception {
        write("leave.md", "# 制度\n\n## 住宿标准\n一线城市每晚不超过 600 元，二线城市不超过 400 元。");
        service.index(false);
        ArgumentCaptor<KnowledgeDoc> saved = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(repository).upsertDoc(saved.capture());
        KnowledgeDoc indexed = saved.getValue();
        org.mockito.Mockito.clearInvocations(repository);
        // 上次失败：hash / 模型 / 切块参数都没变，但状态是 FAILED
        when(repository.find("leave.md")).thenReturn(Optional.of(new KnowledgeDoc(
                indexed.docPath(), indexed.contentHash(), indexed.chunkConfig(), indexed.chunkCount(),
                indexed.embeddingModel(), indexed.indexedAt(), KnowledgeDoc.STATUS_FAILED)));

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.indexed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void chunkConfigChangeTriggersReindex() throws Exception {
        write("leave.md", "# 制度\n\n## 住宿标准\n一线城市每晚不超过 600 元，二线城市不超过 400 元。");
        service.index(false);
        ArgumentCaptor<KnowledgeDoc> saved = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(repository).upsertDoc(saved.capture());
        KnowledgeDoc indexed = saved.getValue();
        when(repository.find("leave.md")).thenReturn(Optional.of(new KnowledgeDoc(
                indexed.docPath(), indexed.contentHash(), "1200-50-100", indexed.chunkCount(),
                indexed.embeddingModel(), indexed.indexedAt(), KnowledgeDoc.STATUS_INDEXED)));

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.indexed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void removesDocumentThatDisappearedFromDisk() {
        when(repository.allPaths()).thenReturn(Set.of("gone.md"));

        KnowledgeIndexService.IndexResult result = service.index(false);

        assertThat(result.removed()).isEqualTo(1);
        // 按 doc_path 过滤删除，不再依赖 knowledge_chunk 里残留的 id
        verify(vectorStore).delete(any(Filter.Expression.class));
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

    @Test
    void writesNewVersionBeforeDeletingStaleChunks() throws Exception {
        write("leave.md", "# 制度\n\n## 住宿标准\n一线城市每晚不超过 600 元，二线城市不超过 400 元。");
        when(repository.chunkIds("leave.md")).thenReturn(Set.of("old-1", "old-2"));

        service.index(false);

        // 顺序很重要：先写新块，再删旧块；反过来会出现检索空白窗口
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(vectorStore);
        order.verify(vectorStore).add(anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> staleIds = ArgumentCaptor.forClass(List.class);
        order.verify(vectorStore).delete(staleIds.capture());
        assertThat(staleIds.getValue()).contains("old-1", "old-2");
        // chunk 表有记录时不需要按 doc_path 全删
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }

    @Test
    void submitIndexFinishesJobAfterIndexCompletes() {
        when(jobRepository.start("MANUAL", true, properties.getJobTimeoutMinutes()))
                .thenReturn(Optional.of(42L));
        KnowledgeIndexService direct = new KnowledgeIndexService(properties, repository, jobRepository,
                vectorStore, new MarkdownChunker(800, 50, 100), directExecutor());

        Optional<Long> jobId = direct.submitIndex("MANUAL", true);

        assertThat(jobId).contains(42L);
        verify(jobRepository).finish(eq(42L), any(KnowledgeIndexService.IndexResult.class));
        verify(jobRepository, never()).fail(anyLong(), anyString());
    }

    @Test
    void submitIndexReturnsEmptyWhenAnotherJobIsRunning() {
        when(jobRepository.start("STARTUP", false, properties.getJobTimeoutMinutes()))
                .thenReturn(Optional.empty());

        assertThat(service.submitIndex("STARTUP", false)).isEmpty();
        verify(jobRepository, never()).finish(anyLong(), any());
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return shutdown;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
