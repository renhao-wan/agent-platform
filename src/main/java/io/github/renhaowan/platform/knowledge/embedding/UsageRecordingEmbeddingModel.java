package io.github.renhaowan.platform.knowledge.embedding;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * embedding 用量记录装饰器。
 * <p>PgVectorStore 批量写入时走 {@code embed(List<String>)} → {@code embedForResponse} → {@link #call}，
 * 所以覆盖 {@code call} 即可覆盖索引与查询两条路径。
 * <p>打点失败绝不能影响检索/索引本身——用量是附属信息，不是主链路。
 */
@Slf4j
public class UsageRecordingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final EmbeddingUsageRepository usageRepository;
    private final String model;

    public UsageRecordingEmbeddingModel(EmbeddingModel delegate,
            EmbeddingUsageRepository usageRepository, String model) {
        this.delegate = delegate;
        this.usageRepository = usageRepository;
        this.model = model;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response = delegate.call(request);
        try {
            Integer tokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                    ? null
                    : response.getMetadata().getUsage().getPromptTokens();
            int documents = request.getInstructions() == null ? 0 : request.getInstructions().size();
            usageRepository.record(model, documents, tokens == null ? 0 : tokens);
        } catch (Exception e) {
            log.warn("embedding 用量记录失败：{}", e.getMessage());
        }
        return response;
    }

    @Override
    public float[] embed(Document document) {
        // 走本类 call 而不是委托：单条 embedding 也要计入用量
        return embed(List.of(document.getText())).get(0);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
