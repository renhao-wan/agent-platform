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
- **ToolCallback / ToolDefinition**：ToolCallback = "可执行工具"接口（`getToolDefinition()` 给模型看广告，`call()` 真正干活）；ToolDefinition = 名称/描述/JSON Schema 参数说明。→ `DynamicToolRegistry.GatewayToolCallback`（schema 广告位 + 委托工具层执行）
- **usage 统计**：每次响应 `ChatResponse.getMetadata().getUsage()` 带 prompt/completion token 数。→ `TokenRecorder` 落 `llm_usage` 表（best-effort，失败不影响对话）。

## 2. OpenAI 兼容协议：一个 URL 约定，换厂商不用换代码

- 请求报文 `POST {base-url}/chat/completions`，三段式：
  1. `messages`：`[{role:"system",content:...},{role:"user",content:...},...]`（上面 Message 体系的线上形态）
  2. `tools`：`[{type:"function",function:{name,description,parameters(JSON Schema)}}]`（工具"菜单"）
  3. 响应里 `choices[0].message.tool_calls`：`[{id,function:{name,arguments}}]`（模型点菜）
- **为什么百炼/DeepSeek 都能接**：大家约定都用这套报文，只换 `base-url` 和 `api-key`。本项目 `application.yml` 默认指向 DeepSeek（`https://api.deepseek.com`；`/v1` 与不带 `/v1` 的路径实测都可用），`LLM_BASE_URL`/`LLM_MODEL`/`LLM_API_KEY` 三个环境变量一改即切厂商。
- **厂商特有的"思考开关"只能走代码透传**（2026-10 实测）：
  - 形态各家不同——qwen3 非流式要求 `enable_thinking=false`；DeepSeek 的 thinking **默认开启**，关闭是 `{"thinking":{"type":"disabled"}}`。→ `OpenAiPlanner.decide()` 按模型名前缀分支调 `extraBody(...)`。
  - **别用 yml 的 `spring.ai.openai.chat.extra-body.*`**：实测它绑出来是空对象（报文里 `"extra_body":{}`），根本不生效——这版 Spring AI 上它是一个看起来对、实则静默失效的配置。
  - 代码版 extraBody 发出去时参数会**出现两次**：包一份在 `extra_body` 里、再展开一份到顶层（实测 `…,"extra_body":{"thinking":{"type":"disabled"}},"thinking":{"type":"disabled"}}`）。DeepSeek 认的是顶层那份。⚠️ 多出来的 `extra_body` 字段是否被 DeepSeek 容忍，**首次真实调用要确认没有 400**。
  - **DeepSeek 必须关 thinking**，两个理由：① thinking 模式下 `temperature` 被静默忽略（不报错、也不生效）；② 带 `tools` 的请求要求把每轮 `reasoning_content` 回传并拼进上下文，而本项目 ReAct 循环手动构造 `ToolResponseMessage`、不保留该字段。

## 3. RestClient（Spring 6）：RestTemplate 的接班人

- 链式拼请求，像 MyBatis-Plus 的 QueryWrapper 那样顺滑：`restClient.post().uri(...).contentType(JSON).body(obj).retrieve().toEntity(String.class)`。→ `GenericHttpForwarder`。
- **retrieve() vs exchange()（重要）**：`retrieve()` 遇 4xx/5xx 直接抛 `RestClientResponseException`（把 HTTP 错误翻译成异常流）；`exchange((req,resp)->...)` 不抛，**原始状态码和响应流都给你自己处理**。转发器用 `exchange()` 是因为"上游 500"要原样转成工具执行结果交给模型，而不是变成平台自己的异常。→ `GenericHttpForwarder.forward()`。
- 超时是**两部分**：connect timeout（连不上，快速失败）与 read timeout（连上了但迟迟不回）。RestClient 用 `ClientHttpRequestFactory` 设，两个值都从 `ForwardProperties` 读。

## 4. SSE（SseEmitter）：HTTP 上的单向推送

- 本质是一个**不结束的 HTTP 响应**：服务端持续往里写"帧"，浏览器 EventSource 自动解析。比 WebSocket 简单（无需协议升级、单向够用），是所有 LLM 打字机效果的通用做法。
- 帧格式（文本协议，`event:`/`data:` 两行一空格分组隔）：
  ```
  event: tool_call
  data: {"tool":"createBooking","arguments":"{...}"}
  ```
  → `AgentEventEmitter`（类型化封装：session/plan/tool_call/tool_result/confirm_request/answer/error/done，事件名集中在 `EventType`）。
