# FRAMEWORK-NOTES：写给"会传统 Java 后端、没学过 Spring AI/RPC"的你

> 阅读方式：每节 = 白话解释 + 本项目对应类。读完再回头看代码，注释里的术语都能对上。

## 1. Spring AI：一个把"调大模型"封装成调本地服务的框架

- **ChatModel vs ChatClient**：ChatModel 是底层 HTTP 客户端（负责发 /chat/completions 请求、解析响应，类似你手写的 `RestTemplate + DTO`）；ChatClient 是套在它外面的流式门面（链式拼 prompt/options，类似 MyBatis 的 SqlSession 之于 JDBC）。本项目在 `impl/OpenAiPlanner` 用 `ChatClient.builder(chatModel).build()` 构造。
- **Message 体系**（`org.springframework.ai.chat.messages`）：对话就是 `List<Message>`，四种角色对应四种类——
  - `SystemMessage`：给模型的"岗位说明书"（人设/规则），每轮都放最前。→ `AgentRunner.resolveSystemPrompt()`
  - `UserMessage`：用户输入。→ `AgentRunner.run()` 里 `new UserMessage(userText)`
  - `AssistantMessage`：模型的回复；**带 toolCalls 时表示"我要调工具"而不是说话**。→ `AgentRunner` 判断 `output.hasToolCalls()`
  - `ToolResponseMessage`：工具执行结果回填给模型的载体（一问一答的"答"由它扮演）。→ `history.add(ToolResponseMessage.builder()...)`
- **工具调用原理（function calling）**：你把工具清单发给模型 → 模型不执行，只回一个 `toolCalls`（工具名+JSON 参数）→ **你的代码**执行真实调用 → 结果作为 ToolResponseMessage 追加进历史 → 再调模型，它继续决策。本质是"模型出嘴，你来动手"。
- **`internalToolExecutionEnabled(false)` 为什么是显式循环的前提**：Spring AI 默认会自己执行上面整套循环（隐式执行），你拿不到中间过程。关掉后每轮 `decide()` 只返回模型决策，执行权在你手里——`AgentRunner` 才能插桩 SSE 事件、人工确认（`ConfirmManager`）、步数熔断。
- **ToolCallback / ToolDefinition**：ToolCallback = "可执行工具"接口（`getToolDefinition()` 给模型看广告，`call()` 真正干活）；ToolDefinition = 名称/描述/JSON Schema 参数说明。→ `DynamicToolRegistry.GatewayToolCallback`（schema 广告位 + 委托网关执行）
- **usage 统计**：每次响应 `ChatResponse.getMetadata().getUsage()` 带 prompt/completion token 数。→ `TokenRecorder` 落 `llm_usage` 表（best-effort，失败不影响对话）。

## 2. OpenAI 兼容协议：一个 URL 约定，换厂商不用换代码

- 请求报文 `POST {base-url}/chat/completions`，三段式：
  1. `messages`：`[{role:"system",content:...},{role:"user",content:...},...]`（上面 Message 体系的线上形态）
  2. `tools`：`[{type:"function",function:{name,description,parameters(JSON Schema)}}]`（工具"菜单"）
  3. 响应里 `choices[0].message.tool_calls`：`[{id,function:{name,arguments}}]`（模型点菜）
- **为什么 DashScope/智谱/GLM 都能接**：大家约定都用这套报文，只换 `base-url` 和 `api-key`。本项目 `application.yml` 的 `spring.ai.openai.base-url` 默认指向 DashScope compatible-mode，`LLM_BASE_URL` 环境变量一改即切厂商。
- 兼容性小坑：qwen3 系在非流式调用下要求 `enable_thinking=false`，通过 `extraBody` 透传 → `OpenAiPlanner.decide()`。

## 3. JSON-RPC 2.0：MCP 底下的信封格式（对照 gateway/message/）

- 请求：`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{...}}` → `JsonRpcRequest`（record 一行定义）。
- 响应二选一：成功 `{"result":...}`；失败 `{"error":{"code","message"}}` → `JsonRpcResponse`（工厂方法 success/failure）。
- **通知（notification）= 没有 id 的请求**：不需要回复。→ `MessageDispatcher.dispatch()` 见 `id == null` 直接返回 null。
- 错误码：`-32601` 方法不存在、`-32602` 参数错误、`-32603` 内部错误（JSON-RPC 标准）；`-32001/-32002` 是 MCP 扩展（未授权/限流）。全部定义在 `McpProtocol`，别再写字面量。
- 处理器怎么找：`MessageHandler` 接口声明自己负责的 method，`MessageDispatcher` 把 Spring 容器里的所有实现转成 Map 直查（策略表模式）。

