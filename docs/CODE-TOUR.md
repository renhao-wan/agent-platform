# 代码导览 — agent-platform 逐文件说明

> 用途：给作者本人的复述地图（每个文件一句话 + 面试考点），也供读者按图索骥。
> 共 61 个主文件 + 19 个测试类。标注：★ = 面试深挖高发区；【①②③④】= 对应简历亮点 1-4。

## 推荐通读顺序（约 40 分钟）

```
PlatformApplication → application.yml → ChatController → AgentRunner →
Planner/OpenAiPlanner → ToolGateway/InProcessToolGateway → DynamicToolRegistry →
ToolAccessService → GenericHttpForwarder → OpenApiImportService →
ContextTrimmer → ConfirmManager
```

沿一次真实请求读：你在页面说一句话 → ChatController 收到 → AgentRunner 循环 →
`ToolGateway` 进程内调工具层 → 闸门校验 → 转发给 booking 真实执行 → 原路返回 → SSE 推给你。

---

## 1. gateway/ —— 工具层（简历亮点①②）

> 名字还叫 gateway，但实体已经变了：这里不再有协议层，只有"注册什么、放不放行、怎么转发"。
> 与 `agent/` 同处一个 Spring 应用，被 `ToolGateway` 以普通方法调用触达。

### registry/ 工具注册与访问控制
- **OpenApiSourceRegistrar.java**【②】：启动时按 `platform.gateway.sources` 逐个拉取业务系统的 OpenAPI 契约、解析、注册。**"契约可访问"就等于"工具可用"**——不必手工导入，改了业务系统的 `@Operation` 说明或角色声明，重启平台即生效。契约地址的 origin 直接作为该来源工具的调用基地址，故契约本身不必写死主机名。单个来源失败只告警跳过，不阻断启动。
- **OpenApiImportService.java** ★【②】：OpenAPI JSON → 工具定义。规则：`operationId`→工具名（缺失则 slug 兜底）、`summary`→描述（模型选工具的依据）、path 参数填 `url_template` 占位、query/requestBody 合并生成 `inputSchema`、**敏感与角色一律读业务系统的扩展字段**（`x-require-confirm` / `x-required-role`，DELETE 天然算敏感）——平台不猜测接口语义，所以"取消"二字本身不再触发任何标记。
  `$ref` 必须**就地展开**：工具 schema 是脱离原契约、单独发给模型的片段，`#/components/schemas/...` 在模型侧查不到，OpenAI 兼容接口会以 "Pointer ... does not exist" 拒绝整轮请求。自引用按层截断成宽松 object 骨架，不会无限展开。
- **ToolRegistryService.java**：按 gatewayId 作用域查询/更新；同名工具更新不重复插入。**工具名在同一网关内是全局命名空间**，多个业务系统共用一个 gatewayId 时后来的同名工具会顶掉先前的——导入时若发现同名但"主机与路径都不同"，留下来源变更告警（只改地址、改端口属正常操作，不告警）。
- **ToolAccessService.java** ★★【① 安全边界】：系统里**唯一的工具访问入口**，两道闸门都在这一个类里。
  `listForModel(gatewayId)` 做**闸门 1·清单组装**——下发该网关**全部启用工具，不按角色裁剪**；角色要求写进工具描述随清单一起给模型，于是模型知道"这个能力存在、但当前身份可能不够"。
  面试点：为什么不在清单里就把没权限的工具裁掉？裁掉的话模型只会答"我没有这个功能"，**把权限问题说成了能力问题**，用户拿不到"你需要找管理员开权限"这个正确结论。
  `invoke(gatewayId, userRole, userId, name, argsJson)` 做**闸门 2·执行点鉴权**——定位工具 → 角色不匹配直接返回 `ToolCallResult(true, "权限不足：需要 X 角色")` → 参数非法 JSON 也如实回喂 → 通过后交 `GenericHttpForwarder`。
  面试点：为什么拒绝要包装成文本结果而不是抛异常？因为抛异常会打断 ReAct 循环，而模型需要"看到失败、自己决定重试还是转述用户"。
