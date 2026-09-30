# agent-platform

> 轻量级 **MCP 工具网关与 Agent 运行时**（Java / Spring Boot 3）——任何后台服务凭一份 OpenAPI 文档即可将接口**零代码**注册为大模型工具；Agent 运行时让大模型**安全地**（确认 / 限流 / 裁剪）使用工具完成真实业务操作，SSE 全程直播推理过程。

## 架构

```
        浏览器单页控制台（EventSource）
             │ HTTP + SSE
             ▼
┌─────────────────────────────────────────────┐
│ agent-platform :8000                        │
│                                             │
│  agent 模块（自研）                           │
│   ReAct 循环：规划→调工具→观察→反思→回答       │
│   上下文裁剪 / Token 打点 / 敏感操作确认        │
│         │ MCP 客户端（tools.list / tools.call）│
│  gateway 模块（手写 MCP 协议层）                │
│   SSE + Streamable HTTP 双传输                │
│   initialize / tools.list / tools.call 状态机  │
│   OpenAPI→Tool 自动注册 · apiKey 多租户 · 限流  │
└─────────┬───────────────────────────────────┘
          │ 泛化 HTTP 转发
          ▼
   业务系统（meeting-booking 等任意 OpenAPI 服务）
```

## 核心特性

1. **手写 MCP 协议层**：实现 SSE 与 Streamable HTTP 双传输、initialize / tools.list / tools.call 消息状态机、会话生命周期管理（Redis TTL 滑动续期 + PubSub 多实例同步）
2. **零代码工具接入**：存量 OpenAPI 接口自动解析注册为标准化 Tool（path/query/body 参数 → inputSchema）；DELETE 类接口自动标记敏感
3. **ReAct 显式循环**：规划-执行-反思逐步驱动，SSE 分阶段推送（intent / plan / tool_call / confirm_request / answer）
4. **上下文工程**：滑动窗口 + 工具轨迹摘要混合裁剪，Token usage 全链路打点
5. **安全治理**：apiKey 多租户隔离、Redisson 令牌桶限流（故障 fail-open）、敏感操作人工二次确认（挂起/恢复）

## 快速开始

```bash
# 1. 启动中间件（MySQL 3307 / Redis 6380，与宿主机实例隔离）
docker compose -f docker-compose.dev.yml up -d

# 2. 配置 LLM 与平台自环凭证
export LLM_API_KEY=sk-xxx                    # DashScope API Key
export GATEWAY_API_KEY=$(curl -s -X POST http://localhost:8000/admin/tenants \
  -H "Content-Type: application/json" -d '{"name":"platform-agent"}' | sed 's/.*"apiKey":"\([^"]*\)".*/\1/')

# 3. 启动应用
mvn spring-boot:run

# 4. 全链路冒烟：导入 OpenAPI → initialize → tools/list → tools/call
bash docs/smoke-m1.sh

# 5. 打开控制台对话
open http://localhost:8000/
```

## 设计决策（ADR）

| 编号 | 决策 | 一句话理由 |
|---|---|---|
| [ADR-001](docs/adr/ADR-001-handwritten-mcp-protocol.md) | 手写 MCP 协议层而非使用 Spring AI MCP starter | starter 解决"单应用静态工具暴露"，网关需要动态注册、多租户、多实例会话同步——这些本就要自研，索性协议层一起实现 |
| [ADR-002](docs/adr/ADR-002-dual-transport.md) | 同时实现 SSE 与 Streamable HTTP 双传输 | MCP 规范双传输并存；SSE 兼容存量客户端，Streamable 是规范演进方向，网关作为基础设施必须都支持 |
| [ADR-003](docs/adr/ADR-003-context-trimming.md) | 滑动窗口 + 轨迹摘要混合裁剪 | 纯滑窗丢关键事实，纯摘要成本高且慢；混合策略保住最近上下文与工具结论，Token 开销实测见下 |

## 性能数据

| 指标 | 数值 | 方法 |
|---|---|---|
| SSE 并发长连接 | **40 并发 200/200，P50 1.03s / P95 1.32s / P99 1.35s**（分位差 <330ms，无线程饥饿） | 并发压测，每请求含：MySQL 会话落库 + MCP 自环 tools/list + 上游调用与错误事件回传（`docs/load/sse-bash-load.sh`） |
| 限流精度 | **60 次/分钟精确生效（60 放行 + 5×429 实测）** | Redisson RRateLimiter，真实 Redis，越界请求全部 429 |
| Token 裁剪收益 | **30 轮长会话总 Token 降 31.8%**（166,656 → 113,716）；**单次调用 prompt 末轮降 78.6%**（7860 → 1684）；代价：裁剪致遗忘多 20 次重查（阈值需按场景调优，见 ADR-003） | 开/关裁剪 A/B 实测，`docs/experiment-token.sh`（qwen-turbo，usage 全量打点） |
| 敏感操作确认 | confirm_request → 人工授权 → 执行 → CANCELLED 全链路实测 | LLM 驱动 cancel 工具的真实会话 |

## 技术栈

Java 17 · Spring Boot 3.4 · Spring AI 1.1（OpenAI 兼容协议）· MyBatis-Plus · MySQL 8.4 · Redis / Redisson · MCP（Model Context Protocol）· SSE · Docker Compose · JMeter

## 测试

```bash
mvn test    # 56 项：协议一致性 / 转发拼装 / 裁剪策略 / 循环三态（执行·同意·拒绝·超步数）/ 限流 fail-open
```
