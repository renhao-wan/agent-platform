package io.github.renhaowan.platform.knowledge.index;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
                    id           VARCHAR(64) PRIMARY KEY,
                    doc_path     VARCHAR(255) NOT NULL,
                    doc_title    VARCHAR(255) NOT NULL DEFAULT '',
                    section      VARCHAR(255),
                    chunk_index  INT NOT NULL,
                    visible_permission VARCHAR(64) NOT NULL DEFAULT 'public',
                    raw_text     TEXT NOT NULL DEFAULT ''
                )
                """);
        // 旧库补列：关键词兜底需要正文，权限过滤需要可见性
        jdbcTemplate.execute(
                "ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS doc_title VARCHAR(255) NOT NULL DEFAULT ''");
        jdbcTemplate.execute(
                "ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS visible_permission VARCHAR(64) NOT NULL DEFAULT 'public'");
        jdbcTemplate.execute(
                "ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS raw_text TEXT NOT NULL DEFAULT ''");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_doc ON knowledge_chunk(doc_path)");
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_permission ON knowledge_chunk(visible_permission)");
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

    /** 该文档当前已写入的块 id；版本化写入据此只删"新版本不再包含"的旧块。 */
    public Set<String> chunkIds(String docPath) {
        return jdbcTemplate.queryForList(
                        "SELECT id FROM knowledge_chunk WHERE doc_path = ?", String.class, docPath)
                .stream().collect(Collectors.toSet());
    }

    public void replaceChunks(String docPath, List<ChunkRef> chunks) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE doc_path = ?", docPath);
        if (chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO knowledge_chunk
                            (id, doc_path, doc_title, section, chunk_index, visible_permission, raw_text)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                chunks.stream()
                        .map(c -> new Object[] {c.id(), docPath, c.docTitle(), c.section(), c.index(),
                                c.visiblePermission(), c.text()})
                        .toList());
    }

    /**
     * 向量检索无结果时的关键词兜底。{@code pattern} 已由调用方完成 LIKE 转义。
     * <p>权限过滤写进 SQL：无权内容从不进入候选集。{@code *} 表示全见；否则只命中
     * public 与调用者拥有的权限码。短块优先——它更像精确条目。
     */
    public List<ChunkRow> searchByKeyword(String pattern, Set<String> permissions, int limit) {
        boolean all = permissions != null && permissions.contains("*");
        String sql = "SELECT doc_title, section, raw_text FROM knowledge_chunk WHERE raw_text ILIKE ? ESCAPE '\\'";
        List<Object> args = new ArrayList<>();
        args.add(pattern);
        if (!all) {
            Set<String> visible = new LinkedHashSet<>();
            visible.add("public");
            if (permissions != null) {
                visible.addAll(permissions);
            }
            sql += " AND visible_permission IN ("
                    + String.join(", ", visible.stream().map(p -> "?").toList()) + ")";
            args.addAll(visible);
        }
        sql += " ORDER BY length(raw_text) ASC, chunk_index ASC LIMIT ?";
        args.add(limit);
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new ChunkRow(
                        rs.getString("doc_title"),
                        rs.getString("section"),
                        rs.getString("raw_text")),
                args.toArray());
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

    public record ChunkRef(String id, String docTitle, String section, int index,
            String visiblePermission, String text) {
    }

    public record ChunkRow(String docTitle, String section, String text) {
    }
}
