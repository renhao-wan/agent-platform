# ADR-010：JWT 只承载身份，角色与权限每次请求回库加载

日期：2026-10-20　状态：已采纳

## 背景

早期实现把 `role` 写进 JWT：登录时由 `app_user.role` 取出，`JwtAuthFilter` 验签后直接信任 claim，工具执行点再用这个 role 与该工具声明的角色做相等比较。这个做法足以跑通演示，但它把**授权状态放进了调用方持有的凭证**：

- **降权/停用无法及时生效**。管理员把某人从 ADMIN 调成 STAFF，只要旧 token 没过期，对方仍以 ADMIN 身份调用。要解决就得补 token 撤销、黑名单或 token version，而这些机制本身又要求服务端状态——绕了一圈仍回到"回库查"。
- **角色与操作直接绑定，无法复用**。业务系统声明"需要 ADMIN"，平台就只能在代码里比较字符串；一旦某个角色的能力需要拆分（如"能看管理台"与"能改工具"），只能再造角色、再改业务契约，权限粒度被角色数量锁死。
- **知识库可见性无法与工具授权共用一套规则**。工具看 role，知识块也看 role，但权限码这一层缺失，导致"某角色能看到哪些文档"只能靠角色枚举。

## 备选方案

| 方案 | 生效延迟 | 服务端状态 | 代价 | 适用场景 |
|---|---|---|---|---|
| role 写进 JWT（原实现） | token 过期 | 无 | 无法及时降权；要撤销就引入黑名单 | 授权几乎不变、可接受窗口期 |
| 服务端会话（session store） | 即时 | Redis/内存 | 引入会话中间件、多实例共享、过期清理 | 需要强制下线与在线状态 |
| **每请求回库加载（当前）** | 下一个请求 | MySQL | 每请求 2 次索引查询 | 单实例、授权需要可运营 |
| token version / 黑名单 | 下一个请求 | MySQL/Redis | 登出/改密时写 version；仍需回库校验 | 需要"旧 token 立即失效" |

## 决策

1. **JWT 只证明身份**：只写 `uid`（+ iat/exp），不含角色与权限。旧 token 中残留的 `role` claim 一律忽略。
2. **授权在请求时解析**：`JwtAuthFilter` 解析 `uid` 后调用 `RbacService.loadPrincipal(uid)`，回库读取用户状态、单角色（`app_user.role`）与角色-权限映射（`app_role_permission`），构造 `UserPrincipal(uid, role, permissions)` 写入 `UserContext`。停用账号返回 401，权限不足返回 403。
3. **轻量 RBAC 数据模型**：`app_user.role` → `app_role` → `app_role_permission`。单角色、无角色继承、无按来源角色。`*` 是通配权限；平台内置 `admin:console` / `admin:source` / `admin:tool` / `admin:knowledge` / `admin:rbac`。
4. **业务契约声明权限码**：`x-required-permission`，执行点判定 `user.hasPermission(required)`。旧 `x-required-role` 已彻底移除，不做兼容归一；平台仍然不解释权限码的业务含义。
5. **管理端只做角色-权限矩阵**：查看角色与权限、覆盖式保存映射。不做角色 CRUD、用户角色分配——那些属于完整 IAM，不是本项目的目标。
6. **种子不覆盖人工配置**：`app_role.seeded` 标记默认权限是否已种过，`RbacSeeder` 只在未种过时写入。管理员在界面上改过之后，重启不会复活默认值。
7. **知识库可见性纳入同一套权限集合**：`knowledge_chunk.visible_permission`，缺省 `public`。检索 SQL 内过滤；`*` 不过滤；`visible_role` 已移除。

## 后果

- **降权/停用在下一个请求生效**，无需撤销接口。SSE 长连接在建连时快照一次身份，对话中途变更在下一个请求生效——这是有意的取舍：不在流式会话里周期性重查，避免每个 token 都打断对话。
- **每个受保护请求多 2 次主键/索引查询**（用户 + 角色权限）。授权数据量很小，MySQL 本地查询远低于一次 LLM 调用的量级；如未来成为瓶颈，可在 `loadPrincipal` 上加短 TTL 缓存，但不能把缓存做成"永不过期"。
- **权限码成为平台与业务的共同词汇**：业务系统定义权限码，管理员决定哪些角色拥有它。删掉一个业务系统后，平台代码不需要跟着改；残留的权限码只影响管理端可选项，不影响鉴权正确性。
- **明确不做**：token 撤销/强制下线、多角色、角色继承、按来源角色、细粒度资源级权限（如"只能取消自己的预订"由业务系统自行校验）。

## 相关

- [ADR-007](ADR-007-contract-driven-authorization.md)：权限要求由业务契约声明，平台不编码业务语义。
- [ADR-008](ADR-008-contract-reconciliation.md)：契约对账；`required_permission` 参与契约指纹。
- [KNOWLEDGE-RAG.md §8](../KNOWLEDGE-RAG.md)：知识库权限过滤为何必须发生在检索 SQL 内。
