# 亮点 ②：零改造接入——契约即工具

> 一句话：业务系统只提供一份 OpenAPI 文档，平台把它变成工具并**持续对账**；业务系统对 AI 零感知。
>
> 代码入口：`gateway/registry/OpenApiImportServiceImpl`、`ContractSyncServiceImpl`、`ToolRegistryServiceImpl`、`GenericHttpForwarder`
> 背景决策：`docs/adr/ADR-007-contract-driven-authorization.md`、`docs/adr/ADR-008-contract-reconciliation.md`、`docs/adr/ADR-009-source-discovery-and-polling.md`

## 1. 30 秒版本

```
业务系统（不改代码）                     平台
  /v3/api-docs  ──GET──▶  解析 operation → 工具定义 → 快照对账 → 写 MySQL
                                                           ↓
用户一句话 ──▶ ReAct 循环 ──▶ ToolGateway ──▶ 闸门校验 ──▶ HTTP 转发 ──▶ 业务系统 REST
```

四个关键点：

1. **契约即工具**：`operationId` 是工具名，`summary` 是工具描述，参数合成 `inputSchema`，`x-required-role` / `x-require-confirm` 是权限声明。
2. **持续对账**：不是"导入一次"，而是每轮拿契约推导的期望状态与库内工具收敛（ADR-008）。
3. **失败语义**：后端非 2xx / 超时 / 权限不足 / 用户拒绝，一律包装成 `isError` 文本观察回喂模型，循环不中断。
4. **来源可运营**：`GET /admin/sources` 看每个来源最后一次成功与差异计数；`POST /admin/sources/sync` 立即触发。

## 2. 契约 → 工具：具体规则

| 契约字段 | 工具字段 | 说明 |
|---|---|---|
| `operationId` | `name` | 缺失时按 method + path 生成 slug 兜底 |
| `summary` | `description` | **直接决定模型选工具的准确率**，必须是动作句（"查询指定时间段可用的会议室"） |
| path / query / requestBody | `input_schema` | 合并成一个 JSON Schema；`$ref` **就地展开** |
| `x-required-role` | `required_role` | 兼容裸值与 springdoc `@Extension` 的嵌套形状 |
| `x-require-confirm` | `require_confirm` | 敏感操作人工确认 |
| `servers[0].url` 或配置 `baseUrl` | `url_template` 前缀 | 平台优先用抓取契约的 origin 或显式 baseUrl，契约自称的 host 不可信 |

`$ref` 必须内联：工具的 schema 是脱离原契约、单独发给模型的片段，`#/components/schemas/...` 在模型侧查不到，OpenAI 兼容接口会直接拒绝整轮请求。

## 3. 为什么是"对账"而不是"导入"

| 问题 | 一次性导入 | 快照对账 |
|---|---|---|
| 接口删除 | 旧工具永远留下 | `present=0` 软下线 |
| `operationId` 改名 | 旧名留幽灵、新名新增 | 旧名下线、新名新增 |
| 运行中契约变更 | 必须重启 | 下一个周期自动收敛 |
| 管理员禁用工具 | 下次导入被打开 | `enabled` 与 `present` 分离，不被覆盖 |
| 抓取失败 | 可能删掉工具 | 保留 last-known-good |
| 同名冲突 | 悄悄顶掉 | 整份快照放弃，标 `CONFLICT` |

核心语义：**契约是期望状态，数据库是当前状态，对账负责收敛**。这是 Kubernetes controller 的模型，也是 MCP `tools/list_changed` 想表达的意思。

## 4. 深挖问题清单

**Q：为什么用轮询，不学 Nacos 用长连接/推送？**
被同步的是契约内容，变更只在发版时发生；业务系统不装任何平台 SDK，springdoc 也不会发"契约已变更"事件。要推送就得给每个业务系统加 webhook + 鉴权 + 重试，直接破坏零改造。轮询天然自愈、成本可忽略（3 个来源约每分钟 0.6 次 GET），契约未变时哈希比对后跳过。进化方向是"webhook 提及时性 + 轮询做兜底"，不是长连接。详见 ADR-009。

