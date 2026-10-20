# agent-platform

> 轻量级 **AI 助手平台**（Java / Spring Boot 3）——任何后台服务凭一份 OpenAPI 文档即可将接口**零代码**注册为大模型工具；Agent 运行时让大模型**安全地**（越权拦截 / 敏感确认 / 上下文裁剪）使用工具完成真实业务操作，SSE 全程直播推理过程。
>
> **定位：员工的统一入口**。平台自己完成登录与对话，业务系统无需 AI 改造；它不是供其他前端直接复用的 AI 中间件（身份接入与用户映射尚未实现）。当前只覆盖静态服务凭证与全量契约导入，其余能力是明确的非目标，见 [docs/architecture/ARCHITECTURE.md §8](docs/architecture/ARCHITECTURE.md)。

## 架构

```
        浏览器单页控制台（EventSource）
             │ HTTP + SSE
             ▼
┌─────────────────────────────────────────────┐
│ agent-platform :8000                        │
│                                             │
│  agent 模块（自研）                           │
│   ReAct 循环：规划→调工具→观察→反思→回答       │
│   上下文裁剪 / Token 打点 / 敏感操作确认        │
│         │ ToolGateway（进程内方法调用）        │
│  gateway 模块（工具层）                        │
│   OpenAPI→Tool 自动注册                       │
│   清单组装 · 执行点鉴权 · 泛化 HTTP 转发       │
└─────────┬───────────────────────────────────┘
          │ 泛化 HTTP 转发
          ▼
   业务系统（meeting-booking 等任意 OpenAPI 服务）
```

## 核心特性

1. **进程内工具调用**：Agent 与工具层同进程，越权校验、泛化转发、结果回喂都在一次方法调用内完成——没有协议层、没有网络跳转
2. **零代码工具接入**：存量 OpenAPI 接口自动解析注册为标准化 Tool（path/query/body 参数 → inputSchema，文档内 `$ref` 就地对模型展开）；权限要求与敏感度由业务系统在契约里用扩展字段声明（`x-required-permission` / `x-require-confirm`），平台不猜测接口语义。业务后端需要满足的条件与"不满足时平台会怎样"见 [接入规范](docs/integration/BUSINESS-SYSTEM-INTEGRATION.md)
3. **ReAct 显式循环**：规划-执行-反思逐步驱动，SSE 分阶段推送（plan / tool_call / tool_result / confirm_request / answer）
4. **上下文工程**：滑动窗口 + 工具轨迹摘要混合裁剪，Token usage 全链路打点
5. **轻量 RBAC + 三道权限闸门**：JWT 只带身份（uid），角色与权限每次请求回库加载——停用/降权下一个请求即生效；管理端提供角色-权限矩阵。三道闸门分别为清单组装（把能力与权限要求一并下发给模型——裁掉会让模型把权限问题答成能力问题）· 执行点鉴权（★ 安全边界）· 敏感操作人工二次确认。校验依据是代码里的用户权限集合，不是模型说了什么
6. **Agentic RAG**：制度文档按 Markdown 标题切块、embedding 入 pgvector；检索封装成内部工具 `search_knowledge`，由模型在循环中自主决定何时查、用什么 query。可见性在检索 SQL 内过滤，无权内容不进候选集（[设计](docs/architecture/KNOWLEDGE-RAG.md)）

## 快速开始

```bash
# 1. 填模型 API Key 与 JWT 密钥（.env 已 gitignore，照 .env.example 复制一份即可）
cp .env.example .env
#    编辑 .env，填入 LLM_API_KEY（供应商与模型名见 LLM_BASE_URL / LLM_MODEL）
#    JWT_SECRET 必填（至少 32 字节）：openssl rand -base64 48
#    需要知识库检索时再填 EMBEDDING_API_KEY；留空则知识库模块不装配，其余功能不受影响

# 2. 起 MySQL(3307) + pgvector(5433) + 应用(8000)
docker compose up -d --build

# 3. 浏览器打开 http://localhost:8000/ 开始对话
```

种子账号：`admin/admin123`（ADMIN）、`staff01/staff123`（STAFF）。

