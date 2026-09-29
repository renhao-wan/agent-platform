# ADR-001：手写 MCP 协议层，而非使用 Spring AI MCP starter

日期：2026-09-29　状态：已采纳

## 背景

平台需要把"任意业务系统的 HTTP 接口"聚合为大模型可调用的工具。Spring AI 提供
`spring-ai-starter-mcp-server`，可以快速把一个应用暴露为 MCP server。

## 决策

自行实现 MCP 协议层：SSE 与 Streamable HTTP 双传输端点、initialize / tools.list / tools.call
消息状态机、基于 Redis 的会话管理。

## 理由

1. **问题形态不同**：starter 面向"单应用把编译期固定的 `@Tool` 方法暴露出去"；网关要求
   **工具在数据库中、运行时动态增删（管理端 OpenAPI 导入）、多租户（apiKey → 租户 → 工具集合隔离）、
   多实例会话漂移（Redis PubSub 同步）**。这三件事 starter 均不提供，网关的实体本就要自研。
2. **控制权**：网关是长连接基础设施，会话生命周期、断连清理、错误码、心跳都属于必须自主掌控的细节。
3. **学习收益**：协议实现过程即对 MCP 规范的完整理解，`tools/list` 与 `tools/call` 的行为可逐条对照规范验证
   （见 `docs/smoke-m1.sh` 与协议一致性测试）。

## 后果

- 正面：协议细节可讲、可测、可控；动态注册与多租户不受框架限制。
- 负面：需自行跟随 MCP 规范版本（当前支持 2024-11-05 / 2025-03-26 / 2025-06-18 协商）；
  规范演进需手动跟进。
