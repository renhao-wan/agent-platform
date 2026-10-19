package io.github.renhaowan.platform.knowledge.index;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 索引任务表：让"重建要多久、卡在哪、是否失败"可查询，而不是只在日志里。
 * <p>跨实例互斥靠部分唯一索引 {@code WHERE status='RUNNING'}：同一时刻只允许一行 RUNNING，
 * 多副本同时触发时后到的插入冲突即放弃，不需要分布式锁。
 */
public class KnowledgeIndexJobRepository {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeIndexJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_index_job (
                    id          BIGSERIAL PRIMARY KEY,
                    trigger_by  VARCHAR(16) NOT NULL,
                    force       BOOLEAN NOT NULL DEFAULT FALSE,
                    status      VARCHAR(16) NOT NULL,
                    total       INT NOT NULL DEFAULT 0,
                    indexed     INT NOT NULL DEFAULT 0,
                    skipped     INT NOT NULL DEFAULT 0,
                    failed      INT NOT NULL DEFAULT 0,
                    removed     INT NOT NULL DEFAULT 0,
                    error       TEXT,
                    started_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                    finished_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_job_running
                ON knowledge_index_job ((status)) WHERE status = 'RUNNING'
                """);
    }

    /**
     * 抢占执行权。返回空表示已有任务在跑（或刚被别的实例抢到）。
     * <p>先清理超时的 RUNNING：进程被 kill 时没有机会回写终态，否则会永久阻塞后续索引。
     */
    public Optional<Long> start(String trigger, boolean force, int timeoutMinutes) {
        jdbcTemplate.update("""
                        UPDATE knowledge_index_job
                        SET status = ?, error = '任务超时，进程可能已中断', finished_at = NOW()
                        WHERE status = 'RUNNING' AND started_at < NOW() - make_interval(mins => ?)
                        """,
                STATUS_FAILED, timeoutMinutes);
        try {
            Long id = jdbcTemplate.queryForObject("""
                            INSERT INTO knowledge_index_job (trigger_by, force, status)
                            VALUES (?, ?, 'RUNNING') RETURNING id
                            """,
                    Long.class, trigger, force);
            return Optional.ofNullable(id);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * 回写终态。全部文档都失败时标 FAILED——否则任务看着"成功"、检索却全空，
     * 运维会误判成检索质量问题（实测硅基流动余额不足时正是这种表现）。
     */
    public void finish(long id, KnowledgeIndexService.IndexResult result) {
        boolean allFailed = result.total() > 0 && result.indexed() == 0 && result.failed() > 0;
        String error = allFailed
                ? "全部 " + result.failed() + " 份文档索引失败，请检查 embedding 服务与日志"
                : null;
        jdbcTemplate.update("""
                        UPDATE knowledge_index_job
                        SET status = ?, total = ?, indexed = ?, skipped = ?, failed = ?, removed = ?,
                            error = ?, finished_at = NOW()
                        WHERE id = ?
                        """,
                allFailed ? STATUS_FAILED : STATUS_SUCCEEDED, result.total(), result.indexed(),
                result.skipped(), result.failed(), result.removed(), error, id);
    }

    public void fail(long id, String error) {
        String message = error == null ? "unknown" : error.length() > 2000 ? error.substring(0, 2000) : error;
        jdbcTemplate.update("""
                        UPDATE knowledge_index_job SET status = ?, error = ?, finished_at = NOW()
                        WHERE id = ?
                        """,
                STATUS_FAILED, message, id);
    }

    public Optional<Job> find(long id) {
        List<Job> rows = jdbcTemplate.query("""
                        SELECT id, trigger_by, force, status, total, indexed, skipped, failed, removed,
                               error, started_at, finished_at
                        FROM knowledge_index_job WHERE id = ?
                        """,
                this::mapRow, id);
        return rows.stream().findFirst();
    }

    public List<Job> recent(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, trigger_by, force, status, total, indexed, skipped, failed, removed,
                               error, started_at, finished_at
                        FROM knowledge_index_job ORDER BY id DESC LIMIT ?
                        """,
                this::mapRow, Math.max(1, Math.min(100, limit)));
    }

    private Job mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Job(
                rs.getLong("id"),
                rs.getString("trigger_by"),
                rs.getBoolean("force"),
                rs.getString("status"),
                rs.getInt("total"),
                rs.getInt("indexed"),
                rs.getInt("skipped"),
                rs.getInt("failed"),
                rs.getInt("removed"),
                rs.getString("error"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                finished == null ? null : finished.toLocalDateTime());
    }

    public record Job(long id, String triggerBy, boolean force, String status, int total,
            int indexed, int skipped, int failed, int removed, String error,
            LocalDateTime startedAt, LocalDateTime finishedAt) {
    }
}
