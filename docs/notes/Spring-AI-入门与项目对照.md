# Spring AI 入门：从一次 HTTP 调用到看懂本项目的 Agent 循环

> **写给谁**：会写 Java / Spring Boot，但没系统学过 Spring AI 的人。
>
> **目标**：读完能回答三个问题——大模型调用到底在干什么、Spring AI 替你封装了哪一层、本项目每一行 Spring AI 代码为什么这么写。
>
> **读法**：前 7 章按顺序读，建立主线；第 8–10 章是知识库（RAG）部分，可以等看到 `knowledge/` 包时再读；第 11–14 章是速查、读码路线和踩坑记录。每节末尾给出**项目对应代码**，读完立刻回代码里对照。
>
> **版本基准**：`Spring AI 1.1.1` + `Spring Boot 3.4.5`。Spring AI 在 1.0 前后 API 变动较大（builder 化、starter 改名），网上旧示例可能对不上，遇到编译不过先确认版本。

## 0. 阅读地图

| 你想搞清楚 | 看哪节 | 项目对应 |
|---|---|---|
| 大模型请求/响应到底长什么样 | §1 | `OpenAiPlanner`、`pom.xml` |
| Spring AI 有哪些抽象、各管什么 | §2 | 全局 |
| ChatModel 与 ChatClient 的区别 | §3 | `OpenAiPlanner` |
| 多轮对话怎么表示、工具结果怎么回填 | §4 | `AgentRunner` |
| 温度、模型名、思考开关怎么传 | §5 | `OpenAiPlanner`、`application.yml` |
| 工具调用怎么工作、隐式与显式的区别 | §6 | `DynamicToolRegistryImpl`、`AgentRunner` |
| 工具多时怎么缩小候选集 | §6.8 | `ToolSelectorChain`、`WattAiToolSelector` |
| token 用量、超时、异常怎么处理 | §7 | `TokenRecorder`、`AgentProperties` |
| 文本怎么变成向量 | §8 | `KnowledgeConfig`、`UsageRecordingEmbeddingModel` |
| 向量库怎么增删查、怎么做权限过滤 | §9 | `KnowledgeIndexService`、`KnowledgeSearchService` |
| RAG 和 Agentic RAG 的区别 | §10 | `KnowledgeSearchTool` |
| 流式、ChatMemory、Advisor、结构化输出怎么用 | §11 | 本项目未用，但面试常问 |
| 应该按什么顺序读这个项目 | §13 | — |

---

## 1. 先把 Spring AI 放一边：大模型调用就是一次 HTTP POST

理解框架之前，先记住它的原始形态。所有"聊天补全"接口，本质上都是：

```http
POST {base-url}/v1/chat/completions
Authorization: Bearer {api-key}
Content-Type: application/json

{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "你是一个业务操作助手……"},
    {"role": "user",   "content": "帮我订明天下午 3 点的 3 号会议室"}
  ],
  "temperature": 0.2,
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "createBooking",
        "description": "创建一条会议室预订",
        "parameters": { "type": "object", "properties": { "...": {} } }
      }
    }
  ]
}
```

响应是一个 JSON：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "createBooking",
              "arguments": "{\"roomId\":3,\"date\":\"2026-10-22\",\"start\":\"15:00\",\"end\":\"16:00\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 812, "completion_tokens": 46, "total_tokens": 858 }
}
```

### 1.1 三段报文，三个关键概念

1. **`messages`：对话上下文。** 模型本身不记得任何东西，每次请求都要把完整对话重新发过去。所以"多轮对话"在工程上就是"维护一个不断变长的消息列表"。
2. **`tools`：工具菜单。** 你把可用工具的名字、描述、参数 JSON Schema 一起发过去。模型**不执行**工具，它只在响应里说"我要调用 `createBooking`，参数是这些"。
3. **`usage`：本次消耗的 token。** prompt tokens（输入）+ completion tokens（输出）。计费、限流、成本分析都靠它。

### 1.2 工具调用为什么是"两趟"

工具调用的完整过程是：

```
第一趟：你发 messages + tools
        ← 模型回 tool_calls（只出嘴，不动手）
你执行真实函数（查数据库、调业务系统）
第二趟：你发 messages + 本轮 assistant(tool_calls) + tool 结果
        ← 模型根据结果继续决策（再调工具，或给最终回答）
