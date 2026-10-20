# 亮点 ③：ReAct 显式循环与 SSE 执行栈

> 一句话：**工具执行权在代码里，不在 Spring AI 里**——每一轮只拿模型的决策，由循环手动执行、手动回填、逐步推送事件。
>
> 代码入口：`agent/loop/AgentRunner`、`agent/loop/impl/OpenAiPlanner`、`agent/sse/ChatController`、`agent/sse/AgentEventEmitter`、`agent/confirm/ConfirmManagerImpl`
> 背景决策：`docs/decisions/04-工具前置路由.md`、`docs/decisions/06-进程内工具调用.md`

## 1. 30 秒版本

```
用户一句话
  → ChatController 建 SseEmitter（15 分钟超时），把任务丢进自定义线程池（agent-loop-*）
  → AgentRunner：
      载入历史 → ContextTrimmer 裁剪
      循环（maxSteps=8）：
        Planner.decide(history, tools)
        ├─ 无 toolCalls → answer + done
        └─ 有 toolCalls → 逐个执行
             敏感？先权限预判 → ConfirmManager 挂起 → 推 confirm_request → 等用户
             平台内部工具？recallHistory / search_knowledge 本地执行
             业务工具？ToolGateway(ToolAccessService 闸门2) → HTTP 转发
             结果包成 ToolResponseMessage 回填 → 下一轮
  → SSE 事件：session / plan / tool_call / tool_result / confirm_request / answer / error / done
```

## 2. 最关键的一行

```java
// OpenAiPlanner 构造 ChatOptions 时
.internalToolExecutionEnabled(false)
```

Spring AI 默认会自己执行工具。关掉之后，每轮只拿回模型的 `toolCalls` 决策，执行权收归 `AgentRunner`。这是 SSE 分阶段事件、敏感确认挂起、执行点鉴权能够存在的前提——如果让框架隐式执行，中间没有插桩点。

## 3. 线程与连接模型

- **请求线程只负责建立连接**：`ChatController` 返回 `SseEmitter`，把循环提交给显式 `ThreadPoolExecutor`，长连接不占 Tomcat 业务线程。
- **线程池参数**：核心 4 / 上限 8 / 有界队列 64 / `AbortPolicy` / 命名 `agent-loop-N` / daemon。拒绝时返回友好错误而不是静默丢任务。
- **身份显式跨线程**：`UserContext.get()` 在 HTTP 线程取值，作为参数传给 `AgentRunner.run(...)`。ThreadLocal 不跨线程池。
- **事件发送串行化**：`AgentEventEmitter.send` 是 `synchronized`，避免多个工具结果并发写同一个 SSE 连接。
- **同一会话串行**：`SessionLockRegistry.tryAcquire(sessionKey)` 拒绝并发续聊，避免两条循环交错写历史；锁在循环结束（含异常/拒绝）时释放。

## 4. 深挖问题清单

**Q：为什么不用 Spring AI 的隐式工具执行？**
隐式执行把"模型决策 → 工具执行 → 观察回填"藏在框架里，中间无法插桩。关掉后才能在每步做：SSE 事件、敏感确认挂起、权限校验、步数熔断、轨迹落库。**执行权必须在我们自己的代码里**。

**Q：为什么用自定义线程池，不用 `Executors.newFixedThreadPool`？**
`Executors` 工厂方法用无界队列，任务堆积会 OOM；也不能命名线程、不能选拒绝策略。显式 `new ThreadPoolExecutor` 七个参数都可见，队列有界、拒绝有策略、线程可定位。

**Q：40 并发压测的意义是什么？**
验证长连接不占容器线程、事件推送不互相阻塞：40 并发 200/200，P50 1.03s / P99 1.35s，分位差小于 330ms。**这组数据来自一次性压测脚本，脚本未随仓库发布**；口述时应说明"本地自测、脚本未入库"，不要说成可复现基准。

**Q：工具调用失败会不会打断对话？**
不会。后端非 2xx、超时、权限不足、用户拒绝、非法 JSON，全部包装成文本观察回填，模型自己决定重试、换参数还是向用户说明。循环只有两个出口：模型不再调用工具，或达到 `maxSteps`。

