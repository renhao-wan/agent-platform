package io.github.renhaowan.platform.knowledge.embedding;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * embedding 用量打点（Postgres，与向量同库，不把知识库模块耦到业务 MySQL）。
 * <p>企业要回答"知识库每月花多少钱"：按模型与时间聚合 prompt tokens，乘以供应商单价即可。
 */
public class EmbeddingUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_usage (
                    id             BIGSERIAL PRIMARY KEY,
                    model          VARCHAR(64) NOT NULL,
                    document_count INT NOT NULL,
                    prompt_tokens  INT NOT NULL,
                    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_knowledge_usage_created
                ON knowledge_usage(created_at)
                """);
    }

    public void record(String model, int documentCount, int promptTokens) {
        jdbcTemplate.update("""
                        INSERT INTO knowledge_usage (model, document_count, prompt_tokens)
                        VALUES (?, ?, ?)
                        """,
                model, documentCount, promptTokens);
    }

    public UsageSummary summary() {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS calls,
                               COALESCE(SUM(document_count), 0) AS docs,
                               COALESCE(SUM(prompt_tokens), 0) AS tokens
                        FROM knowledge_usage
                        """,
                (rs, rowNum) -> new UsageSummary(
                        rs.getLong("calls"), rs.getLong("docs"), rs.getLong("tokens")));
    }

    public record UsageSummary(long calls, long documents, long promptTokens) {
    }
}
