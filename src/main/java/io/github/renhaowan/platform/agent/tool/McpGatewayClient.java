package io.github.renhaowan.platform.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.gateway.message.McpProtocol;

/**
 * 以 MCP 客户端身份访问平台网关（Streamable HTTP 自环）。
 * 会话惰性建立（initialize 响应头 Mcp-Session-Id）；
 * 遇到无效会话（HTTP 400 / -32602）自动重初始化并重试一次。
 * <p>每次 tools/list 与 tools/call 请求携带 {@code X-User-Id}/{@code X-User-Role} 两个头
 * （取自当前会话用户）——网关据此做工具可见性过滤与执行点鉴权，身份必须逐请求传递，
 * 不能以无状态单例 header 固化。
 */
public interface McpGatewayClient {

    /** 兼容保留：请优先引用 McpProtocol.HEADER_SESSION_ID */
    String SESSION_HEADER = McpProtocol.HEADER_SESSION_ID;

    /**
     * 惰性建立 MCP 会话（首次用到才握手，之后复用）。
     * <p>MCP 握手 = 发 initialize 请求（带协议版本/能力声明），网关校验通过后
     * 在响应头 Mcp-Session-Id 返回会话凭证——类似 HTTP 会话 cookie 的获取动作。
     */
    void ensureSession();

    /**
     * tools/list：拉取当前用户可用的工具清单（名称/描述/参数 schema，已按角色过滤）。
     * 供 DynamicToolRegistry 转成 Spring AI ToolCallback——模型靠这份 schema 知道"有什么工具、怎么传参"。
     * 会话过期（网关返回 400）时自动重握手并重试一次。
     */
    JsonNode listTools(UserPrincipal user);

    /**
     * tools/call：执行工具并返回文本结果；失败也返回"含错误说明的文本"而非抛异常——
     * 让模型看到错误后自行决策下一步（重试/换路/放弃），这是 Agent 容错的关键设计。
     * 会话过期时自动重握手并重试一次。
     */
    String callTool(String name, String argumentsJson, UserPrincipal user);

    void reset();
}