## 4. MCP 协议：AI 界的"USB 接口"（模型怎么安全地用你的工具）

- **initialize（握手）**：客户端报协议版本/能力，服务端确认版本并**在响应头 `Mcp-Session-Id` 发会话凭证**。→ `InitializeHandler` + `StreamableGatewayController.post()`（create 后写响应头）。
- **tools/list（查菜单）**：返回本租户可用工具（name/description/inputSchema），Agent 拿它转成 Spring AI 的 ToolCallback。→ `ToolsListHandler`、`DynamicToolRegistry`。
- **tools/call（点菜）**：传工具名+参数，网关定位注册的 ToolDefinition → `GenericHttpForwarder` 把参数拼成真实 HTTP 请求转发给业务系统 → 结果包成 `content[0].text`。执行失败不抛协议错误，而是 `isError=true`——把"菜没了"当正常话术让模型自己改主意。
- **SSE vs Streamable 两种传输**：老 SSE 是两条路（GET 建下行流 + POST `/mcp/message` 收上行）；Streamable 用**一个** `/mcp` 端点三种方法（POST 收发一体、GET 可选开下行流、DELETE 挂断），更省连接。→ `SseGatewayController` / `StreamableGatewayController`。
- **Mcp-Session-Id 的作用**：会话路由凭证。多实例部署时，会话体在 Redis（`GatewaySessionService`），谁持有 SSE 连接谁负责写出，跨实例靠 Redis PubSub 转交（`SseConnectionRegistry`/`SessionEventSubscriber`）。

## 5. RestClient（Spring 6）：RestTemplate 的接班人

- 链式拼请求，像 MyBatis-Plus 的 QueryWrapper 那样顺滑：`restClient.post().uri(...).contentType(JSON).body(obj).retrieve().toEntity(String.class)`。→ `McpGatewayClient.post()`、`GenericHttpForwarder`。
- **retrieve() vs exchange()（重要）**：`retrieve()` 遇 4xx/5xx 直接抛 `RestClientResponseException`（把 HTTP 错误翻译成异常流）；`exchange((req,resp)->...)` 不抛，**原始状态码和响应流都给你自己处理**。转发器用 `exchange()` 是因为"上游 500"要原样转成工具执行结果交给模型，而不是变成网关自己的异常。→ `GenericHttpForwarder.forward()`；`McpGatewayClient` 用 retrieve() + catch 异常，把 400 翻译成"会话过期重握手"。

## 6. SSE（SseEmitter）：HTTP 上的单向推送

- 本质是一个**不结束的 HTTP 响应**：服务端持续往里写"帧"，浏览器 EventSource 自动解析。比 WebSocket 简单（无需协议升级、单向够用），是所有 LLM 打字机效果的通用做法。
- 帧格式（文本协议，`event:`/`data:` 两行一空格分组隔）：
  ```
  event: tool_call
  data: {"tool":"createBooking","arguments":"{...}"}
  ```
  → `AgentEventEmitter`（类型化封装：session/intent/plan/tool_call/tool_result/confirm_request/answer/error/done，事件名集中在 `EventType`）。
- **心跳保活**：连接空闲太久会被 Nginx/网关掐掉，周期发一行注释帧 `: keep-alive` 续命。→ `SseHeartbeat`（25s 一次，写失败即停表）。
- 超时与清理：`new SseEmitter(timeoutMillis)`，`onCompletion/onTimeout/onError` 回调里摘除注册表条目 → `SseConnectionRegistry.register()`。

## 7. Redisson：把 Redis 的数据结构搬进 Java 接口

- **RRateLimiter（分布式令牌桶）**：限流状态放 Redis，多实例共享同一个桶，比 Guava RateLimiter（单机内存）适合集群。`RateType.OVERALL` = 所有实例合用一份配额。→ `RateLimitService`（每 apiKey 一桶，60 次/分钟；Redis 挂了 fail-open 放行——限流是保护措施，不能反过来变成故障放大器）。
- **RTopic（发布订阅）**：`getTopic(name).publish(msg)` 广播，所有订阅实例收到——用 Redis 的 pub/sub 实现跨 JVM 事件。→ `SseConnectionRegistry`（A 实例收到消息但连接在 B 实例 → 发频道 → B 的 `SessionEventSubscriber` 代写）。
- **RBucket（分布式 KV）**：`getBucket(key)` 即 `GET/SET` 的对象版，`set(value, ttl)` 原子带过期。→ `GatewaySessionService`（会话体 `gw:session:{key}`，30 分钟滑动续期：每次校验命中就 `expire` 续命）。

