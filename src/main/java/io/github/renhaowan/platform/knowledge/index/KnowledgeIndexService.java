package io.github.renhaowan.platform.knowledge.index;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.chunk.FrontMatter;
import io.github.renhaowan.platform.knowledge.chunk.KnowledgeChunk;
import io.github.renhaowan.platform.knowledge.chunk.MarkdownChunker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 知识库索引：扫描目录 → content hash 比对 → 只重索引变更文档。
 * <p>启动路径上零 embedding 调用：hash 未变的文档直接跳过；首次部署才付一次全量成本。
 * 索引跑在独立线程池，不阻塞 Web 启动。单文档失败只标 FAILED，不影响其余文档。
 */
@Slf4j
public class KnowledgeIndexService {

    private final KnowledgeProperties properties;
    private final KnowledgeDocRepository repository;
    private final VectorStore vectorStore;
    private final MarkdownChunker chunker;
    private final ExecutorService executor;

    public KnowledgeIndexService(KnowledgeProperties properties, KnowledgeDocRepository repository,
            VectorStore vectorStore, MarkdownChunker chunker, ExecutorService executor) {
        this.properties = properties;
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.executor = executor;
    }

    public void indexAsync() {
        executor.submit(() -> {
            try {
                IndexResult result = index(false);
                log.info("知识库索引完成：{}", result);
            } catch (Exception e) {
                log.warn("知识库索引失败：{}", e.getMessage());
            }
        });
    }

    /** 启动与手动重建共用。force=true 时忽略 hash，全部重新切块与向量化。 */
    public synchronized IndexResult index(boolean force) {
        Path root = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.info("知识库目录不存在，跳过索引：{}", root);
            return new IndexResult(0, 0, 0, 0, 0);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("知识库目录扫描失败 {}：{}", root, e.getMessage());
            return new IndexResult(0, 0, 0, 0, 0);
        }

        Set<String> presentPaths = new HashSet<>();
        int indexed = 0;
        int skipped = 0;
        int failed = 0;

        for (Path file : files) {
            String docPath = root.relativize(file).toString().replace('\\', '/');
            presentPaths.add(docPath);
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String hash = sha256(content);
                Optional<KnowledgeDoc> existing = repository.find(docPath);
                if (!force && existing.isPresent()
                        && hash.equals(existing.get().contentHash())
                        && properties.getEmbeddingModel().equals(existing.get().embeddingModel())) {
                    skipped++;
                    continue;
                }
                indexOne(docPath, content, hash);
                indexed++;
            } catch (Exception e) {
                failed++;
                log.warn("知识文档索引失败 {}：{}", docPath, e.getMessage());
                markFailedQuietly(docPath);
            }
        }

        int removed = 0;
        for (String known : repository.allPaths()) {
            if (!presentPaths.contains(known)) {
                try {
                    removeOne(known);
                    removed++;
                } catch (Exception e) {
                    log.warn("知识文档清理失败 {}：{}", known, e.getMessage());
                }
            }
        }
        return new IndexResult(files.size(), indexed, skipped, failed, removed);
    }

    private void indexOne(String docPath, String content, String hash) {
        FrontMatter frontMatter = FrontMatter.parse(content);
        String docTitle = firstNonBlank(frontMatter.title(),
                MarkdownChunker.firstH1(frontMatter.body()), fileNameWithoutSuffix(docPath));
        String visibleRole = frontMatter.visibleRole();
        List<KnowledgeChunk> chunks = chunker.chunk(frontMatter.body());

        // 先精确删掉上一篇的块，再写新块：同一 docPath 的旧内容不会与新内容并存
        List<String> oldIds = repository.chunkIds(docPath);
        if (!oldIds.isEmpty()) {
            vectorStore.delete(oldIds);
        }

        List<Document> documents = new ArrayList<>(chunks.size());
        List<KnowledgeDocRepository.ChunkRef> refs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            String id = UUID.nameUUIDFromBytes(
                    (docPath + "#" + i).getBytes(StandardCharsets.UTF_8)).toString();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("doc_path", docPath);
            metadata.put("doc_title", docTitle);
            metadata.put("section", chunk.section());
            metadata.put("visible_role", visibleRole);
            metadata.put("chunk_index", i);
            metadata.put("embedding_model", properties.getEmbeddingModel());
            // text 带前缀参与向量化；raw_text 保留正文，返回给模型时不重复来源标题
            metadata.put("raw_text", chunk.text());

            documents.add(Document.builder()
                    .id(id)
                    .text(chunk.prefixed(docTitle))
                    .metadata(metadata)
                    .build());
            refs.add(new KnowledgeDocRepository.ChunkRef(id, chunk.section(), i));
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
        repository.replaceChunks(docPath, refs);
        repository.upsertDoc(new KnowledgeDoc(docPath, hash, documents.size(),
                properties.getEmbeddingModel(), LocalDateTime.now(), KnowledgeDoc.STATUS_INDEXED));
        log.info("知识文档已索引 {}（{} 块）", docPath, documents.size());
    }

    private void removeOne(String docPath) {
        List<String> ids = repository.chunkIds(docPath);
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
        repository.deleteDoc(docPath);
        log.info("知识文档已移除 {}", docPath);
    }

    private void markFailedQuietly(String docPath) {
        try {
            repository.markFailed(docPath);
        } catch (Exception e) {
            log.warn("知识库状态写入失败 {}：{}", docPath, e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static String fileNameWithoutSuffix(String docPath) {
        String name = Path.of(docPath).getFileName().toString();
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record IndexResult(int total, int indexed, int skipped, int failed, int removed) {
    }
}
