# 代码审查记录：安全、正确性与架构一致性

> 审查日期：2026-10-20。范围为 `agent-platform` 全部 `src/main`、`src/test` 与部署配置（`application.yml`、`schema.sql`、`docker-compose.yml`、前端 `index.html`）。
>
> 方法：只读通读，**未运行测试、未修改代码**。下文行号基于 2026-10-20 的 `main`；代码变更后行号可能漂移，以符号名为准。
>
> 状态列随修复更新：`待修复` / `已修复` / `接受`。修复一项后请同步更新本表与对应小节。

## 状态总览

| # | 级别 | 问题 | 影响 | 状态 |
|---|---|---|---|---|
| H1 | 高 | 管理员启停工具会清空 `required_permission` 与服务凭证 | 闸门 2 失效；业务调用 401 | 已修复 |
| H2 | 高 | 未配置 `JWT_SECRET` 时静默使用公开默认密钥 | 可伪造任意 uid（含 admin）的 token | 已修复 |
| H3 | 高 | 会话归属对"不存在的会话"无条件放行 | 孤儿会话可被他人读写；会话固定 | 已修复 |
| M1 | 中 | 密码为无盐 SHA-256，登录无限流 | 离线爆破、在线撞库 | 已修复 |
| M2 | 中 | 确认 token 不绑定用户/会话 | 任意登录者可批准他人敏感操作 | 已修复 |
| M3 | 中 | Actuator 公开且 `show-details: always` | 暴露 DB/磁盘细节与内部指标 | 已修复 |
| M4 | 中 | `loadHistory` 取**最早** 200 条 | 长会话丢失最新上下文 | 已修复 |
| M5 | 中 | SSE 断开后循环与确认挂起仍占线程 | 8 线程可被长时间占满 | 已修复 |
| M6 | 中 | 同一 `sessionKey` 并发对话无串行化 | 消息交错、checkpoint 重复 | 已修复 |
| M7 | 中 | LLM 调用与路由调用无显式超时 | 单次挂死长期占住 loop 线程 | 已修复 |
| M8 | 中 | 用户拒绝敏感工具时缺 `tool_result` 事件 | 前端轨迹与权限不足分支不一致 | 已修复 |
| M9 | 中 | `ToolCallback.call()` 以 `null` 身份执行 | 误开隐式执行时安全语义静默改变 | 已修复 |
| L1 | 低 | 鉴权 URI 精确匹配，矩阵参数可绕过过滤器 | `principal` 为 null → 500 | 已修复 |
| L2 | 低 | 路径参数用 `URLEncoder`，空格编码为 `+` | 路径段错误 | 已修复 |
| L3 | 低 | 知识检索 INFO 日志记录完整 query | 敏感内容进入日志系统 | 已修复 |
| L4 | 低 | `ConfirmManagerImpl.await` 中断不清理、存在小竞态 | 轻度内存残留；极快确认可能被当作拒绝 | 已修复 |
| L5 | 低 | `sessionKey` 无格式/长度校验 | 超长值触发 SQL 异常 | 已修复 |
| L6 | 低 | `DELETE` 默认 `requireConfirm=1`，未标注为平台默认 | 排障时可能误判为契约声明 | 已修复 |
| L7 | 低 | 测试残留旧文案"需要 ADMIN 角色" | 误导维护者 | 已修复 |
| L8 | 低 | `AuthController` javadoc 已过时 | 文档漂移 | 已修复 |
| L9 | 低 | token 存 localStorage；缺失 `LLM_API_KEY` 启动不报错 | XSS 可窃取；故障延迟暴露 | 已修复 |

**整体判断：good。** 分层、核心不变量与测试覆盖都比较扎实；风险集中在两处——**部署默认安全**与**身份/会话边界**——以及一个会破坏权限声明的数据写入 bug（H1）。设计本身不需要推倒重来。

---

## 1. H1：`setEnabled` 触发 `ALWAYS` 策略，清空权限声明与服务凭证

**位置**：`gateway/registry/impl/ToolRegistryServiceImpl.java:160-172`；`gateway/registry/entity/ToolDefinition.java:35-65`