## 8. MyBatis-Plus：只写业务 SQL，模板 CRUD 不写

- **BaseMapper**：继承即得 insert/selectById/update/delete，零 SQL。→ 7 个 Mapper 接口（如 `ChatMessageMapper`）全是空体。
- **QueryWrapper**：链式拼 where，字段名用字符串（数据库列名，注意蛇形）：`new QueryWrapper<ChatMessage>().eq("session_key", k).orderByAsc("id").last("LIMIT 200")`。→ `AgentSessionService.loadHistory()`、`ToolRegistryService`。`last()` 是在 SQL 尾部拼原始片段的逃生舱。
- **@TableName/@TableId**：实体↔表映射（`ChatMessage` ↔ `chat_message`）。驼峰↔下划线由 `map-underscore-to-camel-case` 自动转。
- **@Version 乐观锁**：更新时自动带 `where version=旧值`，冲突更新失败重试。本项目暂无并发写争用场景，未启用——知道机制即可。
- 逻辑删除（@TableLogic）本项目刻意不用：`gateway_session` 的"关闭"要保留审计轨迹，用的是 `expired_at IS NULL` 条件更新。

## 9. Lombok：编译期改字节码，消灭模板代码

- **@Data**：生成全部 getter/setter、`equals/hashCode/toString`。MyBatis-Plus 靠 setter 回填查询结果，所以实体必须有——手写 7 个实体上百行，注解一行搞定。→ 7 个实体类。
- **@Slf4j**：生成 `private static final Logger log = LoggerFactory.getLogger(X.class);`。→ 全项目日志统一 `log.warn(...)`，不再手写 Logger 声明。
- **@RequiredArgsConstructor**：为**所有 final 未初始化字段**生成构造器。Spring 单构造器注入由此免写——字段加 final、类加注解即可，新依赖只加一行字段。→ 14 个纯 DI 类（`AgentRunner` 10 个依赖全 final）。注意：带初始化的字段（如 `ChatController.executor`）不进构造器。
- 配套坑：Lombok 是注解处理器，JDK 23 起默认禁用"从 classpath 自动发现处理器"，必须配 `maven-compiler-plugin` 的 `annotationProcessorPaths`（见本项目 pom.xml 注释）。

## 10. 线程池：ThreadPoolExecutor 七参数与拒绝策略（对照 ChatController）

- **为什么禁用 Executors 工厂**：`newFixedThreadPool` 队列无界（堆积 OOM）、`newCachedThreadPool` 线程数无上限（线程爆炸 OOM）。显式 `new ThreadPoolExecutor(...)` 把容量摆上台面。→ `ChatController.newLoopExecutor()`。
- 七参数速记（对照代码）：① corePoolSize=4 常驻线程；② maximumPoolSize=8 上限（注意：**队列满才扩线程**，顺序反直觉）；③④ keepAliveTime=60s+TimeUnit 非核心线程空闲回收；⑤ workQueue=`LinkedBlockingQueue(64)` 有界排队（必须有界，否则拒绝策略形同虚设）；⑥ threadFactory 命名 `agent-loop-N`+daemon（线程 dump 定位、不阻 JVM 退出）；⑦ handler 拒绝策略。
- **拒绝策略选型**：AbortPolicy 抛异常 → `chat()` catch 后给用户 SSE 推"系统繁忙"（快速失败+明确反馈）；DiscardPolicy 静默丢（连接悬死）；CallerRunsPolicy 会让 Tomcat 工作线程亲自跑几十秒的对话循环，拖垮容器吞吐。对话任务是"长时占用型"，宁可拒不可等。
- 定时任务版：`ScheduledThreadPoolExecutor(1, factory)` 替代 `newSingleThreadScheduledExecutor`——后者同样藏无界队列且不可配策略。→ `SseHeartbeat`。

---
*对应代码全部有同主题中文注释；深入细节见 docs/CODE-TOUR.md 与 docs/adr/。*
