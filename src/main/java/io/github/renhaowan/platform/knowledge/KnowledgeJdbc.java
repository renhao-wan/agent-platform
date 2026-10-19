package io.github.renhaowan.platform.knowledge;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库专用连接（Postgres）。
 * <p>不作为 {@code DataSource} bean 暴露：业务数据在 MySQL、知识向量在 Postgres，
 * 把第二个 DataSource 注册进容器会让 MyBatis 的 {@code @ConditionalOnSingleCandidate} 失去唯一候选，
 * 反而威胁现有数据源装配。这里用私有包装持有连接池，容器销毁时关闭。
 */
public class KnowledgeJdbc implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeJdbc(KnowledgeProperties properties) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(properties.getVectorStoreUrl());
        ds.setUsername(properties.getVectorStoreUsername());
        ds.setPassword(properties.getVectorStorePassword());
        ds.setPoolName("knowledge-pg");
        ds.setMaximumPoolSize(4);
        ds.setConnectionTimeout(3000);
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