```java
ToolDefinition update = new ToolDefinition();
update.setId(id);
update.setEnabled(enabled ? 1 : 0);
update.setUpdatedAt(LocalDateTime.now());
mapper.updateById(update);
```

`description`、`requiredPermission`、`authHeaderName`、`authHeaderValue` 标了 `FieldStrategy.ALWAYS`——MyBatis-Plus 会把**值为 null 的字段也写入 SET 子句**。因此一次启停操作生成的 SQL 实际是：

```sql
UPDATE tool_definition
SET enabled = ?, updated_at = ?, description = NULL,
    required_permission = NULL, auth_header_name = NULL, auth_header_value = NULL
WHERE id = ?
```

后果：工具变成"全员可用"（闸门 2 失去依据），转发不再携带服务凭证，模型也失去描述。现有 `setEnabledTogglesOnlyEnabledFlag` 用 mock mapper，看不到 SQL，因此测试无法发现。

**修复**：回写整行，或用 `UpdateWrapper` 只更新两列。

```java
ToolDefinition tool = mapper.selectById(id);
if (tool == null) {
    return false;
}
tool.setEnabled(enabled ? 1 : 0);
tool.setUpdatedAt(LocalDateTime.now());
mapper.updateById(tool);
```

**回归测试**：需要能观察 SQL 的测试（H2/Testcontainers），单测至少断言传入实体包含完整的 `requiredPermission` 与凭证字段。同时把"不得用 partial 实体调用 `updateById`"写进实体注释。


> **修复**：`setEnabled` 改为先 `selectById` 取完整实体，再改 `enabled` 回写；单测断言 `name`/`description`/`urlTemplate` 等字段被原样保留。
---

## 2. H2：默认 JWT 密钥可伪造管理员身份

**位置**：`agent/auth/JwtService.java:33-40`；`docker-compose.yml`；`.env.example`

`JwtService` 在缺少 `JWT_SECRET` 时静默回退到仓库内公开常量 `dev-jwt-secret-please-change`；compose 与 `.env.example` 都没有该项。按 README 的默认部署路径启动后，任何能看到仓库的人都能自行签发：

```json
{"uid": 1, "iat": 0, "exp": 9999999999}
```

`JwtAuthFilter` 每次请求用 `loadPrincipal(uid)` 回库，签名就是唯一凭证；种子 `admin` 的 id 通常为 1。JWT 只带 uid 是正确设计（ADR-010），但前提是密钥不可预测且必须显式注入。

**修复**：缺失或过短时拒绝启动。

```java
public JwtService(@Value("${JWT_SECRET:}") String secret) {
    if (secret == null || secret.isBlank() || secret.length() < 32) {
        throw new IllegalStateException("JWT_SECRET 未配置或长度不足 32 字节；拒绝使用默认密钥启动");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
}
```

配套：compose 使用 `${JWT_SECRET:?JWT_SECRET must be set in .env}`，`.env.example` 增加生成说明；`src/test/resources/application.yml` 提供测试专用值，避免 `@SpringBootTest` 因 fail-fast 失败。


> **修复**：`JwtService` 在密钥缺失或不足 32 字节时抛 `IllegalStateException` 拒绝启动；compose 用 `${JWT_SECRET:?...}` 强制注入，`.env.example` 与 README 补 `openssl rand -base64 48` 说明，测试资源提供固定密钥。
---

## 3. H3：不存在的会话被放行，制造可共享的孤儿会话

**位置**：`agent/memory/impl/AgentSessionServiceImpl.java:124-128`；`agent/sse/ChatController.java:94-105`

```java
return findBySessionKey(sessionKey)
        .map(session -> session.getUserId() == null || session.getUserId().equals(userId))
        .orElse(true);
```

`ChatController` 在请求带 `sessionKey` 时只做归属校验、不创建会话行；`appendMessage` 直接写 `chat_message`。于是自造 UUID 可以：

