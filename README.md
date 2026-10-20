# agent-platform

> 轻量级 **AI 助手平台**（Java / Spring Boot 3）——任何后台服务凭一份 OpenAPI 文档即可将接口**零代码**注册为大模型工具；Agent 运行时让大模型**安全地**（越权拦截 / 敏感确认 / 上下文裁剪）使用工具完成真实业务操作，SSE 全程直播推理过程。
>
> **定位：员工的统一入口**。平台自己完成登录与对话，业务系统无需 AI 改造；它不是供其他前端直接复用的 AI 中间件（身份接入与用户映射尚未实现）。当前只覆盖静态服务凭证与全量契约导入，其余能力是明确的非目标，见 [docs/ARCHITECTURE.md §8](docs/ARCHITECTURE.md)。

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
2. **零代码工具接入**：存量 OpenAPI 接口自动解析注册为标准化 Tool（path/query/body 参数 → inputSchema，文档内 `$ref` 就地对模型展开）；权限要求与敏感度由业务系统在契约里用扩展字段声明（`x-required-permission` / `x-require-confirm`），平台不猜测接口语义。业务后端需要满足的条件与"不满足时平台会怎样"见 [接入规范](docs/BUSINESS-SYSTEM-INTEGRATION.md)
3. **ReAct 显式循环**：规划-执行-反思逐步驱动，SSE 分阶段推送（plan / tool_call / tool_result / confirm_request / answer）
4. **上下文工程**：滑动窗口 + 工具轨迹摘要混合裁剪，Token usage 全链路打点
5. **轻量 RBAC + 三道权限闸门**：JWT 只带身份（uid），角色与权限每次请求回库加载——停用/降权下一个请求即生效；管理端提供角色-权限矩阵。三道闸门分别为清单组装（把能力与权限要求一并下发给模型——裁掉会让模型把权限问题答成能力问题）· 执行点鉴权（★ 安全边界）· 敏感操作人工二次确认。校验依据是代码里的用户权限集合，不是模型说了什么
6. **Agentic RAG**：制度文档按 Markdown 标题切块、embedding 入 pgvector；检索封装成内部工具 `search_knowledge`，由模型在循环中自主决定何时查、用什么 query。可见性在检索 SQL 内过滤，无权内容不进候选集（[设计](docs/KNOWLEDGE-RAG.md)）

## 快速开始

```bash
# 1. 填模型 API Key（.env 已 gitignore，照 .env.example 复制一份即可）
cp .env.example .env
#    编辑 .env，填入 LLM_API_KEY（供应商与模型名见 LLM_BASE_URL / LLM_MODEL）
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

## 设计决策（ADR）

| 编号 | 决策 | 一句话理由 |
|---|---|---|
| [ADR-003](docs/adr/ADR-003-context-trimming.md) | 滑动窗口 + 轨迹摘要混合裁剪 | 纯滑窗丢关键事实，纯摘要成本高且慢；混合策略保住最近上下文与工具结论，Token 开销实测见下 |
| [ADR-004](docs/adr/ADR-004-tool-routing-deferred.md) | 工具前置路由：接口先行，默认休眠 | 工具数未达阈值时整链休眠、全量直通——规模化前不为主流程添一分开销 |
| [ADR-005](docs/adr/ADR-005-session-memory-recall.md) | 会话记忆用内部工具调回，不用 RAG | 对话消息短碎、上下文依赖强，嵌入质量天然差；把 RAG 推迟到数据规模证明它必要的时刻 |
| [ADR-006](docs/adr/ADR-006-in-process-tool-invocation.md) | 进程内工具调用，删除 MCP 协议层与 HTTP 自环 | 自环的理由只有"预留拆分部署"一句且从未兑现，代价是每次调用多一跳网络、异常跨协议层 |
| [ADR-007](docs/adr/ADR-007-contract-driven-authorization.md) | 权限要求与工具来源一律从业务契约读取 | 权限码与敏感度由业务方在契约里声明、工具来源启动时自动拉取——平台不编码业务语义，"零改造接入"才成立 |
| [ADR-008](docs/adr/ADR-008-contract-reconciliation.md) | 工具契约按来源做快照对账 | 每轮用契约推导的期望状态与库内工具收敛：新增/更新/软下线；人工启停不被覆盖，抓取失败保留上一版 |
| [ADR-009](docs/adr/ADR-009-source-discovery-and-polling.md) | 来源静态配置 + 主动轮询，不引入注册中心 | 变更源不在业务进程内，推模型要求业务改造；轮询天然自愈。演进路径（DB 注册表 → webhook → 服务发现）已写明但不提前实现 |
| [ADR-010](docs/adr/ADR-010-identity-only-jwt-and-rbac.md) | JWT 只承载身份，角色与权限每请求回库 | 授权是可变的服务端状态；写进 token 意味着降权要等过期，补撤销机制又回到服务端状态 |

业务系统接入需要满足的完整契约要求、失败语义、身份通道，以及"不满足规范时平台的实际行为"对照表，见 [docs/BUSINESS-SYSTEM-INTEGRATION.md](docs/BUSINESS-SYSTEM-INTEGRATION.md)。

项目亮点按四个主题各自成文，含高频追问问答：[docs/highlights/](docs/highlights/README.md)。

## 性能数据

| 指标 | 数值 | 方法 |
|---|---|---|
| SSE 并发长连接 | **40 并发 200/200，P50 1.03s / P95 1.32s / P99 1.35s**（分位差 <330ms，无线程饥饿） | 并发压测，每请求含：MySQL 会话落库 + 进程内工具层查询 + 上游调用与错误事件回传 |
| Token 裁剪收益 | **30 轮长会话总 Token 降 31.8%**（166,656 → 113,716）；**单次调用 prompt 末轮降 78.6%**（7860 → 1684）；代价：裁剪致遗忘多 20 次重查（阈值需按场景调优，见 ADR-003） | 开/关裁剪 A/B 实测（qwen-turbo，usage 全量打点） |
| 敏感操作确认 | confirm_request → 人工授权 → 执行 → CANCELLED 全链路实测 | LLM 驱动 cancel 工具的真实会话 |

> 前两行由一次性实验脚本测得，脚本未随仓库发布——数字是当时实测的结果，仓库不再提供复现入口。

## 技术栈

Java 17 · Spring Boot 3.4 · Spring AI 1.1（OpenAI 兼容协议）· MyBatis-Plus · MySQL 8.4 · SSE · WireMock · Docker Compose

## 测试

```bash
mvn test    # 252 项：转发拼装与超时故障 / OpenAPI 解析与 $ref 展开 / 契约自动注册、来源注册表与快照对账 / RBAC（身份加载·权限映射·/admin URI 判定）/ 裁剪策略 / 循环三态（执行·同意·拒绝·超步数）/ 越权拦截 / 工具路由责任链 / 端到端自愈
```