**Q：契约改了，平台多久知道？**
默认 5 分钟；`POST /admin/sources/sync` 或 `POST /admin/sources/{key}/sync` 可立即触发。启动时也会对账一次。

**Q：业务系统删了一个接口，工具会怎样？**
下一次成功对账后 `present=0`，不删行（保留 id 与审计），模型清单立即不再下发。接口恢复后下一次对账自动复活。

**Q：业务系统暂时宕机，工具会消失吗？**
不会。抓取失败只更新来源状态与 `last_error`，保留 `contract_hash` / `tool_count` / `last_success_at`，工具保持原状。真正调用时转发器返回失败文本，模型据此告知用户。

**Q：不同系统的 operationId 重名怎么办？**
同一个 `gatewayId` 下工具名是全局命名空间。对账发现同名工具仍由另一个来源以 `present=1` 声明时，整份快照放弃并标 `CONFLICT`，不会部分写入。接入时要约定命名唯一；系统更多时应该一系统一 `gatewayId`。

**Q：为什么对账不先删后插？**
会改变工具 id，管理端启停、审计记录失去稳定标识，同步窗口内清单为空，人工 `enabled` 全丢。所以逐工具比对、软下线。

**Q：为什么失败不阻断同步？**
一次上游发布如果因为一个破坏性变更被卡住，整份契约停在旧版本，反而更危险。平台识别四类破坏性变更（端点、必填参数、类型、角色）并告警，但先同步。完整兼容性检查交给 oasdiff 这类专用工具。

**Q：怎么保证工具描述质量？**
`summary` 就是描述，所以要求业务方写成动作句。这是**业务方的责任**，平台不替它改写语义——一旦平台开始润色描述，就又在编码业务语义。

**Q：业务系统把失败装在 HTTP 200 里怎么办？**
平台只以非 2xx / 异常判定失败。业务异常装在 200 响应体里，模型会把"预订不存在"读成"订好了"。接口文档必须约定用状态码表达失败；唯一例外是幂等重放返回既有记录，语义上属于成功。

**Q：转发层怎么处理参数？**
`{param}` 从 arguments 取值并 URL 编码；GET/DELETE 余参拼 query；POST/PUT/PATCH 余参作 JSON body；schema 声明了 `properties.body` 时自动解包。响应 8KB 截断，防止打爆模型上下文。

**Q：新增一个业务系统要做什么？**
当前：在 `platform.gateway.sources` 加一项 `id` + `url`，重启平台。要免重启就需要把来源定义搬进数据库并提供管理端 CRUD——这是已识别的缺口，见 ADR-009 的演进路径。

## 5. 实测证据

- 启动对账：booking 7 + expense 5 + repair 4 = 16 个工具；
- 再次对账：全部 `unchanged`，不写库（实测 `POST /admin/sources/sync` 返回 `unchanged 7/5/4`）；
- 软下线：手工快照导入一个工具后再次导入空契约，管理端显示 `present=false`，模型工具清单中消失；
- 人工禁用后再次对账，`enabled` 保持 `false`；
- 错误自愈：模型首次 `create` 时间格式错，后端 400，模型自动改格式重试成功。

## 6. 已知边界

- 新增来源需改配置并重启；来源移除后工具不会自动下线（需要生命周期收敛）；
- 默认 5 分钟最终一致；
- 破坏性变更只识别四类且只告警，没有通知渠道；
- 手工导入（不带 `sourceKey`）产生的工具没有 `source_key`，不在对账范围内，可能成为幽灵工具；
- 多实例下定时对账与缓存刷新未协调（ADR-008 第 9 条）。

## 7. 相关文档

- `docs/ARCHITECTURE.md §3`：一次完整请求的路径
- `docs/CODE-TOUR.md`：`registry/` 与 `forward/` 逐文件说明
- `docs/adr/ADR-008-contract-reconciliation.md`：快照对账的完整决策
- `docs/adr/ADR-009-source-discovery-and-polling.md`：为什么用轮询、为什么现在不接注册中心