1. 通过校验并向 `chat_message` 写入消息，而 `chat_session` 永远没有对应行（孤儿会话）；
2. 被任何其他用户用同一 key 读写（仍然命中 `orElse(true)`）；
3. 不出现在会话列表，也无法通过 `deleteSession` 删除（无会话行）。

**修复**：授权判断 deny by default；兼容逻辑单独收敛。

```java
return findBySessionKey(sessionKey)
        .map(session -> session.getUserId() != null && session.getUserId().equals(userId))
        .orElse(false);
```

`ChatController` 对"带 key 但查无会话"返回 403 语义的 SSE 错误，**不要静默创建**——静默创建等于给攻击者一个可固定的会话。历史 `user_id IS NULL` 的会话做一次性迁移（认领或删除）；若必须保留"续聊放行"，把它做成显式迁移开关，不要混入日常归属判断。

同时给 `sessionKey` 加 UUID 形状与长度校验。


> **修复**：`ownsSession` 改为 `orElse(false)` 且要求 `userId` 非空；`ChatController` 对携带 `sessionKey` 的请求只校验归属、不静默创建，失败返回 403 语义的 SSE 事件；`sessionKey` 增加 UUID 形状校验。
---

## 4. M1：密码存储与登录防护

**位置**：`agent/auth/impl/AuthServiceImpl.java:36`；`schema.sql:177-185`；`data.sql`

- 无盐 SHA-256：彩虹表与 GPU 爆破几乎不设防，相同密码摘要相同；
- `String.equals` 非恒定时间比较；
- `POST /api/v1/auth/login` 无失败计数、锁定或限流；
- 用户不存在直接返回，仍存在可测量的时间差（文案统一值得保留）。

**修复**：改为 PBKDF2（JDK 自带，无需新依赖），存量 SHA-256 校验通过后原地升级。

```java
// pbkdf2$<iterations>$<saltBase64>$<hashBase64>
static boolean verify(String raw, String stored) {
    if (stored.startsWith("pbkdf2$")) {
        // 解析参数 → PBKDF2 → MessageDigest.isEqual 恒定时间比较
    }
    // 兼容旧摘要；成功后在登录路径中重写为 pbkdf2
    return MessageDigest.isEqual(sha256Hex(raw).getBytes(UTF_8), stored.getBytes(UTF_8));
}
```

另加按用户名 + IP 的失败限流（例如 5 次 / 5 分钟），并从公共 API 移除 `sha256Hex`。生产部署必须更换 `data.sql` 的种子口令。


> **修复**：新增 `PasswordHasher`（PBKDF2-HMAC-SHA256、210000 次迭代、16 字节随机盐、恒时比较），旧 SHA-256 摘要登录成功后原地升级；新增 `LoginThrottle`（5 次/5 分钟，超限 429）与 `TooManyLoginAttemptsException`；`data.sql` 种子口令改为 PBKDF2。
---

## 5. M2：确认 token 不绑定发起者

**位置**：`agent/confirm/ConfirmController.java:33-36`

任意已登录用户只要拿到 `confirmToken`，就能批准或拒绝他人的敏感操作。`PendingCall` 已包含 `sessionKey`，`AgentRunner` 也持有 `principal`，信息是齐的。

**修复（最小）**：

```java
ConfirmManager.PendingCall pending = confirmManager.pendingOf(request.confirmToken());
UserPrincipal me = UserContext.get();
if (pending == null || me == null
        || !sessionService.ownsSession(pending.sessionKey(), me.uid())) {
    return new ConfirmResult(false);
}
return new ConfirmResult(confirmManager.complete(request.confirmToken(), request.approved()));
```

**修复（更彻底）**：在 `request(...)` 时把 `uid` 写入 `PendingCall`，`complete` 时直接比对，不依赖会话归属的间接推导。


> **修复**：`PendingCall` 增加 `userId`，`request(...)` 写入发起者，`ConfirmController` 在恢复时比对当前用户，不一致按未知 token 处理。
---

## 6. M3：Actuator 暴露面

**位置**：`application.yml:126-131`；`JwtAuthFilter.requiresAuth`

