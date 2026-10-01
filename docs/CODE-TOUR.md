# 代码导览 — agent-platform 逐文件说明

> 用途：给作者本人的复述地图（每个文件一句话 + 面试考点），也供读者按图索骥。
> 共 63 个主文件 + 15 个测试类。标注：★ = 面试深挖高发区；【①②③④】= 对应简历亮点 1-4。

## 推荐通读顺序（约 40 分钟）

```
PlatformApplication → application.yml → ChatController → AgentRunner →
Planner/OpenAiPlanner → McpGatewayClient → DynamicToolRegistry →
MessageDispatcher → ToolsCallHandler → GenericHttpForwarder →
OpenApiImportService → SseConnectionRegistry/SessionEventSubscriber →
ContextTrimmer → ConfirmManager
```

沿一次真实请求读：你在页面说一句话 → ChatController 收到 → AgentRunner 循环 →
McpGatewayClient 自环调网关 → 网关分发/转发 → booking 真实执行 → 原路返回 → SSE 推给你。

---

## 1. gateway/ —— MCP 工具网关（简历亮点①：手写协议层）

### transport/ 传输端点
- **SseGatewayController.java** ★【①】
  `GET /{gw}/mcp/sse`：建会话（Redis）→ 注册 SseEmitter → 下发 `endpoint` 事件（告知消息端点）→ 启动 25s 心跳。
  `POST /{gw}/mcp/message`：校验会话（gatewayId + tenantId 双匹配，防劫持）→ 分发处理 → **响应经 SSE 流回**（HTTP 202 空体）。
  面试点：会话不在/租户不符 → 400 -32602；SSE 断连即会话失效。
- **StreamableGatewayController.java** ★【①】
  `POST /{gw}/mcp`：`initialize` 创建会话并在**响应头**回 `Mcp-Session-Id`；普通消息直回 JSON；客户端开过 GET 流则响应经流回（202）。
  `GET /{gw}/mcp`：服务器→客户端事件流。`DELETE`：终止会话（Redis 删除 + 审计盖章 + 连接 complete）。
  面试点：与 SSE 的差异表（会话建立方式/消息路径/终止方式）见 ADR-002。

### message/ 协议与分发
- **MessageDispatcher.java** ★：`method → Handler` 直排分发（Map 路由，无策略树）；`id` 为空 = 通知，不产生响应；未知方法 -32601；Handler 异常 -32603。
- **InitializeHandler.java**：版本协商（支持 2024-11-05 / 2025-03-26 / 2025-06-18，不认识则回落默认）+ `capabilities.tools` + `serverInfo`。
- **ToolsListHandler.java** ★：查 `tool_definition`（按租户+网关），输出 `name/description/inputSchema` + 平台扩展字段 `x-require-confirm`。
- **ToolsCallHandler.java** ★★【①+安全边界】：定位工具 → **（用户级权限校验预留位置，见 ARCHITECTURE §4 闸门 2）** → 泛化转发 → 结果包装为 `content[0].text`；失败包装为 `isError=true`（模型可读、循环不断，不打断协议）。
- **JsonRpcRequest / JsonRpcResponse / MessageContext.java**：协议信封（2.0 请求/响应/错误码）与一次调用的上下文。
- **JsonRpcErrorWriter.java**：过滤器层的标准 JSON-RPC 错误输出（401/429）。

### registry/ 工具注册
- **OpenApiImportService.java** ★【①】：OpenAPI JSON → 工具定义。规则：`operationId`→工具名（缺失则 slug 兜底）、`summary`→描述（模型选工具的依据）、path 参数填 `url_template` 占位、query/requestBody 合并生成 `inputSchema`、**DELETE 或"取消/cancel"字样自动标 require_confirm**。
- **ToolRegistryService.java**：按（租户, 网关）作用域查询/更新；同名工具更新不重复插入。
- **ToolDefinition.java / ToolDefinitionMapper.java**：`tool_definition` 表实体（URL 模板、inputSchema、require_confirm、auth_header）。

