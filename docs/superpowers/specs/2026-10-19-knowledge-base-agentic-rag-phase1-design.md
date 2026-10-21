# 知识库 Agentic RAG 升级 — 阶段 1 设计文档：检索充分性评估与补检索

- 日期：2026-10-19（v2 修订：吸收设计评审意见，同日）
- 状态：v2 评审修订版（待终审）
- 范围：阶段 1（CRAG-lite / Adaptive-RAG 补全）为可直接实施需求；阶段 2~4 仅作路线图概览，不在本期范围。

**v2 修订摘要**（对应评审 P0/P1 问题，全部已在正文落实）：

1. 预算从"各步骤结束后检查耗时"改为**统一 deadline 机制**：单调时钟、各阶段共享剩余预算、底层调用可取消、超时后禁止进入后续阶段（4.2.2）。
2. AC8 拆分为 **AC8a（合并池不变式）/ AC8b（最终注入策略）**：明确"合并池不丢失首轮片段"≠"最终 Prompt 不丢失首轮片段"，最终注入采用**首轮保底配额策略**（产品决策，见 4.2.3）。
3. 评估器改为**固定判定顺序**（空 → 小库豁免 → 命中不足 → 弱命中 → 充分），小库豁免更名 `SMALL_CORPUS` 并明确语义：跳过补检 ≠ 检索结果充分，也 ≠ 已召回全部语料（4.2.4）。
4. `rewriteForRetry()` 增加**结果状态机**（REWRITTEN / PASSTHROUGH / INVALID）：失败或无效时直接结束补检，绝不重复执行相同查询（4.2.5）。
5. 补检增加**独立并发护栏**，不依赖 chatSemaphore 控制补检负载（4.2.1）。
6. 开关生效语义修正：**库级开关每请求读库、即时生效；全局总闸为启动期配置、修改需重启**。回滚主路径是库级开关（4.3、5）。
7. 计量遵循**边界计量原则**：计量放在共享服务内部（rewriteForRetry / EmbeddingServiceImpl），编排器不事后补记，避免漏记与重复记（4.5）。
8. 补全：入口输入不变式、降级决策总表、去重与排序确定性、迁移与混合部署顺序、UsageLog 计量口径、四组验收测试清单。

## 1. 背景与动机

知识库绑库问答当前是单轮 RAG 管线：问题改写（带指代门控）→ 向量化 → `KnowledgeRetrievalPipeline` 统一口径检索（topK / 阈值过滤 / rerankK 截断）→ 空结果拒答 → 流式生成。一次检索定成败：命中偏少或相似度偏低时，要么拿噪声片段硬生成，要么直接拒答，用户只能自己换个问法重试。

项目里已具备该问题的部分基础设施，本设计是"补全 Adaptive-RAG 的正向一半"，而非引入新范式：

- `QueryRewriteServiceImpl` 已有"无必要不调 LLM"的门控设计哲学（skipWithoutPronoun）；
- `KnowledgeRetrievalPipeline` 是检索口径的唯一定义点（Chat 绑库问答与检索测试端点共用），其口径注释明确：**余弦距离 = 1 - 余弦相似度**，阈值过滤即 `distance ≤ 1 - similarityThreshold`；
- `KnowledgeRetrievalTestServiceImpl` 已按"改写 → 召回 → 重排 → 答案"分阶段计时，具备灰度验证与观测能力；
- 绑库分支已有距离阈值拒答，说明"无据不答"原则已落地。

阶段 1 要补的是：**检索结果可能不充分时，自动改写并补检索一次**，其余保持不变。

## 2. 目标 / 非目标 / 反目标

### 目标

- G1 绑库问答在检索结果触发"不充分"判定时，自动改写问题并补检索至多 1 次，提升答案可用率；首轮命中为空（现状直接拒答的场景）同样触发补检。
- G2 命中良好的问题零额外成本：v0 评估器为纯启发式，不调 LLM、不增加延迟；开关关闭时评估器不运行，零额外查询（含 chunk 总数 count）。
- G3 全链路可观测：每轮检索的轮次、命中数、判定结果、触发原因、耗时、降级原因均有 trace（日志 + 检索测试响应字段）。
- G4 可灰度、可回滚：库级开关默认关闭且**每请求从数据库读取（即时生效）**；全局总闸为启动期配置（发版级保险丝，修改需重启）。关闭或全局关闸时行为与现状完全一致，无需数据回滚。