`health` 与 `prometheus` 未鉴权，且 `show-details: always`。建议：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

Prometheus 的定时对账指标确实有价值（见 `RECONCILIATION-BACKLOG.md` 第 5 项），但抓取端点应走独立 `management.server.port` + 网络隔离或 Basic Auth，而不是与业务端共用 8000。


> **修复**：`show-details` 改为 `never`；Actuator 移到独立管理端口 `8081`（`MANAGEMENT_PORT`），compose 不发布该端口，业务 8000 不再暴露指标。
---

## 7. M4：长会话只加载开头 200 条

**位置**：`agent/memory/impl/AgentSessionServiceImpl.java:55-58`；`SessionController.java:42`

```java
.orderByAsc("id").last("LIMIT 200")   // 取最早，不是最近
```

约 50~80 轮后触顶：Agent 看不到最近上下文，历史面板也只显示前半段。`ContextTrimmer` 只能裁短，无法补回未加载的数据。

**修复**：

```java
List<ChatMessage> recent = messageMapper.selectList(new QueryWrapper<ChatMessage>()
        .eq("session_key", sessionKey)
        .orderByDesc("id")
        .last("LIMIT 200"));
Collections.reverse(recent);
return recent;
```


> **修复**：`loadHistory` 改为 `ORDER BY id DESC LIMIT 200` 后在内存反转，保证取到最近 200 条且按时间正序；新增回归测试断言 SQL 与顺序。
---

## 8. M5 / M6：线程与并发

**M5（SSE 断开）**：`AgentEventEmitter` 在发送失败后只置 `closed=true`，循环继续；若正卡在 `confirmManager.await(token, 300)`，一个线程最多被占 5 分钟。线程池 core 4 / max 8，8 个这样的请求即触发 `AbortPolicy`。

建议：注册 `emitter.onCompletion/onError` 设置取消标志，`AgentRunner` 每步检查；断开状态下遇到 confirm 直接按拒绝处理。

**M6（同会话并发）**：两个请求交错 `loadHistory` / `appendMessage`，会导致消息顺序错乱与重复 checkpoint。前端 `busy` 只防单页。建议按 `sessionKey` 做 `ConcurrentHashMap<String, Lock>` + `tryLock`，抢不到直接返回"该会话正在处理中"；或数据库层加版本号。


> **修复**：`AgentEventEmitter` 增加 `isClosed()`，`ChatController` 注册 `onCompletion/onTimeout/onError` 回调；`AgentRunner` 每步开头检查断开即退出，断开时敏感操作直接按拒绝处理。新增 `SessionLockRegistry` 按 `sessionKey` 互斥，抢不到锁返回"该会话正在处理中"并以 `done` 收尾，执行结束与拒绝入池两条路径都释放锁。
---

## 9. M7：LLM 与路由调用缺超时

**位置**：`agent/loop/impl/OpenAiPlanner.java:65-69`；`agent/tool/impl/WattAiToolSelector.java:49-55`

转发层有明确的 2s/10s（`ForwardProperties`），但 LLM 调用与 wattai 路由的 `RestClient` 都没有 connect/read timeout。后者甚至已 import `Duration` 却未使用。

建议：给 wattai 的 `RestClient` 设置 `SimpleClientHttpRequestFactory` 超时（如 2s/3s）；给 Spring AI ChatClient 配置显式的连接/读取超时与有限重试，并在 `AgentRunner` 侧为单轮 `decide` 设置上限。


> **修复**：新增 `RestClientCustomizer` 给 Spring AI 的 `RestClient.Builder` 配置 JDK HttpClient 连接超时（默认 5s）与读超时（默认 120s）；wattai 路由改为 `JdkClientHttpRequestFactory`（2s/3s）。
---

## 10. M8：拒绝分支事件不一致

**位置**：`agent/loop/AgentRunner.java:154-159`

权限不足分支发 `toolCall + toolResult`，用户拒绝分支只写 `resultText`。前端轨迹会跳过"已拒绝"这一步。