### security/ 接入鉴权
- **ApiKeyFilter.java** ★：MCP 端点与管理端（/admin/tenants 除外）要求 `X-Api-Key` → 查租户 → 限流 → 写入 `TenantContext`。失败输出 JSON-RPC 401/429。
- **RateLimitService.java** ★：Redisson `RRateLimiter`（OVERALL，60 次/分/接入方）；**故障 fail-open**（可用性优先，后端还有业务校验兜底）——取舍已写入 ADR。
- **TenantContext.java / GatewayProperties.java / WebConfig.java**：请求级身份 ThreadLocal、`platform.gateway.*` 配置、过滤器注册（order=1）。
- **tenant/Tenant.java / TenantMapper.java / TenantService.java**：租户表 + api_key 查询（60s 本地缓存，每请求鉴权不打库）。

### session/ 会话与多实例
- **GatewaySessionService.java** ★【④】：会话体存 Redis（30 分钟滑动 TTL，validate 时续期），MySQL 只留审计（best-effort，失败不阻断建连）。
- **SseConnectionRegistry.java** ★★【④】：本实例持有的 SSE 连接表。回复路径：**本机有连接直写；没有则 `publishAsync` 到 Redis 频道**，由持有连接的实例代发。
- **SessionEventSubscriber.java**【④】：订阅 `gw:session-events` 频道，收到广播后本机有连接才写出。
- **SseHeartbeat.java**：25s 注释帧保活，写失败自动停止该连接的心跳。
- **GatewaySession.java / GatewaySessionMapper.java**：会话审计表实体。

### forward/ 泛化转发
- **GenericHttpForwarder.java** ★★【①】：网关"手"的部分。拼装规则：`url_template` 的 `{param}` 从 arguments 取值（URL 编码）；GET/DELETE 余参拼 query；POST/PUT/PATCH 余参作 UTF-8 JSON body；**inputSchema 若声明了 `properties.body` 则自动解包**（导入规则的逆向）；`auth_header_name/value` 存在则附带服务凭证。执行：connect 2s / read 10s；响应 **8KB 截断**（防打爆模型上下文）；非 2xx 与异常统一转为 `success=false` 的结果（由模型决策下一步，不抛协议错误）。

### tenant/ web/ 
- 租户管理与协议导入的管理端 REST（`/admin/tenants` 开放、`/admin/tools`、`/admin/protocols/import` 需 key）。

---

## 2. agent/ —— ReAct 运行时（简历亮点②③④）

### loop/ 决策循环
- **AgentRunner.java** ★★【②】：全项目心脏。流程：意图分类（规则通道）→ 载入历史（裁剪后）→ 循环 `Planner.decide` → 有 toolCalls 则逐个执行（敏感工具先挂起等确认）→ `ToolResponseMessage` 回填 → 无 toolCalls 则 answer。系统提示词每轮动态注入 `{{CURRENT_DATE}}/{{CURRENT_WEEKDAY}}`（消除模型编造日期）。步数熔断 maxSteps=8。
- **Planner.java / OpenAiPlanner.java** ★【②】：规划器抽象 + OpenAI 兼容实现。关键一行：`internalToolExecutionEnabled(false)`——关闭 Spring AI 隐式工具执行，每轮只取回模型的 toolCalls 决策，**执行权收归循环**（这是事件插桩/确认挂起/权限校验能存在的前提）。qwen3 系模型自动透传 `extraBody: {enable_thinking: false}`（DashScope 非流式限制；注：1.1.1 的 extraBody 序列化未生效，已切 qwen-turbo，待 Spring AI 升级）。
- **IntentClassifier.java / RuleIntentClassifier.java**：意图分类（规则通道：取消/预订/其他关键词），LLM 通道为预留扩展点。

### sse/ 用户入口
- **ChatController.java** ★：`POST /api/v1/chat` → SseEmitter + 固定线程池（8 线程，命名 `agent-loop-*`）异步跑循环——**长连接不占容器业务线程**。
- **AgentEventEmitter.java**：8 类事件（session/intent/plan/tool_call/tool_result/confirm_request/answer/error/done）的类型化发射器，连接断开静默丢弃（循环照常落库）。

### tool/ 工具接入
- **McpGatewayClient.java** ★【①②的缝合线】：Agent 以 MCP 客户端身份**自环调用**网关——initialize（响应头取 Mcp-Session-Id）→ tools/list → tools/call；400 会话过期自动重初始化重试一次。
- **DynamicToolRegistry.java** ★：tools/list 结果 → 手写 `GatewayToolCallback`（实现 Spring AI ToolCallback，**仅作 schema 广告位**——执行走 McpGatewayClient 手动通道，这样事件/确认/权限才能插桩）；`x-require-confirm` 映射；5 分钟缓存。
- **ToolSelector.java / NoopToolSelector.java / WattAiToolSelector.java**【ADR-004】：工具前置路由扩展点。wattai：工具 ≥4 个时调决策模型（state=用户话术，choices=工具名），高置信单选、低置信 top-k、异常 fail-open；**当前默认关闭**（托管端点延迟 ~2s、中文路由置信度不足，探测数据在 ADR）。

