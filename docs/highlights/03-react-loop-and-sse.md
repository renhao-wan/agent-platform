# 亮点 ③：ReAct 显式循环与 SSE 执行栈

> 一句话：**工具执行权在代码里，不在 Spring AI 里**——每一轮只拿模型的决策，由循环手动执行、手动回填、逐步推送事件。
>
> 代码入口：`agent/loop/AgentRunner`、`agent/loop/impl/OpenAiPlanner`、`agent/sse/ChatController`、`agent/sse/AgentEventEmitter`、`agent/confirm/ConfirmManagerImpl`
> 背景决策：`docs/adr/ADR-004-tool-routing-deferred.md`、`docs/adr/ADR-006-in-process-tool-invocation.md`

## 1. 30 秒版本

```
用户一句话
  → ChatController 建 SseEmitter，把任务丢进自定义线程池（agent-loop-*）
  → AgentRunner：
      载入历史 → ContextTrimmer 裁剪
      循环（maxSteps=8）：
        Planner.decide(history, tools)
        ├─ 无 toolCalls → answer + done
        └─ 有 toolCalls → 逐个执行
             敏感？ConfirmManager 挂起 → 推 confirm_request → 等用户
             平台内部工具？recallHistory / search_knowledge 本地执行
             业务工具？ToolGateway(ToolAccessService 闸门2) → HTTP 转发
             结果包成 ToolResponseMessage 回填 → 下一轮
  → SSE 事件：session / plan / tool_call / tool_result / confirm_request / answer / error / done
```

## 2. 最关键的一行

```java
// OpenAiPlanner 构造 ChatClient 时
.internalToolExecutionEnabled(false)
```

Spring AI 默认会自己执行工具。关掉之后，每轮只拿回模型的 `toolCalls` 决策，执行权收归 `AgentRunner`。这是 SSE 分阶段事件、敏感确认挂起、执行点鉴权能够存在的前提——如果让框架隐式执行，中间没有插桩点。

## 3. 线程与连接模型

- **请求线程只负责建立连接**：`ChatController` 返回 `SseEmitter`，把循环提交给显式 `ThreadPoolExecutor`，长连接不占 Tomcat 业务线程。
- **线程池参数**：核心 4 / 上限 8 / 有界队列 64 / `AbortPolicy` / 命名 `agent-loop-N` / daemon。拒绝时返回友好错误而不是静默丢任务。
- **身份显式跨线程**：`UserContext.get()` 在 HTTP 线程取值，作为参数传给 `AgentRunner.run(...)`。ThreadLocal 不跨线程池。
- **事件发送串行化**：`AgentEventEmitter.send` 是 `synchronized`，避免多个工具结果并发写同一个 SSE 连接。

## 4. 深挖问题清单

**Q：为什么不用 Spring AI 的隐式工具执行？**
隐式执行把"模型决策 → 工具执行 → 观察回填"藏在框架里，中间无法插桩。关掉后才能在每步做：SSE 事件、敏感确认挂起、角色校验、步数熔断、轨迹落库。**执行权必须在我们自己的代码里**。

**Q：为什么用自定义线程池，不用 `Executors.newFixedThreadPool`？**
`Executors` 工厂方法用无界队列，任务堆积会 OOM；也不能命名线程、不能选拒绝策略。显式 `new ThreadPoolExecutor` 七个参数都可见，队列有界、拒绝有策略、线程可定位。

**Q：40 并发压测的意义是什么？**
验证长连接不占容器线程、事件推送不互相阻塞：40 并发 200/200，P50 1.03s / P99 1.35s，分位差小于 330ms。数据来自一次性压测脚本（脚本未随仓库发布），口述时说明方法即可。

**Q：工具调用失败会不会打断对话？**
不会。后端非 2xx、超时、权限不足、用户拒绝、非法 JSON，全部包装成 `isError=true` 的文本观察回填，模型自己决定重试、换参数还是向用户说明。循环只有两个出口：模型不再调用工具，或达到 `maxSteps`。

**Q：maxSteps 为什么是 8？**
ReAct 的典型链路是"查可用性 → 创建 → 回答"两三步；8 步给重试和纠错留余量，同时防止模型陷入死循环。超限返回明确错误事件，不静默截断。

**Q：确认挂起是怎么实现的？**
`ConfirmManager.request` 生成 token 并创建 `CompletableFuture`，循环 `await` 等待；`POST /api/v1/confirm` 用 token 恢复。超时视为拒绝。单实例内存实现，重启丢挂起项即拒绝——语义安全。

**Q：`recallHistory` / `search_knowledge` 为什么不过工具层？**
它们是平台内部能力，不是业务系统提供的工具。由 `AgentRunner` 本地执行，不参与权限闸门与敏感确认；`search_knowledge` 的权限过滤在检索 SQL 内完成（内容一旦进上下文就收不回）。

**Q：工具数量多了，模型选不准怎么办？**
预留 `ToolSelector` 责任链（ADR-004）：工具数未达阈值（默认 20）整链休眠、全量直通；达到阈值后逐环尝试，节点异常或低置信弃权传给下一环，全部弃权回落全量。当前规模处于休眠状态，收益为负时不付出开销。

**Q：为什么 Agent 和工具层是同进程调用？**
曾经是 Agent 以 MCP 客户端身份 HTTP 自环调自己的网关。那套设计只服务"预留拆分部署"这个从未兑现的目标，代价是每次调用多一跳 HTTP、异常跨协议层。ADR-006 删除后，`ToolGateway` 变成进程内方法调用；边界仍然清晰——将来真拆分，换一个实现即可。

**Q：一次对话的状态存在哪？**
`chat_message` 即写即落库，重启不丢；`checkpoint` 存裁剪摘要；`llm_usage` 记模型用量。确认项是内存态，重启即失效。

## 5. 实测证据

- `AgentRunnerTest`：执行 / 同意 / 拒绝 / 超步数四态；
- `AgentSelfHealingTest`：WireMock 后端首次 500、二次 200，循环不中断，两次观察都回填落库；
- 真实会话：模型首次调用时间格式错误 → 400 → 自动修正重试成功；
- 敏感操作：confirm_request → 用户同意 → 执行 → CANCELLED 全链路实测。

## 6. 已知边界

- `ConfirmManager` 单实例内存态；
- 线程池参数是静态配置，没有按负载动态调整；
- SSE 断开后循环继续落库，但没有断线重连续传；
- 没有分布式追踪/请求 ID 贯穿模型调用与工具转发（日志靠 sessionKey 关联）。

## 7. 相关文档

- `docs/ARCHITECTURE.md §3`：完整请求时序图
- `docs/CODE-TOUR.md`：`AgentRunner` / `ChatController` 逐文件说明
- `docs/adr/ADR-006-in-process-tool-invocation.md`：删除 MCP 自环的理由
- `docs/adr/ADR-004-tool-routing-deferred.md`：路由责任链的触发条件
