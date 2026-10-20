package io.github.renhaowan.platform.knowledge.search;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.index.KnowledgeDocRepository;
import io.github.renhaowan.platform.knowledge.index.KnowledgeDocRepository.ChunkRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 知识检索：query 向量化 + SQL 相似度检索 + 角色过滤。
 * <p>过滤条件写进检索表达式而不是"查出来再筛"——无权内容从不进入候选集。
 * <p>向量结果为空时按关键词回查 chunk 表：制度编号、专有名词这类查询向量不敏感，
 * 但精确子串能命中。兜底结果不伪造相关度（score 为 NaN）。
 */
@Slf4j
public class KnowledgeSearchService {

    private static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_FALLBACK_TOKEN_CHARS = 64;
    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\\s，。、；：,.;:!?！？（）()【】\\[\\]\"']+");

    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;
    private final KnowledgeDocRepository docRepository;
    private final KnowledgeSearchAuditRepository auditRepository;
    private final MeterRegistry meterRegistry;

    public KnowledgeSearchService(VectorStore vectorStore, KnowledgeProperties properties,
            KnowledgeDocRepository docRepository, KnowledgeSearchAuditRepository auditRepository,
            MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.docRepository = docRepository;
        this.auditRepository = auditRepository;
        this.meterRegistry = meterRegistry;
    }

    public List<SearchHit> search(long userId, String query, int requestedTopK, String role) {
        return searchDetailed(userId, query, requestedTopK, role).hits();
    }

    /**
     * 与 {@link #search} 相同的检索路径，额外返回实际使用的模式与耗时，
     * 供管理端调试面板解释"为什么这次是空结果/关键词兜底"。
     */
    public SearchOutcome searchDetailed(long userId, String query, int requestedTopK, String role) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) {
            return new SearchOutcome(List.of(), "empty", 0);
        }
        if (normalized.length() > MAX_QUERY_CHARS) {
            normalized = normalized.substring(0, MAX_QUERY_CHARS);
        }
        int topK = Math.max(1, Math.min(properties.getTopKMax(), requestedTopK));

        long started = System.nanoTime();
        List<SearchHit> hits = new ArrayList<>();
        String mode = "vector";
        try {
            hits.addAll(vectorSearch(normalized, topK, role));
            if (hits.isEmpty()) {
                mode = "empty";
                if (properties.isKeywordFallbackEnabled()) {
                    hits.addAll(keywordSearch(normalized, topK, role));
                    if (!hits.isEmpty()) {
                        mode = "keyword";
                    }
                }
            }
            return new SearchOutcome(List.copyOf(hits), mode, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            mode = "error";
            throw e;
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            recordObservability(userId, normalized, topK, role, hits, mode, elapsedMs);
        }
    }

    private List<SearchHit> vectorSearch(String query, int topK, String role) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.getScoreThreshold());
        String filter = roleFilter(role);
        if (filter != null) {
            builder.filterExpression(filter);
        }

        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>(documents.size());
        for (Document document : documents) {
            var metadata = document.getMetadata();
            hits.add(new SearchHit(
                    stringValue(metadata.get("doc_title"), "未知文档"),
                    stringValue(metadata.get("section"), ""),
                    stringValue(metadata.get("raw_text"), document.getText()),
                    score(document)));
        }
        return hits;
    }

    /** 先按整串查，未命中再退到最长词元；避免把整段自然语言当 LIKE 模式。 */
    private List<SearchHit> keywordSearch(String query, int topK, String role) {
        List<ChunkRow> rows = docRepository.searchByKeyword(likePattern(query), role, topK);
        if (rows.isEmpty()) {
            String token = longestToken(query);
            if (!token.isEmpty() && !token.equals(query)) {
                rows = docRepository.searchByKeyword(likePattern(token), role, topK);
            }
        }
        return rows.stream()
                .map(row -> new SearchHit(row.docTitle(), row.section(), row.text(), Double.NaN))
                .toList();
    }

    /** ADMIN 可读全部；其余角色只读 PUBLIC 与自己的角色。缺省可见性必须是 PUBLIC。 */
    private String roleFilter(String role) {
        if ("ADMIN".equals(role)) {
            return null;
        }
        String current = role == null || role.isBlank()
                ? "PUBLIC"
                : role.replace("'", "''");
        return "visible_role == 'PUBLIC' || visible_role == '" + current + "'";
    }

    private void recordObservability(long userId, String query, int topK, String role,
            List<SearchHit> hits, String mode, long elapsedMs) {
        String roleTag = role == null || role.isBlank() ? "anonymous" : role;
        List<String> sources = hits.stream()
                .map(hit -> hit.title() + " > " + hit.section()
                        + (Double.isNaN(hit.score()) ? "" : String.format("/%.3f", hit.score())))
                .toList();

        // 审计与指标都是附属信息，失败不能影响检索结果
        try {
            auditRepository.record(userId, role, query, topK, hits.size(), sources, elapsedMs);
        } catch (Exception e) {
            log.warn("检索审计写入失败：{}", e.getMessage());
        }
        if (meterRegistry != null) {
            meterRegistry.counter("knowledge.search.requests", "role", roleTag, "mode", mode).increment();
            meterRegistry.timer("knowledge.search.duration", "mode", mode)
                    .record(Duration.ofMillis(elapsedMs));
        }
        log.info("知识检索 query=\"{}\" role={} topK={} mode={} hits={} elapsed={}ms sources={}",
                query, role, topK, mode, hits.size(), elapsedMs, sources);
    }

    private static String likePattern(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String longestToken(String query) {
        String best = "";
        for (String token : TOKEN_SPLIT.split(query)) {
            String candidate = token.strip();
            if (candidate.length() >= 2 && candidate.length() <= MAX_FALLBACK_TOKEN_CHARS
                    && candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    /** PgVectorStore 已把距离换算成相似度写进 Document.score（1 - cosine distance）。 */
    private static double score(Document document) {
        Double score = document.getScore();
        if (score == null || score.isNaN()) {
            return Double.NaN;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    public record SearchHit(String title, String section, String text, double score) {
    }

    /** hits + 实际检索模式（vector/keyword/empty）+ 耗时，调试面板的返回形状。 */
    public record SearchOutcome(List<SearchHit> hits, String mode, long elapsedMs) {
    }
}