### 非目标（本期明确不做）

- LLM 充分性评估 / 打分器（仅预留接口位）；
- 查询拆解与多子查询规划（阶段 2）；
- 生成后引用校验、质量标记与重生成（阶段 3）；
- 真实 rerank 模型接入、Web/SQL 多源路由（阶段 4 及独立项）；
- 端到端请求级总预算（现状亦无，本期不新增；补检预算只覆盖补检环节，见 4.2.2）；
- 前端任何改动（本期不发新 SSE 事件，问答页零联动）。

### 反目标（出现即视为失败）

- 未开启 agent 的知识库行为有任何可感知变化（含新增数据库查询等隐性开销）；
- 评估或补检索绕过 `KnowledgeRetrievalPipeline` 统一口径（阈值过滤、截断规则），或在编排器内复制第二套过滤/截断逻辑；
- 补检无延迟预算或预算不可执行（仅事后检查耗时不构成硬上限）；
- 补检无并发限制，高峰期拖垮 LLM / Embedding / 向量库等共享资源；
- 循环无界或成本不可审计。

## 3. 用户故事与验收标准

### 用户故事

- US1（补检成功）：绑库用户提问"这个制度什么时候生效"，首轮检索命中为空或触发不充分判定 → 系统自动改写补检 → 命中后生成带编号引用的答案。用户全程无感知中断，SSE 事件序列与现状一致。
- US2（零成本路径）：命中良好 → 仅 1 轮检索、1 次 embedding，无补检调用，TTFT 与现状一致。
- US3（观测）：库主在检索测试端点发起测试 → 响应新增 `iterations` 轮次明细（每轮改写后问题、命中数、是否充分、触发原因、耗时）及最终 `retryStatus` / `degradationReason`。
- US4（灰度控制）：库主关闭 agent 开关 → 问答行为与现状完全一致（即时生效）；全局总闸作为发版级保险丝（修改后滚动重启生效）。

### 验收标准

- AC1 开关关闭（全局或库级任一关闭）时：绑库问答与检索测试的响应与现版本语义一致（回归），且不产生任何额外数据库查询。
- AC2 开启且首轮判定充分：仅 1 轮检索、1 次 embedding，无补检调用。
- AC3 开启且首轮不充分：至多 2 轮检索（1 次补检）、至多 2 次 embedding。**补检采用统一 deadline**：进入补检时记录单调时钟（`System.nanoTime()`），改写、embed、检索各阶段共享剩余预算；任一阶段执行前剩余预算不足即降级，超时后禁止进入后续阶段；已进入的阶段通过可取消的执行方式释放请求线程（机制见 4.2.2）。预算默认 8000ms，可配。
- AC4 补检后仍为空：走现有拒答文案，不硬生成；补检后候选非空：照常生成（与现状一致，不扩大拒答面）。是否充分仅记观测指标 `agent_insufficient_final_total`，其语义为"补检执行后最终候选仍被启发式判定不充分的次数"——**不是失败回答数，也不是幻觉数**（见 4.5）。
- AC5 每轮召回均经 `KnowledgeRetrievalPipeline.filterAndTruncate` 过滤截断，无旁路；编排器只调用、不复制其逻辑。
- AC6 检索测试响应含 `iterations` 字段；旧客户端解析不受影响（多余字段无害）。
- AC7 补检产生的改写与 embedding 调用均计入 `UsageLogService`，且**与实际调用一一对应**：首轮复用调用方已算好的向量不触发 embed 调用、不重复计量；补检 embed 按实际调用计量；改写 LLM 调用完成即计量（即使改写结果被判 INVALID）；调用异常未完成则不计量、以 ERROR 日志记录。计量全部发生在共享服务内部（边界计量原则，见 4.5）。
- AC8a（合并池不变式）合并候选池**包含首轮 `filterAndTruncate` 输出的全部片段**；同 chunkId 保留距离更小者（距离相等保留首轮，先入池）。补检只可能扩充池，不可能丢失首轮片段。
- AC8b（最终注入策略）注入生成 Prompt 前按**首轮保底配额策略**截断至单轮口径上限 K：首轮片段保底 `G = min(首轮片段数, ceil(K/2))` 席，其余席位由补检新增片段按距离升序填满；输出按（距离, chunkId）升序确定排序。**不承诺每个首轮片段都进入最终上下文，承诺首轮证据至少保底 ceil(K/2) 席（当首轮片段数足够时）**；最终 Prompt 引用编号从实际注入列表动态生成（`[1]..[chunks.size()]`）。K 与策略细节见 4.2.3。
- AC9 补检受独立并发护栏限制（`wisread.knowledge.agent.retry-concurrency`，默认 8）：超限时以非阻塞 `tryAcquire` 立即跳过补检并降级首轮结果（reason=`concurrency_limit`），不增加任何等待延迟。

