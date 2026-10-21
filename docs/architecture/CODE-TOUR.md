# 代码导览 — agent-platform 逐文件说明

> 用途：给作者本人的复述地图（每个文件一句话 + 面试考点），也供读者按图索骥。
> 共 87 个主文件 + 38 个测试类。标注：★ = 面试深挖高发区；【①②③④】= 对应简历亮点 1-4。

## 推荐通读顺序（约 40 分钟）

```
PlatformApplication → application.yml → ChatController → AgentRunner →
Planner/OpenAiPlanner → ToolGateway/InProcessToolGateway → DynamicToolRegistry →
ToolAccessService → GenericHttpForwarder → OpenApiImportService →
ContextTrimmer → ConfirmManager → MarkdownChunker → KnowledgeIndexService →
KnowledgeSearchService/KnowledgeSearchTool
```

沿一次真实请求读：你在页面说一句话 → ChatController 收到 → AgentRunner 循环 →
`ToolGateway` 进程内调工具层 → 闸门校验 → 转发给 booking 真实执行 → 原路返回 → SSE 推给你。

---

## 1. gateway/ —— 工具层（简历亮点①②）

> 名字还叫 gateway，但实体已经变了：这里不再有协议层，只有"注册什么、放不放行、怎么转发"。
> 与 `agent/` 同处一个 Spring 应用，被 `ToolGateway` 以普通方法调用触达。

### registry/ 工具注册与访问控制
- **OpenApiSourceRegistrar.java**【②】：启动对账的触发器——从来源注册表取激活来源，逐个做完整快照对账（实际逻辑在 `ContractSyncService`）。**"契约可访问"就等于"工具可用"**，且这个等式持续成立：接口新增/修改/删除会在下一次对账收敛，不必重启平台。单个来源失败只告警跳过、保留它上一版工具，不阻断启动。`platform.gateway.sync-on-startup=false` 可关掉启动这一轮（例如平台先于业务系统启动时）。
- **ContractSyncService.java + impl/ContractSyncServiceImpl.java** ★【② 决策 08】：契约对账主流程：抓取 → 解析 → `applySnapshot` → 记录来源状态。失败时只更新 `tool_source.status/last_error`，**保留 `contract_hash` / `tool_count` / `last_success_at`**（last-known-good），不下线任何工具；同名冲突标 `CONFLICT` 并整份放弃。有实际变化时发布 `ToolCatalogChangedEvent`。定时、启动、管理端新建/编辑后的立即对账与手工触发共用一把进程内 `ReentrantLock`：抢不到直接返回 `BUSY`，不排队、不并发写同一份工具集合。每次对账打 Micrometer 指标（`platform.contract.sync` / `platform.contract.sync.duration` / `platform.contract.breaking`）。
- **SourceRegistryService.java + impl/SourceRegistryServiceImpl.java**【决策 09】：来源注册表。`seedFromConfig()` 启动时把 yml 里的 `sources` 种进库；`listAll` / `listActive` / `find`；`create` / `update` / `retire` 供管理端使用。**所有权规则**：`origin=CONFIG` 的来源每次启动按配置刷新；管理员编辑后提升为 `origin=ADMIN`，配置不再覆盖；配置里移除的来源软下线（`enabled=0`、`status=RETIRED`，名下工具 `present=0`）。编辑时关闭"启用"与 `DELETE` 同义——只置 `enabled=0` 只能阻止后续对账，已下发的工具必须一并软下线；重新启用会回到 `PENDING`，由随后的对账按契约恢复工具。配置错误（缺 id、id 重复、非 http(s)）按启动前置条件失败处理，请求参数错误返回 400。
- **SourceConfigSeeder.java**：`@Order(0)` 的 `ApplicationRunner`，只做种子、不访问业务系统，因此 `sync-on-startup=false` 时仍执行；`seed-on-startup=false` 可关闭（测试用）。
- **SourceConflictException.java**：重复创建来源、或试图删除配置管理的来源 → 全局异常映射 409（后者提示改配置）。
- **ContractSyncScheduler.java**：`sync-interval-ms`（默认 5 分钟）周期对账；`sync-enabled=false` 关闭。与启动对账共用同一路径，首次执行延迟一个周期避免重复拉取。
- **ToolSource.java / ToolSourceMapper.java**：`tool_source` 表——每个来源的 `origin`（CONFIG/ADMIN）、`enabled`、`status`（PENDING/OK/FAILED/CONFLICT/RETIRED）、契约地址与可选 `base_url`、服务凭证、`contract_hash`、`tool_count`、上次新增/更新/下线/破坏性变更计数、`last_attempt_at` / `last_success_at` / `last_error`。是"这个来源现在到底怎么了"的观测面。可被管理端清空的字段标 `updateStrategy = ALWAYS`。
- **OpenApiImportService.java** ★【②】：OpenAPI JSON → 工具定义。规则：`operationId`→工具名（缺失则 slug 兜底）、`summary`→描述（模型选工具的依据）、path 参数填 `url_template` 占位、query/requestBody 合并生成 `inputSchema`、**敏感与权限要求一律读业务系统的扩展字段**（`x-require-confirm` / `x-required-permission`，DELETE 天然算敏感）——平台不猜测接口语义，所以"取消"二字本身不触发任何标记。
  `$ref` 必须**就地展开**：工具 schema 是脱离原契约、单独发给模型的片段，`#/components/schemas/...` 在模型侧查不到，OpenAI 兼容接口会以 "Pointer ... does not exist" 拒绝整轮请求。自引用按层截断成宽松 object 骨架，不会无限展开。