- **心跳保活**：连接空闲太久会被 Nginx/反向代理掐掉，通用做法是周期发一行注释帧 `: keep-alive` 续命。本项目用户侧未单独实现心跳——`SseEmitter` 自带的 timeout 与浏览器 EventSource 的自动重连够用。
- 超时与清理：`new SseEmitter(timeoutMillis)`，`onCompletion/onTimeout/onError` 回调里做收尾。→ `ChatController.chat()`；连接断开后事件静默丢弃、循环照常落库。

## 5. MyBatis-Plus：只写业务 SQL，模板 CRUD 不写

- **BaseMapper**：继承即得 insert/selectById/update/delete，零 SQL。→ 6 个 Mapper 接口（如 `ChatMessageMapper`）全是空体。
- **QueryWrapper**：链式拼 where，字段名用字符串（数据库列名，注意蛇形）：`new QueryWrapper<ChatMessage>().eq("session_key", k).orderByAsc("id").last("LIMIT 200")`。→ `AgentSessionService.loadHistory()`、`ToolRegistryService`。`last()` 是在 SQL 尾部拼原始片段的逃生舱。
- **@TableName/@TableId**：实体↔表映射（`ChatMessage` ↔ `chat_message`）。驼峰↔下划线由 `map-underscore-to-camel-case` 自动转。
- **@Version 乐观锁**：更新时自动带 `where version=旧值`，冲突更新失败重试。本项目暂无并发写争用场景，未启用——知道机制即可。
- 逻辑删除（@TableLogic）本项目刻意不用：审计要的是"发生过什么"的完整轨迹，物理删或标记删都留给业务表自己决定。

## 6. Lombok：编译期改字节码，消灭模板代码

- **@Data**：生成全部 getter/setter、`equals/hashCode/toString`。MyBatis-Plus 靠 setter 回填查询结果，所以实体必须有——手写 6 个实体上百行，注解一行搞定。→ 6 个实体类。
- **@Slf4j**：生成 `private static final Logger log = LoggerFactory.getLogger(X.class);`。→ 全项目日志统一 `log.warn(...)`，不再手写 Logger 声明。
- **@RequiredArgsConstructor**：为**所有 final 未初始化字段**生成构造器。Spring 单构造器注入由此免写——字段加 final、类加注解即可，新依赖只加一行字段。→ 十多个纯 DI 类（`AgentRunner` 10 个依赖全 final）。注意：带初始化的字段（如 `ChatController.executor`）不进构造器。
- 配套坑：Lombok 是注解处理器，JDK 23 起默认禁用"从 classpath 自动发现处理器"，必须配 `maven-compiler-plugin` 的 `annotationProcessorPaths`（见本项目 pom.xml 注释）。

## 7. 线程池：ThreadPoolExecutor 七参数与拒绝策略（对照 ChatController）

- **为什么禁用 Executors 工厂**：`newFixedThreadPool` 队列无界（堆积 OOM）、`newCachedThreadPool` 线程数无上限（线程爆炸 OOM）。显式 `new ThreadPoolExecutor(...)` 把容量摆上台面。→ `ChatController.newLoopExecutor()`。
- 七参数速记（对照代码）：① corePoolSize=4 常驻线程；② maximumPoolSize=8 上限（注意：**队列满才扩线程**，顺序反直觉）；③④ keepAliveTime=60s+TimeUnit 非核心线程空闲回收；⑤ workQueue=`LinkedBlockingQueue(64)` 有界排队（必须有界，否则拒绝策略形同虚设）；⑥ threadFactory 命名 `agent-loop-N`+daemon（线程 dump 定位、不阻 JVM 退出）；⑦ handler 拒绝策略。
- **拒绝策略选型**：AbortPolicy 抛异常 → `chat()` catch 后给用户 SSE 推"系统繁忙"（快速失败+明确反馈）；DiscardPolicy 静默丢（连接悬死）；CallerRunsPolicy 会让 Tomcat 工作线程亲自跑几十秒的对话循环，拖垮容器吞吐。对话任务是"长时占用型"，宁可拒不可等。
- 定时任务版：`ScheduledThreadPoolExecutor(1, factory)` 替代 `newSingleThreadScheduledExecutor`——后者同样藏无界队列且不可配策略。

---
*对应代码全部有同主题中文注释；深入细节见 docs/architecture/CODE-TOUR.md 与 docs/decisions/。*