- **ToolDefinition.java / ToolDefinitionMapper.java**：`tool_definition` 表实体（url_template、inputSchema、require_confirm、auth_header、required_role）。`description`/`required_role`/`auth_header_*` 标了 `updateStrategy = ALWAYS`——MyBatis-Plus 默认跳过 null 字段，会让"业务系统已撤销的声明"永远留在库里。
- **GatewayProperties.java**：`platform.gateway.sources` 配置项（契约地址 + 服务凭证），供 `OpenApiSourceRegistrar` 读取。

### forward/ 泛化转发
- **GenericHttpForwarder.java** ★★【②】：工具层的"手"。拼装规则：`url_template` 的 `{param}` 从 arguments 取值（URL 编码）；GET/DELETE 余参拼 query；POST/PUT/PATCH 余参作 UTF-8 JSON body；**inputSchema 若声明了 `properties.body` 则自动解包**（导入规则的逆向）；`auth_header_name/value` 存在则附带服务凭证，**另附 `X-On-Behalf-Of-User: {uid}` 声明代表哪个最终用户**（业务系统仅在服务凭证校验通过时才采信）。执行：connect 2s / read 10s；响应 **8KB 截断**（防打爆模型上下文）；非 2xx 与异常统一转为 `ForwardResult(success=false, error=...)`（由模型决策下一步，不抛异常）。用 `exchange()` 而非 `retrieve()` 正是为了保住原始状态码。
- **ForwardProperties.java**：`platform.gateway.forward.connect-timeout-ms` / `read-timeout-ms`（默认 2000 / 10000，原先硬编码在类里）。

### web/ 管理端
- **ProtocolAdminController.java** ★：`POST /admin/protocols/import`（gatewayId + openapi + baseUrl + 服务级 auth header；**角色与敏感度不在这里传**——它们是业务系统写在契约里的声明）与 `GET /admin/tools`。整个 `/admin/**` 由 `JwtAuthFilter` 要求 ADMIN 角色——**没有第二套凭证体系**。

---

## 2. agent/ —— ReAct 运行时（简历亮点③④）

### loop/ 决策循环
- **AgentRunner.java** ★★【③】：全项目心脏。流程：载入历史（裁剪后）→ 循环 `Planner.decide`（语义决策由模型对工具清单自主完成，平台保持领域中立） → 有 toolCalls 则逐个执行（敏感工具先挂起等确认）→ `ToolResponseMessage` 回填 → 无 toolCalls 则 answer。系统提示词每轮动态注入 `{{CURRENT_DATE}}/{{CURRENT_WEEKDAY}}`（消除模型编造日期）。步数熔断 maxSteps=8。**本次重构里这个类的主循环一行没改**——只把注入的 `McpGatewayClient` 换成 `ToolGateway`。
- **Planner.java / impl/OpenAiPlanner.java** ★【③】：规划器抽象 + OpenAI 兼容实现。关键一行：`internalToolExecutionEnabled(false)`——关闭 Spring AI 隐式工具执行，每轮只取回模型的 toolCalls 决策，**执行权收归循环**（这是事件插桩/确认挂起/权限校验能存在的前提）。默认指向 DeepSeek（`application.yml` 的 `LLM_BASE_URL`/`LLM_MODEL` 可切厂商）。**厂商特有的"思考开关"按模型名前缀走 `extraBody` 透传**——qwen3 要 `enable_thinking=false`，DeepSeek 的 thinking 默认开启、必须显式关（原因见 FRAMEWORK-NOTES §2：它带 tools 时要求回传 `reasoning_content`，与本循环手动构造 `ToolResponseMessage` 冲突）。**不要**改用 yml 的 `extra-body`，那个键在此版本实测静默失效。

### sse/ 用户入口
- **ChatController.java** ★：`POST /api/v1/chat` → SseEmitter + 显式 `ThreadPoolExecutor`（核心 4/上限 8，有界队列 64，AbortPolicy 拒绝→友好报错，命名线程 `agent-loop-*`）异步跑循环——**长连接不占容器业务线程**。身份在 HTTP 线程用 `UserContext.get()` 取出后**显式传参**给线程池里的循环（ThreadLocal 不跨线程池）。
- **AgentEventEmitter.java**：8 类事件（session/plan/tool_call/tool_result/confirm_request/answer/error/done）的类型化发射器，连接断开静默丢弃（循环照常落库）。

