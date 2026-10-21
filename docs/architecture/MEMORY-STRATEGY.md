# 会话记忆策略：为什么用"工具调回"而不是 RAG

> 本文是决策 05 的决策展开：把"会话记忆为什么不选 RAG、而选内部工具调回（agentic recall）"
> 的完整工程分析沉淀下来。结论与决策记录见 `docs/decisions/05-会话记忆调回.md`，
> 裁剪机制本身见 `docs/decisions/03-上下文裁剪.md`。
> 当第 6 节的任一触发信号出现时，以本文为基线重评估。

## 1. 问题定义：这个系统里的"记忆"是什么

先分清三类信息，它们的权威源和丢失代价完全不同：

| 信息类型 | 例子 | 权威源 | 被裁剪后的代价 |
|---|---|---|---|
| 业务事实 | 房间号、预订状态、时间 | 业务系统（booking 库） | 模型重查一次工具即可恢复——多花一次调用，不是任务失败（量化：激进裁剪重查询 +48.8%，决策 03 实验） |
| 对话内结论 | "刚才说好的标题""上次选的 6 人间" | 对话历史本身 | 裁剪即不可还原；业务系统查不到 |
| 偏好与约束 | "以后别订带投影的""标题都带部门名" | 只存在于用户话语中 | 同上，且跨会话目前为零 |

裁剪（决策 03）负责控制上下文规模，但它只解决"热层"（最近 6 条）之外的部分——
**温层（checkpoint 压缩标记）不含事实、冷层（全量历史）躺在库里没人取**，这就是记忆策略要补的缺口。

## 2. RAG 公平陈述：它是什么、擅长什么

标准 RAG 流程：历史/文档**切块 → 逐块嵌入 → 存向量库 → 查询时嵌入 + top-k 召回 → 自动注入上下文**。

它真正擅长的场景：**海量非结构化内容、查询语义模糊、人工无法枚举关键词**——典型是文档问答
（几千篇文档里找"怎么配置重试策略"）。它的本质是"语义模糊匹配 + 系统自动猜要什么"。

## 3. 为什么不适合"对话记忆"（核心论证）

**① 嵌入对象质量天然差。** 对话消息短、碎、上下文依赖："就订那个""按刚才的来"单独拿出来
嵌入，向量里几乎没有可匹配的语义；指代（"那个"）必须结合前文才能消解。要让检索可用，
就得先做会话级聚块/预摘要——而"怎么生成高质量摘要块"恰恰把我们带回了决策 03 已经否掉的
"每轮 LLM 摘要"（成本延迟双翻倍）。**RAG 在对话场景的第一步就踩在需要先解决的老问题上。**

**② 成本模型错位：写入时付费 vs 读取时付费。** RAG 为每条消息付一次嵌入调用 + 向量库写入，
不管这条消息将来会不会被再次需要——而历史里真正会被再次引用的是少数关键结论
（重查询实验里模型反复要的只是房间号/预订号这类事实）。工具调回把成本挪到读取侧：
缺了才查，查的是现成的 MySQL，毫秒级、零额外调用费。**为 90%+ 永远不会再被引用的消息
预付嵌入成本，是在为不会发生的需求买单。**

**③ 基础设施与评估负担不成比例。** RAG 要引入：嵌入模型接入、向量库选型与运维、切块策略、
top-k 调参，以及一套"检索质量评估"否则调不了参——这是一个全新子系统。而工具调回用的是
**已经在生产路径上的** chat_message/checkpoint 两张表和一个内部工具类。当前规模：
单会话 8 步 ReAct 上限、30 轮长会话已是压测级负载、6000 token 阈值下 30 轮才触发一次裁剪——
这个数据量级撑不起一条向量检索链路的成本。