## 4. 详细设计（阶段 1）

### 4.1 触发范围

- 仅 `conversation.knowledgeBaseId != null` 的会话问答分支（`ChatServiceImpl` 绑库路径）与检索测试端点接入。
- 未绑库项目文档链路零改动（硬约束：不触碰原有项目文档上传、管理、检索逻辑）。

### 4.2 新增组件（全部位于知识库模块侧）

#### 4.2.1 KnowledgeRagOrchestrator（`service/impl`，新类）

**职责**：编排"检索 → 评估 →（改写 → 重新 embed → 补检）→ 合并"的有界流程；对上层暴露单一入口，Chat 与检索测试共用，防止口径分叉。

**入口契约**：

```java
RetrievalOutcome orchestrate(String query, float[] queryEmbedding, KnowledgeBase kb, Long userId);
```

- **输入不变式（调用方保证）**：`query` 与 `queryEmbedding` 必须是同一文本的产物。现状两处调用方均满足——`ChatServiceImpl` 对改写后问题做 embedding 后传入；`KnowledgeRetrievalTestServiceImpl` 同理。`rewriteForRetry()` 的输入也是同一 `query`（首轮改写后的独立问题，已含指代消解），保证补检索与首轮语义同源。
- 首轮复用调用方传入的 embedding（不重复 embed、不重复计量）；补检时对改写后的问题二次 embed，计入用量。

**编排流程（顺序固定）**：

1. 检查开关（全局 && 库级）；任一关闭 → 透传单轮结果（透传路径与现有代码调用完全相同，保证 AC1，评估器与 count 查询均不执行）。
2. 首轮检索结果 + 评估器判定（4.2.4）。
3. 充分或 `SMALL_CORPUS` → 结束，返回首轮结果。
4. 不充分 → 检查补检并发护栏（`tryAcquire`，失败即降级 reason=`concurrency_limit`）→ 进入补检（受 4.2.2 deadline 约束）：改写（4.2.5）→ embed → 统一管线检索 → 每轮结果独立过 `filterAndTruncate`。
5. 两轮均成功 → 按 4.2.3 合并去重、确定性排序、保底配额截断，形成最终候选。
6. 全部路径收敛为 `RetrievalOutcome` 返回，调用方按 `candidates` 是否为空走既有生成/拒答逻辑，不感知内部降级细节。

**结果对象 `RetrievalOutcome`**（形状留给 builder）：至少含 `candidates`（最终注入列表）、`rounds`、`retryStatus`、`degradationReason`、`iterationsTrace`。**"当前最优结果"的唯一定义**：最后一个成功完成的检索轮次经 `filterAndTruncate` 过滤后的候选（通常为首轮）；两轮均成功时为合并池按 4.2.3 截断后的最终列表。

**与 chatSemaphore 的关系**：补检（含其 LLM / embed / 检索调用）全部发生在 `chatSemaphore.acquire()` 之前，不占用全局生成串行区（与现状检索位于信号量之前的结构一致）。注意两点：

- 补检不占生成串行区 ≠ 不增加请求总延迟；信号量等待时间与生成时间不在补检预算内（现状亦如此），尾延迟通过 `agent_retry_duration` 计时器观测（4.5）。
- 正因补检在信号量之前，**补检负载必须由独立并发护栏约束**（AC9），否则补检高峰会绕过生成信号量直接冲击 LLM / Embedding / 向量库等共享资源。

**检索轮次上限** `maxRetrievalRounds = 2`（首轮 + 1 次补检），本期为常量；库级 `max_iterations` 列本期只建不用，为阶段 2 预留。

#### 4.2.2 补检 deadline 机制（AC3）

预算不是"各步骤完成后检查 elapsedMs"，而是一把从补检开始即生效、各阶段共享的统一 deadline：

