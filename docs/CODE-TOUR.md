# 代码导览 — agent-platform 逐文件说明

> 用途：给作者本人的复述地图（每个文件一句话 + 面试考点），也供读者按图索骥。
> 共 79 个主文件 + 26 个测试类。标注：★ = 面试深挖高发区；【①②③④】= 对应简历亮点 1-4。

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
- **OpenApiSourceRegistrar.java**【②】：启动对账的触发器——按 `platform.gateway.sources` 让每个来源做一次完整快照对账（实际逻辑在 `ContractSyncService`）。**"契约可访问"就等于"工具可用"**，且这个等式持续成立：接口新增/修改/删除会在下一次对账收敛，不必重启平台。单个来源失败只告警跳过、保留它上一版工具，不阻断启动。`platform.gateway.sync-on-startup=false` 可关掉启动这一轮（例如平台先于业务系统启动时）。
- **ContractSyncService.java + impl/ContractSyncServiceImpl.java** ★【② ADR-008】：契约对账主流程：抓取 → 解析 → `applySnapshot` → 记录来源状态。失败时只更新 `tool_source.status/last_error`，**保留 `contract_hash` / `tool_count` / `last_success_at`**（last-known-good），不下线任何工具；同名冲突标 `CONFLICT` 并整份放弃。有实际变化时发布 `ToolCatalogChangedEvent`。来源 `id` 未配置时按 host+端口兜底并告警（容器内外会得到不同键，不推荐依赖）。
- **ContractSyncScheduler.java**：`sync-interval-ms`（默认 5 分钟）周期对账；`sync-enabled=false` 关闭。与启动对账共用同一路径，首次执行延迟一个周期避免重复拉取。
- **ToolSource.java / ToolSourceMapper.java**：`tool_source` 表——每个来源的 `status`（PENDING/OK/FAILED/CONFLICT）、`contract_hash`、`tool_count`、上次新增/更新/下线/破坏性变更计数、`last_attempt_at` / `last_success_at` / `last_error`。是"这个来源现在到底怎么了"的观测面。
- **OpenApiImportService.java** ★【②】：OpenAPI JSON → 工具定义。规则：`operationId`→工具名（缺失则 slug 兜底）、`summary`→描述（模型选工具的依据）、path 参数填 `url_template` 占位、query/requestBody 合并生成 `inputSchema`、**敏感与角色一律读业务系统的扩展字段**（`x-require-confirm` / `x-required-role`，DELETE 天然算敏感）——平台不猜测接口语义，所以"取消"二字本身不再触发任何标记。
  `$ref` 必须**就地展开**：工具 schema 是脱离原契约、单独发给模型的片段，`#/components/schemas/...` 在模型侧查不到，OpenAI 兼容接口会以 "Pointer ... does not exist" 拒绝整轮请求。自引用按层截断成宽松 object 骨架，不会无限展开。