**④ 召回决策权错位。** RAG 是"系统猜你要什么"：top-k 自动注入，注入错了就是占上下文的噪音，
而且用户和模型都看不到"检索失败"这件事。工具调回是"模型知道自己缺什么"：它带着明确意图
（用户问了"上次那个"）发起检索，结果不好可以换关键词重试，检索失败会如实回喂
（"未检索到相关记录"）——**失败可见、可重试、可观测**（recallHistory 的每次调用都有
tool_call/tool_result 事件与落库留痕）。对 function calling 成熟的模型，后者的有效召回更高。

**⑤ 与现有架构的咬合成本。** 本项目所有工具执行本来就收归 ReAct 循环手动分派
（这是事件插桩、确认挂起、权限校验能存在的前提）。内部工具调回顺着这个结构加一个分派分支即可；
RAG 则要在"消息落库 → 触发裁剪 → 组装上下文"之间再插入一条异步嵌入管线，
与"写即落库"的同步路径耦合。

## 4. 采纳方案回顾：三层记忆 + 内部工具调回

| 层 | 内容 | 载体 | 成本 |
|---|---|---|---|
| 热 | 最近 6 条原样 | 上下文窗口（决策 03） | 零 |
| 温 | 压缩标记（消息数 + 工具调用次数，不含事实） | checkpoint 表 | 零（确定性规则生成） |
| 冷 | 全量历史原文 | chat_message 表 | `recallHistory` 按关键词检索，缺了才查 |

硬性约束（为什么这么定位）：

- **领域中立**：只查对话历史、零业务语义——换一条业务线该工具原样可用，平台代码不出现业务词的既有原则不被破坏；
- **角色无关**：只查当前 sessionKey 自己的数据，天然无越权面——因此可以不走工具层的角色/确认闸门；
- **平台自有**：不进工具层、不参与工具路由责任链（始终在册）——它是平台能力，不是某个业务系统的工具。

## 5. 诚实的代价（这个选择放弃了什么）

- **LIKE 检索无语义匹配**：换了说法就查不到（"靠窗的房间"查不到"采光好"）。缓解手段是模型换词重试
  （工具会如实返回"未检索到"），但确实存在召回盲区；
- **多一跳工具调用**：该调不调或调而不中会多花一步（步数上限 8 兜底）；不过对比"重查询"路径——
  一次 LLM decide + 一次业务调用——一次本地 SQL 检索便宜一个数量级；
- **依赖模型判断力**：判断"要不要调回"本身可能出错，靠工具 description + 系统提示词规则引导，
  不是机制保证；
- **跨会话记忆仍未覆盖**："我上次订过什么"需要用户级记忆，涉及归属与清理策略，是独立的演进决策。

## 6. 什么时候该重评估（明确的触发信号）

出现以下任一信号，按本文基线重新评估（演进路径见第 7 节）：

1. **规模信号**：单会话长度常态化超过 100 轮，或跨会话/用户级记忆成为真实需求（历史跨越几十个会话，关键词无法覆盖）；
2. **质量信号**：recallHistory"调了但没检索到"成为高频事件（可从 tool_call 日志直接统计命中率）——说明关键词检索不够用了；
3. **需求信号**：出现真正的语义模糊查询（"我上次订的那个靠窗的房间"这类无关键词可提取的诉求）。

## 7. 演进路径（为什么这个决策不是死胡同)

工具契约（名称 `recallHistory`、参数 `keyword/limit`、调用方式）与数据层（chat_message 全量落库）
都已经固定，未来升级只换 `HistoryRecallTool.execute()` 内部实现：

```
LIKE（现状） → MySQL 全文索引（仍零新增依赖） → 嵌入 + 向量检索（届时才引入向量库）
```

每一步都是上一段的直接替换，调用方与模型侧零改动。**这就是本决策的核心：不是"拒绝 RAG"，
而是把 RAG 推迟到数据规模证明它必要的时刻，并用工具抽象把迁移成本隔离在一个类里。**

## 8. 向量记忆方案（未实现，仅在触发信号出现后实施）

> 本节是迁移到第三步的设计草案，**当前代码没有实现**。面试或文档中引用时必须明确这一点。
> 触发条件见 §6；不做跨会话用户级记忆、自动摘要、每轮强制召回。