```java
} else if (!approved) {
    events.toolCall(call.name(), call.arguments() == null ? "{}" : call.arguments());
    resultText = "用户拒绝了该操作";
    events.toolResult(call.name(), resultText);
}
```


> **修复**：用户拒绝分支补齐 `toolCall` 与 `toolResult` 事件，与权限不足分支表现一致。
---

## 11. M9：广告位回调不应具备执行语义

**位置**：`agent/tool/impl/DynamicToolRegistryImpl.java:114-118`

`call()` 以 `null` 身份委托网关。当前不可达（`internalToolExecutionEnabled(false)`），但一旦有人打开隐式执行（不变量 1 明确禁止），未声明权限的工具会以匿名身份直接执行。建议改为显式失败：

```java
throw new UnsupportedOperationException(
        "ToolCallback 仅用于向模型广告 schema；执行必须走 AgentRunner 手动通道");
```


> **修复**：`GatewayToolCallback.call()` 改为抛 `UnsupportedOperationException`，明确回调只用于 schema 广告；测试断言该行为。
---

## 12. 低危项

| # | 位置 | 说明与建议 |
|---|---|---|
| L1 | `agent/auth/JwtAuthFilter.java:80-83` | `/api/v1/chat` 等用 `equals` 精确匹配；Spring MVC 剥离矩阵参数后 `/api/v1/chat;x=y` 可绕过过滤器，controller 内 `principal` 为 null → NPE → 500。改用 `startsWith` 或规范化 URI。 |
| L2 | `gateway/forward/GenericHttpForwarder.java:164` | `URLEncoder.encode` 把空格编码成 `+`，路径段里 `+` 是字面量。改为 `encode(...).replace("+", "%20")` 或 `UriUtils.encodePathSegment`。 |
| L3 | `knowledge/search/KnowledgeSearchService.java:181` | INFO 日志记录完整 query；审计表已承担可追溯职责，日志建议只记长度/hash 或降为 DEBUG。 |
| L4 | `agent/confirm/impl/ConfirmManagerImpl.java:30-44` | `InterruptedException` 时不清理、不恢复中断标志；`complete` 先 `remove` 再 `complete`，而 `await` 先 `get`，存在"极快点击被当作拒绝"的小竞态。建议由 `await` 在 `finally` 统一清理并恢复中断。 |
| L5 | `agent/sse/ChatController.java:56, 94-98` | `sessionKey` 无格式/长度校验；超过 `CHAR(36)` 触发 SQL 异常。服务端只接受 UUID 形状。 |
| L6 | `gateway/registry/impl/OpenApiImportServiceImpl.java:75-76` | `DELETE` 默认 `requireConfirm=1` 是 HTTP 语义级默认，不违反"平台不编码业务语义"的字面约束，但应在 ADR 或管理端标注"平台默认值，可被 `x-require-confirm=false` 覆盖"。 |
| L7 | `test/.../AgentRunnerTest.java:217` | 断言文案仍为 `"权限不足：需要 ADMIN 角色"`，与权限码语义脱节。改为 `booking:cancel` 之类。 |
| L8 | `agent/auth/AuthController.java:10` | javadoc 未覆盖当前鉴权范围（chat/confirm/tools/sessions/admin）。 |
| L9 | `static/index.html:1068, 1540`；`application.yml:24` | token 存 localStorage（当前 `mdToHtml` 先转义，风险已缓解）；`LLM_API_KEY` 缺失时启动不报错，建议启动校验或在 health 中显式反映。 |

---

**逐条修复情况**：

- L1：`requiresAuth` 先剥离矩阵参数再匹配；新增用例覆盖 `/api/v1/chat;x=y`。
- L2：路径段改用 `UriUtils.encodePathSegment`（空格 → `%20`），query 仍用 `URLEncoder`。
- L3：INFO 只记 `queryLen`/命中来源，query 原文降到 DEBUG。
- L4：`await` 在 `finally` 清理并恢复中断标志；`complete` 先完成 future 再移除。
- L5：`sessionKey` 增加 UUID 正则校验。
- L6：导入处注释明确 DELETE 默认确认是平台安全默认值、可被 `x-require-confirm=false` 覆盖。
- L7：测试文案改为权限码 `booking:cancel`。
- L8：`AuthController` javadoc 同步当前鉴权范围。
- L9：前端认证状态改用 `sessionStorage`；新增 `LlmKeyStartupCheck`，`LLM_API_KEY` 缺失时启动 WARN。

