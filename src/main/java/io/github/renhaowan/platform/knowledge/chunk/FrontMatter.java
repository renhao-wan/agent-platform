package io.github.renhaowan.platform.knowledge.chunk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markdown front-matter（文档顶部的 {@code ---} 头部）。
 * 只支持 {@code key: value} 单行形式——知识文档是人工整理的，不值得为此引入完整 YAML 解析。
 */
public record FrontMatter(Map<String, String> fields, String body) {

    public static final String DEFAULT_VISIBLE_ROLE = "PUBLIC";

    public static FrontMatter parse(String markdown) {
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            return new FrontMatter(Map.of(), text);
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            return new FrontMatter(Map.of(), text);
        }
        int lineEnd = text.indexOf('\n', end + 1);
        String header = text.substring(4, end);
        String body = lineEnd < 0 ? "" : text.substring(lineEnd + 1);

        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            fields.put(trimmed.substring(0, colon).strip(), trimmed.substring(colon + 1).strip());
        }
        return new FrontMatter(fields, body);
    }

    public String title() {
        return fields.get("title");
    }

    public String visibleRole() {
        String role = fields.get("visible_role");
        return role == null || role.isBlank() ? DEFAULT_VISIBLE_ROLE : role.strip().toUpperCase();
    }
}
