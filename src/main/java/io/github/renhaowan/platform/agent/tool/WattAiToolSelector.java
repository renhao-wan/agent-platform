package io.github.renhaowan.platform.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 行为契约：工具数 < minTools 直接透传；置信度低于阈值取概率 top-k；
 * 任何异常 fail-open 返回全量（路由层故障不影响对话主流程）。
 */
public class WattAiToolSelector implements ToolSelector {

    private static final Logger log = LoggerFactory.getLogger(WattAiToolSelector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROUTE_QUESTION =
            "Which tool should be invoked to handle the user request?";

    private final RestClient restClient;
    private final String baseUrl;
    private final int minTools;
    private final double confidenceThreshold;
    private final int topK;

    public WattAiToolSelector(String baseUrl, String bearerKey,
                              int minTools, double confidenceThreshold, int topK) {
        this.baseUrl = baseUrl;
        this.minTools = minTools;
        this.confidenceThreshold = confidenceThreshold;
        this.topK = topK;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (bearerKey != null && !bearerKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + bearerKey);
        }
        this.restClient = builder.build();
    }

    @Override
    public List<ToolCallback> select(String userText, List<ToolCallback> allTools) {
        if (allTools.size() < minTools) {
            return allTools;
        }
        try {
            Decision decision = callDecisionModel(userText, allTools);
            if (decision.confidence() < confidenceThreshold) {
                log.warn("wattai low confidence {}, fallback to top-{}", decision.confidence(), topK);
                return topKByProbability(allTools, decision);
            }
            return allTools.stream()
                    .filter(t -> t.getToolDefinition().name().equals(decision.argmax()))
                    .toList();
        } catch (Exception e) {
            log.warn("wattai routing failed, fail-open: {}", e.getMessage());
            return allTools;
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