### tool/ 工具接入
- **ToolGateway.java + impl/InProcessToolGateway.java** ★【①②的缝合线】：Agent 访问工具层的**唯一出口**。
  接口两个方法：`listTools(user)` / `callTool(name, argsJson, user)`，约定**失败也返回文本、不抛异常**。
  进程内实现直接把调用转给 `ToolAccessService`，gatewayId 从 `AgentProperties` 取；失败文本加 `tool error: ` 前缀供模型识别。
  面试点：为什么留接口？——将来工具层若真拆成独立部署，换个实现即可，Agent 循环不用动。目前**没有第二实现**，"进程内"就是唯一形态。
- **DynamicToolRegistry.java** ★：工具层返回的工具定义 → 手写 `GatewayToolCallback`（实现 Spring AI ToolCallback，**仅作 schema 广告位**——`call()` 留给 Spring AI 的隐式路径，本项目不走；真正执行在 `AgentRunner` 手动通道）；`require_confirm` 列映射为敏感标记；缓存 5 分钟（清单对所有人一样，无需按角色分桶）；刷新是"先构建后整体切换"，失败保留旧桶——注册失败不影响在跑对话。
- **ToolSelector.java / impl/ToolSelectorChain.java / impl/WattAiToolSelector.java**【ADR-004】：工具前置路由责任链。`AgentRunner` 进主模型前先过链：工具数未达 `routing-tool-count-threshold`（默认 20）整链休眠、全量直通；达到后按 `routing-handlers` 顺序逐环尝试，节点以 `Optional.empty` 弃权（前置不满足/异常）传下一环，任一环节点接管即短路，全部弃权回落全量——路由层任何故障不影响主流程。wattai 节点：调决策模型（state=用户话术，choices=工具名），高置信单选、低置信 top-k 降级、argmax 落空/异常弃权。当前规模下链休眠（收益为负），扩展位预留 jev/本地语义路由。

### auth/ 用户身份
- **JwtService.java + AuthController/AuthService/AuthServiceImpl**：`POST /api/v1/auth/login` 签发 JWT（claims: uid/role），解析失败抛 `JwtException`。`InvalidCredentialsException` 由全局异常处理转 401。
- **JwtAuthFilter.java** ★：手写过滤器（不引入 Spring Security）。拦 `/api/v1/chat`、`/api/v1/confirm` 与 `/admin/**`，解出 `UserPrincipal` 写入 `UserContext`（请求结束清理），其余路径放行；`/admin/**` 额外要求 `ADMIN` 角色，403 输出"需要 ADMIN 角色"。401/403 在过滤器层直接写 JSON（尚未进 `@ControllerAdvice` 范围）。
- **UserPrincipal.java / UserContext.java**：身份 record（uid + role）与请求级 ThreadLocal。
- **entity/AppUser.java / mapper/AppUserMapper.java**：`app_user` 表（username / password_hash / role / status）。
- **WebConfig.java**：只注册一个 `JwtAuthFilter`（order=1）。

### context/ confirm/ memory/
- **ContextTrimmer.java** ★【④】：chars/2 粗估超阈值（默认 6000）→ 保留 system + 最近 6 条，更早内容压成摘要 SystemMessage（落 checkpoint 表）。确定性规则，单测友好。
- **TokenRecorder（接口）+ impl/TokenRecorderImpl**【④】：usage 打点 best-effort（31.8% 实验的数据源）。
- **ConfirmManager（接口）+ impl/ConfirmManagerImpl / ConfirmController**★【①】：token → CompletableFuture 挂起；`POST /api/v1/confirm` 恢复；超时 = 拒绝。单实例内存实现（重启丢确认项视为拒绝，语义安全）。
- **AgentSessionService（接口）+ impl/AgentSessionServiceImpl** + 四实体四 Mapper：chat_session / chat_message / checkpoint / llm_usage（消息即写即落库，重启不丢对话）。
- **HistoryRecallTool**【④ ADR-005】：平台自有内部工具 `recallHistory`——会话记忆"冷层"取回通道。模型自主判断何时按关键词检索当前会话的早期消息与里程碑（chat_message/checkpoint，MySQL LIKE，跨表按时间合并）；AgentRunner 本地执行，不进工具层、不参与路由/确认，角色无关。三层记忆 = 最近 6 条（热）+ checkpoint 摘要（温）+ 全量历史调回（冷），替代向量库 RAG。
- **AgentProperties.java / AgentConfig.java**：`platform.agent.*` 配置与需要原始值参数的 Bean 装配。

