package io.github.renhaowan.platform.knowledge;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 知识库健康检查：Postgres 可达即 UP。只在知识库启用时装配（配置类条件已保证）。
 * <p>向量库挂了不影响平台其余功能，但检索会全部失败——让 /actuator/health 直接暴露这一点。
 */
public class KnowledgeHealthIndicator implements HealthIndicator {

    private final KnowledgeJdbc knowledgeJdbc;

    public KnowledgeHealthIndicator(KnowledgeJdbc knowledgeJdbc) {
        this.knowledgeJdbc = knowledgeJdbc;
    }

    @Override
    public Health health() {
        try {
            knowledgeJdbc.jdbcTemplate().queryForObject("SELECT 1", Integer.class);
            return Health.up().withDetail("vectorStore", "postgres+pgvector").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
