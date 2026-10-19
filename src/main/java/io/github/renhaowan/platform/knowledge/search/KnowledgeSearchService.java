package io.github.renhaowan.platform.knowledge.search;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 知识检索：query 向量化 + SQL 相似度检索 + 角色过滤。
 * <p>过滤条件写进检索表达式而不是"查出来再筛"——无权内容从不进入候选集。
 */
public class KnowledgeSearchService {

    private static final int MAX_QUERY_CHARS = 500;

    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    public KnowledgeSearchService(VectorStore vectorStore, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<SearchHit> search(String query, int requestedTopK, String role) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() > MAX_QUERY_CHARS) {
            normalized = normalized.substring(0, MAX_QUERY_CHARS);
        }
        int topK = Math.max(1, Math.min(properties.getTopKMax(), requestedTopK));

        SearchRequest request = SearchRequest.builder()
                .query(normalized)
                .topK(topK)
                .similarityThreshold(properties.getScoreThreshold())
                .filterExpression(roleFilter(role))
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        List<SearchHit> hits = new ArrayList<>(documents == null ? 0 : documents.size());
        if (documents == null) {
            return hits;
        }
        for (Document document : documents) {
            var metadata = document.getMetadata();
            hits.add(new SearchHit(
                    stringValue(metadata.get("doc_title"), "未知文档"),
                    stringValue(metadata.get("section"), ""),
                    stringValue(metadata.get("raw_text"), document.getText()),
                    similarity(metadata.get("distance"))));
        }
        return hits;
    }

    /** ADMIN 可读全部；其余角色只读 PUBLIC。缺省可见性必须是 PUBLIC（见 docs/KNOWLEDGE-RAG.md §8）。 */
    private String roleFilter(String role) {
        return "ADMIN".equals(role)
                ? "visible_role == 'PUBLIC' || visible_role == 'ADMIN'"
                : "visible_role == 'PUBLIC'";
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static double similarity(Object distance) {
        if (distance instanceof Number number) {
            double score = 1.0 - number.doubleValue();
            return Math.max(0.0, Math.min(1.0, score));
        }
        return Double.NaN;
    }

    public record SearchHit(String title, String section, String text, double score) {
    }
}