## 13. 架构一致性观察

**已落实、与文档自洽的部分：**

- 不变量 1/2/3/5/6/7/8/9 都能在代码中找到对应实现：显式执行在 `OpenAiPlanner`，闸门 2 在 `ToolAccessService`，失败包装在 `GenericHttpForwarder.exchange()`，身份显式传参在 `ChatController:91`，权限过滤在检索表达式内，`present/enabled` 分离在 `applySnapshot`，JWT 只带 uid 在 `JwtService.issue`。
- 闸门 1 "全量下发、不裁剪"的理由落在 `DynamicToolRegistryImpl.describe()`，与 `ARCHITECTURE.md §4.2` 一致。
- 契约对账的"整份快照 + 同名冲突整份放弃 + 失败保留 last-known-good"边界处理严谨；`ContractSyncServiceImpl` 的 `ReentrantLock` 可重入计数正确（`syncAll` 嵌套 `sync` 时 hold count 2→1→0）。

**两处名实不符：**

1. **`prompts/system.md` 把平台绑定为"会议预约助手"。** 它属于部署配置而非 Java 逻辑，不违反不变量 4 的字面要求，但按 CLAUDE.md 的判据——"删掉一个业务系统后，平台逻辑是否需要跟着改"——删掉 meeting-booking 后这份提示词必须改。建议改为按 `gatewayId` 可替换的资源，或在文档中明确"示例部署的提示词"。
2. **`ConfirmManager` 是单实例内存实现。** 当前演示没问题，但多实例部署会出现"确认请求落 A、恢复落 B → 永远超时"。建议在 README/ADR 显式写明"仅支持单实例"及迁移方向（共享存储 + 幂等）。

> **修复**：1）`prompts/system.md` 首句改为"你是一个业务操作助手。当前示例部署接入的是会议预约系统"，不再把平台绑定为单一业务；2）README 新增"部署边界"一节，写明 `ConfirmManager` 与 `SessionLockRegistry` 仅支持单实例及迁移方向，`ARCHITECTURE.md` 闸门 3 同步说明。

---

## 14. 修复顺序建议

1. **H1、H2**：一个破坏权限数据，一个让所有安全设计失效，优先修；
2. **H3、M2**：身份/会话边界的越权面；
3. **M4**：功能性正确问题，影响日常使用；
4. **M1、M3**：安全默认与暴露面；
5. **M5、M6、M7**：可用性/稳定性，配合压测验证；
6. **M8、M9 与低危项**：一致性与防御性改进。

每项修复请补对应测试，尤其是 H1（需要能观察 SQL 的断言）、H3（不存在会话返回 false）、M8（拒绝时 `toolResult` 被调用）。

---

## 15. 对照的最佳实践

- **Fail-fast**：安全配置缺失应在启动时报错，而不是带病运行（H2、L9）。
- **Secure by default**：Actuator 详情、无盐摘要、确认 token 不绑定身份，都是默认值选了不安全的一侧（M1、M2、M3）。
- **Deny by default**：授权中的"数据缺失"应解释为拒绝，而不是允许（H3）。
- **单一职责**：`setEnabled` 只应改 `enabled`；遇到部分更新应使用 `UpdateWrapper` 显式声明列，不依赖实体字段策略（H1）。
- **框架契约显式化**：`FieldStrategy.ALWAYS` 是为"契约字段可被清空"服务的，误用于部分更新就从"清空声明"变成"意外清空声明"。实体注释应写明"不得用于 partial update"。
- **同语义同表现**：拒绝与权限不足都是"执行前终止"，外部可观察事件应一致（M8）。
- **可观测性不等于全量记录**：日志系统通常没有与审计表同等的权限与保留策略，不应复制敏感全文（L3）。