### 8.1 数据流

```text
裁剪发生
  → 计算本次滑出区间 (cursor, keepFrom 之前最后一条消息 id]
  → 同事务写 memory_index_job 并推进 cursor（唯一键幂等）
  → 独立线程池领取 PENDING 任务
  → 按"完整工具回合"聚成片段
  → 批量 embedding
  → 写 memory_chunk（session_key + chunk_key 唯一）
  → 任务置 DONE；失败按策略重试
```

对话主路径不等待嵌入完成。新片段尚未索引时，关键词通道仍然可用。

### 8.2 表结构

MySQL 侧只放任务与水位线；向量放 Postgres，与知识库共用同一个 pgvector 实例和私有连接池（不注册为 `DataSource` bean，否则 MyBatis 的单候选条件会失效）。

```sql
-- MySQL：索引任务，与 knowledge_index_job 同模式
CREATE TABLE memory_index_job (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key     CHAR(36)     NOT NULL,
    from_message_id BIGINT       NOT NULL,
    to_message_id   BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/DONE/FAILED
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(512) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL,
    UNIQUE KEY uk_memory_job_range (session_key, from_message_id, to_message_id),
    KEY idx_memory_job_status (status, id)
);

-- MySQL：已索引到哪条消息
CREATE TABLE session_memory_cursor (
    session_key              CHAR(36) NOT NULL PRIMARY KEY,
    indexed_until_message_id BIGINT   NOT NULL,
    chunk_config_hash        CHAR(64) NULL,
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);
```

```sql
-- Postgres/pgvector：片段与向量
CREATE TABLE memory_chunk (
    id              UUID         PRIMARY KEY,
    session_key     CHAR(36)     NOT NULL,
    chunk_key       VARCHAR(128) NOT NULL,
    from_message_id BIGINT       NOT NULL,
    to_message_id   BIGINT       NOT NULL,
    content         TEXT         NOT NULL,
    content_hash    CHAR(64)     NOT NULL,
    embedding       vector(1024) NOT NULL,  -- bge-m3
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (session_key, chunk_key)
);

CREATE INDEX idx_memory_chunk_session ON memory_chunk (session_key);
CREATE INDEX idx_memory_chunk_embedding
    ON memory_chunk USING hnsw (embedding vector_cosine_ops);
```

### 8.3 切块

- 最小单元是完整工具回合：一条 user/assistant 消息加它触发的全部 tool call 与 tool result。不要把 tool call 和结果切到两个块里。
- 合并到 600～800 字符或 6 条消息封顶，先到先切。
- 块前缀保留时间、角色、工具名：`[10-21 14:03][USER]`、`[TOOL → create_booking]`。
- 只有硬切时才留 100 字符重叠；按回合切不留重叠。
- `content_hash` 跳过重复内容；`chunk_key` 用 `sessionKey:fromId-toId` 或 hash，保证同一区间只写一次。

### 8.4 水位线与幂等

用消息 id，不用条数。消息只追加、id 单调，不会因为新增消息产生偏移。

1. 读 `session_memory_cursor.indexed_until_message_id`，没有则取 0；
2. 本次滑出区间是 `(cursor, keepFrom 前一条消息 id]`；
3. 同事务写 `memory_index_job`（唯一键去重）并推进 cursor；
4. cursor 只在 job 落库成功后推进，避免消息在两步之间丢失。

失败时 cursor 不前进，下一轮重新覆盖同一区间，唯一键保证不会重复入队。

### 8.5 异步执行与重试

- 对话路径只入队，不等待 embedding——裁剪本身已是同步开销，不能再叠加一次外部调用。
- 独立线程池或定时扫描领取 PENDING：MySQL 8 用 `SELECT ... FOR UPDATE SKIP LOCKED`，一次取一批。
- 批量嵌入，减少请求次数。
- 重试：指数退避，最多 3 次；4xx（鉴权、配额、参数）不重试——重试不会让它成功，只会放大故障时间。
- `RUNNING` 超时（如 10 分钟）按 `updated_at` 回收为 `FAILED` 或重新 `PENDING`，避免进程崩溃后任务永久卡住。