**Q：maxSteps 为什么是 8？**
ReAct 的典型链路是"查可用性 → 创建 → 回答"两三步；8 步给重试和纠错留余量，同时防止模型陷入死循环。超限返回明确错误事件，不静默截断。

**Q：确认挂起是怎么实现的？**
`ConfirmManager.request` 生成 token 并创建 `CompletableFuture`，循环 `await` 等待；`POST /api/v1/confirm` 用 token 恢复，且校验确认项属于当前用户。超时视为拒绝。单实例内存实现，重启丢挂起项即拒绝——语义安全。

**Q：确认等待 5 分钟，SSE 连接不会被代理断开吗？**
**当前没有心跳**。`SseEmitter` 本身 15 分钟超时，`confirm-timeout-seconds` 默认 300 秒；等待期间连接上没有任何数据。直连没问题，但经过 Nginx/网关时，空闲超时短于确认超时会被断开。生产应在等待期间周期性发送 SSE comment 心跳，并让代理 idle timeout 大于确认超时。这是当前实现一个明确的缺口。

**Q：客户端断开后循环会停吗？**
下一步开始前会检查 `events.isClosed()`；确认等待前若已断开，直接按拒绝处理，避免空等。但**当前步**如果已经拿到模型响应，仍会把工具执行完并落库。之后不再继续。没有断点续传。

**Q：同一会话并发发两条消息会怎样？**
`SessionLockRegistry.tryAcquire` 只允许一个循环；第二条收到"该会话正在处理中"错误事件，不进入循环。锁是单实例内存态，多实例下失效。

**Q：模型一次返回多个工具调用，会并行执行吗？**
不会。`AgentRunner` 按顺序逐个执行（confirm 需要逐个等待，且工具之间可能有依赖）。并行能降延迟，但需要处理确认顺序、依赖、同一资源竞争；当前规模不值得。这是可优化点，不是缺陷。

**Q：LLM 供应商挂起会怎样？**
`platform.agent.llm-connect-timeout-ms` 默认 5 秒、`llm-read-timeout-ms` 默认 120 秒，避免 loop 线程无限期占用。没有自动重试、故障转移或熔断；供应商整体不可用时，每个请求各自超时失败。

**Q：怎么取消一个正在执行的对话？**
没有取消 API。客户端只能断开 SSE；服务端在下一步前停止，但当前工具调用无法中断。生产可加 `POST /api/v1/chat/{sessionKey}/cancel` + 循环内取消标志，或使用可中断的 HTTP 客户端。

**Q：`recallHistory` / `search_knowledge` 为什么不过工具层？**
它们是平台内部能力，不是业务系统提供的工具。由 `AgentRunner` 本地执行，不参与权限闸门与敏感确认；`search_knowledge` 的权限过滤在检索 SQL 内完成（内容一旦进上下文就收不回）。

**Q：工具数量多了，模型选不准怎么办？**
预留 `ToolSelector` 责任链（决策 04）：工具数未达阈值（默认 20）整链休眠、全量直通；达到阈值后逐环尝试，节点异常或低置信弃权传给下一环，全部弃权回落全量。当前规模处于休眠状态，收益为负时不付出开销。

**Q：为什么 Agent 和工具层是同进程调用？**
曾经是 Agent 以 MCP 客户端身份 HTTP 自环调自己的网关。那套设计只服务"预留拆分部署"这个从未兑现的目标，代价是每次调用多一跳 HTTP、异常跨协议层。决策 06 删除后，`ToolGateway` 变成进程内方法调用；边界仍然清晰——将来真拆分，换一个实现即可。

**Q：一次对话的状态存在哪？**
`chat_message` 即写即落库，重启不丢；`checkpoint` 存裁剪标记；`llm_usage` 记模型用量。确认项与会话锁是内存态，重启即失效。

## 5. 实测证据

- `AgentRunnerTest`：执行 / 同意 / 拒绝 / 超步数 / 权限预判顺序；
- `AgentSelfHealingTest`：WireMock 后端首次 500、二次 200，循环不中断，两次观察都回填落库；
- `ConfirmManagerTest`：同意、拒绝、超时、token 不存在；
- 真实会话：模型首次调用时间格式错误 → 400 → 自动修正重试成功；
- 敏感操作：confirm_request → 用户同意 → 执行 → CANCELLED 全链路实测；
- 40 并发压测：本地一次性脚本，脚本未入库，仅作参考。

