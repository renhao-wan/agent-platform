package io.github.renhaowan.platform.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

@ExtendWith(MockitoExtension.class)
class UsageRecordingEmbeddingModelTest {

    @Mock
    private EmbeddingModel delegate;
    @Mock
    private EmbeddingUsageRepository repository;

    private UsageRecordingEmbeddingModel model;

    @BeforeEach
    void setUp() {
        model = new UsageRecordingEmbeddingModel(delegate, repository, "BAAI/bge-m3");
    }

    @Test
    void recordsPromptTokensAndDocumentCount() {
        EmbeddingRequest request = request("a", "b");
        EmbeddingResponse response = response(new DefaultUsage(12, 0));
        when(delegate.call(request)).thenReturn(response);

        EmbeddingResponse actual = model.call(request);

        assertThat(actual).isSameAs(response);
        verify(repository).record("BAAI/bge-m3", 2, 12);
    }

    @Test
    void missingUsageMetadataRecordsZeroTokens() {
        EmbeddingRequest request = request("a");
        EmbeddingResponse response = response(null);
        when(delegate.call(request)).thenReturn(response);

        assertThat(model.call(request)).isSameAs(response);
        verify(repository).record("BAAI/bge-m3", 1, 0);
    }

    @Test
    void usageRecordingFailureDoesNotBreakEmbedding() {
        EmbeddingRequest request = request("a");
        EmbeddingResponse response = response(new DefaultUsage(3, 0));
        when(delegate.call(request)).thenReturn(response);
        doThrow(new IllegalStateException("usage table down")).when(repository)
                .record(anyString(), anyInt(), anyInt());

        assertThat(model.call(request)).isSameAs(response);
    }

    private static EmbeddingRequest request(String... texts) {
        return new EmbeddingRequest(List.of(texts), EmbeddingOptions.builder().build());
    }

    private static EmbeddingResponse response(DefaultUsage usage) {
        Embedding embedding = new Embedding(new float[] {0.1f, 0.2f}, 0);
        if (usage == null) {
            return new EmbeddingResponse(List.of(embedding));
        }
        return new EmbeddingResponse(List.of(embedding),
                new EmbeddingResponseMetadata("BAAI/bge-m3", usage));
    }
}
