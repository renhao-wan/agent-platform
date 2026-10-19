package io.github.renhaowan.platform.knowledge;

import io.github.renhaowan.platform.common.ApiResponse;
import io.github.renhaowan.platform.knowledge.index.KnowledgeIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理端：手动重建索引。{@code /admin/**} 已由 JwtAuthFilter 强制 ADMIN。
 * <p>用 ObjectProvider 而不是直接注入：知识库未启用时该 bean 不存在，控制器仍要能启动。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final ObjectProvider<KnowledgeIndexService> indexService;

    @PostMapping("/admin/knowledge/reindex")
    public ApiResponse<?> reindex(@RequestParam(defaultValue = "false") boolean force) {
        KnowledgeIndexService service = indexService.getIfAvailable();
        if (service == null) {
            return ApiResponse.error(503, "知识库未启用（EMBEDDING_API_KEY 为空）");
        }
        return ApiResponse.ok(service.index(force));
    }
}
