package io.github.renhaowan.platform.knowledge;

import io.github.renhaowan.platform.knowledge.chunk.MarkdownChunker;
import io.github.renhaowan.platform.knowledge.index.KnowledgeDocRepository;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchTool;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库装配。整个配置类受 {@link KnowledgeEnabledCondition} 控制——未配置 embedding key
 * 时不连 Postgres、不注册工具，平台其余功能与 {@code mvn test} 均不依赖向量库。
 * <p>为什么手动构造而不走 starter 自动配置：chat 已被 DeepSeek 占用，embedding 要指向
 * SiliconFlow；自动配置会抓主 DataSource（MySQL），也会复用 DeepSeek 的 base-url。
 */
@Slf4j
@Configuration
@Conditional(KnowledgeEnabledCondition.class)
public class KnowledgeConfig {

    @Bean(destroyMethod = "close")
    public KnowledgeJdbc knowledgeJdbc(KnowledgeProperties properties) {
        try {
            return new KnowledgeJdbc(properties);
        } catch (RuntimeException e) {
            // 配置了 key 却连不上向量库属于部署错误：明确失败，不静默摘掉知识库
            throw new IllegalStateException(
                    "知识库 Postgres 不可用（" + properties.getVectorStoreUrl()
                            + "）。请先启动 pgvector（docker compose up -d pgvector），"
                            + "或清空 EMBEDDING_API_KEY 以禁用知识库。", e);
        }
    }

    @Bean
    public KnowledgeDocRepository knowledgeDocRepository(KnowledgeJdbc knowledgeJdbc) {
        KnowledgeDocRepository repository = new KnowledgeDocRepository(knowledgeJdbc.jdbcTemplate());
        repository.initSchema();
        return repository;
    }

    @Bean
    public EmbeddingModel knowledgeEmbeddingModel(KnowledgeProperties properties) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.getEmbeddingBaseUrl())
                .apiKey(properties.getEmbeddingApiKey())
                .embeddingsPath("/v1/embeddings")
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(properties.getEmbeddingModel())
                        .build());
    }

    @Bean
    public VectorStore knowledgeVectorStore(KnowledgeJdbc knowledgeJdbc,
            EmbeddingModel knowledgeEmbeddingModel, KnowledgeProperties properties) {
        return PgVectorStore.builder(knowledgeJdbc.jdbcTemplate(), knowledgeEmbeddingModel)
                // dimensions 必须与 embedding 模型一致；写在配置里而不是靠模型自省，
                // 是为了让"换了维度不匹配的模型"在建表阶段就暴露，而不是查询时才报错。
                .dimensions(properties.getEmbeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .vectorTableName("knowledge_vector")
                .build();
    }

    @Bean
    public MarkdownChunker markdownChunker(KnowledgeProperties properties) {
        return new MarkdownChunker(properties.getMaxChars(), properties.getMinChars(), properties.getOverlapChars());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService knowledgeIndexExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knowledge-index-0");
            thread.setDaemon(false);
            return thread;
        });
    }

    @Bean
    public KnowledgeIndexService knowledgeIndexService(KnowledgeProperties properties,
            KnowledgeDocRepository repository, VectorStore vectorStore,
            MarkdownChunker markdownChunker, ExecutorService knowledgeIndexExecutor) {
        return new KnowledgeIndexService(properties, repository, vectorStore, markdownChunker,
                knowledgeIndexExecutor);
    }

    @Bean
    public KnowledgeSearchService knowledgeSearchService(VectorStore vectorStore, KnowledgeProperties properties) {
        return new KnowledgeSearchService(vectorStore, properties);
    }

    @Bean
    public KnowledgeSearchTool knowledgeSearchTool(KnowledgeSearchService searchService,
            KnowledgeProperties properties) {
        return new KnowledgeSearchTool(searchService, properties);
    }

    @Bean
    public ApplicationRunner knowledgeIndexRunner(KnowledgeIndexService indexService) {
        return args -> {
            log.info("知识库已启用，开始后台索引");
            indexService.indexAsync();
        };
    }
}
