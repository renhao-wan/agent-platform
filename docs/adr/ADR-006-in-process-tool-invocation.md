# ADR-006：Agent 以进程内调用访问工具层，删除手写 MCP 协议层与 HTTP 自环

日期：2026-10-18　状态：已采纳（取代 ADR-001、ADR-002）

## 背景

平台最初把"工具层"实现为一个完整的 MCP 服务端：手写 JSON-RPC 协议层、SSE 与 Streamable HTTP 双传输、会话生命周期、apiKey 多租户接入鉴权、令牌桶限流。而 Agent 运行时则以 **MCP 客户端**身份，通过真实 HTTP 回环调用同一进程内的这套服务端（`http://localhost:8000/{gw}/mcp`）。

这个自环的唯一理由是"**预留拆分部署**"——文档里写"gateway/ 与 agent/ 是逻辑分包，预留拆分部署，尚未拆"。事实是这个拆分从未发生，也没有明确的排期。

代价却是每天都在付的：

1. **每次工具调用多一跳 HTTP 与一次协议编解码**：一次方法调用被摊成"序列化 → 本机网络 → 反序列化 → 分发"；
2. **异常要跨协议层翻译**：业务系统 500 本是一个 HTTP 状态码，却要经过"包装成 JSON-RPC `isError` 结果 → 客户端解包 → 再变成文本观察"三段转换才能到达模型；
3. **读代码要跨协议追踪**：想知道"模型点了一个工具之后发生了什么"，得从 `AgentRunner` 跳到 `McpGatewayClient`、`StreamableGatewayController`、`MessageDispatcher`、`ToolsCallHandler` 才能找到真正做事的那一段；
4. **附带一整套只为自环服务的外设**：apiKey 鉴权、租户表、限流、Redis 会话存储与 PubSub 多实例同步——它们存在的前提是"有人从网络外部调进网关"，而实际上唯一的调用方是同一个进程里的 Agent。

更关键的是：**协议层与项目的四个亮点（越权防护 / OpenAPI 零改造接入 / ReAct 显式循环 / 分层上下文管理）全都无关**。四个亮点分别落在 `ToolAccessService`、`OpenApiImportService` + `GenericHttpForwarder`、`AgentRunner` + `OpenAiPlanner`、`ContextTrimmer` + `HistoryRecallTool` 上，协议层既不是它们的前提，也不为它们提供任何能力。它反而是叙述负担——讲"我怎么防越权"要先解释半天 MCP 是什么。

## 备选方案

| 方案 | 评估 |
|---|---|
| 保持现状（自环 + 手写协议层） | 保留"标准 MCP 客户端可接入"的能力，但该能力无人使用；继续付上面四项代价 |
| 保留协议层、只去掉网络跳转（同进程直接调 dispatcher） | 去掉了一跳网络，但协议编解码与异常翻译仍在——治了标 |
| **删除协议层与自环，Agent 进程内调工具层** | ✅ 采纳：链路缩到"一次方法调用"，异常直通，读代码不需要跨协议；代价是放弃 MCP 互操作能力（见后果） |

## 决策

1. **新增 `agent/tool/ToolGateway` 接口**（`listTools` / `callTool`），作为 Agent 访问工具层的唯一出口；实现 `InProcessToolGateway` 直接转调 `ToolAccessService`。留着接口是为了"将来若真拆分部署，换实现即可，Agent 循环不用动"——但**现在没有第二个实现**，"进程内"就是唯一形态。
2. **工具层收拢为 `gateway/registry`（注册/访问控制）+ `gateway/forward`（泛化转发）+ `gateway/web`（管理端）三个包**。`ToolAccessService` 成为唯一访问入口，两道权限闸门都落在这个类里（原来分别散在 `ToolsListHandler` 与 `ToolsCallHandler`）。
3. **删除**：`gateway/message`（JSON-RPC 信封、`McpProtocol`、`MessageDispatcher`、四个 Handler）、`gateway/transport`（两个控制器）、`gateway/session`（会话与多实例同步）、`gateway/security`（apiKey / 限流 / 租户上下文）、`gateway/tenant`、`gateway/web/TenantAdminController`、`agent/tool/McpGatewayClient(Impl)`。
4. **一并删除只为自环服务的外部鉴权与 Redis**：`redisson-spring-boot-starter` 与 `spring-boot-starter-webflux` 依赖、`tenant` 与 `gateway_session` 表、`tool_definition.tenant_id` 列、`spring.data.redis.*` 配置。用户 JWT 一套鉴权取代"apiKey（客户端应用）+ JWT（用户）"两套；`/admin/**` 改为要求 `ADMIN` 角色。
5. **`AgentRunner` 主循环零改动**——只把注入的 `McpGatewayClient` 换成 `ToolGateway`。这本身就是原设计"执行权在循环手里"的验证：只要执行入口是可替换的，换掉整条传输链路不影响循环逻辑。
6. 重构前的最后一个提交保留在分支 `legacy/mcp-gateway`（tip `1197a97`）。

## 后果

**正面**

- 一次工具调用 = 一次方法调用，无协议编解码、无本机 HTTP；
- 异常路径从三段翻译缩为一段：`ForwardResult(success=false)` → `ToolCallResult(isError=true)` → 文本观察。核心不变量"失败永不抛异常、一律作为观察回喂模型"变得更直白；
- 依赖面收窄：只剩 MySQL 与 LLM，本地启动无需再起 Redis；
- 删除的测试类（协议分发、双传输、会话、鉴权、限流）换来两个更有价值的测试：`GenericHttpForwarderTimeoutTest`（传输故障的真实形态）与 `AgentSelfHealingTest`（端到端自愈贯通）。

**负面**

- 放弃了"标准 MCP 客户端接入"的能力：外部 MCP 客户端不能再连进来当工具源。若将来需要，必须重新引入一个协议边界——届时不必重写工具层，只需在 `ToolGateway` 之外再加一个 MCP 服务端适配器；
- 若将来工具层真的拆分部署，需要重新引入进程边界。届时可参考 `legacy/mcp-gateway` 分支；
- 删掉了多实例会话同步能力——单实例部署下无影响，多实例化需另想方案（当前无此需求）。