- **ToolRegistryService.java + impl/ToolRegistryServiceImpl.java** ★【② 决策 08】：按 gatewayId 作用域查询/更新。`applySnapshot` 是快照对账本体：新增 / 指纹变化则更新（保留 `id`、`createdAt`、人工 `enabled`）/ 未变不动 / 契约里消失则 `present=0` 软下线（不删行，重新出现时复活）。写入前整体判冲突：同名工具仍被其他来源以 `present=1` 声明、或占用了平台保留名（`search_knowledge` / `recallHistory`）时整份放弃，不留半新半旧状态。**工具名在同一网关内是全局命名空间**。新增的写操作缺 `x-required-permission` 只记 `PERMISSION_UNDECLARED` 告警，仍正常注册——是否对全员开放由业务方决定。
- **ContractHashes.java**：契约指纹。只覆盖契约决定的字段（name/method/url_template/description/inputSchema/require_confirm/required_permission），服务凭证单独比对；整份契约的指纹按逐工具指纹排序后再哈希，工具顺序变化不算变更。
- **ToolChangeAnalyzer.java**：变更告警，只告警不阻断。端点变化、新增必填参数、必填参数类型变化、权限收紧/变更/撤销；新增的写操作缺 `x-required-permission` 由 `ToolRegistryServiceImpl` 记 `PERMISSION_UNDECLARED`。完整语义 diff 交给 oasdiff 这类专用工具，平台不重复造轮子。
- **ToolDefinition.java / ToolDefinitionMapper.java**：`tool_definition` 表实体。`present`（契约里是否仍存在，对账写）与 `enabled`（管理员启停，人工写）分离，有效工具 = 两者都为 1。`description`/`required_permission`/`auth_header_*`/`source_key`/`contract_hash` 标了 `updateStrategy = ALWAYS`——MyBatis-Plus 默认跳过 null 字段，会让"业务系统已撤销的声明"永远留在库里。
- **GatewayProperties.java**：`platform.gateway.sources` 配置项（稳定 `id` + 契约地址 + 服务凭证 + 可选 baseUrl）、`sync-enabled` / `sync-on-startup` / `seed-on-startup` / `sync-interval-ms`。配置只做启动种子，运行时来源清单以 `tool_source` 为准。
- **ToolAccessService.java** ★★【① 安全边界】：系统里**唯一的工具访问入口**，两道闸门都在这一个类里。
  `listForModel(gatewayId)` 做**闸门 1·清单组装**——下发该网关**全部启用工具，不按权限裁剪**；权限要求写进工具描述随清单一起给模型，于是模型知道"这个能力存在、但当前身份可能不够"。
  面试点：为什么不在清单里就把没权限的工具裁掉？裁掉的话模型只会答"我没有这个功能"，**把权限问题说成了能力问题**，用户拿不到"你需要找管理员开权限"这个正确结论。
  `invoke(gatewayId, user, name, argsJson)` 做**闸门 2·执行点鉴权**——定位工具 → 权限集合不命中直接返回 `ToolCallResult(true, "权限不足：需要 X 权限")` → 参数非法 JSON 也如实回喂 → 通过后交 `GenericHttpForwarder`。`denial(...)` 是给确认弹窗用的只读预判，**不能替代执行点鉴权**。
  面试点：为什么拒绝要包装成文本结果而不是抛异常？因为抛异常会打断 ReAct 循环，而模型需要"看到失败、自己决定重试还是转述用户"。
- **ToolDefinition.java / ToolDefinitionMapper.java**：`tool_definition` 表实体（url_template、inputSchema、require_confirm、auth_header、required_permission）。`description`/`required_permission`/`auth_header_*` 标了 `updateStrategy = ALWAYS`——MyBatis-Plus 默认跳过 null 字段，会让"业务系统已撤销的声明"永远留在库里。
- **GatewayProperties.java**：见上；配置里的 `sources` 仅供启动种子使用，运行时以数据库注册表为准。