1. 进入补检时记录 `deadlineNanos = System.nanoTime() + budgetMs * 1_000_000`（单调时钟，不受系统时间回拨影响）。
2. **每个阶段执行前**计算剩余预算 `remainingMs`；`remainingMs` 不足（≤ 0 或低于该阶段最小预留）→ 立即降级，不启动该阶段。不存在"每一步重新获得完整 8 秒"的情况。
3. **阶段执行可取消**：补检各阶段提交到补检专用线程池执行，调用线程以 `future.get(remainingMs, MILLISECONDS)` 等待；超时即 `future.cancel(true)` 中断底层调用并降级，请求线程按时返回，不被无界阻塞。
4. 超时降级后**禁止进入后续任何阶段**，按已完成轮次的候选生成（4.6 决策表）。
5. **前置核实任务（builder 编码第一步）**：核实 DashScope chat / embedding 客户端的超时配置、向量检索 SQL 的执行超时（JDBC queryTimeout）；缺失则在本期补配。这是第 3 点"可取消"的真实兜底——若客户端不响应中断且无自身超时，取消后仍可能残留悬挂线程，必须在客户端侧再有超时上限。
6. 预算边界明确：补检预算只覆盖补检环节（改写 + embed + 检索 + 评估）；首轮检索、信号量等待、生成不在预算内；本期不引入端到端请求总预算（非目标）。

#### 4.2.3 合并与最终注入策略（产品决策，AC8a / AC8b）

**合并池不变式（AC8a）**：合并仅在两轮 `filterAndTruncate` 输出上进行——按 chunkId 去重（chunkId 为 knowledge_chunks 表主键，两轮检索同一知识库，作为去重 key 安全）；同 chunkId 保留距离更小者，距离相等保留首轮；合并池包含首轮全部片段。

**单轮口径上限 K 的定义**（与 `filterAndTruncate` 契约对齐，消除 topK / 过滤后数量 / rerankK 三者歧义）：

- `rerankEnabled = true` 且 `rerankK` 已配置 → `K = rerankK`（每轮 `filterAndTruncate` 已截断到 K，首轮片段数 ≤ K）；
- 其余情况（rerank 未启用或 rerankK 未配置，单轮不截断）→ `K = topK`（每轮召回上限，首轮片段数 ≤ topK）。

即：**最终 Prompt 片段数上限 = 单轮可能的最大值**，Prompt 长度相对现状不劣化。

**首轮保底配额策略（AC8b）**：

1. `G = min(首轮片段数, ceil(K/2))`——首轮保底席位数；
2. 最终列表 = 首轮列表前 G 个（`filterAndTruncate` 输出已按距离升序，直接取前 G）+ 补检新增片段（chunkId 不在首轮列表中）按距离升序取满剩余 `K - G` 席；
3. 输出统一按（距离升序, chunkId 升序）排序——**确定性排序，不依赖任何 Map 遍历顺序**，测试可复现；
4. 最终 Prompt 引用编号从该实际注入列表生成（`[1]..[size]` 动态），与返回的引用数据一一对应。

**决策记录**：为什么不用"全局距离优先"（合并池按距离截断）——向量距离不是答案价值的严格排序，补检片段距离更小不等于对原始问题更相关；首轮证据是既得基线，补检是投机性改善，默认关闭 + 灰度的背景下取保守策略。为什么不用"首轮全量优先"——首轮命中不足正是补检的动因，必须给补检留出注入空间。保底配额（首轮至少 ceil(K/2) 席、补检至多 floor(K/2) 席）同时约束两个方向的风险。**明确取舍**：该策略不保证"答案质量不劣化"（无任何截断策略能仅凭距离保证这一点），答案质量靠灰度期抽检验证（5.4）。

**示例**（K=3，即 rerankK=3）：

| 排名 | 来源 | chunk | 距离 |
|---|---|---|---|
| 1 | 第一轮 | A | 0.10 |
| 2 | 第一轮 | B | 0.15 |
| 3 | 第一轮 | C | 0.20 |
| 4 | 补检 | D | 0.08 |
| 5 | 补检 | E | 0.12 |

- 全局距离优先的结果是 D、A、E——首轮 B、C 全部被挤出；
- 保底配额：G = min(3, 2) = 2 → 首轮取 A、B；剩余 1 席取补检最优 D → 最终 D(0.08)、A(0.10)、B(0.15)。首轮证据保底保留 2 席。