- **ToolRegistryService.java + impl/ToolRegistryServiceImpl.java** ★【② ADR-008】：按 gatewayId 作用域查询/更新。`applySnapshot` 是快照对账本体：新增 / 指纹变化则更新（保留 `id`、`createdAt`、人工 `enabled`）/ 未变不动 / 契约里消失则 `present=0` 软下线（不删行，重新出现时复活）。写入前整体判冲突：同名工具仍被其他来源以 `present=1` 声明时整份放弃，不留半新半旧状态。**工具名在同一网关内是全局命名空间**。
- **ContractHashes.java**：契约指纹。只覆盖契约决定的字段（name/method/url_template/description/inputSchema/require_confirm/required_role），服务凭证单独比对；整份契约的指纹按逐工具指纹排序后再哈希，工具顺序变化不算变更。
- **ToolChangeAnalyzer.java**：破坏性变更识别，只告警不阻断。四类：端点变化、新增必填参数、必填参数类型变化、角色收紧/变更。完整语义 diff 交给 oasdiff 这类专用工具，平台不重复造轮子。
- **ToolDefinition.java / ToolDefinitionMapper.java**：`tool_definition` 表实体。`present`（契约里是否仍存在，对账写）与 `enabled`（管理员启停，人工写）分离，有效工具 = 两者都为 1。`description`/`required_role`/`auth_header_*`/`source_key`/`contract_hash` 标了 `updateStrategy = ALWAYS`——MyBatis-Plus 默认跳过 null 字段，会让"业务系统已撤销的声明"永远留在库里。
- **GatewayProperties.java**：`platform.gateway.sources` 配置项（稳定 `id` + 契约地址 + 服务凭证 + 可选 baseUrl）、`sync-enabled` / `sync-on-startup` / `sync-interval-ms`。
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
- **ProtocolAdminController.java** ★：`POST /admin/protocols/import`（gatewayId + openapi + baseUrl + 服务级 auth header；**角色与敏感度不在这里传**——它们是业务系统写在契约里的声明；带可选 `sourceKey` 时按快照对账，不带则沿用逐条 upsert）、`GET /admin/tools`（含已下线/已禁用工具，便于观察对账结果）、`PUT /admin/tools/{id}/enabled`（人工启停，对账不会覆盖）。整个 `/admin/**` 由 `JwtAuthFilter` 要求 ADMIN 角色——**没有第二套凭证体系**。
- **ToolSourceAdminController.java**【ADR-008】：`GET /admin/sources`（各来源最后对账状态与计数）、`POST /admin/sources/sync`（全部立即对账）、`POST /admin/sources/{sourceKey}/sync`（单来源，未配置 404）。用于排障和"改完契约立刻生效"的演示，与定时任务走同一条路径。

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
- **JwtAuthFilter.java** ★：手写过滤器（不引入 Spring Security）。拦 `/api/v1/chat`、`/api/v1/confirm`、`/api/v1/tools`、`/api/v1/sessions/**` 与 `/admin/**`，解出 `UserPrincipal` 写入 `UserContext`（请求结束清理），其余路径放行；`/admin/**` 额外要求 `ADMIN` 角色，403 输出"需要 ADMIN 角色"。401/403 在过滤器层直接写 JSON（尚未进 `@ControllerAdvice` 范围）。
- **UserPrincipal.java / UserContext.java**：身份 record（uid + role）与请求级 ThreadLocal。
- **entity/AppUser.java / mapper/AppUserMapper.java**：`app_user` 表（username / password_hash / role / status）。
- **WebConfig.java**：只注册一个 `JwtAuthFilter`（order=1）。

### context/ confirm/ memory/
- **ContextTrimmer.java** ★【④】：chars/2 粗估超阈值（默认 6000）→ 保留 system + 最近 6 条，更早内容压成摘要 SystemMessage（落 checkpoint 表）。确定性规则，单测友好。
- **TokenRecorder（接口）+ impl/TokenRecorderImpl**【④】：usage 打点 best-effort（31.8% 实验的数据源）。
- **ConfirmManager（接口）+ impl/ConfirmManagerImpl / ConfirmController**★【①】：token → CompletableFuture 挂起；`POST /api/v1/confirm` 恢复；超时 = 拒绝。单实例内存实现（重启丢确认项视为拒绝，语义安全）。
- **AgentSessionService（接口）+ impl/AgentSessionServiceImpl** + 四实体四 Mapper：chat_session / chat_message / checkpoint / llm_usage（消息即写即落库，重启不丢对话）。
- **SessionController.java**：历史会话入口。`GET /api/v1/sessions` 列当前用户最近 50 条（标题取各自首条 USER 消息，无则"新对话"）；`GET /api/v1/sessions/{key}/messages` 只回 USER/ASSISTANT 供回放，工具调用轨迹不进入历史。归属校验与续聊共用 `ownsSession`——`user_id` 为 NULL 的存量会话放行，他人的返回 403。
- **HistoryRecallTool**【④ ADR-005】：平台自有内部工具 `recallHistory`——会话记忆"冷层"取回通道。模型自主判断何时按关键词检索当前会话的早期消息与里程碑（chat_message/checkpoint，MySQL LIKE，跨表按时间合并）；AgentRunner 本地执行，不进工具层、不参与路由/确认，角色无关。三层记忆 = 最近 6 条（热）+ checkpoint 摘要（温）+ 全量历史调回（冷），替代向量库 RAG。
- **AgentProperties.java / AgentConfig.java**：`platform.agent.*` 配置与需要原始值参数的 Bean 装配。

