package io.github.renhaowan.platform.gateway.registry.impl;

import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/** 契约指纹：只包含由 OpenAPI 决定、同步时可能变化的字段，服务凭证等部署配置不计入。 */
public final class ContractHashes {

    private ContractHashes() {
    }

    public static String of(ToolDefinition tool) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, tool.getName());
        append(canonical, tool.getHttpMethod());
        append(canonical, tool.getUrlTemplate());
        append(canonical, tool.getDescription());
        append(canonical, tool.getInputSchema());
        append(canonical, String.valueOf(tool.getRequireConfirm()));
        append(canonical, tool.getRequiredRole());
        append(canonical, tool.getRequiredPermission());
        return sha256(canonical.toString());
    }

    /** 整份契约的指纹：逐个工具指纹排序后再哈希，工具顺序变化不算变更。 */
    public static String ofAll(Collection<ToolDefinition> tools) {
        List<String> perTool = tools.stream().map(ContractHashes::of).sorted().toList();
        return sha256(String.join(",", perTool));
    }

    /** 带长度前缀，避免不同字段拼接后产生相同输入。 */
    private static void append(StringBuilder canonical, String value) {
        String text = value == null ? "" : value;
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
