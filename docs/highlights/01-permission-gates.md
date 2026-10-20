# 亮点 ①：三道权限闸门——AI 越权防护

> 一句话：**模型只提议、不授权；权限校验在代码里，不在模型脑里。**
>
> 代码入口：`gateway/registry/ToolAccessService`、`agent/auth/JwtAuthFilter`、`agent/confirm/ConfirmManagerImpl`
> 背景决策：`docs/adr/ADR-007-contract-driven-authorization.md`、`docs/architecture/ARCHITECTURE.md §4`

## 1. 30 秒版本

大模型可能幻觉出不存在的工具，也可能被提示注入诱导去调管理员接口。平台把"能不能做"拆成三道闸门，其中只有第二道是安全边界：

| 闸门 | 位置 | 做什么 | 是否安全边界 |
|---|---|---|---|
| 1 清单组装 | `listForModel(gatewayId)` | 下发**全部启用工具**，不按权限裁剪；权限要求写进工具描述 | 否，体验优化 |
| 2 执行点鉴权 | `invoke(...)` | 用**请求时回库加载的权限集合**与工具声明的 `required_permission` 比对，不命中即拒绝 | **是** |
| 3 人工确认 | `ConfirmManager` + `POST /api/v1/confirm` | 敏感操作挂起，用户同意才执行，超时视为拒绝 | 否，防误操作 |

拒绝不抛异常，而是返回 `isError=true` 的文本观察（`权限不足：需要 X 权限`）。模型读得到、能向用户转述，ReAct 循环不中断。

授权数据来自轻量 RBAC：JWT 只带 `uid`，`JwtAuthFilter` 每次请求回库读取 `app_user.role` 与 `app_role_permission`，构造 `UserPrincipal(uid, role, permissions)`。降权/停用在下一个请求生效（ADR-010）。

## 2. 一次越权的完整流转

```
staff01："帮我把 3 号预订取消"（cancel 声明 x-required-permission=booking:cancel）
 ① 模型在清单里看得到 cancel，描述里已标"需要 booking:cancel 权限"
 ② 模型发起调用（正常尝试，或被注入后硬调）
 ③ ToolAccessService.invoke：STAFF 的权限集合不含 booking:cancel
 ④ 返回 isError=true 文本："权限不足：需要 booking:cancel 权限"
 ⑤ 模型转述："这个操作需要相应权限"
```

同一句话换成已被授予 `booking:cancel` 的角色，则 ③ 放行、进入闸门 3（如果该工具声明了 `x-require-confirm`），确认后真正转发到业务系统。

## 3. 关键设计

- **权限要求来自业务契约**：`x-required-permission` 由业务系统在 OpenAPI operation 上声明，平台只读不猜。平台代码里没有"取消=管理员"这类业务判断。
- **身份显式跨线程传递**：`ChatController` 在 HTTP 线程取出 `UserPrincipal`，作为参数传给线程池里的 `AgentRunner`。ThreadLocal 不跨线程池，循环内再取会拿到 null。
- **业务侧身份**：转发时附服务级凭证 `X-Service-Key` 与 `X-On-Behalf-Of-User: {uid}`；业务系统仅在服务凭证校验通过时才采信用户头。
- **闸门 2 只有二十行**：定位工具 → 查权限集合 → 不命中返回文本 → 放行给转发器。安全逻辑短，是为了能被审计和穷举测试。

## 4. 深挖问题清单

**Q：为什么闸门 1 不把没权限的工具裁掉？**
裁掉后模型看到的世界里"这个工具不存在"，它会对用户说"我没有这个功能"——把权限问题说成了能力问题。用户得不到"去找管理员开权限"这个正确结论。所以全量下发、权限要求写进描述，模型做三段式判断：提前判断 → 照常尝试 → 被拒后转述。

**Q：全量下发会不会让模型更容易被诱导去调管理员工具？**
会更容易"尝试"，但尝试不等于成功。真正的边界是闸门 2；模型幻觉或被注入后硬调，仍然会被代码拒绝。闸门 1 的作用是让模型少走弯路，不是拦住谁。

**Q：为什么鉴权不放在 prompt 或模型侧？**
prompt 是模型输入的一部分，可被注入覆盖；模型没有可信身份。鉴权必须由不经过模型的代码，用会话绑定的身份完成。

**Q：AI 幻觉出一个不存在的工具名怎么办？**
`invoke` 先按名称定位，找不到就返回 `isError` 文本（未知工具），不会执行任何东西。

**Q：拒绝为什么返回文本而不是抛异常？**
抛异常会打断 ReAct 循环，模型没有机会解释。文本观察让模型把拒绝转述给用户，决策循环继续。

**Q：确认和鉴权谁先？**
现在是**先只读预判、再确认、最后执行点鉴权**。`AgentRunner` 看到 `require_confirm` 时先调 `ToolAccessService.denial` 做权限预判：权限不足直接返回闸门 2 同款拒绝文本，不弹确认；预判通过才挂起 `ConfirmManager`，用户同意后再进入闸门 2 的强制鉴权。

这个顺序是修出来的：原先直接先确认、后鉴权，无权限用户会看到确认弹窗、点完同意才被告知"权限不足"。安全上无害（鉴权始终在执行前），体验上是把权限问题拖到了两秒之后。改动守住一条原则：**前置预判只能"跳过确认"，不能"拦截执行"**——预判只为少打扰用户，真正的安全边界仍是执行点的闸门 2。

**Q：敏感操作的判定也是模型决定吗？**
不是。`x-require-confirm` 由业务系统在契约里声明；平台额外保留 `DELETE` 天然敏感这条 HTTP 语义常识。平台不按 summary 里的"取消/退订"猜。

**Q：用户 A 能不能看用户 B 的会话/预订？**
会话入口 `SessionController` 用 `ownsSession` 校验归属，他人会话 403；业务数据归属由业务系统依据 `X-On-Behalf-Of-User` 过滤。平台不替业务系统做数据级权限。

**Q：多实例下确认挂起怎么处理？**
当前 `ConfirmManager` 是单实例内存实现，重启丢确认项即视为拒绝（语义安全）。多实例需要会话粘性或把挂起项入库——见 ADR-008 第 9 条的边界说明。

**Q：这二十行代码测过什么？**
`ToolAccessServiceTest` 覆盖闸门 1 全量下发、闸门 2 拒绝/放行/`*` 通配、未知工具、非法 JSON、转发失败回喂；`AgentRunnerTest` 覆盖确认三态与超步数；`JwtAuthFilterTest` 覆盖 401/403 与 `/admin/**` 权限映射。

## 5. 已知边界

- `ConfirmManager` 单实例内存态，不支持多实例；
- 没有按工具的调用审计表，拒绝原因目前只体现在 SSE 事件与日志中；
- 单角色 + 角色-权限映射；不做多角色、角色继承、按来源分角色与强制下线；
- 业务系统的数据级权限仍由业务系统负责，平台只透传身份。

## 6. 相关文档

- `docs/architecture/ARCHITECTURE.md §4`：闸门分层与实测证据
- `docs/architecture/CODE-TOUR.md`：`ToolAccessService` 逐行说明
- `docs/adr/ADR-007-contract-driven-authorization.md`：角色声明为何上移到契约