#### 4.2.4 KnowledgeRetrievalGrader（接口 + `HeuristicKnowledgeRetrievalGrader` 实现）

**输入**：过滤截断后的候选片段列表 + 知识库配置 + 该库已索引 chunk 总数（count 查询，仅评估器实际运行时执行）。**输出**：`{sufficient, reason}`。

**判定顺序（固定，串行 guard 语句实现，消除规则排列歧义）**：

1. 候选为空 → 不充分（`NO_HITS`，尝试补检）；
2. 库内已索引 chunk 总数 ≤ L → 跳过补检（`SMALL_CORPUS`）；其中 `L = min(K, 3)`，K 为 4.2.3 定义的单轮口径上限（rerank 未启用时取 topK），L 下限 3 走配置项可调；
3. 候选数 < L → 不充分（`LOW_HITS`，尝试补检）；
4. top1 相似度 < 弱命中线 → 不充分（`WEAK_TOP_HIT`，尝试补检）；
5. 其余 → 充分（`SUFFICIENT`）。

**`SMALL_CORPUS` 语义（重要）**：仅表示"库规模足够小，补检预期无新增收益"，**不代表检索结果充分，也不代表已召回全部语料**（topK 召回上限与阈值过滤仍可能丢弃部分 chunk）。它不是 `SUFFICIENT`，在 trace 与 `agent_retry_skipped_total{reason="small_corpus"}` 中独立呈现，不混入充分统计。小库豁免**优先于**弱命中规则（上表顺序 2 在 4 之前）：库内语料已全部参与检索判定时，换检索角度也不会带来新候选。

**相似度定义（必须复用管线唯一定义）**：本项目距离口径为**余弦距离 = 1 - 余弦相似度**（`KnowledgeRetrievalPipeline` 口径注释与阈值过滤逻辑一致），评估器内部 `similarity = 1 - distance`，**禁止自行推导第二种转换**。

**弱命中线与边界**：

- 已配置阈值时：弱命中线 = `min(threshold + margin, 1.0)`，margin 默认 0.05、可配；封顶 1.0 防止越界；
- 未配置阈值（管线允许 null，此时不过滤）时：弱命中线 = 0.5 绝对兜底；
- 比较为严格小于（`top1Similarity < weakLine` 判弱）；
- threshold = 0 / 1、margin 封顶、null 阈值与已配置阈值的行为差异——单元测试逐一覆盖（8.A）。

**chunk 总数查询成本**：仅 Agent 开启且评估器运行时执行（开关关闭 → 评估器不运行 → 零额外 SQL，保证 AC1）；按 `(knowledge_base_id, status='INDEXED')` 走索引 count。本期**不做缓存**（灰度期 QPS 低，索引 count 可接受）；若后续观测到压力，再评估缓存及其在索引增删后的失效策略。索引未完成 / 重建中的库：count 只统计 INDEXED 状态，语义自洽。

**评估器异常**：按 `SUFFICIENT` 处理（快速失败让路给生成，不让评估器阻塞主链路），记 `grader_error`。

预留 LLM 评估器实现位（同接口），本期不实现。

#### 4.2.5 QueryRewriteService 扩展

新增 `rewriteForRetry(question, userId)`，返回**结果状态机**（非裸字符串，杜绝"返回原字符串被当成功改写再查一遍"）：

- `REWRITTEN(text)`：有效新检索词（非空、≠ 输入）→ 允许继续补检；
- `PASSTHROUGH`：LLM 调用失败 / 超时 / 异常 → **直接结束补检**，使用首轮结果（不回退原问题重查——相同查询只是重复付费）；
- `INVALID`：LLM 返回空白、与输入相同或无效内容 → **直接结束补检**（调用已发生、已计量，但不再执行无意义的相同检索）。

**提示词约束**（防改写改变用户意图，如把"生效时间"改成"修订时间"）：

- 保留原问题的关键实体、时间范围、版本号与限定条件；
- 不得改变原问题的语义目标，只换检索角度；
- 只生成一个检索表达，不回答用户问题。

**输入**：首轮改写后的独立问题（已含指代消解、自包含），与 4.2.1 入口不变式同源；签名不带历史——属刻意设计，勿改为用户原始问题。

