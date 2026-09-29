# ADR-002：同时实现 SSE 与 Streamable HTTP 双传输

日期：2026-09-29　状态：已采纳

## 背景

MCP 规范定义了两种传输：SSE（GET 建连 + POST 消息端点，响应经流回）与
Streamable HTTP（单端点 POST/GET/DELETE，`Mcp-Session-Id` 头管理会话）。

## 决策

网关同时实现两种传输，共用同一套消息分发器与会话服务。

## 理由

1. **兼容**：存量 MCP 客户端（含各 IDE 插件）多数仍走 SSE；不实现即丢失兼容面。
2. **演进**：Streamable 是规范明确替代 SSE 的方向（单端点、可恢复、无状态友好），新客户端默认走它。
3. **成本可控**：两者共享 session/handler 层，传输层只是"报文进出"的薄壳，双实现的边际成本低。

## 关键实现差异（面试常问）

| 维度 | SSE | Streamable HTTP |
|---|---|---|
| 会话建立 | GET 建连，首个 `endpoint` 事件告知消息端点 | 首个 `initialize` 的响应头 `Mcp-Session-Id` |
| 消息方向 | POST 到独立 message 端点，响应**必须**经 SSE 流回 | POST 即响应（JSON 或流） |
| 终止 | 连接断开即结束 | 显式 DELETE |
| 心跳 | 25s 注释帧 | 流上同样保活 |

## 后果

双传输使网关可接入任意 MCP 客户端；代价是会话路由需同时覆盖两种形态
（SSE 由连接注册表持有，Streamable 由会话头携带），已由统一的会话校验
（gatewayId + tenantId 双匹配）收敛。