---

## 3. common/ web/ resources/

- `common/ApiResponse.java`：统一响应包装 `{code, message, data}`。
- `common/GlobalExceptionHandler.java`：参数校验 400、NoResourceFound 404（防吞）、其余 500。
- `web/PingController.java`：健康探针。
- `resources/static/index.html`：单页控制台（fetch 流解析 SSE，8 类事件渲染，确认按钮）。
- `resources/schema.sql`：6 张表幂等建表（IF NOT EXISTS + information_schema 守卫的 ALTER）。
- `resources/prompts/system.md`：系统提示词（含动态日期占位符）。

## 4. 测试（19 类，98 项）

| 测试类 | 覆盖 |
|---|---|
| OpenApiImportServiceTest (13) | 三类参数解析、slug 兜底、baseUrl 覆盖、非法 JSON、扩展字段声明敏感/角色（裸值与 springdoc 嵌套两种形状）、`$ref` 内联与自引用截断 |
| ToolAccessServiceTest (10) | 闸门 1 全量下发、闸门 2 拒绝/放行、未知工具、非法 JSON、转发失败回喂 |
| GenericHttpForwarderTest (10) | query/body/path 填充、UTF-8、8KB 截断、后端 500、不可达 |
| ToolSelectorChainTest (7) | 阈值休眠、逐环弃权传递、短路、全弃权回落、异常降级、argmax 越界 |
| HistoryRecallToolTest (7) | 关键词检索、跨表合并、条数/长度上限、未命中如实回喂 |
| WattAiToolSelectorTest (5) | 高置信单选、低置信 top-k、到点异常弃权 |
| JwtServiceTest (5) | 签发/解析、过期、篡改签名 |
| ChatAuthFilterTest (5) | 401 三态、放行、admin 范围（403） |
| AgentRunnerTest (5) | 循环三态：执行/同意/拒绝/超步数、recall 本地执行 |
| OpenApiSourceRegistrarTest (5) | 启动注册、基地址随契约来源而非其声明、显式覆盖、来源不可达不阻断启动、空清单跳过 |
| GenericHttpForwarderTimeoutTest (4) | WireMock：读超时、连接中断、非 JSON 响应体、截断边界 |
| DynamicToolRegistryTest (4) | 确认映射、空清单、刷新失败保留旧桶 |
| ConfirmManagerTest (4) | 同意/拒绝/超时/未知 token |
| AuthControllerTest (4) | 登录成功/密码错/用户不存在/参数校验 |
| ToolCatalogControllerTest (3) | 工具目录端点：未登录 401、身份透传、服务凭证不外泄 |
| ContextTrimmerTest (3) | 不裁剪/裁剪结构/摘要计数 |
| ToolRegistryServiceTest (2) | upsert 插入/更新 |
| PlatformApplicationTests (1) | 上下文启动 |
| AgentSelfHealingTest (1) | ★ 端到端：WireMock 业务系统首次 500、二次 200，ReAct 循环不中断、两次观察都回填落库 |

## 5. 一次请求的类名级走线

```
index.html → ChatController.chat
  → AgentSessionService.createSession / loadHistory
  → ContextTrimmer.trim
  → DynamicToolRegistry.callbacks（→ ToolGateway.listTools → ToolAccessService.listForModel 闸门1）
  → ToolSelectorChain.route（工具数 <20 整链休眠）
  → AgentRunner 循环：
      Planner.decide（deepseek-flash，OpenAI 兼容协议）
      → ToolGateway.callTool（进程内方法调用）
        → ToolAccessService.invoke（闸门2 鉴权）
        → GenericHttpForwarder → 业务系统 REST
      → 观察回填 ToolResponseMessage → 下一轮
  → AgentEventEmitter.send（SSE）→ 浏览器
```
