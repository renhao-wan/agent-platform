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
 * <p>为什么还要 chunk 表：向量库的 delete 需要 id 列表；知道"这篇文档上一次写入了哪些块"
 * 才能在重建时精确删掉旧版本，而不是按 filter 清空后重建（中途失败会整篇丢失）。
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
                    chunk_count     INT NOT NULL,
                    embedding_model VARCHAR(64) NOT NULL,
                    indexed_at      TIMESTAMP NOT NULL,
                    status          VARCHAR(16) NOT NULL
                )
                """);
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
                        SELECT doc_path, content_hash, chunk_count, embedding_model, indexed_at, status
                        FROM knowledge_doc WHERE doc_path = ?
                        """,
                (rs, rowNum) -> new KnowledgeDoc(
                        rs.getString("doc_path"),
                        rs.getString("content_hash"),
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

    public List<String> chunkIds(String docPath) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM knowledge_chunk WHERE doc_path = ?", String.class, docPath);
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
                            (doc_path, content_hash, chunk_count, embedding_model, indexed_at, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (doc_path) DO UPDATE SET
                            content_hash = EXCLUDED.content_hash,
                            chunk_count = EXCLUDED.chunk_count,
                            embedding_model = EXCLUDED.embedding_model,
                            indexed_at = EXCLUDED.indexed_at,
                            status = EXCLUDED.status
                        """,
                doc.docPath(), doc.contentHash(), doc.chunkCount(), doc.embeddingModel(),
                Timestamp.valueOf(doc.indexedAt()), doc.status());
    }

    public void markFailed(String docPath) {
        jdbcTemplate.update("""
                        INSERT INTO knowledge_doc
                            (doc_path, content_hash, chunk_count, embedding_model, indexed_at, status)
                        VALUES (?, '', 0, '', ?, 'FAILED')
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