**计量**：内部调用 `usageLogService`（与现有 `rewrite()` 同一模式，计量在服务边界内完成）；调用完成即计量（含结果判 INVALID 的场景）；异常未完成不计量、ERROR 日志带堆栈。

现有 `rewrite(...)` 方法与行为不变。

### 4.3 配置与迁移

- V11 迁移：`knowledge_bases` 新增 `agent_enabled BOOLEAN NOT NULL DEFAULT FALSE`、`max_iterations INT NULL`。
- 开关双层：实际生效 = 全局总闸 && 库级开关。全局 `wisread.knowledge.agent.enabled` 默认 `true`（**启动期 @Value 注入，修改需重启生效**——发版级保险丝）；库级默认 `false`（灰度位，**随实体每请求从数据库读取，JPA 无二级缓存，即时生效**）——默认关闭由库级承担，与"问答行为由库配置驱动"的既有模式一致。**回滚主路径是库级开关**。
- 预算：`wisread.knowledge.agent.retry-budget-ms`，默认 `8000`，语义见 4.2.2（统一 deadline，覆盖补检的改写 + embed + 检索 + 评估环节，不含信号量等待与生成）。
- 并发护栏：`wisread.knowledge.agent.retry-concurrency`，默认 `8`，补检专用信号量（非阻塞 tryAcquire）。
- 启发式参数：`wisread.knowledge.agent.grader.*`（LOW_HITS 下限、WEAK_TOP_HIT margin）。
- **迁移与部署顺序**：新实例启动时由 Flyway 自动执行 V11（如需人工先行执行亦可）；旧实例在加列后不受影响——Hibernate 只 SELECT/INSERT 已声明列，未映射的新列走数据库默认值，不存在 `SELECT *` 兼容问题。**迁移 + 实体 + DTO 回写必须在真实 PostgreSQL 上做集成测试**（`mvn clean compile` 只能发现编译问题，不能证明 Flyway / ORM / 线上数据行为正确）。
- **更新接口防覆盖**：`KnowledgeBaseServiceImpl.update` 对 `agent_enabled` 沿用现有"请求字段非空才覆盖"的部分更新语义——不携带则保持原值（已开启的库不会被无意关闭），显式携带 `false` 可关闭。`max_iterations` 本期只读透出，不接受更新。
- 执行迁移前 `mvn clean compile` 清理 target（Flyway 版本冲突既有教训）。

### 4.4 改造点（现有文件）

- `ChatServiceImpl`：绑库分支的检索调用替换为 orchestrator 入口；embedding 计算保持在调用方（orchestrator 接收现成向量，满足 4.2.1 不变式）；其余流程（L2 记忆、拒答、buildPrompt、SSE）不动。
- `KnowledgeRetrievalTestServiceImpl`：召回阶段替换为 orchestrator；`KnowledgeRetrievalTestResponse` 新增 `iterations` 字段（含每轮 trace 与最终 `retryStatus` / `degradationReason`）。
- `KnowledgeBase` 实体与 Response/UpdateRequest DTO：新增两个字段的读写（更新接口允许库主改 `agent_enabled`，见 4.3 防覆盖语义；`max_iterations` 只读透出）。
- `ChatServiceImpl.buildPrompt`：引用编号文案 `[1]..[3]` 写死改为动态 `chunks.size()`（一行改动，消除 rerankK ≠ 3 时编号与实际片段数不一致的既有隐患；检索测试端点已是动态写法）。
- 未绑库分支、`KnowledgeRetrievalPipeline` 本体：零改动。

### 4.5 可观测与成本

**日志**：每轮 INFO 一条（round、hits、sufficient、reason、elapsedMs），降级时 WARN 一条（degradationReason、剩余预算），沿用现有中文日志风格。

**Micrometer 指标**（仿照现有 `memoryInjectHits` 模式）：

- `agent_retry_total`：实际执行补检的次数（走到改写/检索阶段）；
- `agent_retry_skipped_total{reason}`：评估后未执行补检的次数，reason ∈ `small_corpus | rewrite_passthrough | rewrite_invalid | embedding_failed | concurrency_limit | budget_exceeded | grader_error`（v2：原独立的 `agent_retry_budget_exceeded_total` 合并为 reason 标签，避免口径重叠）；
- `agent_insufficient_final_total`：补检执行后最终候选仍被判定不充分的次数。**指标语义声明**：这是启发式观测值，不是失败回答数、不是幻觉数——"检索非空""检索充分""答案有据"是三个不同指标，本期仅观测前两者，答案有据靠抽检（5.4）；
- `agent_retry_duration`（Timer）：补检环节端到端耗时分布，观测尾延迟（补检不占生成串行区 ≠ 不增加总延迟，须以分布数据验证）。

