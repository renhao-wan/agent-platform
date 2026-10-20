# 业务系统接入规范

> **结论先行**
>
> - 业务系统**不需要写任何 AI 代码**：不引模型 SDK、不调 LLM、不感知 ReAct 循环、不知道工具被模型如何选择。
> - 但它**不是零配合**：必须提供一份合格的 OpenAPI 契约，并在契约里声明"这个操作需要什么权限""是否要人工确认""失败用什么状态码表达"。
> - 这些声明**默认不会自动出现**：springdoc 不会凭空生成 `x-required-permission`，需要业务方加注解，或直接手写契约。
> - **完全解耦做不到**，也不应该追求：权限门槛与失败语义是业务方独有的知识。平台若靠关键词去猜（"取消"=管理员、"审批"=敏感），就把业务语义编码进了平台，接入第二个系统时必然猜错。

---

## 1. 边界：什么叫"对 AI 无感知"

| 业务系统需要做 | 业务系统不需要做 |
|---|---|
| 照常暴露 HTTP 接口与 `/v3/api-docs` | 引入 Spring AI / LangChain / 任何模型 SDK |
| 在契约里声明权限码、确认标记（§3.6 / §3.7） | 理解工具调用协议、SSE、ReAct |
| 用 HTTP 状态码表达失败（§3.8） | 为 AI 单独开一套接口 |
| 可选：校验服务凭证并采信 `X-On-Behalf-Of-User`（§3.9） | 维护"哪些角色能调哪些工具"——那在平台侧配置 |
| 可选：写操作做幂等（§3.10） | 感知平台的工具清单、对账周期、模型供应商 |

一句话：**业务系统提供的是"接口的元数据"，不是"AI 的功能"。** 这些元数据同时也是它对自己接口的正常描述；平台只是读取方。

---

## 2. 平台如何接入

1. 业务系统暴露 OpenAPI JSON（Spring Boot + springdoc 默认路径 `/v3/api-docs`）。
2. 平台登记一个**来源**：契约地址、可选调用基地址、可选服务凭证。
   - 启动种子：`platform.gateway.sources`（`application.yml`）；
   - 运行时增删改：`POST/PUT/DELETE /admin/sources`（需要 `admin:source` 权限）。
3. 平台立即抓取并**快照对账**，把契约中的 operation 注册为工具；此后默认每 5 分钟对一次，也可 `POST /admin/sources/sync` 手工触发。
4. 员工在对话中让模型调用工具时，平台按 §3 的声明做执行点鉴权与确认，再转发给业务系统。

**调用基地址**：带来源接入时，平台使用**契约 URL 的 origin**（例如契约在 `http://booking:8090/v3/api-docs`，则调用 `http://booking:8090`），或来源里显式配置的 `base_url`。**契约里的 `servers[0].url` 不会覆盖它**——契约自称的主机名不可信，避免一份契约把平台转发指向任意地址。

---

## 3. 契约硬性要求

不满足本节的要求时，平台的行为见 §5。标「必须」的项是接入验收项。

### 3.1 可达性与格式

- **必须**：契约地址可从平台访问，返回 OpenAPI 3.x 的 JSON。
- **必须**：平台访问 `/v3/api-docs` 不需要业务系统的用户登录态（它只用服务凭证，见 §3.9）。
- 支持 `paths` 下的 `get` / `post` / `put` / `delete` / `patch`；其他方法和 `webhooks` 会被忽略。

### 3.2 工具名

- **必须**：每个 operation 有稳定、唯一、可读的 `operationId`。
- 缺失时平台会用 `方法_路径` 生成 slug 兜底，工具仍能注册，但名字不可读、改路径就改名，模型选择准确率明显下降。
- **工具名在整个平台内是全局命名空间**：两个来源出现同名工具时，后对账的那份契约**整份放弃**（状态 `CONFLICT`），不会部分写入。
- **不得占用平台保留名**：`search_knowledge`、`recallHistory` 是平台内部工具名，业务契约若使用会让整份契约被拒绝（`CONFLICT`）。这类工具由 Agent 运行时本地执行，不经过业务系统。
- `operationId` 一经上线不要随意改：改名等于删旧建新，模型的历史经验与人工启停状态都会断掉。

### 3.3 描述

- **必须**：`summary` 写成**动作句**，直接回答"这个接口做什么"。
  - 好：`"查询指定时间段可用的会议室"`、`"取消指定预订"`
  - 差：`"会议室接口"`、`"BookingController.cancel"`