### forward/ 泛化转发
- **GenericHttpForwarder.java** ★★【②】：工具层的"手"。拼装规则：`url_template` 的 `{param}` 从 arguments 取值（URL 编码）；GET/DELETE 余参拼 query；POST/PUT/PATCH 余参作 UTF-8 JSON body；**inputSchema 若声明了 `properties.body` 则自动解包**（导入规则的逆向）；`auth_header_name/value` 存在则附带服务凭证，**另附 `X-On-Behalf-Of-User: {uid}` 声明代表哪个最终用户**（业务系统仅在服务凭证校验通过时才采信）。执行：connect 2s / read 10s；响应 **8KB 截断**（防打爆模型上下文）；非 2xx 与异常统一转为 `ForwardResult(success=false, error=...)`（由模型决策下一步，不抛异常）。用 `exchange()` 而非 `retrieve()` 正是为了保住原始状态码。
- **ForwardProperties.java**：`platform.gateway.forward.connect-timeout-ms` / `read-timeout-ms`（默认 2000 / 10000，原先硬编码在类里）。

### web/ 管理端
- **ProtocolAdminController.java** ★：`GET /admin/tools`（含已下线/已禁用工具，便于观察对账结果）、`PUT /admin/tools/{id}/enabled`（人工启停，对账不会覆盖）。**没有手工导入入口**——工具定义只能由来源对账写入，否则会留下没有来源归属、不参与对账的幽灵工具；接入新接口请登记来源（`/admin/sources`）。整个 `/admin/**` 由 `JwtAuthFilter` 按 URI 映射权限码（`/admin/tools` → `admin:tool`，其余见 `JwtAuthFilter`）——**没有第二套凭证体系**。
- **ToolSourceAdminController.java**【决策 08/009】：`GET /admin/sources`（各来源最后对账状态与计数）、`POST /admin/sources`（新建并立即对账）、`PUT /admin/sources/{sourceKey}`（编辑并立即对账）、`DELETE /admin/sources/{sourceKey}`（软下线并立即下线其工具）、`POST /admin/sources/sync`（全部立即对账）、`POST /admin/sources/{sourceKey}/sync`（单来源，未配置/已下线 404）。**只能管理来源，不能编辑工具**：契约地址、调用基地址、服务凭证、启停；工具定义永远由契约推导。凭证只入不出——查询只返回 `serviceKeyConfigured`。`SourceView` 另带 `staleTools` / `breakingChanges` 明细（由 `tool_source.last_stale_tools` / `last_breaking_changes` 的 JSON 还原），管理台据此展开"上次对账到底下线了哪几个工具、哪条接口发生破坏性变更"。

---

## 2. agent/ —— ReAct 运行时（简历亮点③④）

### loop/ 决策循环
- **AgentRunner.java** ★★【③】：全项目心脏。流程：载入历史（裁剪后）→ 循环 `Planner.decide`（语义决策由模型对工具清单自主完成，平台保持领域中立） → 有 toolCalls 则逐个执行（敏感工具先挂起等确认）→ `ToolResponseMessage` 回填 → 无 toolCalls 则 answer。系统提示词每轮动态注入 `{{CURRENT_DATE}}/{{CURRENT_WEEKDAY}}`（消除模型编造日期）。步数熔断 maxSteps=8。每次工具执行把**工具名、调用参数（截断 2KB）与结果**落 `chat_message`，参数供事后审计（"取消了哪条预订"）。**本次重构里这个类的主循环一行没改**——只把注入的 `McpGatewayClient` 换成 `ToolGateway`。
- **Planner.java / impl/OpenAiPlanner.java** ★【③】：规划器抽象 + OpenAI 兼容实现。关键一行：`internalToolExecutionEnabled(false)`——关闭 Spring AI 隐式工具执行，每轮只取回模型的 toolCalls 决策，**执行权收归循环**（这是事件插桩/确认挂起/权限校验能存在的前提）。默认指向 DeepSeek（`application.yml` 的 `LLM_BASE_URL`/`LLM_MODEL` 可切厂商）。**厂商特有的"思考开关"按模型名前缀走 `extraBody` 透传**——qwen3 要 `enable_thinking=false`，DeepSeek 的 thinking 默认开启、必须显式关（原因见 `docs/notes/Spring-AI-入门与项目对照.md` §5.4：它带 tools 时要求回传 `reasoning_content`，与本循环手动构造 `ToolResponseMessage` 冲突）。**不要**改用 yml 的 `extra-body`，那个键在此版本实测静默失效。