```

这就是 ReAct（Reason + Act）循环的物理形态。**"谁执行工具"是 Spring AI 里最重要的一个分水岭**：框架可以替你跑完这个循环（隐式执行），也可以只把每轮决策交给你（显式执行）。本项目选后者，原因见 §6.7。

### 1.3 "OpenAI 兼容"到底兼容了什么

OpenAI 定义了上面这套报文格式，随后 DeepSeek、通义千问、SiliconFlow、vLLM、Ollama 等大量服务都按它实现。于是**换供应商通常只改三样东西**：`base-url`、`api-key`、`model`。

本项目 `application.yml`：

```yaml
spring:
  ai:
    openai:
      base-url: ${LLM_BASE_URL:https://api.deepseek.com}
      api-key: ${LLM_API_KEY:missing-key}
      chat:
        options:
          model: ${LLM_MODEL:deepseek-flash}
          temperature: 0.2
```

> 注意：兼容的是**协议**，不是**所有行为**。各家的"深度思考开关"、能否带 tools 使用 thinking、参数默认值都可能不同——这是本项目在代码里按模型名分支的直接原因（§5.4）。

---

## 2. Spring AI 是什么：客户端 SDK + 统一抽象层

### 2.1 它不生产模型，只是模型的调用方

Spring AI 的职责可以概括成三件事：

1. **封装 HTTP 细节**：序列化请求、鉴权、解析响应、重试。
2. **提供统一抽象**：`ChatModel`、`EmbeddingModel`、`VectorStore` 等接口，换供应商时上层代码不动。
3. **提供 Spring 风格的装配**：starter + 配置属性 + Bean 自动注册。

它和 Spring Data 的定位类似：Spring Data 统一了不同数据库的访问方式，Spring AI 统一了不同模型/向量库的访问方式。

### 2.2 核心抽象一览

| 抽象 | 一句话职责 | 本项目是否使用 |
|---|---|---|
| `ChatModel` | 聊天模型接口：发一次请求，拿一次 `ChatResponse` | 使用（由 starter 注入） |
| `ChatClient` | `ChatModel` 之上的链式门面：拼 prompt/options/tools | 使用 |
| `Message` 家族 | 对话消息类型（system/user/assistant/tool） | 使用 |
| `ChatOptions` | 本次调用的参数（模型、温度、工具、超时） | 使用 |
| `ToolCallback` / `ToolDefinition` | 工具的执行接口 / 给模型看的 schema | 使用（动态构造） |
| `ChatResponse` | 响应：内容、工具调用、token 用量 | 使用 |
| `EmbeddingModel` | 文本转向量 | 使用（手动装配） |
| `VectorStore` | 向量的增删查 | 使用（pgvector） |
| `Document` | 向量库中的一条记录（文本 + 元数据 + 向量） | 使用 |
| `ChatMemory` | 自动管理对话历史的会话记忆 | 未用（项目自己落 MySQL） |
| `Advisor` | 在调用前后插入横切逻辑（记忆、RAG、日志） | 未用（项目显式编排） |
| `@Tool` 注解 | 把一个 Java 方法声明成工具 | 未用（工具来自契约，不是手写） |

### 2.3 依赖与 BOM

```xml
<properties>
    <spring-ai.version>1.1.1</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

BOM 统一管理 Spring AI 各组件的版本，下面引依赖不用写版本号。

本项目用到两个：

```xml
<!-- 聊天：自动装配 OpenAiChatModel + 读取 spring.ai.openai.* 配置 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>

<!-- 向量库：只是库，不是 starter，避免自动配置抢主 DataSource -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store</artifactId>
</dependency>
```

> **版本提示**：Spring AI 1.0 前后 starter 命名改过一轮（旧名如 `spring-ai-openai-spring-boot-starter`）。看网上的示例代码时先确认版本，1.1 的 API 比 0.8/0.9 时代"builder 化"了很多。

---

## 3. ChatModel 与 ChatClient：底层客户端与链式门面

### 3.1 两层关系

```
你的代码
   │
   ▼
ChatClient  ── 链式 API：prompt().messages(...).options(...).call()
   │           负责组装请求、套用默认值、编排 Advisor
   ▼
ChatModel   ── 一次调用的接口：call(Prompt) → ChatResponse
   │           OpenAiChatModel 是它的 OpenAI 协议实现
   ▼
HTTP POST /v1/chat/completions
```

- **`ChatModel`**：类似 `JdbcTemplate`，一次方法调用对应一次远程请求，返回强类型结果。
- **`ChatClient`**：类似 MyBatis 的 `SqlSession` 之于 JDBC——不是必需的，但链式写法更顺手，且能挂载默认 system prompt、Advisor 等。

### 3.2 两种常见写法

**低层，直接用 ChatModel：**

```java
ChatResponse response = chatModel.call(
        new Prompt(
                List.of(new SystemMessage("你是助手"), new UserMessage("你好")),
                OpenAiChatOptions.builder().model("deepseek-flash").temperature(0.2).build()));
```

**高层，用 ChatClient：**

```java
ChatClient client = ChatClient.builder(chatModel).build();

String answer = client.prompt()
        .system("你是助手")
        .user("你好")
        .options(OpenAiChatOptions.builder().temperature(0.2).build())
        .call()
        .content();
```

两者最终发的是同一个 HTTP 请求。`content()` 是快捷方法，只取文本；需要完整响应（工具调用、usage）时用 `.chatResponse()`。

### 3.3 本项目的选择

`OpenAiPlanner` 同时用了两者：

```java
public OpenAiPlanner(ChatModel chatModel, Environment environment) {
    this.chatClient = ChatClient.builder(chatModel).build();
}

public ChatResponse decide(List<Message> history, List<ToolCallback> tools) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
            .toolCallbacks(tools)
            .internalToolExecutionEnabled(false);
    ...
    return chatClient.prompt()
            .messages(history)
            .options(options.build())
            .call()
            .chatResponse();
}
```

为什么是 `ChatClient` 而不是直接 `chatModel.call`？因为这里需要完整的 `ChatResponse`（要读 toolCalls 和 usage），而 `ChatClient` 的链式 API 让"整段历史 + 一组 options"的表达更直接。两者都可以，团队习惯问题。

**关键点**：`ChatClient` 是无状态的，可以放心在单例 Bean 构造器里建一次、反复用。它不像 `RestTemplate` 那样需要担心线程安全（底层 HTTP 客户端由框架管理）。

---

## 4. Message 体系：对话就是一个 `List<Message>`

### 4.1 四种消息

| 类型 | 角色 | 谁产生 | 作用 |
|---|---|---|---|
| `SystemMessage` | system | 你的代码 | 人设、规则、当前日期等全局约束；放在列表最前 |
| `UserMessage` | user | 用户 | 本轮问题 |
| `AssistantMessage` | assistant | 模型 | 模型回复；**带 `toolCalls` 时表示"要求调用工具"** |
| `ToolResponseMessage` | tool | 你的代码 | 工具执行结果回填 |

一个重要认知：**这四种类只是消息的 Java 表示，真正的对话历史仍然只是一个有序列表**。模型没有记忆，你每次调用把整个列表发过去。

### 4.2 工具调用必须"成对"

模型返回带 `toolCalls` 的 `AssistantMessage` 后，下一次请求里**必须**紧跟一条 `ToolResponseMessage`，并且每个 `ToolResponse` 的 `id` 要与对应 `ToolCall` 的 `id` 一致：

```java
history.add(output);                       // assistant(tool_calls)
history.add(ToolResponseMessage.builder()
        .responses(List.of(
                new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText)))
        .build());
```

少一条、id 对不上、或者顺序错了，多数供应商会直接返回 400。这是手写显式循环时最容易踩的坑，也是"用框架隐式执行"最省心的地方之一。

### 4.3 本项目怎么组装历史

`AgentRunner.run()` 的入口：

```java
List<Message> history = new ArrayList<>();
history.add(new SystemMessage(resolveSystemPrompt()));       // 1. system
for (ChatMessage message : sessionService.loadHistory(sessionKey)) {
    history.add(toSpringMessage(message));                    // 2. 数据库里的历史
}
history.add(new UserMessage(userText));                       // 3. 本轮输入
sessionService.appendMessage(...);                            // 4. 即写即落库
history = contextTrimmer.trim(history).messages()             // 5. 超预算时裁剪
```

数据库里存的是自己的 `ChatMessage`（`role` + `content` + `tool_name`），加载时转换成 Spring AI 的 `Message`：

```java
private Message toSpringMessage(ChatMessage message) {
    return switch (message.getRole()) {
        case ChatMessage.ROLE_USER -> new UserMessage(message.getContent());
        case ChatMessage.ROLE_ASSISTANT -> new AssistantMessage(message.getContent());
        default -> new SystemMessage("工具 " + message.getToolName() + " 结果：" + message.getContent());
    };
}
```

**注意这里的取舍**：工具结果在落库后被还原成 `SystemMessage` 文本，而不是原样的 `ToolResponseMessage`。原因是重启后不保证每轮工具调用都还成对存在（历史可能被裁剪），用文本表达对模型更鲁棒；代价是模型看到的是"工具结果摘要"而不是结构化回填。这也是很多自研 Agent 的共同做法。

### 4.4 为什么不直接用 Spring AI 的 ChatMemory

`ChatMemory` 会替你维护消息窗口，但它把历史放在内存（或你配置的存储）里，且与"裁剪 + checkpoint 压缩 + 冷层历史检索"这套三层记忆策略不匹配。本项目需要历史**即写即落库**、需要控制裁剪时机、需要把早期对话压成摘要，所以选择自己管理。概念上的 `ChatMemory` 在第 11 章讲。

---

## 5. Prompt 与 Options：这次调用怎么调

### 5.1 system prompt 放在哪

两种方式：

```java
// 每次调用传
client.prompt().system("你是助手").user("...").call().content();

// 或构造 ChatClient 时设默认值
ChatClient client = ChatClient.builder(chatModel)
        .defaultSystem("你是助手")
        .build();
```

本项目**没有**用 `defaultSystem`，而是在每轮历史的最前面手动放 `SystemMessage`。原因是 system prompt 里要注入当天日期（`{{CURRENT_DATE}}`），内容随日期变化；放在消息列表里也更容易被裁剪器识别和保留。

system prompt 本身从 classpath 读取：

```java
private static final String SYSTEM_PROMPT = loadPrompt();   // /prompts/system.md
```

**为什么值得单独放文件**：提示词是要反复改的"配置"，不是代码。放进 `system.md` 后，调措辞不用改 Java 文件，也方便版本管理时看清 diff。

### 5.2 常用 options

`ChatOptions` 是接口，各家有实现（`OpenAiChatOptions`、`AnthropicChatOptions` 等）。常用项：

| 参数 | 作用 | 本项目 |
|---|---|---|
| `model` | 模型名 | yml 配置默认值，这里不重复设置 |
| `temperature` | 随机性，0–2；越低越稳定 | yml 里 `0.2` |
| `maxTokens` | 输出上限 | 未设置（由供应商默认） |
| `toolCallbacks` | 本次可用的工具 | 每轮传入 |
| `internalToolExecutionEnabled` | 是否让框架自动执行工具 | **false**（§6.7） |
| `extraBody(map)` | 透传供应商非标准字段 | 按模型名传 thinking 开关 |

### 5.3 为什么是 `OpenAiChatOptions` 而不是泛型 `ChatOptions`

因为要用 `extraBody`，它是 OpenAI 兼容协议实现上的扩展点。取最具体的类型，避免运行时才发现某个参数不被投影。

### 5.4 踩过的坑：思考开关

- **qwen3（非流式）**：需要 `enable_thinking=false`。
- **DeepSeek**：thinking 默认开启；带 tools 时要求把每轮 `reasoning_content` 回传并拼进上下文，而本循环手动构造 `ToolResponseMessage`、不保留该字段；此外 thinking 模式下 `temperature` 会被静默忽略。所以必须关。

代码：

```java
if (modelName.startsWith("qwen3")) {
    options.extraBody(Map.of("enable_thinking", false));
} else if (modelName.startsWith("deepseek")) {
    options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
}
```

另外两个实测结论：

- **别用 yml 的 `spring.ai.openai.chat.extra-body.*`**：在这个版本的 Spring AI 上，它绑出来是空对象，报文里发的是 `"extra_body":{}`，静默失效。
- 代码版 `extraBody` 发出去时参数会出现两次（包一份在 `extra_body`、再展开一份到顶层）；DeepSeek 认的是顶层那份。

这类问题不是 Spring AI "有 bug"，而是**"OpenAI 兼容"只覆盖标准字段**，厂商扩展本来就没有统一契约——只能按供应商实测。

---

## 6. Tool Calling：整个项目最核心的一节

### 6.1 三个类型先分清

| 类型 | 是什么 | 谁看 |
|---|---|---|
| `ToolDefinition` | 工具名 + 描述 + 参数 JSON Schema | **模型看**（随请求发过去） |
| `ToolCallback` | 可执行工具：`getToolDefinition()` + `call(String json)` | **框架/你的代码看** |
| `AssistantMessage.ToolCall` | 模型返回的一次调用请求：`id` + `name` + `arguments` | **你的代码看** |

一个工具 = 一份"广告"（Definition）+ 一个"执行入口"（Callback）。模型只看到广告，永远不直接执行 Callback。

### 6.2 隐式执行（Spring AI 默认）

默认情况下，`internalToolExecutionEnabled` 为 `true`。流程是：

```
你的代码: client.prompt().tools(...).user(...).call()
框架内部: 发请求 → 收到 tool_calls → 找到对应 ToolCallback.call() 执行
          → 把结果拼成 ToolResponseMessage 自动再发一次
          → 循环，直到模型给出文本
你的代码: 直接拿到最终文本
```

优点是省心；缺点是**中间过程全在框架里**：你看不到每一轮决策、没法在执行前弹确认、没法在工具执行处做鉴权、没法控制步数。

### 6.3 显式执行（本项目）

关闭隐式执行：

```java
OpenAiChatOptions.builder()
        .toolCallbacks(tools)
        .internalToolExecutionEnabled(false)
```

此后每次 `call()` 只返回模型这一步的原始决策，执行完全由 `AgentRunner` 掌控：

```java
ChatResponse response = planner.decide(history, tools);
AssistantMessage output = response.getResult().getOutput();

if (!output.hasToolCalls()) {          // 模型给最终回答
    // 推送答案、落库、结束
}

for (AssistantMessage.ToolCall call : output.getToolCalls()) {
    // 1. 敏感操作 → 挂起等用户确认
    // 2. 平台内部工具 → 本地执行
    // 3. 业务工具 → 走 ToolGateway（权限闸门 + HTTP 转发）
    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText));
}
history.add(output);                                    // 模型这一步的决策
history.add(ToolResponseMessage.builder().responses(responses).build());  // 执行结果
// 下一轮
```

### 6.4 最常用的写法：`@Tool` 注解

如果工具是固定的、写在代码里的，Spring AI 提供了注解方式：

```java
public class BookingTools {

    @Tool(description = "查询指定时间段可用的会议室")
    public List<Room> findAvailableRooms(
            @ToolParam(description = "日期，格式 yyyy-MM-dd") String date,
            @ToolParam(description = "开始时间，格式 HH:mm") String start) {
        ...
    }
}

ChatClient client = ChatClient.builder(chatModel).build();

String answer = client.prompt()
        .user("明天下午三点有空的会议室？")
        .tools(new BookingTools())      // 注册工具对象
        .call()
        .content();
```

`@Tool`/`@ToolParam` 会被反射解析成 `ToolDefinition`（方法名 → name、描述 → description、参数 → JSON Schema），配合隐式执行可以几行代码做出一个 Agent。

**本项目不用它的原因**：工具不是手写的，而是启动时从各业务系统的 OpenAPI 契约动态生成的（16 个工具、可增删、可启停）。注解是编译期固定的，表达不了"运行时才知道有哪些工具"。

### 6.5 本项目的动态工具

`DynamicToolRegistryImpl` 从数据库读工具，构造 `ToolCallback`：

```java
static class GatewayToolCallback implements ToolCallback {
    private final ToolDefinition tool;   // 项目自己的实体，不是 Spring AI 的

    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(tool.getName())
                .description(describe(tool))          // 描述里追加权限要求
                .inputSchema(tool.getInputSchema())  // 来自业务系统契约
                .build();
    }

    public String call(String toolInput) {
        // 永不被调用：隐式执行已关闭，执行在 AgentRunner
        throw new UnsupportedOperationException(...);
    }
}
```

这体现了一个重要设计：**`ToolCallback` 在这里只是"schema 广告位"**。它存在的唯一目的是让 Spring AI 把工具清单正确序列化进请求；真正的执行路径在 `AgentRunner` → `ToolGateway`，因为那条路径需要用户身份，而 `ToolCallback.call(String)` 没有身份参数。

### 6.6 完整时序

```
AgentRunner                          OpenAiPlanner            Spring AI/HTTP          业务系统
    │                                     │                       │                     │
    │ decide(history, tools) ────────────▶│                       │                     │
    │                                     │ prompt().messages()   │                     │
    │                                     │   .options(toolCallbacks, 隐式执行=false)
    │                                     │   .call().chatResponse() ──POST /chat/completions──▶
    │                                     │                       │◀── tool_calls ──────│
    │◀────────────────────────────────────┘                       │                     │
    │ output.hasToolCalls()                                       │                     │
    │ 敏感? → ConfirmManager 挂起等用户                             │                     │
    │ 业务工具? → ToolGateway → ToolAccessService 闸门2 → HTTP 转发 ─────────────────────▶
    │                                     │                       │                     │
    │ history += assistant(toolCalls) + ToolResponseMessage        │                     │
    │ decide(history, tools) ─────────────────────────────────────────────────────────────▶
    │◀──────────────────────────── 最终文本 ───────────────────────────────────────────────│
```

### 6.7 为什么本项目必须显式执行

三个需求，隐式执行都做不了：

1. **SSE 分阶段事件**：要在"模型决定调工具"和"工具执行完"之间推事件，隐式执行没有插桩点。
2. **敏感操作人工确认**：执行前挂起等用户点确认，隐式执行会直接调下去。
3. **执行点鉴权**：权限校验必须发生在"即将执行"的位置，且需要当前用户身份。

一句话：**执行权必须在自己的代码里**。框架的价值是协议封装，不是替你决定业务流程。

### 6.8 工具变多之后：候选集路由

每轮把**全部**工具发给模型，在十几个工具时没问题；工具数上百后，token 成本线性增长，模型选错工具的概率也会上升。本项目的应对是一条**前置路由责任链**（`ToolSelector`）：

```yaml
platform:
  agent:
    routing-handlers: wattai              # 逗号分隔，按序尝试；置空 = 不路由
    routing-tool-count-threshold: 20      # 工具数达到 20 才启用，未达则整链休眠
```

行为分三层：

1. **规模闸门**：`ToolSelectorChain` 发现工具数 < 20，直接返回全量——当前 16 个工具，所以这条链**实际处于休眠状态**，主流程零开销。
2. **逐环尝试**：达到阈值后交给 `WattAiToolSelector`（一个小型决策模型）从工具名 + 描述中选一个。置信度低于 0.6 时不硬选，而是按概率取 top-2 作为候选集；argmax 不在候选里则弃权。
3. **链尾兜底**：任何节点弃权或抛异常，最终都回落全量工具。**路由是体验优化，不是安全边界**——它只缩小发给模型的候选集，权限校验仍然发生在闸门 2。

需要真实启用时，在 `.env` 里配置 `WATTAI_API_KEY`（对应 `platform.agent.wattai.api-key`）。**未配置时节点直接不装配**，责任链回落全量；配置了但凭证/地址无效（401/403/404 等 4xx）时节点会在首次失败后自动停用并记 error 日志，不会让每轮对话都发一次注定失败的请求；超时、5xx、429 属于临时错误，只弃权本次、下次仍可重试。项目注释里的实测结论是：托管端点中文工具路由置信度持续偏低，因此它被保留为一种可替换的实现，而不是默认依赖。

---

## 7. ChatResponse、Usage 与异常处理

### 7.1 响应结构

```java
ChatResponse response = ...;

Generation generation = response.getResult();     // 通常只有一个候选
AssistantMessage output = generation.getOutput(); // 文本 / toolCalls
String text = output.getText();                   // 可能为 null（纯工具调用时）
boolean wantsTool = output.hasToolCalls();
List<AssistantMessage.ToolCall> calls = output.getToolCalls();

Usage usage = response.getMetadata().getUsage();
int prompt = usage.getPromptTokens();
int completion = usage.getCompletionTokens();
```

**空值防御**：不同供应商/网关在异常情况下可能返回 `getResult() == null` 或 `getOutput() == null`。本项目显式判断并推 `error` 事件，而不是直接 NPE：

```java
AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
if (output == null) { events.error("model returned empty response"); return; }
```

### 7.2 token 用量

每轮 responses 累加 prompt/completion，最后 `tokenRecorder.record(...)` 落 `llm_usage` 表。这是成本核算和上下文治理的数据来源。

注意：**usage 是附属信息，写入失败不能影响对话**——记录层要自己吞异常（项目里 `TokenRecorder` 与 embedding 用量记录都遵循这一条）。

### 7.3 超时

模型调用必须设超时，否则供应商挂起会占死线程：

```yaml
platform:
  agent:
    llm-connect-timeout-ms: 5000     # 连不上，快速失败
    llm-read-timeout-ms: 120000      # 一次完整生成，长回答别设太小
```

Spring AI 的 OpenAI starter 从容器里取 `RestClient.Builder` 来构造 HTTP 客户端，所以本项目没有直接改 starter 的属性，而是注册一个 `RestClientCustomizer`：

```java
@Bean
public RestClientCustomizer llmHttpTimeouts(AgentProperties properties) {
    return builder -> {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getLlmConnectTimeoutMs()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getLlmReadTimeoutMs()));
        builder.requestFactory(factory);
    };
}
```

这是 Spring Boot 的标准扩展点：**框架自动装配的组件通常留了 customizer 接口**，优先用它，而不是自己重新 new 一个客户端覆盖自动配置。→ `AgentConfig.llmHttpTimeouts`

本项目**没有**做自动重试/故障转移：供应商整体不可用时，每个请求各自超时失败。这属于运营治理，不是当前规模的必要项（见 `highlights/03-ReAct循环与SSE.md §7`）。

---

## 8. Embedding：把文本变成向量

### 8.1 概念

Embedding 模型把一段文本映射成一个固定长度的浮点数组，例如 1024 维：

```
"差旅住宿费上限是每晚 500 元"  →  [0.012, -0.034, 0.087, ...]   // 1024 个数
```

语义相近的文本，向量在空间里的方向也相近。判断"相近"最常用**余弦相似度**（cosine similarity）：两个向量夹角的余弦，范围大致 0–1，越大越像。

这一层是 RAG（检索增强生成）的基础：把知识文档切块、逐块向量化存起来；用户提问时把问题也向量化，找最相近的块，再把这些块塞进 prompt 让模型回答。

### 8.2 `EmbeddingModel` 接口

核心方法：

```java
float[] embed(String text);                 // 单条文本
List<float[]> embed(List<String> texts);    // 批量（索引时用）
EmbeddingResponse call(EmbeddingRequest req); // 底层完整入口
int dimensions();                           // 向量维度
```

`EmbeddingResponse` 里有 `getResults()`（每条 `Embedding` 含 `float[] output` 和 index）以及 `getMetadata().getUsage()`（token 用量）。

### 8.3 为什么本项目手动装配，而不是用 starter

`application.yml` 里关键的一行：

```yaml
spring:
  ai:
    model:
      embedding: none    # 关闭 embedding 自动配置
```

原因：**一个应用接了两个不同供应商**。

- 聊天走 DeepSeek（`spring.ai.openai.*`）；
- embedding 走 SiliconFlow（`BAAI/bge-m3`），因为 DeepSeek 的聊天端点没有 embeddings 接口。

如果让 `spring-ai-starter-model-openai` 自动装配 embedding，它会复用同一份 `base-url` / `api-key`，指向错的供应商。所以 `KnowledgeConfig` 手动构造：

```java
OpenAiApi api = OpenAiApi.builder()
        .baseUrl(properties.getEmbeddingBaseUrl())
        .apiKey(properties.getEmbeddingApiKey())
        .embeddingsPath("/v1/embeddings")
        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
        .build();

EmbeddingModel delegate = new OpenAiEmbeddingModel(
        api, MetadataMode.EMBED,
        OpenAiEmbeddingOptions.builder().model(properties.getEmbeddingModel()).build(),
        retryTemplate);
```

这也说明 Spring AI 的一个使用原则：**starter 负责"最常见的单供应商场景"，多供应商/特殊拓扑就自己 new**。抽象接口不变，装配方式可以是手动的。

### 8.4 用量记录：装饰器模式

`UsageRecordingEmbeddingModel` 包住真正的模型：

```java
public EmbeddingResponse call(EmbeddingRequest request) {
    EmbeddingResponse response = delegate.call(request);
    usageRepository.record(model, request.getInstructions().size(),
            response.getMetadata().getUsage().getPromptTokens());
    return response;
}
```

为什么覆写 `call` 就够了：`PgVectorStore` 批量写入时走 `embed(List<String>)` → `embedForResponse` → `call`，索引和查询两条路径最终都汇到这里。

为什么用装饰器而不是改框架代码：这是 Spring 生态里扩展第三方组件的标准做法——组合优于继承，接入点明确，升级框架不受影响。

### 8.5 超时与重试

```java
RetryTemplate retryTemplate = RetryTemplate.builder()
        .maxAttempts(3)
        .exponentialBackoff(500, 2.0, 5000)
        .retryOn(TransientAiException.class)     // 5xx / 限流等可恢复错误
        .retryOn(ResourceAccessException.class)  // 网络连接问题
        .build();
```

**只重试可恢复错误**。4xx（鉴权失败、参数错、配额耗尽）重试多少次都不会成功，只会放大延迟与配额消耗。这是重试策略的通用原则。

---

## 9. VectorStore 与 pgvector：向量库怎么用

### 9.1 `Document`：向量库里的一条记录

```java
Document doc = Document.builder()
        .id("稳定且可复现的 id")
        .text("参与向量化的文本")            // 通常带标题前缀，提升召回
        .metadata(Map.of(
                "doc_path", "制度/差旅.md",
                "doc_title", "差旅报销制度",
                "section", "住宿标准",
                "raw_text", "正文……",        // 返回给模型时用原文，不带前缀
                "visible_permission", "public"))
        .build();
```

几个工程要点：

- **id 必须稳定**：本项目用 `UUID.nameUUIDFromBytes(docPath + "#" + chunkIndex)`，同一块内容重索引后 id 不变，才能做"版本化写入"（先写新块，再删旧块）。
- **向量化文本与展示文本分开**：`text` 带标题前缀参与 embedding（提升召回），`raw_text` 保留原文避免回答里重复标题。
- **metadata 是过滤和审计的依据**：权限、来源、章节都放这里。

### 9.2 增删查

```java
vectorStore.add(documents);                              // 写入（内部批量 embed）
List<Document> hits = vectorStore.similaritySearch(request); // 检索
vectorStore.delete(List.of("id1", "id2"));               // 按 id 删
vectorStore.delete(filterExpression);                    // 按元数据删
```

`similaritySearch` 返回的 `Document` 带 `getScore()`（相似度），`getMetadata()`、`getText()` 可用于组装上下文。

### 9.3 `SearchRequest`：检索参数

```java
SearchRequest request = SearchRequest.builder()
        .query("差旅住宿标准是多少")
        .topK(4)                    // 取前几条
        .similarityThreshold(0.30)  // 低于阈值不要，避免硬塞不相关内容
        .filterExpression("visible_permission == 'public' || visible_permission in ['finance:view']")
        .build();
```

### 9.4 过滤表达式

`filterExpression(String)` 支持一套简单表达式语法，由 `FilterExpressionTextParser` 解析成 `Filter.Expression` 树：

```
visible_permission == 'public'
doc_path == '制度/差旅.md'
section in ['住宿标准', '交通标准'] && visible_permission == 'public'
```

**为什么过滤要交给数据库而不是查完再筛**：向量检索只返回 topK，如果先取 topK 再在内存里过滤权限，可能 4 条结果里有 3 条无权、真实可见的 4 条根本没进候选集。写进 SQL 才能保证"候选集里只有可见内容"。这也是安全要求：无权内容一旦进入上下文就收不回。

### 9.5 `PgVectorStore` 配置

```java
return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .dimensions(1024)                                  // 必须与模型输出维度一致
        .distanceType(COSINE_DISTANCE)
        .indexType(HNSW)                                   // 近似最近邻索引
        .initializeSchema(true)                            // 自动建扩展/表/索引
        .vectorTableName("knowledge_vector")
        .build();
```

要点：

- **维度不是可以随便改的参数**。换 embedding 模型时维度不匹配，建表/查询阶段就会报错。项目把它写在配置里而不是靠模型自省，是为了在启动建表阶段就暴露问题。
- `HNSW` 是图索引，适合在线低延迟检索；数据量小时暴力扫描也能用，索引是性能优化。
- `PgVectorStore` 需要一个 `JdbcTemplate`。本项目故意用**私有连接池**（`KnowledgeJdbc`）而不是注册成 Spring 的 `DataSource`：否则 MyBatis-Plus 的 `@ConditionalOnSingleCandidate` 会失去唯一候选，主库配置被干扰。这是"同一应用连两个数据库"的经典隔离问题。

### 9.6 索引/检索代码对照

| 步骤 | 代码 |
|---|---|
| 扫描 Markdown、按内容 hash 判断是否变化 | `KnowledgeIndexService.index()` |
| 切块、构造 `Document` | `KnowledgeIndexService.indexOne()` |
| 版本化写入（先加后删） | `vectorStore.add(...)` + `vectorStore.delete(staleIds)` |
| 查询并组装结果 | `KnowledgeSearchService.vectorSearch()` |
| 关键词兜底 | `KnowledgeSearchService.keywordSearch()`（查 Postgres 的 `knowledge_chunk` 表） |

**为什么还要关键词兜底**：制度编号、专有名词这类查询对向量不敏感（embedding 更擅长语义，不擅长精确匹配）。向量结果为空时用 `ILIKE` 再查一次，成本低、收益直接。

---

## 10. RAG 与 Agentic RAG

### 10.1 经典 RAG 流水线

```
离线：文档 → 切块 → embedding → 向量库
在线：用户问题 → embedding → 检索 topK → 拼进 prompt → 模型回答
```

在线阶段是**固定流程**：不管用户问什么，都先检索，再回答。

### 10.2 本项目的做法：Agentic RAG

本项目把检索做成一个**工具** `search_knowledge`，交给模型自主决定要不要调、用什么 query：

```
用户："明天帮我订会议室"        → 模型直接调订会议室工具，不检索（制度文档是噪音）
用户："差旅住宿费能报多少？"    → 模型调 search_knowledge("差旅住宿标准")
用户："那出差到上海呢？"        → 模型结合上文构造 query 再检索
```

好处：多轮追问的 query 只有模型能构造好；不该检索的场景不浪费 token 和延迟。代价：多一轮模型决策。

这也解释了为什么 `search_knowledge` **不进工具层、不过权限闸门**：它是平台内部能力，不是一个业务系统的接口。执行方式见 `AgentRunner` 的本地分派分支。

### 10.3 为什么不用 `QuestionAnswerAdvisor`

Spring AI 提供了现成的 RAG Advisor：挂到 `ChatClient` 上，每次调用自动检索并拼接上下文。但它的模型是"每次调用都检索"的固定流程，无法表达"模型自己决定要不要查"；它也不处理本项目的权限过滤表达式、关键词兜底、审计与指标。所以本项目选择显式实现，只借用 `VectorStore` 这个底层抽象。

**选择原则**：Advisor 适合"所有请求都要经过同一段横切逻辑"的场景（日志、固定 RAG、记忆）；当检索时机本身需要模型判断时，把它做成工具更合适。

---

## 11. Spring AI 常用能力速查（本项目未用，但值得知道）

这一节回答"Spring AI 还能干什么"。面试里经常从这里追问。

### 11.1 流式输出

```java
Flux<String> stream = chatClient.prompt()
        .user("写一段介绍")
        .stream()
        .content();

stream.subscribe(chunk -> emitter.send(chunk));
```

`stream()` 返回 Reactor 的 `Flux`，适合直接做打字机效果。**本项目没有用它**：Agent 需要先拿到完整响应才能判断有没有 toolCalls、统计 usage、决定下一步；把模型的流式输出直接透传给前端，会和显式循环的决策点冲突。本项目转发给前端的流，是**平台自己的事件流**（plan/tool_call/tool_result/answer），不是模型 token 流。

### 11.2 ChatMemory

```java
ChatMemory memory = MessageWindowChatMemory.builder()
        .maxMessages(20)
        .build();

ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())  // 1.0 时代为 new MessageChatMemoryAdvisor(memory)
        .build();

client.prompt()
        .user("...")
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        .call()
        .content();
```

它替你维护"最近 N 条消息"。适合简单聊天应用；本项目需要落库、裁剪、压缩标记和冷层检索，所以自己实现（见 §4.4）。

### 11.3 Advisor（拦截器链）

Advisor 类似 Servlet Filter：在请求前后插入逻辑。Spring AI 自带：

- `MessageChatMemoryAdvisor` / `PromptChatMemoryAdvisor`：自动带历史；
- `QuestionAnswerAdvisor`：自动 RAG；
- `SimpleLoggerAdvisor`：打印请求/响应，排查"到底发了什么"时很好用。

也可以自己实现 `CallAroundAdvisor` 做审计、脱敏、限流。注意它的插入点在**框架调用层**，不像本项目的 ReAct 循环那样插在"每次工具执行"的位置。

### 11.4 结构化输出

```java
record Booking(String room, String date, String start, String end) {}

Booking booking = chatClient.prompt()
        .user("从这段话里抽取出预订信息：...")
        .call()
        .entity(Booking.class);
```

框架会让模型按目标类型的 JSON Schema 输出，并自动反序列化。适合"信息抽取"类任务；本项目让模型直接调工具，不需要它。

### 11.5 多模态

```java
chatClient.prompt()
        .user(u -> u.text("这张图里有什么？")
                .media(MimeTypeUtils.IMAGE_PNG, new ClassPathResource("a.png")))
        .call()
        .content();
```

支持图片、音频等输入（取决于模型）。本项目是纯文本业务场景。

### 11.6 MCP（Model Context Protocol）

Spring AI 1.x 提供 MCP client/server starter，让 Agent 通过标准协议连接外部工具服务。**本项目的历史实现**曾经自己手写 MCP 服务端、再让 Agent 以 MCP 客户端 HTTP 自环调用，后来删掉了（决策 06）——原因是它只服务"预留拆分部署"这个从未兑现的目标，却让每次调用多一跳 HTTP、读代码要跨协议追踪。现在工具层是进程内方法调用。

知道这个背景很重要：**不是"用 MCP 才现代"，而是按实际边界选通信方式**。

---

## 12. 项目里用到的其它框架（读代码时会遇到）

### 12.1 RestClient（Spring 6）

`RestTemplate` 的现代替代品，链式调用：

```java
restClient.post().uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload)
        .retrieve()
        .toEntity(String.class);
```

**`retrieve()` 与 `exchange()` 的区别（重要）**：

- `retrieve()`：4xx/5xx 直接抛 `RestClientResponseException`，把 HTTP 错误翻译成异常流；
- `exchange((request, response) -> ...)`：不抛，原始状态码和响应体交给你处理。

转发器用 `exchange()`，因为"上游 500"不是一个需要平台处理的异常，而是要原样转成工具结果交回模型。→ `GenericHttpForwarder`

超时分两段：connect timeout（连不上）与 read timeout（连上了不回），都从 `ForwardProperties` 读。前者可确认请求未送达；后者请求可能已被业务系统执行，平台按"结果未知"回喂，写操作提示先查询确认（见 `integration/BUSINESS-SYSTEM-INTEGRATION.md §3.10`）。

### 12.2 SSE（Server-Sent Events）

本质是一个**不结束的 HTTP 响应**，服务端持续写帧：

```
event: tool_call
data: {"tool":"createBooking","arguments":"{...}"}
```

浏览器用 `EventSource` 自动解析并重连。比 WebSocket 简单，单向推送足够。

项目实现要点：

- `SseEmitter` 由 `ChatController` 创建并立即返回，循环在独立线程池跑；
- 事件类型集中在 `EventType`，由 `AgentEventEmitter` 封装；
- 所有连接共享一个守护调度器，每 15 秒发一条 comment 心跳，防止代理 idle timeout 断链；
- 连接断开后事件静默丢弃，循环在下一步前停止。

### 12.3 MyBatis-Plus

- **`BaseMapper`**：继承即得 insert/selectById/update/delete，零 SQL。项目里 Mapper 接口都是空体。
- **`QueryWrapper`**：链式拼 where，字段名是数据库列名（蛇形）：`.eq("session_key", k).orderByAsc("id").last("LIMIT 200")`。
- **`@TableName` / `@TableId`**：实体与表映射；驼峰转下划线靠 `map-underscore-to-camel-case`。
- **`@Version` 乐观锁**：更新自动带版本条件。本项目暂无并发写争用场景，未启用。
- **逻辑删除（`@TableLogic`）刻意不用**：审计要完整轨迹，删不删由业务表自己决定。

### 12.4 Lombok

- `@Data`：getter/setter/equals/hashCode/toString。MyBatis-Plus 靠 setter 回填查询结果。
- `@Slf4j`：生成 logger，不用手写 `LoggerFactory.getLogger`。
- `@RequiredArgsConstructor`：为所有 final 未初始化字段生成构造器，Spring 单构造器注入免写 `@Autowired`。
- 注意：JDK 23 起注解处理器不再自动从 classpath 发现，必须在 `maven-compiler-plugin` 配 `annotationProcessorPaths`（见 `pom.xml` 注释）。

### 12.5 线程池

对话是**长时占用型任务**，线程池参数必须显式控制：

| 参数 | 值 | 说明 |
|---|---|---|
| corePoolSize | 4 | 常驻线程 |
| maximumPoolSize | 8 | 上限；注意是**队列满后才扩线程** |
| keepAliveTime | 60s | 非核心线程空闲回收 |
| workQueue | `LinkedBlockingQueue(64)` | 有界队列，拒绝策略才有意义 |
| threadFactory | `agent-loop-N` + daemon | 线程 dump 可定位，不阻 JVM 退出 |
| handler | `AbortPolicy` | 拒绝时快速失败，SSE 推"系统繁忙" |

不用 `Executors` 工厂方法的原因：`newFixedThreadPool` 队列无界（堆积 OOM），`newCachedThreadPool` 线程数无上限（线程爆炸）。

---

## 13. 按这个顺序读代码

### 第一遍：主链路（半天）

1. `ChatController.chat()`：怎么建 SSE、怎么提交线程池、身份怎么跨线程传。
2. `OpenAiPlanner.decide()`：一次模型调用到底发了什么。
3. `AgentRunner.run()`：循环的结构，重点看四件事——历史组装、toolCalls 分派、ToolResponseMessage 回填、失败如何变成文本。
4. `DynamicToolRegistryImpl.GatewayToolCallback`：工具 schema 怎么给到模型。

带着问题读：**模型说的每一句话，在代码里对应哪一行？**

### 第二遍：权限与工具层（半天）

1. `ToolAccessService.listForModel()` / `invoke()`：三道闸门在哪。
2. `ConfirmManagerImpl`：挂起与恢复。
3. `GenericHttpForwarder`：参数怎么拼、失败怎么包装。
4. `OpenApiImportServiceImpl` + `ContractSyncServiceImpl`：契约怎么变工具、怎么对账。

### 第三遍：知识库（按需）

1. `KnowledgeConfig`：手动装配了哪些 Bean、为什么。
2. `KnowledgeIndexService`：切块、Document 构造、版本化写入。
3. `KnowledgeSearchService`：SearchRequest、过滤表达式、关键词兜底。
4. `KnowledgeSearchTool`：为什么它是工具、又为什么不过工具层。

### 第四遍：测试

从 `AgentRunnerTest`、`AgentSelfHealingTest`、`OpenAiImportServiceTest` 看"每个设计承诺怎么被验证"。测试名本身就是需求清单。

---

## 14. 踩坑清单（新增代码前先看）

| 现象 | 原因 | 处理 |
|---|---|---|
| 工具调用参数丢了 `enum`/`default` | 解析契约时只拷了 `type/format` | 参数 schema 整体保留（本项目已修） |
| 第二次请求 400，提示 tool call 未响应 | `AssistantMessage(toolCalls)` 与 `ToolResponseMessage` 未成对或 id 不匹配 | 每个 `call.id()` 都要有对应 `ToolResponse` |
| 隐式执行导致无法插桩 | 默认 `internalToolExecutionEnabled=true` | 显式设 false，自己驱动循环 |
| thinking 模式下 temperature 不生效 | 供应商行为 | 关 thinking（§5.4） |
| yml 的 `extra-body` 静默失效 | 该版本绑定问题 | 用 `OpenAiChatOptions.extraBody(...)` |
| embedding 走错供应商 | starter 复用 chat 的 base-url/api-key | `spring.ai.model.embedding: none` + 手动装配 |
| pgvector 报维度不匹配 | 换模型后维度变了 | `dimensions` 与模型一致，建表阶段暴露 |
| 检索结果里混杂无权内容 | 先 topK 后过滤 | 过滤写进 `filterExpression` |
| 工具结果撑爆上下文 | 大响应原样回填 | 8KB 截断 |
| 模型"看不到任何工具" | MySQL 不可用，清单刷新拿到空桶 | 保留旧桶，失败不覆盖（`DynamicToolRegistryImpl.refresh`） |
| 流式输出与显式循环冲突 | 流式拿不到完整 toolCalls 决策 | 平台事件流与模型 token 流分开 |

---

## 15. 术语表

| 术语 | 含义 |
|---|---|
| Token | 模型处理文本的最小单位（约 1–2 个汉字或半个英文单词），计费与上下文长度的单位 |
| Prompt | 发给模型的完整输入，包含 system、历史、用户消息 |
| Completion | 模型生成的输出部分 |
| Tool Calling / Function Calling | 模型输出"要调用某函数及参数"，由外部代码实际执行 |
| ReAct | Reason + Act：模型先规划、再调用工具、看结果、继续决策的循环 |
| Embedding | 把文本映射为稠密向量，用于语义检索 |
| Vector Store | 存储向量并支持相似度检索的数据库（本项目用 pgvector） |
| RAG | Retrieval-Augmented Generation：检索资料后拼进 prompt 再生成 |
| Agentic RAG | 把检索做成工具，由模型自主决定何时查、查什么 |
| Advisor | Spring AI 的调用拦截器链，可在请求前后插入逻辑 |
| MCP | Model Context Protocol，模型与外部工具服务的标准协议 |
| 隐式/显式工具执行 | 框架自动跑完工具循环 / 框架只返回决策、由你的代码执行 |

---

## 16. 关联文档

- `docs/architecture/ARCHITECTURE.md`：分层、三道权限闸门、一次请求的完整路径
- `docs/architecture/CODE-TOUR.md`：逐文件导览与测试清单
- `docs/architecture/KNOWLEDGE-RAG.md`：切块、检索与 Agent 自主调用的设计权衡
- `docs/highlights/03-ReAct循环与SSE.md`：显式循环的生产细节与追问
- `docs/integration/BUSINESS-SYSTEM-INTEGRATION.md`：契约要求与失败语义
- `docs/decisions/`：每个关键取舍的背景与备选方案