**计量原则（AC7，边界计量）**：计量放在共享服务调用边界——`rewriteForRetry()` 内部计量（同 `rewrite()` 模式）；embed 计量已在 `EmbeddingServiceImpl.embed()` 内部（首轮复用向量不触发调用即不计量，补检 embed 自动计量）；**编排器不事后补记费用**，杜绝"重试入口绕开原计量路径"导致的漏记，也杜绝事后补记导致的重复记。

### 4.6 异常与边界（降级决策总表）

所有路径收敛为 `RetrievalOutcome`，调用方仅按 `candidates` 是否为空走既有生成 / 拒答逻辑：

| 场景 | 行为 | 结果来源 | reason |
|---|---|---|---|
| 首轮为空，补检成功且有新增 | 合并后生成（合并后仍为空 → 拒答） | 合并池 | — |
| 首轮有结果，补检成功且有新增 | 按 4.2.3 保底配额合并注入生成 | 合并池 | — |
| 首轮有结果，补检成功但无新增（全被过滤 / 去重后为空） | 首轮结果生成 | 首轮 | — |
| 评估判 `SMALL_CORPUS` | 跳过补检；首轮候选照常生成 / 拒答 | 首轮 | small_corpus |
| 改写 `PASSTHROUGH`（失败/超时/异常） | 跳过补检 | 首轮 | rewrite_passthrough |
| 改写 `INVALID`（空白/与输入相同） | 跳过补检，**不重复执行相同查询** | 首轮 | rewrite_invalid |
| 补检 embed 失败 | 跳过补检 | 首轮 | embedding_failed |
| 补检任一阶段预算耗尽 | 立即降级，禁止进入后续阶段 | 已完成轮次（通常为首轮） | budget_exceeded |
| 补检并发超限（tryAcquire 失败） | 立即降级 | 首轮 | concurrency_limit |
| 评估器自身异常 | 按充分处理，跳过补检 | 首轮 | grader_error |
| 补检后最终候选为空 | 现有拒答文案（不硬生成） | — | — |

补检失败与"补检成功但结果为空"是两类路径：前者永远回落首轮候选，后者按合并结果处理——决策表已全部覆盖，不存在未定义的"当前最优结果"。

## 5. 上线与回滚

1. **迁移先行 + 默认关闭上线**：V11 加列（DEFAULT FALSE），滚动部署，零行为变化；
2. **真实数据库集成测试**：迁移、实体映射、DTO 回写（含不携带 `agent_enabled` 的更新请求不覆盖原值）在真实 PostgreSQL 上验证通过后再灰度；
3. 用检索测试端点对自建知识库验证 `iterations` trace 与启发式命中情况，调参（margin、LOW_HITS 下限）；
4. 对试点知识库逐个开启，观测 `agent_retry_total`、`agent_retry_duration` 分布、`agent_retry_skipped_total` reason 分布；
5. **答案质量抽检**：对补检样本（尤其 `agent_insufficient_final_total` 命中的样本）人工标注"是否真正有证据回答"，区分检索非空 / 检索充分 / 答案有据三个指标，作为是否扩大灰度的依据；
6. 回滚：库级关开关即时生效（每请求读库）；全局总闸为发版级保险丝（修改需滚动重启）；V11 为加列默认值迁移，无需数据回滚。

## 6. 路线图概览（非本期范围）

- 阶段 2 查询规划：`KnowledgeQueryPlanner` 复杂度门控 + 多跳问题拆解，子查询分别检索合并；启用 `max_iterations`。
- 阶段 3 生成后自检：v0 仅在 `done` 事件带 `citationsValid` 标记（复用 `CitationParsingService`）；v1 可选非流式预检 + SSE 回放模式。
- 阶段 4：真实 rerank 模型（如 DashScope gte-rerank，与 agent 循环正交，性价比更高可提前）；Web/SQL 多源路由缓行。