### sse/ 用户入口
- **ChatController.java** ★：`POST /api/v1/chat` → SseEmitter + 显式 `ThreadPoolExecutor`（核心 4/上限 8，有界队列 64，AbortPolicy 拒绝→友好报错，命名线程 `agent-loop-*`）异步跑循环——**长连接不占容器业务线程**。身份在 HTTP 线程用 `UserContext.get()` 取出后**显式传参**给线程池里的循环（ThreadLocal 不跨线程池）。
- **AgentEventEmitter.java**：8 类事件（session/plan/tool_call/tool_result/confirm_request/answer/error/done）的类型化发射器，连接断开静默丢弃（循环照常落库）。`done` 携带本轮 `steps` / `elapsedMs` / `promptTokens` / `completionTokens` / `totalTokens`，前端据此显示"N 步 · X 秒 · Y tokens"。空闲期每 `sse-heartbeat-interval-ms`（默认 15 秒）发一条 SSE comment 保活——确认等待最长 300 秒、单次生成最长 120 秒，没有它会被反代 idle timeout 断链；comment 不产生事件，前端无感。

### tool/ 工具接入
- **ToolGateway.java + impl/InProcessToolGateway.java** ★【①②的缝合线】：Agent 访问工具层的**唯一出口**。
  接口两个方法：`listTools(user)` / `callTool(name, argsJson, user)`，约定**失败也返回文本、不抛异常**。
  进程内实现直接把调用转给 `ToolAccessService`，gatewayId 从 `AgentProperties` 取；失败文本加 `tool error: ` 前缀供模型识别。
  面试点：为什么留接口？——将来工具层若真拆成独立部署，换个实现即可，Agent 循环不用动。目前**没有第二实现**，"进程内"就是唯一形态。
- **DynamicToolRegistry.java** ★：工具层返回的工具定义 → 手写 `GatewayToolCallback`（实现 Spring AI ToolCallback，**仅作 schema 广告位**——`call()` 留给 Spring AI 的隐式路径，本项目不走；真正执行在 `AgentRunner` 手动通道）；`require_confirm` 列映射为敏感标记；缓存 5 分钟（清单全量下发、与权限无关，无需按用户分桶）；刷新是"先构建后整体切换"，失败保留旧桶——注册失败不影响在跑对话。
- **ToolSelector.java / impl/ToolSelectorChain.java / impl/WattAiToolSelector.java**【决策 04】：工具前置路由责任链。`AgentRunner` 进主模型前先过链：工具数未达 `routing-tool-count-threshold`（默认 20）整链休眠、全量直通；达到后按 `routing-handlers` 顺序逐环尝试，节点以 `Optional.empty` 弃权（前置不满足/异常）传下一环，任一环节点接管即短路，全部弃权回落全量——路由层任何故障不影响主流程。wattai 节点：调决策模型（state=用户话术，choices=工具名），高置信单选、低置信 top-k 降级、argmax 落空/异常弃权；凭证只在 `platform.agent.wattai.api-key`（由 `WATTAI_API_KEY` 注入）配置，为空时节点不装配，401/403/404 等不可恢复错误首次失败后自动停用，429/5xx/超时只弃权本次。当前规模下链休眠（收益为负），扩展位预留 jev/本地语义路由。

