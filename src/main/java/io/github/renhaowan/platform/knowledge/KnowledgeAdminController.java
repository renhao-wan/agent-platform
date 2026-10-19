package io.github.renhaowan.platform.knowledge;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.knowledge.embedding.EmbeddingUsageRepository;
import io.github.renhaowan.platform.knowledge.eval.KnowledgeEvalService;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchAuditRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理端。{@code /admin/**} 已由 JwtAuthFilter 强制 ADMIN。
 * <p>用 ObjectProvider 而不是直接注入：知识库未启用时这些 bean 不存在，控制器仍要能启动。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final ObjectProvider<KnowledgeIndexService> indexService;
    private final ObjectProvider<KnowledgeSearchAuditRepository> auditRepository;
    private final ObjectProvider<EmbeddingUsageRepository> usageRepository;
    private final ObjectProvider<KnowledgeEvalService> evalService;

    /** 异步提交：立即返回 jobId，进度用 /jobs 查询，避免管理端请求被长索引拖住。 */
    @PostMapping("/admin/knowledge/reindex")
    public ApiResponse<?> reindex(@RequestParam(defaultValue = "false") boolean force) {
        KnowledgeIndexService service = indexService.getIfAvailable();
        if (service == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        return service.submitIndex("MANUAL", force)
                .<ApiResponse<?>>map(jobId -> ApiResponse.ok(Map.of("jobId", jobId)))
                .orElseGet(() -> ApiResponse.error(409,
                        "已有知识库索引任务在运行，请稍后通过 /admin/knowledge/jobs 查询"));
    }

    @GetMapping("/admin/knowledge/jobs")
    public ApiResponse<?> jobs(@RequestParam(defaultValue = "20") int limit) {
        KnowledgeIndexService service = indexService.getIfAvailable();
        if (service == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        return ApiResponse.ok(service.jobs(limit));
    }

    @GetMapping("/admin/knowledge/jobs/{id}")
    public ApiResponse<?> job(@PathVariable long id) {
        KnowledgeIndexService service = indexService.getIfAvailable();
        if (service == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        return service.job(id)
                .<ApiResponse<?>>map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error(404, "索引任务不存在"));
    }

    @GetMapping("/admin/knowledge/audit")
    public ApiResponse<?> audit(@RequestParam(defaultValue = "50") int limit) {
        KnowledgeSearchAuditRepository repository = auditRepository.getIfAvailable();
        if (repository == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        return ApiResponse.ok(repository.recent(limit));
    }

    @GetMapping("/admin/knowledge/usage")
    public ApiResponse<?> usage() {
        EmbeddingUsageRepository repository = usageRepository.getIfAvailable();
        if (repository == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        return ApiResponse.ok(repository.summary());
    }

    /** 跑固定题库，返回 Recall@K / MRR / 空结果率，用于比较切块与阈值调整的效果。 */
    @PostMapping("/admin/knowledge/eval")
    public ApiResponse<?> eval(@RequestParam(defaultValue = "4") int topK) {
        KnowledgeEvalService service = evalService.getIfAvailable();
        if (service == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        try {
            return ApiResponse.ok(service.evaluate(topK));
        } catch (IllegalStateException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