- `summary` 就是工具描述，是模型选工具的主要依据。`description` 在 `summary` 缺失时兜底；两者都缺时退化为 `GET /api/v1/...`，能注册但基本选不准。
- 参数与请求体字段的 `description` 也会进入工具 schema。`enum` / `items` / `default` 等约束会随 schema 原样下发，但仍建议在 `description` 里写清枚举含义——模型读自然语言比读 JSON Schema 更稳。

### 3.4 路径参数

- path 模板参数必须写成 `{name}`，并在 `parameters` 中声明同名参数。
- **必须**给 path 参数写 `"required": true`；否则平台不会把它放进工具的 `required` 列表，模型可能漏填，平台会以空字符串替换占位符，请求通常落成 404。
- 平台按参数名从模型给的 arguments 中取值并做 URL 编码。

### 3.5 工具 schema 的边界

平台把 operation 的参数与请求体合并成一个 JSON Schema 交给模型，边界如下：

| 项 | 平台行为 |
|---|---|
| `parameters`（无论 `in` 是 path / query / header / cookie） | 全部进入 schema；**转发时非 body 方法的剩余参数一律拼进 query**。因此**不要用 header 参数作为业务输入** |
| `requestBody` | 只读取 `application/json`；`multipart/form-data`、表单、文件上传**不支持** |
| 参数类型 | `schema` 整体保留（`type` / `format` / `description` / `enum` / `items` / `default` / 数值边界等）；也兼容 `content.application/json.schema` 形状 |
| `$ref` | 文档内 `#/components/...` **就地展开**；自引用会截断成宽松 object，不会无限展开 |
| 参数校验 | 平台**不校验**参数是否满足 schema 或 required；参数不全时仍会发起请求。业务系统必须自己校验并返回 4xx |

### 3.6 权限声明（闸门 2 的依据）

在需要权限的 operation 上声明扩展字段：

```yaml
x-required-permission: booking:cancel
```

- **必须**：权限码与平台 `app_role_permission.permission_code` 完全一致；建议用 `<系统>:<动作>` 命名（`booking:cancel`、`expense:approve`、`repair:assign`）。
- 平台兼容两种形状：
  - 裸值：`"x-required-permission": "booking:cancel"`（手写契约）；
  - springdoc 嵌套对象：`{"value": "booking:cancel"}`（`@Extension` 生成）。
- **未声明 = 全员可用**。平台不会因为接口是写操作、或名字里有"取消/审批/删除"就自动加权限——这是有意的：平台不编码业务语义。
- **"哪些角色拥有这个权限"不在契约里**。业务系统只声明权限码；管理员在平台 `/admin/rbac` 的矩阵里把权限码授予角色。业务系统不需要知道、也不应该硬编码"ADMIN 能取消"。

springdoc（与 meeting-booking 的写法一致）：

```java
@Operation(summary = "取消指定预订",
        extensions = {
                @Extension(name = "x-required-permission",
                        properties = @ExtensionProperty(name = "value", value = "booking:cancel")),
                @Extension(name = "x-require-confirm",
                        properties = @ExtensionProperty(name = "value", value = "true"))
        })
@PostMapping("/{id}/cancel")
public ApiResponse<BookingResponse> cancel(...) { ... }
```

### 3.7 敏感确认（闸门 3）

```yaml
x-require-confirm: true
```

- 支持 `true`、`"true"`、`{"value": "true"}` 三种写法。
- **`DELETE` 方法天然视为敏感**，无需声明；其他方法必须显式声明。
- 声明后，平台在执行前通过 SSE 推 `confirm_request` 给用户；用户同意才转发，拒绝或超时视为拒绝（`isError` 文本回喂模型）。
- 幂等重放也走这条路径：模型重试一次写操作，用户会被再问一次。业务系统自己的幂等在 §3.10。

### 3.8 失败必须用 HTTP 状态码表达

平台只以 **HTTP 状态码**判断成功/失败：2xx = 成功，非 2xx 或网络异常 = 失败，并作为 `isError=true` 的观察回喂模型。

- **必须**：业务失败返回 4xx/5xx，并在响应体中给出可读的错误信息。
- **不要把业务失败装在 HTTP 200 的响应体里**（如 `{"code":1001,"message":"该时段已被占用"}`）。平台会把它当成功告诉模型，模型于是对用户说"订好了"。业务码可以保留给人排查，但状态码必须同时正确。
- 唯一例外：**幂等重放**返回的是既有记录，语义上属于成功，保持 2xx。