### auth/ 用户身份与 RBAC
- **JwtService.java + AuthController/AuthService/AuthServiceImpl**：`POST /api/v1/auth/login` 校验密码后签发 JWT——**claims 只有 `uid`（+ iat/exp）**，角色与权限不写进 token。登录响应另带角色与权限列表，仅用于前端渲染入口。解析失败抛 `JwtException`。`InvalidCredentialsException` 由全局异常处理转 401。
- **RbacService.java + impl/RbacServiceImpl.java** ★：授权数据源。`loadPrincipal(uid)` 一次读用户状态、单角色与 `app_role_permission`，构造 `UserPrincipal(uid, role, permissions)`；账号停用返回 empty（过滤器转 401）。`listRoles` / `replacePermissions` / `knownPermissions` 供管理端矩阵，内置权限码与工具契约声明的权限码合并展示。
- **RbacSeeder.java**：启动时补默认角色与默认权限。`app_role.seeded` 标记是否种过——只在未种过时写入，管理员改过之后重启不会复活默认值；`platform.rbac.seed-on-startup=false` 可关闭。
- **JwtAuthFilter.java** ★：手写过滤器（不引入 Spring Security）。拦 `/api/v1/chat`、`/api/v1/confirm`、`/api/v1/tools`、`/api/v1/sessions/**` 与 `/admin/**`；解析 uid 后**每请求回库** `loadPrincipal`，写入 `UserContext`（请求结束清理），其余路径放行。`/admin/**` 按 URI 映射权限码校验（`/admin/rbac` → `admin:rbac`、`/admin/knowledge` → `admin:knowledge`、`/admin/sources` → `admin:source`、`/admin/tools` → `admin:tool`，其余 → `admin:console`），403 输出"需要 X 权限"。401/403 在过滤器层直接写 JSON（尚未进 `@ControllerAdvice` 范围）。
- **RbacAdminController.java**：`GET /admin/rbac/roles`（角色 + 权限）、`GET /admin/rbac/permissions`（可选权限码）、`PUT /admin/rbac/roles/{code}/permissions`（覆盖式保存）。未知角色 404。不做角色 CRUD 与用户角色分配。
- **UserPrincipal.java / UserContext.java**：身份 record（uid + role + permissions，`hasPermission` 命中 `*` 或精确权限码）与请求级 ThreadLocal。
- **entity/AppUser.java / AppRole.java / AppRolePermission.java + 对应 Mapper**：`app_user`（username / password_hash / role / status）、`app_role`（code / name / description / seeded）、`app_role_permission`（role_code / permission）。
- **WebConfig.java**：只注册一个 `JwtAuthFilter`（order=1）。

### context/ confirm/ memory/
- **ContextTrimmer.java** ★【④】：chars/2 粗估超阈值（默认 6000）→ 保留 system + 最近 6 条，更早内容替换为计数标记 SystemMessage（落 checkpoint 表，不含事实）。确定性规则，单测友好。
- **TokenRecorder（接口）+ impl/TokenRecorderImpl**【④】：usage 打点 best-effort（31.8% 实验的数据源）。
- **ConfirmManager（接口）+ impl/ConfirmManagerImpl / ConfirmController**★【①】：token → CompletableFuture 挂起；`POST /api/v1/confirm` 恢复；超时 = 拒绝。单实例内存实现（重启丢确认项视为拒绝，语义安全）。
- **AgentSessionService（接口）+ impl/AgentSessionServiceImpl** + 四实体四 Mapper：chat_session / chat_message / checkpoint / llm_usage（消息即写即落库，重启不丢对话）。
- **SessionController.java**：历史会话入口。`GET /api/v1/sessions` 列当前用户最近 50 条（标题取各自首条 USER 消息，无则"新对话"）；`GET /api/v1/sessions/{key}/messages` 只回 USER/ASSISTANT 供回放，工具调用轨迹不进入历史；`GET /api/v1/sessions/{key}/usage` 汇总该会话 `llm_usage`（prompt/completion/total tokens 与调用次数）；`DELETE /api/v1/sessions/{key}` 删除本人会话并级联清消息与 checkpoint。归属校验与续聊共用 `ownsSession`——`user_id` 为 NULL 的存量会话放行，他人的返回 403，不存在返回 404。
- **HistoryRecallTool**【④ 决策 05】：平台自有内部工具 `recallHistory`——会话记忆"冷层"取回通道。模型自主判断何时按关键词检索当前会话的早期消息与里程碑（chat_message/checkpoint，MySQL LIKE，跨表按时间合并）；AgentRunner 本地执行，不进工具层、不参与路由/确认，角色无关。三层记忆 = 最近 6 条（热）+ checkpoint 压缩标记（温）+ 全量历史调回（冷），替代向量库 RAG。
- **AgentProperties.java / AgentConfig.java**：`platform.agent.*` 配置与需要原始值参数的 Bean 装配。

---

## 3. knowledge/ —— 知识库 RAG（Agentic Retrieval）

> 与 `agent/`、`gateway/` 平级的独立模块；整包受 `EMBEDDING_API_KEY` 开关控制，未配置时所有 bean 不装配。
> 设计权衡见 `docs/architecture/KNOWLEDGE-RAG.md`。