镜像由本目录 `Dockerfile` 多阶段构建、自带 jar，无需宿主机先 `mvn package`（改了代码要 `--build` 重建）。

可选——起三个业务系统（会议室预订 :8090 + 两个 WireMock 模拟后端：报销 :8091 / 报修 :8092），用于演示跨系统聚合与差异化权限：

```bash
cd ../business-systems && docker compose up -d --build
# 再以 ADMIN 身份登录——平台启动时会自动拉取三个系统的契约并注册，无需手工导入；
# 之后默认每 5 分钟重新对账，接口新增/修改/删除会自动收敛（也可 POST /admin/sources/sync 立即触发）
# 来源本身可在运行时增删改：POST/PUT/DELETE /admin/sources（yml 里的 sources 只是启动种子）
```

## 设计决策记录

关键取舍（裁剪、路由、记忆、进程内调用、契约授权与对账、来源轮询、身份型 JWT）的一句话索引在 [ARCHITECTURE.md §6](docs/architecture/ARCHITECTURE.md#6-关键设计决策详见-docsdecisions)；完整背景与备选方案见 [docs/decisions/](docs/decisions/)。

业务系统接入需要满足的完整契约要求、失败语义、身份通道，以及"不满足规范时平台的实际行为"对照表，见 [docs/integration/BUSINESS-SYSTEM-INTEGRATION.md](docs/integration/BUSINESS-SYSTEM-INTEGRATION.md)。

项目亮点按四个主题各自成文，含高频追问问答：[docs/highlights/](docs/highlights/README.md)。

## 部署边界

当前只支持**单实例**。敏感确认的挂起状态（`ConfirmManager`）与同会话串行化（`SessionLockRegistry`）都在进程内存里：多实例部署时确认请求可能落到 A、用户的批准落到 B，A 上的循环只能等到超时（视为拒绝）；同一会话的并发请求也可能被不同实例同时放行。需要多实例时，把两者换成共享存储 + 幂等键（确认 token 落库、按会话做分布式锁），并同步调整 `docs/decisions/08-契约快照对账.md` 已写明的定时对账互斥假设。

Actuator（`health` / `prometheus`）在独立管理端口 **8081**，不与业务共享 8000；compose 不发布该端口，监控侧经容器内网访问即可。

## 性能数据

| 指标 | 数值 | 方法 |
|---|---|---|
| SSE 并发长连接 | **40 并发 200/200，P50 1.03s / P95 1.32s / P99 1.35s**（分位差 <330ms，无线程饥饿） | 并发压测，每请求含：MySQL 会话落库 + 进程内工具层查询 + 上游调用与错误事件回传 |
| Token 裁剪收益 | **30 轮长会话总 Token 降 31.8%**（166,656 → 113,716）；**单次调用 prompt 末轮降 78.6%**（7860 → 1684）；代价：裁剪致遗忘多 20 次重查（阈值需按场景调优，见 决策 03） | 开/关裁剪 A/B 实测（qwen-turbo，usage 全量打点） |
| 敏感操作确认 | confirm_request → 人工授权 → 执行 → CANCELLED 全链路实测 | LLM 驱动 cancel 工具的真实会话 |

> 前两行由一次性实验脚本测得，脚本未随仓库发布——数字是当时实测的结果，仓库不再提供复现入口。

## 技术栈

Java 17 · Spring Boot 3.4 · Spring AI 1.1（OpenAI 兼容协议）· MyBatis-Plus · MySQL 8.4 · SSE · WireMock · Docker Compose

## 测试

```bash
mvn test    # 282 项：转发拼装与超时故障 / OpenAPI 解析、$ref 展开与参数约束保真 / 契约自动注册、来源注册表与快照对账 / RBAC（身份加载·权限映射·/admin URI 判定）/ 密码哈希与登录限流 / 裁剪策略 / 循环三态（执行·同意·拒绝·超步数）/ 越权拦截 / 工具路由责任链 / SSE 保活与消息校验 / 端到端自愈 / 会话锁与知识检索
```
