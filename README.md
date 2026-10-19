# agent-platform

> 轻量级 **AI 助手平台**（Java / Spring Boot 3）——任何后台服务凭一份 OpenAPI 文档即可将接口**零代码**注册为大模型工具；Agent 运行时让大模型**安全地**（越权拦截 / 敏感确认 / 上下文裁剪）使用工具完成真实业务操作，SSE 全程直播推理过程。

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
│   可见性过滤 · 执行点鉴权 · 泛化 HTTP 转发       │
└─────────┬───────────────────────────────────┘
          │ 泛化 HTTP 转发
          ▼
   业务系统（meeting-booking 等任意 OpenAPI 服务）
```

## 核心特性

1. **进程内工具调用**：Agent 与工具层同进程，越权校验、泛化转发、结果回喂都在一次方法调用内完成——没有协议层、没有网络跳转
2. **零代码工具接入**：存量 OpenAPI 接口自动解析注册为标准化 Tool（path/query/body 参数 → inputSchema）；DELETE 与"取消/cancel"类接口自动标记敏感
3. **ReAct 显式循环**：规划-执行-反思逐步驱动，SSE 分阶段推送（plan / tool_call / tool_result / confirm_request / answer）
4. **上下文工程**：滑动窗口 + 工具轨迹摘要混合裁剪，Token usage 全链路打点
5. **三道权限闸门**：可见性过滤（闸门 1）· 执行点鉴权（闸门 2，★ 安全边界）· 敏感操作人工二次确认（闸门 3）——校验依据是代码里的用户身份，不是模型说了什么

## 快速开始

```bash
# 1. 启动 MySQL（容器映射 3307，与宿主机实例刻意隔离）
docker compose up -d mysql

# 2. 生成本地 profile（已 gitignore）：DB 指向容器 3307 + 填模型 API Key
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml

# 3. 启动应用
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 4. 浏览器打开 http://localhost:8000/ 开始对话
```

种子账号：`admin/admin123`（ADMIN）、`staff01/staff123`（STAFF）。

不带 `mysql` 参数直接 `docker compose up -d` 则连平台自身一起容器化运行（需先 `mvn -DskipTests package`）。

可选——起三个业务系统（会议室预订 :8090 + 两个 WireMock 模拟后端：报销 :8091 / 报修 :8092），用于演示跨系统聚合与差异化权限：

```bash
cd ../business-systems && docker compose up -d
# 再以 ADMIN 身份登录，POST /admin/protocols/import 导入各自的 openapi.json
```

## 设计决策（ADR）

| 编号 | 决策 | 一句话理由 |
|---|---|---|
| [ADR-003](docs/adr/ADR-003-context-trimming.md) | 滑动窗口 + 轨迹摘要混合裁剪 | 纯滑窗丢关键事实，纯摘要成本高且慢；混合策略保住最近上下文与工具结论，Token 开销实测见下 |
| [ADR-004](docs/adr/ADR-004-tool-routing-deferred.md) | 工具前置路由：接口先行，默认休眠 | 工具数未达阈值时整链休眠、全量直通——规模化前不为主流程添一分开销 |
| [ADR-005](docs/adr/ADR-005-session-memory-recall.md) | 会话记忆用内部工具调回，不用 RAG | 对话消息短碎、上下文依赖强，嵌入质量天然差；把 RAG 推迟到数据规模证明它必要的时刻 |
| [ADR-006](docs/adr/ADR-006-in-process-tool-invocation.md) | 进程内工具调用，删除 MCP 协议层与 HTTP 自环 | 自环的理由只有"预留拆分部署"一句且从未兑现，代价是每次调用多一跳网络、异常跨协议层 |

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
mvn test    # 86 项：转发拼装与超时故障 / OpenAPI 解析 / 裁剪策略 / 循环三态（执行·同意·拒绝·超步数）/ 越权拦截 / 工具路由责任链 / 端到端自愈
```
