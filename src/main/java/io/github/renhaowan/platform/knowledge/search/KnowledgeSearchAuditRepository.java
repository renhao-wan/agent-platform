package io.github.renhaowan.platform.knowledge.search;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 检索审计：谁、在什么时候、用什么问题、命中了哪些文档。
 * <p>与 INFO 日志的区别是"可查询、可留存"——合规上要能回答敏感制度被谁查过。
 * 只记来源与分数，不记正文，避免知识内容在审计表里二次留存。
 */
public class KnowledgeSearchAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeSearchAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_search_audit (
                    id         BIGSERIAL PRIMARY KEY,
                    user_id    BIGINT NOT NULL DEFAULT 0,
                    role       VARCHAR(64),
                    query      VARCHAR(500) NOT NULL,
                    top_k      INT NOT NULL,
                    hit_count  INT NOT NULL,
                    sources    TEXT,
                    elapsed_ms INT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW()
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_knowledge_search_audit_created
                ON knowledge_search_audit(created_at)
                """);
    }

    public void record(long userId, String role, String query, int topK, int hitCount,
            List<String> sources, long elapsedMs) {
        String joined = String.join("; ", sources);
        if (joined.length() > 4000) {
            joined = joined.substring(0, 4000);
        }
        String truncatedQuery = query.length() > 500 ? query.substring(0, 500) : query;
        jdbcTemplate.update("""
                        INSERT INTO knowledge_search_audit
                            (user_id, role, query, top_k, hit_count, sources, elapsed_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                userId, role, truncatedQuery, topK, hitCount, joined, elapsedMs);
    }

    /** 按留存期清理；由启动流程调用，避免审计表无限增长。 */
    public int purgeOlderThan(int days) {
        if (days <= 0) {
            return 0;
        }
        return jdbcTemplate.update(
                "DELETE FROM knowledge_search_audit WHERE created_at < NOW() - make_interval(days => ?)",
                days);
    }

    public List<AuditRow> recent(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, user_id, role, query, top_k, hit_count, sources, elapsed_ms, created_at
                        FROM knowledge_search_audit ORDER BY id DESC LIMIT ?
                        """,
                (rs, rowNum) -> new AuditRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("role"),
                        rs.getString("query"),
                        rs.getInt("top_k"),
                        rs.getInt("hit_count"),
                        rs.getString("sources"),
                        rs.getInt("elapsed_ms"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                Math.max(1, Math.min(200, limit)));
    }

    public record AuditRow(long id, long userId, String role, String query, int topK,
            int hitCount, String sources, int elapsedMs, LocalDateTime createdAt) {
    }
}
