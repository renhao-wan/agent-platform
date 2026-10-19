package io.github.renhaowan.platform.knowledge.index;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * knowledge_doc / knowledge_chunk 两张表（Postgres，与向量同库）。
 * <p>knowledge_doc 判断文档是否需要重建；knowledge_chunk 记录每块的 id/section/顺序，
 * 用于排查切块结果。删除旧向量按 {@code doc_path} 元数据过滤，不依赖这张表——
 * 记录写失败也不会留下删不掉的孤儿向量。
 */
public class KnowledgeDocRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_doc (
                    doc_path        VARCHAR(255) PRIMARY KEY,
                    content_hash    CHAR(64) NOT NULL,
                    chunk_config    VARCHAR(64) NOT NULL DEFAULT '',
                    chunk_count     INT NOT NULL,
                    embedding_model VARCHAR(64) NOT NULL,
                    indexed_at      TIMESTAMP NOT NULL,
                    status          VARCHAR(16) NOT NULL
                )
                """);
        // 旧库补列：切块参数变化同样触发重建，否则 index 与代码参数会悄悄漂移
        jdbcTemplate.execute(
                "ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS chunk_config VARCHAR(64) NOT NULL DEFAULT ''");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunk (
                    id          VARCHAR(64) PRIMARY KEY,
                    doc_path    VARCHAR(255) NOT NULL,
                    section     VARCHAR(255),
                    chunk_index INT NOT NULL
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_doc ON knowledge_chunk(doc_path)");
    }

    public Optional<KnowledgeDoc> find(String docPath) {
        List<KnowledgeDoc> rows = jdbcTemplate.query("""
                        SELECT doc_path, content_hash, chunk_config, chunk_count, embedding_model,
                               indexed_at, status
                        FROM knowledge_doc WHERE doc_path = ?
                        """,
                (rs, rowNum) -> new KnowledgeDoc(
                        rs.getString("doc_path"),
                        rs.getString("content_hash"),
                        rs.getString("chunk_config"),
                        rs.getInt("chunk_count"),
                        rs.getString("embedding_model"),
                        rs.getTimestamp("indexed_at").toLocalDateTime(),
                        rs.getString("status")),
                docPath);
        return rows.stream().findFirst();
    }

    public Set<String> allPaths() {
        return jdbcTemplate.queryForList("SELECT doc_path FROM knowledge_doc", String.class)
                .stream().collect(Collectors.toSet());
    }

    public void replaceChunks(String docPath, List<ChunkRef> chunks) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE doc_path = ?", docPath);
        if (chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO knowledge_chunk (id, doc_path, section, chunk_index) VALUES (?, ?, ?, ?)",
                chunks.stream()
                        .map(c -> new Object[] {c.id(), docPath, c.section(), c.index()})
                        .toList());
    }

    public void upsertDoc(KnowledgeDoc doc) {
        jdbcTemplate.update("""
                        INSERT INTO knowledge_doc
                            (doc_path, content_hash, chunk_config, chunk_count, embedding_model,
                             indexed_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (doc_path) DO UPDATE SET
                            content_hash = EXCLUDED.content_hash,
                            chunk_config = EXCLUDED.chunk_config,
                            chunk_count = EXCLUDED.chunk_count,
                            embedding_model = EXCLUDED.embedding_model,
                            indexed_at = EXCLUDED.indexed_at,
                            status = EXCLUDED.status
                        """,
                doc.docPath(), doc.contentHash(), doc.chunkConfig(), doc.chunkCount(),
                doc.embeddingModel(), Timestamp.valueOf(doc.indexedAt()), doc.status());
    }

    public void markFailed(String docPath) {
        jdbcTemplate.update("""
                        INSERT INTO knowledge_doc
                            (doc_path, content_hash, chunk_config, chunk_count, embedding_model,
                             indexed_at, status)
                        VALUES (?, '', '', 0, '', ?, 'FAILED')
                        ON CONFLICT (doc_path) DO UPDATE SET status = 'FAILED', indexed_at = EXCLUDED.indexed_at
                        """,
                docPath, Timestamp.valueOf(LocalDateTime.now()));
    }

    public void deleteDoc(String docPath) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE doc_path = ?", docPath);
        jdbcTemplate.update("DELETE FROM knowledge_doc WHERE doc_path = ?", docPath);
    }

    public record ChunkRef(String id, String section, int index) {
    }
}