### 8.6 检索：双通道

工具契约不变，仍接收 `keyword` 和 `limit`，只改 `execute` 内部：

1. **向量通道**：把 `keyword` 当查询文本嵌入，SQL 内按 `session_key` 过滤：

   ```sql
   SELECT ... FROM memory_chunk
   WHERE session_key = ?
   ORDER BY embedding <=> ?::vector
   LIMIT ?
   ```

   过滤必须在 `WHERE` 里，不能先 top-k 再在内存中筛会话，否则会串味且浪费候选。

2. **关键词通道**：保留现有 `LIKE` 查询作为基线兜底。

3. **融合**：用 RRF（reciprocal rank fusion）合并两路结果，按消息 id 去重，保留时间排序。不要直接比较余弦分数和 LIKE 命中数，量纲不同。

4. **输出**：每块带来源消息区间与时间，总字符上限约 2000，避免一次调回打爆上下文。

### 8.7 降级

| 故障 | 行为 |
|---|---|
| embedding 服务不可用 | 跳过向量通道，只走关键词，结果说明"语义检索暂不可用" |
| 索引任务失败 | 对话不受影响，任务保持 FAILED/PENDING，下次重试 |
| 检索超时 | 返回软提示，不抛异常，循环继续 |
| 新片段尚未索引 | 关键词通道仍能查到原文，不依赖索引完成 |
| pgvector 不可达 | 退化为关键词；知识库侧按既有策略 fail fast |

### 8.8 评估与可观测

- 构造 20～50 个真实形态的会话片段，标注每个问题应召回哪条消息；
- 指标：Recall@5、MRR、空结果率、平均检索延迟、每次检索的 embedding 成本；
- 基线：纯 LIKE；对照组：纯向量；候选：RRF 融合；
- 上线门槛：Recall@5 不低于关键词基线，否则保留关键词；
- 评估脚本随仓库提交，保证可复跑。一次性脚本测出的数字不写进简历；
- 指标：`memory_index_job_total{status}`、`memory_index_duration`、`memory_recall_total{channel,hit}`、`memory_recall_latency`、embedding token 用量；
- 审计表 `memory_recall_audit(session_key, keyword, channel, hit_count, latency_ms, created_at)`，不记正文；日志记任务 id、消息区间、失败原因，不记消息内容。

### 8.9 清理、重建与多实例

- 删除会话时级联删除 `memory_chunk`、`memory_index_job` 与 cursor；现有 `deleteSession` 按 `session_key` 删消息与 checkpoint，需要同步扩到这三处。
- 嵌入模型或切块参数变更时，`chunk_config_hash` 不匹配，cursor 重置并按会话重建。
- 按业务要求设保留期，过期片段与向量一起删。
- 任务领取用 `FOR UPDATE SKIP LOCKED` 或状态 CAS；cursor 更新用条件更新 `WHERE indexed_until_message_id < ?`；`(session_key, chunk_key)` 唯一键做最后一道幂等。
- 项目当前是单实例，多实例时还需处理 `ConfirmManager` 与 `SessionLockRegistry` 的内存状态。

### 8.10 实施顺序与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| 1 | MySQL `FULLTEXT` + `ngram` 替换 LIKE | 中文分词生效，工具契约不变，单测覆盖空结果与降级 |
| 2 | 片段向量化最小闭环：表、任务、cursor、双通道 | 水位线不漏不重、失败可续跑、会话隔离、embedding 故障退化为关键词 |
| 3 | 评估与调优 | 固定评估集可复跑，Recall@5 不低于关键词基线 |
| 4 | 清理与多实例互斥 | 删会话级联删除；并发领取不重复处理 |

阶段 1 成本最低，做完即可称"检索增强"；阶段 2 起才值得写"长期记忆"。