- **KnowledgeConfig.java**：手动装配 pgvector 与 embedding 模型（不走 starter，避免自动配置抓主 DataSource / 复用 DeepSeek 的 base-url）。Postgres 连不上时抛带指引的异常（fail fast，不静默摘掉知识库）。
- **KnowledgeJdbc.java**：私有 Hikari 连接池（`knowledge-pg`）。**故意不注册为 DataSource bean**——第二个 DataSource 会让 MyBatis 的 `@ConditionalOnSingleCandidate` 失去唯一候选。
- **chunk/MarkdownChunker.java** ★：纯函数。H2 主切分 → 超长节按 H3 → 段落 → 句子 → 硬切逐级降级；表格按行拆且重复表头，代码块不拆；过小块并入相邻块（导语并入首节时采用首节标题，否则"住宿标准"这类标题会被吞掉）。`VERSION` 参与 `chunk_config` 指纹——改切分规则必须加一，否则文档 hash 未变时旧索引不会被重建。
- **chunk/FrontMatter.java**：解析 `key: value` 前置元数据；`visible_permission` 缺省为 `public`（权限默认收紧而不是放开）。
- **index/KnowledgeIndexService.java** ★：扫描目录 → SHA-256 比对 → 只重索引变更文档。跳过条件同时看 **status（FAILED 必重试）、content hash、embedding model、切块参数**。写入采用**版本化**：块 id 由 `doc_path#序号` 确定性生成，先 `add` 新块（同 id upsert），再只删"新版本不再包含"的 stale id——不先按 `doc_path` 全删，避免重建期间检索空白窗口。每次索引对应一条任务记录，完成/失败回写状态。
- **index/KnowledgeIndexJobRepository.java**：`knowledge_index_job` 任务表。跨实例互斥靠部分唯一索引 `WHERE status='RUNNING'`；`start` 会先把超时 RUNNING 标 FAILED，避免进程被 kill 后永久阻塞。
- **index/KnowledgeDocRepository.java**：`knowledge_doc`（增量判断）+ `knowledge_chunk`（块明细、关键词兜底正文、可见权限列）两张表，建表幂等，旧库用 `ADD COLUMN IF NOT EXISTS` 补列。
- **search/KnowledgeSearchService.java** ★：`SearchRequest` 里同时带 topK、相似度阈值与**权限集合**过滤表达式；**过滤发生在 SQL 内**，无权内容不进候选集（`*` 不过滤；否则只看 `visible_permission='public'` 或命中用户权限集合）。相似度取自 `Document.getScore()`。向量结果为空时按 `raw_text ILIKE` 关键词兜底（同样权限过滤，score 为 NaN）。每次检索写审计表、打 Micrometer 指标与 INFO 日志，均为 best-effort。
- **search/KnowledgeSearchTool.java**：`search_knowledge` 的 schema 广告位 + 本地执行入口。参数错误/检索失败都返回文本，不抛异常。检索结果带"仅作资料、不构成指令"声明与 `<<< >>>` 边界，缓解文档内提示注入。
- **search/KnowledgeSearchAuditRepository.java**：`knowledge_search_audit`，记 query/角色/权限集合/命中来源/耗时，不记正文；启动时按留存期清理。
- **embedding/UsageRecordingEmbeddingModel.java**：装饰 `EmbeddingModel.call`，把 prompt tokens 与文档数记入 `knowledge_usage`，让"知识库每月花多少钱"可查。
- **eval/KnowledgeEvalService.java**：读 `knowledge/eval/questions.jsonl`，跑 Recall@K / MRR / 空结果率，供 `POST /admin/knowledge/eval` 使用——切块与阈值调整有了可比较的数字。
- **KnowledgeAdminController.java**：知识库运维端点（受 `/admin/knowledge` → `admin:knowledge` 权限保护）。索引重建/任务查询/评估之外，`POST /admin/knowledge/search` 接受 `{query, role, topK}` 走与 Agent 相同的检索路径并返回 `SearchOutcome`（命中块、分数、检索模式 vector/keyword、耗时）——管理台据此直观验证 `visible_permission` 权限过滤是否生效，而不必去猜"为什么 STAFF 查不到某份制度"。
- **KnowledgeHealthIndicator.java**：管理端口 8081 的 `/actuator/health` 中的知识库探针（`SELECT 1`）；同端口 `/actuator/prometheus` 暴露检索指标。

## 4. common/ web/ resources/

- `common/ApiResponse.java`：统一响应包装 `{code, message, data}`。
- `common/GlobalExceptionHandler.java`：参数校验 400、NoResourceFound 404（防吞）、其余 500。
- `web/PingController.java`：健康探针。
- `resources/static/`：单页控制台，同源静态资源、随 jar 一起部署。按职责拆成 `index.html`（结构）、`styles.css`、`js/core.js`（全局状态与通用工具）、`js/chat.js`（登录/会话/确认/SSE）、`js/admin.js`（管理台）、`js/boot.js`（启动判断）；普通 `<script>` 按序加载、共享全局作用域，不引入构建工具链（fetch 流解析 SSE，8 类事件渲染，确认按钮，工具调用耗时与回合/会话 token 统计）。拥有 `admin:console` 权限的账号登录后多出「管理台」入口（直连无权端点仍 403），抽屉内分四个 tab：**契约来源**（状态、对账明细、增删改与立即对账）、**工具目录**（按来源分组、搜索/过滤、人工启停）、**知识检索**（以指定角色跑真实检索，观察权限过滤与命中分数）、**角色权限**（角色列表与权限矩阵，覆盖式保存）。
- `resources/schema.sql`：9 张表幂等建表（IF NOT EXISTS + information_schema 守卫的 ALTER）。`chat_message.tool_arguments` 保存 TOOL 消息的调用参数（审计用），存量库由守卫 ALTER 补列。
- `resources/prompts/system.md`：系统提示词（含动态日期占位符）。
- `knowledge/`：5 份制度语料（4 份 `public` + 1 份 `admin:policy`）+ 评估题库；镜像内带一份，compose 另把宿主目录只读挂到 `/app/knowledge`，改文档后重启或 reindex 即生效，不必重建镜像。