## 7. 约束

- 不引入任何 Agent 框架；仅用现有 Spring AI ChatModel / Embedding 体系与普通 Java 编排。
- 知识库模块独立性：新类全部在知识库侧；`ChatServiceImpl` 仅替换绑库分支检索调用点。
- 未开启库零行为变化（含零额外 SQL）；未绑库链路零改动。
- 编排器只调用 `KnowledgeRetrievalPipeline`，不得在编排器内复制第二套阈值过滤或截断逻辑（语义分叉风险）。
- DashScope embedding 1024 维不变；补检 embedding 计入用量。
- 后端校验消息、日志中文；日志遵循 logback-spring.xml 既有双输出。

## 8. 测试与验收清单

### A. 评估器逻辑

- [ ] 空候选返回 `NO_HITS`
- [ ] 小库豁免优先级高于弱命中（`SMALL_CORPUS` 在 `WEAK_TOP_HIT` 之前判定）
- [ ] `LOW_HITS` 与 `WEAK_TOP_HIT` 的边界值（候选数 = L、top1 相似度 = 弱命中线）
- [ ] null 阈值走 0.5 兜底；margin 封顶 1.0；相似度 = 1 - 余弦距离与管线口径一致
- [ ] 评估器抛异常时不阻塞生成（按 SUFFICIENT 降级）

### B. 合并与生成

- [ ] 同 chunkId 去重并保留距离更小者；距离相等保留首轮
- [ ] 合并池包含首轮 `filterAndTruncate` 输出全部候选（AC8a）
- [ ] 最终截断符合保底配额策略：首轮 ≥ ceil(K/2) 席、补检新增 ≤ floor(K/2) 席（K 取 rerankK 或 topK 两种口径分别验证）
- [ ] 距离相同时按 chunkId 升序，排序结果确定可复现
- [ ] 引用编号、Prompt 片段与最终注入列表一一对应（`[1]..[size]` 动态）

### C. 超时与异常

- [ ] 改写 `PASSTHROUGH` / `INVALID` 不执行重复检索（无第二次相同 embed + 检索调用）
- [ ] 补检 embed 失败保留首轮候选生成
- [ ] 预算耗尽后不再启动后续阶段（改写超时不触发 embed）
- [ ] 首轮为空且补检失败时仍正确拒答
- [ ] 超时降级后请求线程按时返回、底层任务被取消（客户端超时配置生效）

### D. 兼容性与运维

- [ ] 任一开关关闭时完全透传旧结果，且零额外 SQL（含 chunk count）
- [ ] 未绑库分支行为无变化
- [ ] 更新请求不携带 `agent_enabled` 时不覆盖原值；显式携带 false 可关闭（真实 PG 集成测试）
- [ ] UsageLog 与实际调用一一对应：首轮复用向量不计量、补检 embed 计一次、改写完成即计量（含 INVALID）、异常未完成不计量
- [ ] 并发补检受护栏限制，超限请求立即降级不阻塞；护栏满时不拖垮正常请求

### 留给 builder 的决定

- `GradeResult`、`RetrievalOutcome`、`RetryRewriteResult` 等类型的具体形状（record / 类、字段命名）；
- 启发式参数初值的落地形式与后续调参方式；
- trace 是否需要结构化落库（本期仅日志 + 测试响应字段）；
- 补检专用线程池的尺寸与队列策略（有界，拒绝策略 = 跳过补检降级）。

## 9. 风险

- 启发式误判（该补不补 / 不该补乱补）：固定判定顺序 + 参数可配 + trace 观测 + 默认关闭灰度兜底；
- 补检引入延迟：统一 deadline（可取消、超时不再进后续阶段）+ 独立并发护栏 + `agent_retry_duration` 分布观测；
- 改写后检索更差：AC8a 保证合并池不丢失首轮片段，AC8b 保证首轮证据保底席位；**答案质量不劣化无法仅靠截断策略保证**，以灰度期人工抽检为最终裁判；
- 计量失真（漏记 / 重复记）：边界计量原则 + D 组测试逐一验证；
- 混合部署与配置兼容：迁移先行 + 真实 PG 集成测试 + 库级开关部分更新语义防覆盖；
- 全局开关非即时生效：已明确为发版级保险丝，回滚主路径为库级开关（即时生效），US4 / G4 / 5.6 口径已统一。