## 6. 落地程度与生产差距

| 能力 | 代码与测试证据 | 完成度 |
|---|---|---|
| 显式 ReAct 循环、手动执行与回填 | `AgentRunner` + `OpenAiPlanner`；`AgentRunnerTest` / `AgentSelfHealingTest` | 接近生产（单实例） |
| SSE 分阶段事件、异常不中断 | `AgentEventEmitter` + `ChatController`；`ChatAuthFilterTest` 等 | 接近生产 |
| 有界线程池 + 拒绝反馈 | `ChatController` 显式 `ThreadPoolExecutor` | 接近生产（参数静态） |
| 敏感确认挂起 / 超时拒绝 / 归属校验 | `ConfirmManagerImpl` + `ConfirmController`；`ConfirmManagerTest` | 单实例可用；多实例演示级 |
| 会话级串行锁 | `SessionLockRegistry`；`SessionLockRegistryTest` | 单实例可用；多实例演示级 |
| LLM 超时 | `llm-connect-timeout-ms` / `llm-read-timeout-ms` | 接近生产（仅超时，无熔断/转移） |
| SSE 心跳与断线续传 | 未实现 | 演示级 |
| 取消正在执行的对话 | 未实现 | 演示级 |
| 分布式追踪 / 请求 ID | 未实现（日志靠 sessionKey） | 演示级 |

**结论**：单实例下，"显式循环 + 分阶段 SSE + 确认挂起"是完整可用的，接近生产。横向扩展（会话锁、确认项）、长连接可靠性（心跳、续传）、运行治理（取消、配额、追踪）仍是演示级。这三点恰好也是面试官最容易追问的地方。

## 7. 后续拓展：问题与解决办法

| 拓展点 | 现在的问题 | 解决办法 | 触发条件 |
|---|---|---|---|
| SSE 心跳 | 确认等待 5 分钟期间无数据，代理可能断链 | 等待期间每 15–30 秒发 SSE comment；代理 idle timeout > 确认超时 | 部署在反向代理/网关后 |
| 断线续传 | 刷新页面后只能看历史，不能恢复当前轮 | 事件带 `id`，支持 `Last-Event-ID`；把本轮事件写入短期事件表，重连时补发 | 前端要求强一致体验 |
| 会话锁跨实例 | 多副本下同一会话可被两个实例同时处理 | Redis 分布式锁（带 TTL 与续租）或数据库行锁 | 部署副本数 > 1 |
| 确认项跨实例 | 用户请求落到另一实例时无法恢复 | `pending_confirm` 共享表 + 状态 CAS；事件经消息总线通知发起实例 | 部署副本数 > 1 |
| 取消执行 | 只能断连接，工具调用不可中断 | 增加 cancel API；循环检查取消标志；HTTP 客户端用可中断请求 | 长任务/误操作 |
| 线程池治理 | 参数静态、无队列等待指标 | 暴露 active/queue 指标；按负载调参；队列等待超时 | 并发上来后 |
| LLM 故障转移 | 供应商不可用时全部请求超时 | 多供应商/多 key 路由、熔断、降级模型；按错误类型重试 | 生产 SLA 要求 |
| 并行工具调用 | 多工具串行执行，延迟叠加 | 无依赖的读工具并行；写工具保持串行；确认按批次处理 | 单轮多工具场景变多 |
| 可观测性 | 无 traceId 贯穿模型与转发 | 生成 requestId 放 MDC，透传到转发 header 与日志；接 OpenTelemetry | 排障成本上升 |
| 成本与配额 | 只记录 usage，无预算 | 按用户/会话累计 token，超限拒绝或降级；日预算告警 | 对外运营 |

## 8. 相关文档

- `docs/architecture/ARCHITECTURE.md §3`：完整请求时序图
- `docs/architecture/CODE-TOUR.md`：`AgentRunner` / `ChatController` 逐文件说明
- `docs/decisions/06-进程内工具调用.md`：删除 MCP 自环的理由
- `docs/decisions/04-工具前置路由.md`：路由责任链的触发条件
