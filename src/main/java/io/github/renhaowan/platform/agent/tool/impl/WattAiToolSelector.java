package io.github.renhaowan.platform.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import io.github.renhaowan.platform.agent.tool.ToolSelector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * wattai 决策模型路由器（System One 范式，Apache-2.0 开源，可自托管 GGUF）。
 * <p>
 * 实测结论（2026-09-30，托管端点 api.wattai.dev v0.5 preview）：
 * 延迟 ~2s（含跨境网络）、中文工具路由置信度持续 <0.4 —— 因此默认关闭，
 * 仅作为 ToolSelector 的一种实现保留；自托管 GGUF（205MB 量化版）可消除网络延迟。
 * <p>
 * 行为契约（责任链节点语义）：工具数 < minTools 弃权；置信度低于阈值降级为概率 top-k
 * （排序仍有价值，属接管而非弃权）；argmax 落在候选外时弃权（不能交出空集让主模型无工具可用）；
 * 任何异常弃权交下一环，由链尾兜底全量直通——路由层故障不影响对话主流程。
 */
@Slf4j
public class WattAiToolSelector implements ToolSelector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROUTE_QUESTION =
            "Which tool should be invoked to handle the user request?";

    private final RestClient restClient;
    private final boolean configured;
    /** 鉴权/配置类错误不可恢复：置位后不再发请求，避免每轮对话重复失败。 */
    private volatile boolean disabled;
    private final int minTools;
    private final double confidenceThreshold;
    private final int topK;

    public WattAiToolSelector(String baseUrl, String bearerKey,
                              int minTools, double confidenceThreshold, int topK,
                              int connectTimeoutMs, int readTimeoutMs) {
        this.minTools = minTools;
        this.confidenceThreshold = confidenceThreshold;
        this.topK = topK;
        boolean usable = bearerKey != null && !bearerKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
        this.configured = usable;
        if (!usable) {
            log.warn("wattai 路由未启用：api-key 或 base-url 为空");
            this.restClient = null;
            return;
        }
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + bearerKey)
                .build();
    }

    @Override
    public Optional<List<ToolCallback>> select(String userText, List<ToolCallback> allTools) {
        if (!configured || disabled || allTools.size() < minTools) {
            return Optional.empty();
        }
        try {
            Decision decision = callDecisionModel(userText, allTools);
            if (decision.confidence() < confidenceThreshold) {
                log.warn("wattai low confidence {}, degrade to top-{}", decision.confidence(), topK);
                return Optional.of(topKByProbability(allTools, decision));
            }
            List<ToolCallback> hit = allTools.stream()
                    .filter(t -> t.getToolDefinition().name().equals(decision.argmax()))
                    .toList();
            return hit.isEmpty() ? Optional.empty() : Optional.of(hit);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                // 限流是临时状态，下次仍值得尝试
                log.warn("wattai 限流（HTTP 429），本次弃权");
            } else if (status >= 400 && status < 500) {
                // 401/403 凭证错误、404 地址错误：重试不会成功，禁用节点
                disabled = true;
                log.error("wattai 请求被拒绝（HTTP {}），已停用该路由节点，"
                        + "请检查 platform.agent.wattai 的 api-key 与 base-url", status);
            } else {
                log.warn("wattai routing failed (HTTP {}), abstain to next handler", status);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("wattai routing failed, abstain to next handler: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private record Decision(String argmax, double confidence, Map<String, Double> probs) {
    }

    private Decision callDecisionModel(String userText, List<ToolCallback> allTools) throws Exception {
        Map<String, String> candidates = new LinkedHashMap<>();
        for (ToolCallback tool : allTools) {
            candidates.put(tool.getToolDefinition().name(), tool.getToolDefinition().description());
        }

        ObjectNode question = MAPPER.createObjectNode();
        question.put("q", ROUTE_QUESTION);
        ArrayNode options = question.putArray("options");
        candidates.keySet().forEach(options::add);
        question.put("kind", "choice");
        question.put("add_none", true);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("state", userText);
        root.set("questions", MAPPER.createArrayNode().add(question));

        String raw = restClient.post()
                .uri("/v1/systemone")
                .contentType(MediaType.APPLICATION_JSON)
                .body(root.toString())
                .retrieve()
                .body(String.class);

        JsonNode result = MAPPER.readTree(raw).path("results").path(0);
        JsonNode argmaxNode = result.path("argmax");
        if (argmaxNode.isMissingNode()) {
            throw new IllegalStateException("wattai response missing argmax");
        }
        Map<String, Double> probs = new LinkedHashMap<>();
        JsonNode optionNodes = result.path("options");
        JsonNode probNodes = result.path("probs");
        for (int i = 0; i < optionNodes.size(); i++) {
            probs.put(optionNodes.get(i).asText(), probNodes.path(i).asDouble());
        }
        return new Decision(argmaxNode.asText(), result.path("confidence").asDouble(), probs);
    }

    /** 低置信兜底：按模型给的概率排序，取前 topK 个真实存在的工具。 */
    private List<ToolCallback> topKByProbability(List<ToolCallback> allTools, Decision decision) {
        List<String> ranked = decision.probs().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        List<ToolCallback> picked = new ArrayList<>();
        for (String name : ranked) {
            allTools.stream()
                    .filter(t -> t.getToolDefinition().name().equals(name))
                    .findFirst()
                    .ifPresent(picked::add);
            if (picked.size() >= topK) {
                break;
            }
        }
        return picked.isEmpty() ? allTools : picked;
    }
}