### context/ confirm/ memory/
- **ContextTrimmer.java** ★【③】：chars/2 粗估超阈值（默认 6000）→ 保留 system + 最近 6 条，更早内容压成摘要 SystemMessage（落 checkpoint 表）。确定性规则，单测友好。
- **TokenRecorder.java**【③】：usage 打点 best-effort（31.8% 实验的数据源）。
- **ConfirmManager.java / ConfirmController.java**★：token → CompletableFuture 挂起；`POST /api/v1/confirm` 恢复；超时 = 拒绝。单实例内存实现（重启丢确认项视为拒绝，语义安全）；多实例化迁移 Redis 是既定演进。
- **AgentSessionService.java** + 四实体四 Mapper：chat_session / chat_message / checkpoint / llm_usage（消息即写即落库，重启不丢对话）。
- **AgentProperties.java / AgentConfig.java**：`platform.agent.*` 配置与需要原始值参数的 Bean 装配。

---

## 3. common/ web/ resources/

- `common/GlobalExceptionHandler.java`：参数校验 400、NoResourceFound 404（防吞）、其余 500。
- `resources/static/index.html`：单页控制台（fetch 流解析 SSE，8 类事件渲染，确认按钮）。
- `resources/schema.sql`：9 张表幂等建表（IF NOT EXISTS + information_schema 守卫的 ALTER）。
- `resources/prompts/system.md`：系统提示词（含动态日期占位符）。

## 4. 测试（15 类，59 项）

| 测试类 | 覆盖 |
|---|---|
| MessageDispatcherTest (5) | initialize 协商/回落、ping、-32601、通知无响应 |
| StreamableGatewayControllerTest (7) | 建会话回头部、缺头拒绝、合法/未知会话、通知 202、终止、404 |
| ToolsHandlersTest (4) | tools/list 结构、未知工具、成功包装、isError 包装 |
| GenericHttpForwarderTest (7) | query/body/path 填充、UTF-8、8KB 截断、后端 500、不可达 |
| OpenApiImportServiceTest (5) | 三类参数解析、slug 兜底、baseUrl 覆盖、非法 JSON、取消类敏感 |
| ToolRegistryServiceTest (2) | upsert 插入/更新 |
| ApiKeyFilterTest (6) | 401 三态、放行、admin 范围 |
| RateLimitServiceTest (3) | 放行/拒绝/fail-open |
| GatewaySessionServiceTest (5) | 建会话、续期、缺失、Redis 宕、关闭 |
| MessageDispatcherTest 之外的 transport 测试 | SSE 见 ApiKeyFilterTest 的 200 用例 |
| ConfirmManagerTest (4) | 同意/拒绝/超时/未知 token |
| ContextTrimmerTest (3) | 不裁剪/裁剪结构/摘要计数 |
| AgentRunnerTest (4) | 循环三态：执行/同意/拒绝/超步数 |
| DynamicToolRegistryTest (2) | 回调构建 + 确认映射、空清单 |
| PlatformApplicationTests (1) | 上下文启动 |

## 5. 一次请求的类名级走线

```
index.html → ChatController.chat
  → AgentSessionService.createSession / loadHistory
  → ContextTrimmer.trim
  → DynamicToolRegistry.callbacks（自环 McpGatewayClient → 网关 tools/list）
  → AgentRunner 循环：
      Planner.decide（qwen-turbo）
      → ToolsCallHandler？不——循环经 McpGatewayClient.callTool
        → StreamableGatewayController.post（tools/call）
        → MessageDispatcher → ToolsCallHandler
        → GenericHttpForwarder → meeting-booking REST
      → 观察回填 → 下一轮
  → AgentEventEmitter.send（SSE）→ 浏览器
```

> 注：Agent 循环调网关走 `StreamableGatewayController`（POST 直回 JSON）；
> SSE 传输端点（`/{gw}/mcp/sse`）保留给标准 MCP 客户端接入场景。
