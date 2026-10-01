package io.github.renhaowan.platform.gateway.message;

/**
 * MCP 协议常量唯一定义点：方法名 / 错误码 / 传输标识 / 会话头 / Redis key 约定。
 * 消灭散落各处的魔法值——协议字段拼错一个字符就是线上事故，集中定义 + 单点引用最稳。
 */
public final class McpProtocol {

    private McpProtocol() {
    }

    /** JSON-RPC 方法名（MCP 规范核心方法；网关侧由 MessageHandler#method() 声明并注册分发） */
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_PING = "ping";

    /**
     * JSON-RPC 错误码。-32601 ~ -32603 是 JSON-RPC 2.0 标准段；
     * -32001 / -32002 是 MCP 扩展段（-32000 起保留给实现方自定义）。
     */
    public static final int ERROR_UNAUTHORIZED = -32001;
    public static final int ERROR_RATE_LIMITED = -32002;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;

    /** 传输标识（gateway_session.transport 审计字段取值） */
    public static final String TRANSPORT_SSE = "SSE";
    public static final String TRANSPORT_STREAMABLE = "STREAMABLE";

    /** HTTP 头：租户鉴权 key / MCP 会话 id / 预留的用户角色透传头 */
    public static final String HEADER_API_KEY = "X-Api-Key";
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** Redis key 约定：会话体（RBucket）、限流器（RRateLimiter）、跨实例事件频道（RTopic） */
    public static final String REDIS_KEY_SESSION_PREFIX = "gw:session:";
    public static final String REDIS_KEY_RATE_LIMIT_PREFIX = "gw:ratelimit:";
    public static final String CHANNEL_SESSION_EVENTS = "gw:session-events";
}