### 3.9 身份通道（可选但推荐）

平台转发时会附加：

| 请求头 | 来源 | 业务系统应做什么 |
|---|---|---|
| `X-Service-Key`（名称可在来源里配置） | 平台来源配置里的服务凭证 | **校验它**；只有校验通过才放行平台调用 |
| `X-On-Behalf-Of-User: {uid}` | 当前登录的平台用户 id | **仅在校验服务凭证之后才采信**；据此把数据记在真实用户名下 |

- 业务系统**必须**校验服务凭证，否则任何人都能绕过平台直接调接口；平台无法替业务系统兜这个底。
- 平台写的是**平台用户 id**，不是业务系统自己的用户 id。演示环境靠"双方都从 1 开始"对齐；生产接入需要真实的用户映射，这属于身份联邦，当前明确不在范围内（见 `ARCHITECTURE.md §1.1`）。
- 不采信 `X-On-Behalf-Of-User` 时，所有操作会记在业务系统的系统账号下——"查询我的预订"这类接口就查不到真实数据。

### 3.10 幂等与超时

- 平台的连接超时 2s、读超时 10s；超时按失败回喂模型，**模型可能重试**。
- 平台**不发送幂等键**。写操作要防重复，必须由业务系统从操作语义派生幂等键（meeting-booking 的做法：`SHA-256(user|room|date|start|end|title)`，唯一索引兜底）。
- 没有幂等的写操作，在模型重试或网络重发时可能产生重复数据。

### 3.11 响应体

- 建议返回 JSON；平台不解析业务结构，原样作为观察交给模型。非 JSON 文本也能工作，但模型更难判断结果。
- 响应超过 **8KB 会截断**。列表接口请分页，不要把整库倒给模型。

---

## 4. 建议项（影响效果，不影响注册）

| 建议 | 原因 |
|---|---|
| 为每个 operation 写清晰的 `summary` + 参数描述 | 直接决定模型选工具与造参数的准确率 |
| 枚举值写进 `description` | schema 里的 `enum` 会原样下发，但自然语言描述更利于模型理解取值含义 |
| 列表接口分页、响应精简 | 8KB 截断，且 token 成本随响应线性增长 |
| 写操作幂等 | 模型重试是常态 |
| 接口名与业务动作一一对应，不要一个接口塞多种操作 | 模型按工具粒度决策，复合接口会迫使它"多走一步" |
| 在业务系统仓库加契约测试（如 meeting-booking 的 `OpenApiContractTest`） | 防止重构时注解丢失、权限声明变空。平台对账会记 `PERMISSION_REMOVED` / `PERMISSION_CHANGED` 告警，但业务仓库自己的 CI 能更早发现 |
| 变更权限码时同步通知平台管理员 | 平台会告警 `PERMISSION_CHANGED`，但授权矩阵要人来改 |

---

## 5. 不满足规范时，平台实际会怎样

### 5.1 已有兜底

| 情况 | 平台行为 |
|---|---|
| 契约抓取失败 / 解析失败 | 来源状态记 `FAILED` 与 `last_error`；**保留上一版工具与指纹**（last-known-good），不软下线任何工具，启动流程也不中断 |
| 某次对账后接口从契约消失 | 工具 `present=0` 软下线，不删行；重新出现时复活，人工启停状态保留 |
| 两个来源声明同名工具 | 整份契约放弃，状态 `CONFLICT`，保留上一版；不会写出半新半旧状态 |
| 契约内工具名重复 | 同上，整份放弃 |
| 权限被收紧 / 撤销 / 变更、端点变化、新增必填参数、参数类型变化 | 记 `PERMISSION_RESTRICTED` / `PERMISSION_REMOVED` / `PERMISSION_CHANGED` / `ENDPOINT_CHANGED` / `REQUIRED_PARAM_ADDED` / `PARAM_TYPE_CHANGED`，写管理端与 `platform.contract.breaking` 指标；**只告警，不阻断** |
| 新增的写操作未声明 `x-required-permission` | 正常注册，同时记 `PERMISSION_UNDECLARED` 告警。写操作指 POST / PUT / PATCH / DELETE |
| 后端超时 / 非 2xx / 返回非 JSON | 统一包装为 `isError=true` 的文本观察，循环不中断 |
| 响应过大 | 截断到 8KB |
| 业务系统短暂不可用 | 下一次对账自动重试；已注册工具保持可用（调用时会失败并回喂模型） |
| 管理员误操作 | 工具可人工停用（`PUT /admin/tools/{id}/enabled`），来源可软下线（`DELETE /admin/sources/{key}`）；契约对账不会覆盖人工启停 |