## 5. 测试（44 类，289 项）

| 测试类 | 覆盖 |
|---|---|
| OpenApiImportServiceTest (15) | 三类参数解析、slug 兜底、baseUrl 覆盖、非法 JSON、扩展字段声明敏感/角色（裸值与 springdoc 嵌套两种形状）、`$ref` 内联与自引用截断、参数 enum/items/default 保真、`content` 形式参数 |
| ToolAccessServiceTest (13) | 闸门 1 全量下发、权限要求随描述、闸门 2 拒绝/放行/`*` 通配、未知工具、非法 JSON、转发失败回喂、确认前权限预判（拒绝/放行/未知工具） |
| GenericHttpForwarderTest (13) | query/body/path 填充、UTF-8、8KB 截断（含代理对不切断）、后端 500（明确失败）、不可达（请求未送达）、本地构造失败（调用未发出，不误判为结果未知） |
| ToolSelectorChainTest (7) | 阈值休眠、逐环弃权传递、短路、全弃权回落、异常降级、argmax 越界 |
| HistoryRecallToolTest (7) | 关键词检索、跨表合并、条数/长度上限、未命中如实回喂 |
| WattAiToolSelectorTest (6) | 高置信单选、低置信 top-k、到点异常弃权、读超时弃权 |
| JwtServiceTest (6) | 签发/解析 uid、过期、篡改签名、token 不含角色 claim、缺失/过短密钥拒绝启动 |
| JwtAuthFilterTest (8) | 拦截范围、`/admin/**` URI → 权限码、登录直通、缺 token 401、停用账号 401、无权 403、`*` 放行且链内可见身份、请求结束清理 |
| RbacServiceImplTest (7) | 身份加载（正常/停用/不存在/空角色）、权限映射、覆盖式替换、未知角色拒绝、可选权限合并、角色列表组装 |
| RbacSeederTest (3) | 缺失角色补种、已种过不覆盖人工配置、未种过补默认 |
| RbacAdminControllerTest (4) | 角色/权限查询、覆盖保存、未知角色 404 |
| ChatAuthFilterTest (6) | 401 三态、放行、admin 范围（403）、同会话并发拒绝 |
| AgentSessionServiceImplTest (14) | 会话创建与复用、历史加载（最近 200 条按时间正序）、消息落库（含工具名与调用参数）、checkpoint 读取、用量汇总、删除本人会话级联清理、拒绝他人/不存在的会话 |
| SessionControllerTest (11) | 会话列表/历史/用量/删除端点：鉴权、归属校验、404、删除本人返回 204 |
| AgentRunnerTest (6) | 循环三态：执行/同意/拒绝/超步数、recall 本地执行、权限不足时跳过确认且不执行 |
| OpenApiSourceRegistrarTest (1) | 启动入口触发全量对账 |
| ContractSyncServiceTest (11) | 成功对账与凭证绑定、baseUrl 覆盖、抓取/解析失败保留 last-known-good、冲突不发布事件且 `last_error` 含冲突工具与占用来源、监听器失败不影响成功状态、从注册表取来源、单来源对账、并发返回 BUSY、完成后释放锁 |
| ToolSourceAdminControllerTest (9) | HTTP 契约：400 参数校验、404 不存在、409 重复创建/下线配置来源、创建后立即对账、凭证只披露 `serviceKeyConfigured` |
| SourceRegistryServiceTest (10) | 配置种子插入/刷新/不覆盖 ADMIN、配置移除软下线工具、id 缺失与重复 fail fast、重复创建 409、编辑提升 ADMIN 且凭证 null 保持、编辑停用即下线其工具、重新启用回到 PENDING、retire 拒绝 CONFIG / 软下线 ADMIN、查询不回显凭证 |
| SourceConfigSeederTest (1) | 启动种子器调用注册表 |
| ToolChangeAnalyzerTest (9) | 新增必填参数、可选转必填、必填类型变化、端点变化、权限收紧/变更/撤销、无变化、非法 schema |
| GenericHttpForwarderTimeoutTest (6) | WireMock：读超时（读操作可重试 / 写操作禁止直接重试）、连接中断、504、非 JSON 响应体、截断边界 |
| DynamicToolRegistryTest (4) | 确认映射、空清单、刷新失败保留旧桶 |
| ConfirmManagerTest (5) | 同意/拒绝/超时/未知 token/发起者绑定；返回前清理挂起项 |
| AgentEventEmitterTest (4) | 心跳 comment、定时保活、关闭后停止、写失败标记断链 |
| ChatRequestValidationTest (3) | 消息空白拒绝、8000 字边界、超长拒绝 |
| SessionLockRegistryTest (3) | 同会话互斥、不同会话并行、释放未知 key 幂等 |
| AuthControllerTest (6) | 登录成功/密码错/用户不存在/参数校验、旧 SHA-256 摘要登录后升级、连续失败限流 |
| PasswordHasherTest (5) | PBKDF2 加盐往返、随机盐、旧摘要识别与兼容、非法格式/迭代上限拒绝 |
| ToolCatalogControllerTest (3) | 工具目录端点：未登录 401、身份透传、服务凭证不外泄 |
| ContextTrimmerTest (3) | 不裁剪/裁剪结构/摘要计数 |
| ToolRegistryServiceTest (13) | 快照新增/未变不重写/更新保留人工禁用/软下线/已下线不重复处理/跨来源冲突整份放弃/已下线可被接管/契约内重名、保留名拒绝、新增写操作缺声明告警、启停 |
| PlatformApplicationTests (1) | 上下文启动 |
| AgentConfigTest (1) | LLM RestClient 连接/读超时配置 |
| LoginThrottleTest (4) | 窗口内失败计数、成功清零、窗口滑出解锁、用户名大小写归一 |
| AgentSelfHealingTest (1) | ★ 端到端：WireMock 业务系统首次 500、二次 200，ReAct 循环不中断、两次观察都回填落库 |
| MarkdownChunkerTest (10) | 按 H2 切分、前缀、H3 降级、硬切重叠、小段合并、导语并入首节时保留小节名、代码块/表格、front-matter |
| KnowledgeIndexServiceTest (10) | 首次索引与元数据、hash 未变跳过、force 重建、FAILED 重试、切块参数变化重建、删除清理、失败标记、版本化写入顺序、任务完成/占用 |
| KnowledgeIndexJobRepositoryTest (3) | 全部失败标 FAILED（避免任务"成功"但检索全空）、部分失败仍 SUCCEEDED、全跳过仍 SUCCEEDED |
| KnowledgeSearchServiceTest (10) | 权限过滤表达式、`*` 不过滤、显式权限集合而非角色名、topK 上限、空 query、结果与分数映射、关键词兜底与 LIKE 转义、审计写入、审计失败不影响检索 |
| KnowledgeSearchToolTest (7) | 空/非法参数、空结果、来源与注入边界、默认 topK、失败降级、callback 广告位 |
| KnowledgeAdminControllerTest (10) | 未启用 503、异步提交返回 jobId、任务占用 409、任务查询与 404、评估报告与题库缺失 400、检索沙盒（空 query 400、按请求角色返回命中与模式） |
| KnowledgeEvalServiceTest (4) | Recall@K / MRR / 空结果率、逐题排名、按题目声明角色检索、题库缺失 |
| UsageRecordingEmbeddingModelTest (3) | tokens 与文档数记录、无 usage 记 0、打点失败不影响 embedding |

## 6. 一次请求的类名级走线

```
index.html → ChatController.chat
  → AgentSessionService.createSession / loadHistory
  → ContextTrimmer.trim
  → DynamicToolRegistry.callbacks（→ ToolGateway.listTools → ToolAccessService.listForModel 闸门1）
  → ToolSelectorChain.route（工具数 <20 整链休眠）
  → AgentRunner 循环：
      Planner.decide（deepseek-flash，OpenAI 兼容协议）
      → 内部工具？recallHistory / search_knowledge 本地执行（不进网关）
          search_knowledge → KnowledgeSearchService → pgvector（权限过滤在 SQL 内）
                            → 空结果时 keyword 兜底 → 审计 + metrics
      → ToolGateway.callTool（进程内方法调用）
        → ToolAccessService.invoke（闸门2 鉴权）
        → GenericHttpForwarder → 业务系统 REST
      → 观察回填 ToolResponseMessage → 下一轮
  → AgentEventEmitter.send（SSE）→ 浏览器
```