---

## 3. knowledge/ —— 知识库 RAG（Agentic Retrieval）

> 与 `agent/`、`gateway/` 平级的独立模块；整包受 `EMBEDDING_API_KEY` 开关控制，未配置时所有 bean 不装配。
> 设计权衡见 `docs/KNOWLEDGE-RAG.md`。

- **KnowledgeConfig.java**：手动装配 pgvector 与 embedding 模型（不走 starter，避免自动配置抓主 DataSource / 复用 DeepSeek 的 base-url）。Postgres 连不上时抛带指引的异常（fail fast，不静默摘掉知识库）。
- **KnowledgeJdbc.java**：私有 Hikari 连接池（`knowledge-pg`）。**故意不注册为 DataSource bean**——第二个 DataSource 会让 MyBatis 的 `@ConditionalOnSingleCandidate` 失去唯一候选。
- **chunk/MarkdownChunker.java** ★：纯函数。H2 主切分 → 超长节按 H3 → 段落 → 句子 → 硬切逐级降级；表格按行拆且重复表头，代码块不拆；过小块并入相邻块（导语并入首节时采用首节标题，否则"住宿标准"这类标题会被吞掉）。`VERSION` 参与 `chunk_config` 指纹——改切分规则必须加一，否则文档 hash 未变时旧索引不会被重建。
- **chunk/FrontMatter.java**：解析 `key: value` 前置元数据；`visible_role` 缺省为 `PUBLIC`（权限默认收紧而不是放开）。
- **index/KnowledgeIndexService.java** ★：扫描目录 → SHA-256 比对 → 只重索引变更文档。跳过条件同时看 **status（FAILED 必重试）、content hash、embedding model、切块参数**。写入采用**版本化**：块 id 由 `doc_path#序号` 确定性生成，先 `add` 新块（同 id upsert），再只删"新版本不再包含"的 stale id——不先按 `doc_path` 全删，避免重建期间检索空白窗口。每次索引对应一条任务记录，完成/失败回写状态。
- **index/KnowledgeIndexJobRepository.java**：`knowledge_index_job` 任务表。跨实例互斥靠部分唯一索引 `WHERE status='RUNNING'`；`start` 会先把超时 RUNNING 标 FAILED，避免进程被 kill 后永久阻塞。
- **index/KnowledgeDocRepository.java**：`knowledge_doc`（增量判断）+ `knowledge_chunk`（块明细、关键词兜底正文、角色列）两张表，建表幂等，旧库用 `ADD COLUMN IF NOT EXISTS` 补列。
- **search/KnowledgeSearchService.java** ★：`SearchRequest` 里同时带 topK、相似度阈值与角色过滤表达式；**过滤发生在 SQL 内**，无权内容不进候选集。相似度取自 `Document.getScore()`。向量结果为空时按 `raw_text ILIKE` 关键词兜底（同样角色过滤，score 为 NaN）。每次检索写审计表、打 Micrometer 指标与 INFO 日志，均为 best-effort。
- **search/KnowledgeSearchTool.java**：`search_knowledge` 的 schema 广告位 + 本地执行入口。参数错误/检索失败都返回文本，不抛异常。检索结果带"仅作资料、不构成指令"声明与 `<<< >>>` 边界，缓解文档内提示注入。
- **search/KnowledgeSearchAuditRepository.java**：`knowledge_search_audit`，记 query/角色/命中来源/耗时，不记正文；启动时按留存期清理。
- **embedding/UsageRecordingEmbeddingModel.java**：装饰 `EmbeddingModel.call`，把 prompt tokens 与文档数记入 `knowledge_usage`，让"知识库每月花多少钱"可查。
- **eval/KnowledgeEvalService.java**：读 `knowledge/eval/questions.jsonl`，跑 Recall@K / MRR / 空结果率，供 `POST /admin/knowledge/eval` 使用——切块与阈值调整有了可比较的数字。
- **KnowledgeHealthIndicator.java**：`/actuator/health` 中的知识库探针（`SELECT 1`）；`/actuator/prometheus` 暴露检索指标。