### 5.2 平台**不**兜底的地方（必须由业务方保证）

| 缺口 | 现状 | 为什么平台不做 |
|---|---|---|
| **未声明权限 = 全员可用** | 能注册、能调用，权限标签为空；对账会记 `PERMISSION_UNDECLARED`（新增写操作）或 `PERMISSION_REMOVED`（声明被撤销）告警，但**不阻断、不阻止调用** | 平台只检查"声明是否存在"，不判断"这个操作该要什么权限"——后者是业务方的知识 |
| **HTTP 200 装业务失败** | 平台当成功回喂模型，模型会向用户报告成功 | 识别响应体里的业务码需要理解每个系统的响应格式，且与"平台不编码业务语义"冲突 |
| **业务系统不校验服务凭证** | 平台照常附加凭证，但对端忽略；接口可被绕过平台直接调用 | 这是业务系统自身的鉴权缺口，平台无法从外部强制 |
| **不采信 `X-On-Behalf-Of-User`** | 所有操作落在系统账号名下 | 平台只能声明"代表谁"，是否采信是业务侧的安全决策 |
| **未做幂等** | 模型重试可能产生重复写入 | 幂等键必须由业务语义派生，平台不知道"什么算同一笔操作" |
| **参数不全 / 类型错误** | 平台不校验，照常转发 | schema 只是给模型的说明书；真正的入参校验属于业务系统 |

### 5.3 可观测入口（排障时看哪里）

- `GET /admin/sources`：每个来源的 `status`、`last_error`、`tool_count`、上次新增/更新/下线数、`staleTools`、`breakingChanges`。
- `GET /admin/tools`：全部工具（含已下线/已停用），可看到 `requiredPermission` 是否为空、`present` / `enabled` 状态。
- 指标：`platform.contract.sync{result}`、`platform.contract.sync.duration`、`platform.contract.breaking{type}`。
- 日志：对账完成/失败、破坏性变更、权限拒绝（`权限不足：需要 X 权限`）。

---

## 6. 接入验收清单

**契约**

- [ ] `/v3/api-docs` 可匿名访问，返回 OpenAPI 3.x JSON
- [ ] 每个 operation 有稳定唯一的 `operationId`
- [ ] 没有占用平台保留名（`search_knowledge` / `recallHistory`）
- [ ] 每个 operation 的 `summary` 是动作句
- [ ] path 参数声明了 `required: true`
- [ ] 请求体是 `application/json`（无文件上传/表单）
- [ ] 没有用 header 参数承载业务输入
- [ ] 需要权限的 operation 声明了 `x-required-permission`
- [ ] 需要人工确认的 operation 声明了 `x-require-confirm`（DELETE 可省略）
- [ ] 业务失败返回 4xx/5xx，而不是 200 + 业务码
- [ ] 幂等重放保持 2xx

**运行时**

- [ ] 业务系统校验 `X-Service-Key`（若启用服务凭证）
- [ ] 校验通过后才采信 `X-On-Behalf-Of-User`
- [ ] 写操作具备幂等性
- [ ] 列表接口分页，单响应 < 8KB
- [ ] 平台侧已在 `/admin/rbac` 为权限码配置角色映射

**平台侧**

- [ ] `POST /admin/sources` 登记来源并首次对账成功（`status=OK`）
- [ ] `GET /admin/tools` 中权限/确认标记符合预期
- [ ] 用无权账号实测：模型收到"权限不足"而非"工具不存在"
- [ ] 对敏感工具实测 `confirm_request` 链路

---

## 7. 参考实现

| 系统 | 用途 | 契约位置 |
|---|---|---|
| `business-systems/meeting-booking` | 真实 Spring Boot 单体，用 springdoc `@Extension` 声明权限与确认 | `/v3/api-docs` |
| `business-systems/expense` | WireMock stub，手写契约（裸值扩展字段） | `__files/openapi.json`，经 `/v3/api-docs` 暴露 |
| `business-systems/repair` | 同上 | 同上 |

`meeting-booking` 的 `OpenApiContractTest` 是契约测试的范例：它断言 `x-required-permission` / `x-require-confirm` 的实际形状，注解一旦丢失，业务仓库自己的 CI 就会失败。
