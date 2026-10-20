package io.github.renhaowan.platform.knowledge.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService.SearchHit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * 检索质量评估：拿固定题库跑一遍检索，算 Recall@K、MRR 与空结果率。
 * <p>没有评估集时，"改了切块参数/阈值是否更好"只能靠感觉。题库是 jsonl，
 * 每行 {@code {"question":"...","expected":["文档标题 > 章节"],"permissions":["*"]}}，
 * permissions 可选（缺省空集合，只检索 public 块）。
 */
@Slf4j
public class KnowledgeEvalService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeSearchService searchService;
    private final KnowledgeProperties properties;

    public KnowledgeEvalService(KnowledgeSearchService searchService, KnowledgeProperties properties) {
        this.searchService = searchService;
        this.properties = properties;
    }

    public EvalReport evaluate(int requestedTopK) {
        Path path = Path.of(properties.getEvalFile()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("评估集不存在：" + path);
        }
        int topK = Math.max(1, Math.min(properties.getTopKMax(), requestedTopK));
        List<EvalCase> cases = load(path);
        if (cases.isEmpty()) {
            throw new IllegalStateException("评估集为空：" + path);
        }

        List<EvalDetail> details = new ArrayList<>(cases.size());
        int matched = 0;
        int emptyResults = 0;
        double reciprocalRankSum = 0;
        for (EvalCase evalCase : cases) {
            List<SearchHit> hits;
            try {
                hits = searchService.search(0L, evalCase.question(), topK,
                        evalCase.permissions(), "eval");
            } catch (RuntimeException e) {
                // 单题检索失败按未命中计：评估要覆盖全部题，不因一题中断
                log.warn("评估检索失败 question=\"{}\"：{}", evalCase.question(), e.getMessage());
                hits = List.of();
            }

            List<String> sources = hits.stream().map(KnowledgeEvalService::sourceOf).toList();
            int rank = 0;
            String matchedSource = null;
            for (int i = 0; i < hits.size(); i++) {
                if (matchesAny(evalCase.expected(), hits.get(i))) {
                    rank = i + 1;
                    matchedSource = sources.get(i);
                    break;
                }
            }
            if (rank > 0) {
                matched++;
                reciprocalRankSum += 1.0 / rank;
            }
            if (hits.isEmpty()) {
                emptyResults++;
            }
            details.add(new EvalDetail(evalCase.question(), rank, matchedSource, sources));
        }

        int total = cases.size();
        return new EvalReport(total, matched, (double) matched / total,
                reciprocalRankSum / total, (double) emptyResults / total, topK, details);
    }

    private List<EvalCase> load(Path path) {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(this::parse)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("评估集读取失败：" + e.getMessage(), e);
        }
    }

    private EvalCase parse(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            String question = node.path("question").asText("");
            if (question.isBlank()) {
                throw new IllegalArgumentException("question 为空");
            }
            List<String> expected = new ArrayList<>();
            node.path("expected").forEach(item -> expected.add(item.asText("")));
            expected.removeIf(String::isBlank);
            if (expected.isEmpty()) {
                throw new IllegalArgumentException("expected 为空");
            }
            Set<String> permissions = new LinkedHashSet<>();
            node.path("permissions").forEach(item -> {
                String value = item.asText("");
                if (!value.isBlank()) {
                    permissions.add(value);
                }
            });
            return new EvalCase(question, expected, permissions);
        } catch (Exception e) {
            throw new IllegalStateException("评估集行格式错误：" + line, e);
        }
    }

    private static boolean matchesAny(List<String> expected, SearchHit hit) {
        for (String value : expected) {
            String target = normalize(value);
            String title = normalize(hit.title());
            String section = normalize(hit.section());
            String full = title + ">" + section;
            if (full.equals(target) || full.startsWith(target)
                    || title.equals(target)
                    || (!section.isEmpty() && section.equals(target))) {
                return true;
            }
        }
        return false;
    }

    private static String sourceOf(SearchHit hit) {
        return hit.section() == null || hit.section().isBlank()
                ? hit.title()
                : hit.title() + " > " + hit.section();
    }

    /** 题库里的大小写、空格、全半角差异不应造成假阴性。 */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    public record EvalCase(String question, List<String> expected, Set<String> permissions) {
    }

    public record EvalDetail(String question, int rank, String matchedSource, List<String> sources) {
    }

    public record EvalReport(int total, int matched, double recallAtK, double mrr,
            double emptyRate, int topK, List<EvalDetail> details) {
    }
}
