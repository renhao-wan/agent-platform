package io.github.renhaowan.platform.knowledge.index;

import java.time.LocalDateTime;

/** knowledge_doc 的一行：用于判断文档是否需要重新索引。 */
public record KnowledgeDoc(
        String docPath,
        String contentHash,
        String chunkConfig,
        int chunkCount,
        String embeddingModel,
        LocalDateTime indexedAt,
        String status) {

    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";
}
