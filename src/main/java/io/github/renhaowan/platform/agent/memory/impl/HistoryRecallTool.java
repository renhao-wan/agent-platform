package io.github.renhaowan.platform.agent.memory.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.memory.entity.Checkpoint;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.mapper.CheckpointMapper;
import io.github.renhaowan.platform.agent.memory.mapper.ChatMessageMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/**
 * 平台自有内部工具：会话记忆调回（recallHistory）——热/温/冷三层记忆的"冷层"取回通道（ADR-005）。
 * <p>
 * 上下文裁剪（ADR-003）把早期对话压成确定性摘要，具体结论不可还原；业务事实可经业务工具重查，
 * 但"只存在于对话中"的信息（口头偏好、临时约束）会随裁剪丢失。本工具让模型自主判断何时
 * 按关键词取回早期原文（chat_message 全量落库 + checkpoint 里程碑），替代引入向量库做 RAG——
 * 检索成本从"每条消息一次嵌入"降为"缺了才查一次"。
 * <p>
 * 定位约束：领域中立（只查对话历史，无任何业务语义）；角色无关（只查当前 sessionKey
 * 的数据，天然无越权面）；平台自有（不进工具层、不参与业务工具路由/确认/鉴权，
 * 由 AgentRunner 本地执行）。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class HistoryRecallTool {

    public static final String TOOL_NAME = "recallHistory";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final int SNIPPET_CHARS = 200;

    static final String DESCRIPTION =
            "按关键词检索当前会话的历史记录（含早期被压缩的对话、工具返回与里程碑摘要）。"
                    + "当用户提到\"之前/上次/刚才\"等早前信息而当前上下文中没有，"
                    + "或需要核实早前对话中的具体结论（房间号、编号、时间等）时调用；仅限当前会话。";

    static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"keyword":{"type":"string","description":"检索关键词，用具体名词（房间名、标题、日期、编号等），不要传整句"},"limit":{"type":"integer","description":"最多返回条数，默认 8，最大 20"}},"required":["keyword"]}""";

    static final String USAGE_HINT =
            "recallHistory 参数无效：请传 JSON 对象 {\"keyword\":\"具体关键词\",\"limit\":8}，keyword 必填且不能为空。";

    private final ChatMessageMapper chatMessageMapper;
    private final CheckpointMapper checkpointMapper;

    /** 执行分派用：是否本内部工具。 */
    public boolean handles(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    /** schema 广告位：进 Planner 工具清单供模型发现；执行不经此路径（AgentRunner 本地分派）。 */
    public ToolCallback callback() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(TOOL_NAME).description(DESCRIPTION)
                        .inputSchema(INPUT_SCHEMA).build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("recallHistory 由 ReAct 循环本地执行，不经 ToolCallback.call()");
            }
        };
    }

    /** 按关键词检索当前会话的消息与里程碑，按时间正序合并返回；失败降级为提示文本，不抛异常。 */
    public String execute(String sessionKey, String argumentsJson) {
        String keyword;
        int limit;
        try {
            JsonNode args = MAPPER.readTree(argumentsJson == null ? "" : argumentsJson);
            keyword = args.path("keyword").asText("");
            limit = args.path("limit").asInt(DEFAULT_LIMIT);
        } catch (Exception e) {
            return USAGE_HINT;
        }
        if (keyword.isBlank()) {
            return USAGE_HINT;
        }
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        try {
            List<Hit> hits = new ArrayList<>();
            for (ChatMessage m : chatMessageMapper.selectList(new QueryWrapper<ChatMessage>()
                    .eq("session_key", sessionKey).like("content", keyword)
                    .orderByAsc("id").last("LIMIT " + limit))) {
                String label = "[消息 #" + m.getId() + "][" + m.getRole()
                        + (m.getToolName() == null ? "" : " → " + m.getToolName()) + "]";
                hits.add(new Hit(m.getCreatedAt(), label + " " + snippet(m.getContent())));
            }
            for (Checkpoint c : checkpointMapper.selectList(new QueryWrapper<Checkpoint>()
                    .eq("session_key", sessionKey).like("summary", keyword)
                    .orderByAsc("id").last("LIMIT " + limit))) {
                hits.add(new Hit(c.getCreatedAt(),
                        "[里程碑 #" + c.getId() + "][" + TIME_FMT.format(c.getCreatedAt()) + "] " + snippet(c.getSummary())));
            }
            if (hits.isEmpty()) {
                return "未在当前会话历史中检索到与「" + keyword + "」相关的记录，可换关键词重试或向用户确认。";
            }
            hits.sort(Comparator.comparing(Hit::at,
                    Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())));
            StringBuilder out = new StringBuilder("当前会话历史检索结果（关键词「")
                    .append(keyword).append("」，按时间排序）：\n");
            int shown = Math.min(limit, hits.size());
            for (int i = 0; i < shown; i++) {
                out.append(hits.get(i).text()).append('\n');
            }
            if (hits.size() > shown) {
                out.append("（另有 ").append(hits.size() - shown).append(" 条未展示，可换更具体的关键词）");
            }
            return out.toString().strip();
        } catch (Exception e) {
            log.warn("recallHistory failed, session {}: {}", sessionKey, e.getMessage());
            return "历史检索暂不可用，请直接向用户确认相关信息。";
        }
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }

    /** 单条命中：时间用于跨表合并排序。 */
    private record Hit(LocalDateTime at, String text) {
    }
}