## 4. common/ web/ resources/

- `common/ApiResponse.java`：统一响应包装 `{code, message, data}`。
- `common/GlobalExceptionHandler.java`：参数校验 400、NoResourceFound 404（防吞）、其余 500。
- `web/PingController.java`：健康探针。
- `resources/static/index.html`：单页控制台（fetch 流解析 SSE，8 类事件渲染，确认按钮）。
- `resources/schema.sql`：6 张表幂等建表（IF NOT EXISTS + information_schema 守卫的 ALTER）。
- `resources/prompts/system.md`：系统提示词（含动态日期占位符）。
- `knowledge/`：5 份制度语料（4 份 PUBLIC + 1 份 ADMIN）+ 评估题库；镜像内带一份，compose 另把宿主目录只读挂到 `/app/knowledge`，改文档后重启或 reindex 即生效，不必重建镜像。

## 5. 测试（30 类，185 项）

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
| OpenApiSourceRegistrarTest (1) | 启动入口触发全量对账 |
| ContractSyncServiceTest (9) | 成功对账与凭证绑定、baseUrl 覆盖、抓取/解析失败保留 last-known-good、冲突不发布事件、监听器失败不影响成功状态、按 id 对账、无 id 兜底键、未知来源 |
| ToolChangeAnalyzerTest (8) | 新增必填参数、可选转必填、必填类型变化、端点变化、角色收紧/变更、无变化、非法 schema |
| GenericHttpForwarderTimeoutTest (4) | WireMock：读超时、连接中断、非 JSON 响应体、截断边界 |
| DynamicToolRegistryTest (4) | 确认映射、空清单、刷新失败保留旧桶 |
| ConfirmManagerTest (4) | 同意/拒绝/超时/未知 token |
| AuthControllerTest (4) | 登录成功/密码错/用户不存在/参数校验 |
| ToolCatalogControllerTest (3) | 工具目录端点：未登录 401、身份透传、服务凭证不外泄 |
| ContextTrimmerTest (3) | 不裁剪/裁剪结构/摘要计数 |
| ToolRegistryServiceTest (12) | upsert 插入/更新、快照新增/未变不重写/更新保留人工禁用/软下线/已下线不重复处理/跨来源冲突整份放弃/已下线可被接管/契约内重名、启停 |
| PlatformApplicationTests (1) | 上下文启动 |
| AgentSelfHealingTest (1) | ★ 端到端：WireMock 业务系统首次 500、二次 200，ReAct 循环不中断、两次观察都回填落库 |
| MarkdownChunkerTest (10) | 按 H2 切分、前缀、H3 降级、硬切重叠、小段合并、导语并入首节时保留小节名、代码块/表格、front-matter |
| KnowledgeIndexServiceTest (10) | 首次索引与元数据、hash 未变跳过、force 重建、FAILED 重试、切块参数变化重建、删除清理、失败标记、版本化写入顺序、任务完成/占用 |
| KnowledgeIndexJobRepositoryTest (3) | 全部失败标 FAILED（避免任务"成功"但检索全空）、部分失败仍 SUCCEEDED、全跳过仍 SUCCEEDED |
| KnowledgeSearchServiceTest (9) | 角色过滤表达式、ADMIN 不过滤、topK 上限、空 query、结果与分数映射、关键词兜底与 LIKE 转义、审计写入、审计失败不影响检索 |
| KnowledgeSearchToolTest (7) | 空/非法参数、空结果、来源与注入边界、默认 topK、失败降级、callback 广告位 |
| KnowledgeAdminControllerTest (7) | 未启用 503、异步提交返回 jobId、任务占用 409、任务查询与 404、评估报告与题库缺失 400 |
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
          search_knowledge → KnowledgeSearchService → pgvector（角色过滤在 SQL 内）
                            → 空结果时 keyword 兜底 → 审计 + metrics
      → ToolGateway.callTool（进程内方法调用）
        → ToolAccessService.invoke（闸门2 鉴权）
        → GenericHttpForwarder → 业务系统 REST
      → 观察回填 ToolResponseMessage → 下一轮
  → AgentEventEmitter.send（SSE）→ 浏览器
```
