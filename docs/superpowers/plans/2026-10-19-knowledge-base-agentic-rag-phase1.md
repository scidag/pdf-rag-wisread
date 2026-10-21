# 知识库 Agentic RAG 阶段 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 绑库问答与检索测试接入"检索充分性评估 + 至多一次补检索"的有界 Agent 流程，默认关闭、可灰度、可回滚。

**Architecture:** 新增 `KnowledgeRagOrchestrator`（Chat 与检索测试共用的唯一编排入口）+ `KnowledgeRetrievalGrader`（纯启发式评估）+ `rewriteForRetry`（带状态机的补检改写）；统一 deadline、独立并发护栏、首轮保底配额合并；所有降级路径收敛为 `RetrievalOutcome`。

**Tech Stack:** Spring Boot 3 + Spring AI（DashScope ChatModel/EmbeddingModel）、MyBatis-Plus、PostgreSQL + pgvector、Flyway、JUnit5 + Mockito + AssertJ、Micrometer。

**Spec:** `docs/superpowers/specs/2026-10-19-knowledge-base-agentic-rag-phase1-design.md`（v2 评审修订版；执行者必须先读 spec，本计划从 spec 论证）

## Global Constraints

- 不引入任何 Agent 框架；仅用现有 Spring AI ChatModel / Embedding 体系与普通 Java 编排（spec §7）
- 未开启库零行为变化，**含零额外 SQL**（AC1）；未绑库链路零改动（spec §7）
- 编排器只调用 `KnowledgeRetrievalPipeline.filterAndTruncate` / `resolveTopK`，禁止复制第二套过滤/截断逻辑（spec §7）
- 相似度口径唯一定义：余弦距离 = 1 - 余弦相似度，评估器内 `similarity = 1 - distance`（spec 4.2.4）
- 补检预算是统一 deadline（单调时钟、各阶段共享剩余预算、超时禁止进后续阶段），不是事后耗时检查（spec 4.2.2，AC3）
- 库级开关默认 false、每请求读库；全局总闸 `wisread.knowledge.agent.enabled` 默认 true、启动期 @Value（spec 4.3）
- `update` 对 `agentEnabled` 仅请求字段非空才覆盖（spec 4.3 防覆盖）
- `max_iterations` 列本期只建不用、只读透出，不接受更新（spec 4.3）
- 计量走边界：`rewriteForRetry` 与 `EmbeddingServiceImpl.embed` 内部计量，编排器不补记（spec 4.5，AC7）
- 日志中文、`@Slf4j`/LoggerFactory，每轮 INFO、降级 WARN、失败 ERROR 带堆栈（AGENTS.md 日志规范）
- 校验消息中文；测试命令均在 `backend/` 目录执行：`mvn test -Dtest=XxxTest`

## Review Focus

实现后最可能坑到使用者的五类输入/失效模式（每条已钉在对应任务的测试步骤里）：

1. **更新请求不携带 agentEnabled 时把已开启的库重置为关闭**（spec 4.3 防覆盖）→ Task 1 测试 `updateWithoutAgentEnabledKeepsOriginalValue`。
2. **小库豁免与弱命中优先级冲突**（count=2、候选=2、top1 相似度 0.41 < 阈值线 0.45）→ Task 2 测试 `smallCorpusTakesPriorityOverWeakTopHit`。
3. **改写失败/无效后仍重复执行相同查询**（相同 embed + 检索白付费）→ Task 5 测试 `invalidRewriteSkipsEmbeddingAndSearch` / `passthroughRewriteSkipsRetry`。
4. **预算超时后线程被无界阻塞、后台任务继续跑** → Task 5 测试 `budgetTimeoutReturnsPromptlyWithoutRetrySearch`（断言快速返回且未发起补检检索）。
5. **合并后等距离片段顺序不确定 / 首轮证据被补检全部挤出** → Task 4 测试 `equalDistanceSortedByChunkId` 与 `quotaKeepsFirstRoundMajority`（spec 4.2.3 示例表）。

---

### Task 1: V11 迁移 + 实体/DTO/更新语义

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__add_knowledge_agent_config.sql`
- Modify: `backend/src/main/java/com/wisread/entity/KnowledgeBase.java`（rerankK 字段后追加两个字段 + getter/setter）
- Modify: `backend/src/main/java/com/wisread/dto/KnowledgeBaseUpdateRequest.java`（追加 `Boolean agentEnabled`；不加 maxIterations——本期只读）
- Modify: `backend/src/main/java/com/wisread/dto/KnowledgeBaseResponse.java`（追加 `Boolean agentEnabled, Integer maxIterations`）
- Modify: `backend/src/main/java/com/wisread/service/impl/KnowledgeBaseServiceImpl.java`（update() 非空覆盖；toResponse 映射）
- Test: `backend/src/test/java/com/wisread/service/KnowledgeBaseServiceAgentToggleTest.java`（新建，Mockito 单测）

**Interfaces:**
- Consumes: 现有 `KnowledgeBaseServiceImpl.update(Long, Long, KnowledgeBaseUpdateRequest)`、`findOwnedKnowledgeBase`、`knowledgeBaseRepository.updateById`。
- Produces: `KnowledgeBase.getAgentEnabled()/setAgentEnabled(Boolean)`、`getMaxIterations()/setMaxIterations(Integer)`；`KnowledgeBaseResponse(..., Boolean agentEnabled, Integer maxIterations)`（Task 7 不依赖，但前端 DTO 契约在此定版）；`KnowledgeBaseUpdateRequest(..., Boolean agentEnabled)`。

- [ ] **Step 1: 写失败测试**（Mock `KnowledgeBaseRepository`，stub `findOwnedKnowledgeBase` 返回 `agentEnabled=true` 的实体）

测试方法（三个）：
- `updateWithoutAgentEnabledKeepsOriginalValue`：请求全字段为 null → update 后实体 `agentEnabled` 仍为 true，且 `updateById` 被调用；
- `updateWithExplicitFalseDisablesAgent`：请求携带 `agentEnabled=false` → 实体被置 false；
- `updateWithExplicitTrueEnablesAgent`：请求携带 `agentEnabled=true` → 实体被置 true。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=KnowledgeBaseServiceAgentToggleTest`
Expected: FAIL（编译错误：KnowledgeBaseUpdateRequest 无 agentEnabled 组件）

- [ ] **Step 3: 实现**

- V11 SQL（两行）：

```sql
ALTER TABLE knowledge_bases ADD COLUMN agent_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE knowledge_bases ADD COLUMN max_iterations INT;
```

- 实体：`private Boolean agentEnabled = false;`、`private Integer maxIterations;`（字段名必须是 `maxIterations`，MyBatis-Plus 驼峰映射 `max_iterations`）+ getter/setter。
- update() 中 rerankK 块之后追加：`if (request.agentEnabled() != null) { knowledgeBase.setAgentEnabled(request.agentEnabled()); }`
- toResponse（及 countBatch 相关的响应构造处，如有独立映射方法）补两个字段映射。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=KnowledgeBaseServiceAgentToggleTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 编译全量 + 提交**

Run: `mvn clean compile`（清 target，Flyway 冲突既有教训）
```bash
git add backend/src/main/resources/db/migration/V11__add_knowledge_agent_config.sql backend/src/main/java/com/wisread/entity/KnowledgeBase.java backend/src/main/java/com/wisread/dto/KnowledgeBaseUpdateRequest.java backend/src/main/java/com/wisread/dto/KnowledgeBaseResponse.java backend/src/main/java/com/wisread/service/impl/KnowledgeBaseServiceImpl.java backend/src/test/java/com/wisread/service/KnowledgeBaseServiceAgentToggleTest.java
git commit -m "feat: 知识库新增 agent 开关字段与 V11 迁移（默认关闭，更新接口非空覆盖）"
```

---

### Task 2: 检索充分性评估器

**Files:**
- Create: `backend/src/main/java/com/wisread/model/GradeResult.java`
- Create: `backend/src/main/java/com/wisread/service/KnowledgeRetrievalGrader.java`
- Create: `backend/src/main/java/com/wisread/service/impl/HeuristicKnowledgeRetrievalGrader.java`
- Test: `backend/src/test/java/com/wisread/service/impl/HeuristicKnowledgeRetrievalGraderTest.java`

**Interfaces:**
- Consumes: `ChunkSearchResult(double distance, Long chunkId, ...)`（现有 model record）；`KnowledgeRetrievalPipeline.resolveTopK(kb, fallback)`。
- Produces:
  - `record GradeResult(boolean sufficient, Reason reason)`，嵌套 `enum Reason { NO_HITS, SMALL_CORPUS, LOW_HITS, WEAK_TOP_HIT, SUFFICIENT }`
  - `interface KnowledgeRetrievalGrader { GradeResult grade(List<ChunkSearchResult> candidates, KnowledgeBase knowledgeBase, long indexedChunkCount); }`，含 `static int resolveSingleRoundCap(KnowledgeBase kb, int fallbackTopK)` —— **单轮口径上限 K 的唯一定义点**：`Boolean.TRUE.equals(kb.getRerankEnabled()) && kb.getRerankK() != null ? kb.getRerankK() : KnowledgeRetrievalPipeline.resolveTopK(kb, fallbackTopK)`（Task 4/5 依赖此静态方法）
  - `HeuristicKnowledgeRetrievalGrader` 构造参数注入配置：`lowHitsMin`（默认 3）、`weakTopMargin`（默认 0.05）、`weakTopAbsolute`（默认 0.5）、`fallbackTopK`（默认 10）。

- [ ] **Step 1: 写失败测试**（直接 new 实例，不用 Spring 上下文）

测试方法（spec 4.2.4 规则顺序 + 8.A 清单）：
- `emptyCandidatesReturnsNoHits`：空列表 → `NO_HITS`，sufficient=false；
- `smallCorpusTakesPriorityOverWeakTopHit`：kb(rerankK=3, threshold=0.40)，indexedChunkCount=2，候选 2 个（top1 distance=0.59 → similarity=0.41 < 0.45）→ `SMALL_CORPUS`（不是 WEAK_TOP_HIT），sufficient=false；
- `smallCorpusIsNotSufficient`：上例断言 sufficient=false（豁免≠充分）；
- `lowHitsBoundaryIsStrict`：候选数 = L（如 L=3、候选 3 个且 top1 相似度高）→ 不是 LOW_HITS（严格小于才触发），落 `SUFFICIENT`；
- `lowHitsWhenCandidatesBelowBar`：候选 2 个、indexedChunkCount=100 → `LOW_HITS`；
- `weakTopHitBoundaryIsStrict`：top1 similarity == 弱命中线（threshold=0.40、margin=0.05、top1 distance=0.55 → sim=0.45 == 线）→ 不是 WEAK_TOP_HIT；
- `weakTopHitWhenJustAboveThreshold`：top1 sim=0.42（线 0.45）→ `WEAK_TOP_HIT`；
- `nullThresholdFallsBackToAbsolute`：threshold=null，top1 sim=0.49 < 0.5 → `WEAK_TOP_HIT`；
- `marginCappedAtOne`：threshold=0.98、margin=0.05 → 线=1.0；top1 sim=0.99 → `WEAK_TOP_HIT`；
- `rerankDisabledCapFallsBackToTopK`：`resolveSingleRoundCap(kb(rerankEnabled=false, topK=10), 10)` == 10；`resolveSingleRoundCap(kb(rerankEnabled=true, rerankK=5), 10)` == 5；`resolveSingleRoundCap(kb(rerankEnabled=true, rerankK=null, topK=null), 10)` == 10。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=HeuristicKnowledgeRetrievalGraderTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现评估器**

判定顺序（spec 4.2.4，串行 guard，禁 else-if 链含混）：
1. 候选空 → `NO_HITS`（不充分）
2. `indexedChunkCount <= L` → `SMALL_CORPUS`（不充分、跳过补检；`L = min(resolveSingleRoundCap(kb, fallbackTopK), lowHitsMin)`）
3. `candidates.size() < L` → `LOW_HITS`（不充分）
4. `similarity(top1) < weakLine` → `WEAK_TOP_HIT`（不充分）；`weakLine = threshold != null ? min(threshold + weakTopMargin, 1.0) : weakTopAbsolute`；`similarity = 1 - distance`
5. 否则 `SUFFICIENT`

类上加 `@Slf4j` 风格说明性 javadoc：候选已过阈值过滤，弱命中只能用余量判定（spec 原话）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=HeuristicKnowledgeRetrievalGraderTest`
Expected: PASS（≥10 个测试）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/wisread/model/GradeResult.java backend/src/main/java/com/wisread/service/KnowledgeRetrievalGrader.java backend/src/main/java/com/wisread/service/impl/HeuristicKnowledgeRetrievalGrader.java backend/src/test/java/com/wisread/service/impl/HeuristicKnowledgeRetrievalGraderTest.java
git commit -m "feat: 新增知识库检索充分性启发式评估器（固定判定顺序 + 小库豁免）"
```

---

### Task 3: 补检改写 rewriteForRetry

**Files:**
- Create: `backend/src/main/java/com/wisread/model/RetryRewriteResult.java`
- Modify: `backend/src/main/java/com/wisread/service/QueryRewriteService.java`（追加方法声明）
- Modify: `backend/src/main/java/com/wisread/service/impl/QueryRewriteServiceImpl.java`（追加实现，现有 `rewrite` 不动）
- Test: `backend/src/test/java/com/wisread/service/QueryRewriteForRetryTest.java`（新建，沿用 QueryRewriteServiceTest 的 Mockito 风格）

**Interfaces:**
- Consumes: 现有 `ChatModel`、`TokenCounter`、`UsageLogService.log(userId, modelName, inputTokens, outputTokens)`。
- Produces:
  - `record RetryRewriteResult(Type type, String text)`，嵌套 `enum Type { REWRITTEN, PASSTHROUGH, INVALID }`（text 仅 REWRITTEN 非空）
  - `RetryRewriteResult rewriteForRetry(String question, Long userId)`（Task 5 依赖）

- [ ] **Step 1: 写失败测试**

测试方法（spec 4.2.5 状态机）：
- `validRewriteReturnsRewritten`：mock 返回"该制度的生效日期规定" → `REWRITTEN` + text 正确 + `usageLogService.log` 调用 1 次；
- `blankOutputReturnsInvalid`：mock 返回 "  " → `INVALID` + `usageLogService.log` 仍调用 1 次（调用已完成，判无效也计量）；
- `sameAsInputReturnsInvalid`：mock 返回与输入相同字符串 → `INVALID`（不重复查询的前提）；
- `llmExceptionReturnsPassthrough`：mock 抛 RuntimeException → `PASSTHROUGH` + `verifyNoInteractions(usageLogService)`（未完成不计量）；
- `promptContainsConstraintCopy`：捕获 SystemMessage 文本，断言包含"关键实体"与"只输出"约束词（spec 4.2.5 提示词约束）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=QueryRewriteForRetryTest`
Expected: FAIL（方法不存在）

- [ ] **Step 3: 实现**

- 提示词（System 消息，一次调用 `chatModel.call`）："针对下面的问题换一个检索角度生成检索词。必须保留原问题的关键实体、时间范围、版本号与限定条件，不得改变原问题的语义目标。只输出一个检索表达，不回答问题。"
- 结果映射：正常返回 → trim 后非空且 `!equals(question)` → `REWRITTEN`；trim 后空或等于输入 → `INVALID`；调用抛异常 → catch 记 ERROR 日志（带堆栈）→ `PASSTHROUGH`。
- 计量：调用完成后（含 INVALID 路径）`usageLogService.log(userId, chatModelName, tokenCounter.count(input), tokenCounter.count(output))`，与现有 `rewrite()` 同一模式。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=QueryRewriteForRetryTest`
Expected: PASS（5 个测试）；同时跑 `mvn test -Dtest=QueryRewriteServiceTest` 确认存量不回归。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/wisread/model/RetryRewriteResult.java backend/src/main/java/com/wisread/service/QueryRewriteService.java backend/src/main/java/com/wisread/service/impl/QueryRewriteServiceImpl.java backend/src/test/java/com/wisread/service/QueryRewriteForRetryTest.java
git commit -m "feat: 新增补检改写 rewriteForRetry（REWRITTEN/PASSTHROUGH/INVALID 状态机）"
```

---

### Task 4: 结果模型 + 合并算法（首轮保底配额）

**Files:**
- Create: `backend/src/main/java/com/wisread/model/RetrievalIteration.java`
- Create: `backend/src/main/java/com/wisread/model/RetrievalOutcome.java`
- Create: `backend/src/main/java/com/wisread/service/impl/KnowledgeRagOrchestratorImpl.java`（本任务只放静态纯函数 + 类骨架，Task 5 补全编排）
- Test: `backend/src/test/java/com/wisread/service/impl/CandidateMergeTest.java`

**Interfaces:**
- Consumes: `ChunkSearchResult`（现有）；`KnowledgeRetrievalGrader.resolveSingleRoundCap`（Task 2）。
- Produces:
  - `record RetrievalIteration(int round, String query, int hits, boolean sufficient, String reason, long elapsedMs)`
  - `record RetrievalOutcome(List<ChunkSearchResult> candidates, int rounds, String retryStatus, String degradationReason, List<RetrievalIteration> iterations)`，常量 `RETRY_COMPLETED = "COMPLETED"`、`RETRY_SKIPPED = "SKIPPED"`（retryStatus：null=未尝试 / COMPLETED / SKIPPED；degradationReason 取 spec 4.6 表值或 null）
  - `KnowledgeRagOrchestratorImpl` 静态包私有方法（Task 5 依赖）：
    - `static List<ChunkSearchResult> mergeWithFirstRoundQuota(List<ChunkSearchResult> firstRound, List<ChunkSearchResult> retryRound, int capK)`
    - `static List<ChunkSearchResult> dedup(List<ChunkSearchResult> firstRound, List<ChunkSearchResult> retryRound)`

- [ ] **Step 1: 写失败测试**

构造 ChunkSearchResult 的辅助方法（chunkId、distance 可变，其余字段随意）。测试方法（spec 4.2.3 + 8.B）：
- `quotaKeepsFirstRoundMajority`：spec 示例表——首轮 A(0.10) B(0.15) C(0.20)，补检 D(0.08) E(0.12)，K=3 → 结果 = D(0.08), A(0.10), B(0.15)（按距离升序；首轮保底 2 席，C 被挤出）；
- `dedupKeepsSmallerDistance`：两轮含同 chunkId（首轮 0.30、补检 0.10）→ 保留 0.10 那条；
- `dedupEqualDistanceKeepsFirstRound`：同 chunkId 同距离 → 保留首轮实例（用可区分的 content 断言）；
- `poolKeepsAllFirstRoundWhenCapLarge`：K=10、首轮 3 + 补检 2 → 全部保留且按距离升序（AC8a 在 K 充裕时的体现）；
- `equalDistanceSortedByChunkId`：合并结果中两个等距离片段 → chunkId 升序（确定性）；
- `retryOnlyDuplicatesYieldFirstRound`：补检结果全部为首轮已有 chunkId（且距离更大）→ 结果与首轮一致；
- `emptyFirstRoundTakesRetryOnly`：首轮空、补检 5 条、K=3 → 距离最优 3 条；
- `emptyRetryKeepsFirstRound`：补检空 → 结果 = 首轮（≤K 时原样）；
- `kEqualsOneGivesAllSeatsToFirstRound`：K=1、首轮 1 条、补检更优 1 条 → 首轮那条（G=ceil(0.5)=1，补检 0 席）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=CandidateMergeTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现合并算法**

算法（spec 4.2.3，确定性、不依赖 Map 遍历顺序）：
1. `dedup`：以首轮列表为基底，补检条目按 chunkId 查重——chunkId 已存在时保留距离更小者（相等保留首轮），不存在则入"补检新增"列表；
2. `mergeWithFirstRoundQuota`：`G = min(firstRound.size(), ceilDiv(capK, 2))`；结果 = 首轮前 G 条（首轮已距离升序）+ 补检新增按（distance, chunkId）升序取前 `capK - G` 条；最终按（distance, chunkId）升序输出。
- `ceilDiv` 用整数运算 `(capK + 1) / 2`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=CandidateMergeTest`
Expected: PASS（9 个测试）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/wisread/model/RetrievalIteration.java backend/src/main/java/com/wisread/model/RetrievalOutcome.java backend/src/main/java/com/wisread/service/impl/KnowledgeRagOrchestratorImpl.java backend/src/test/java/com/wisread/service/impl/CandidateMergeTest.java
git commit -m "feat: 补检候选合并算法（去重 + 首轮保底配额 + 确定性排序）"
```

---

### Task 5: KnowledgeRagOrchestrator 编排（deadline + 并发护栏 + 指标 + 降级全表）

**Files:**
- Create: `backend/src/main/java/com/wisread/service/KnowledgeRagOrchestrator.java`（接口）
- Modify: `backend/src/main/java/com/wisread/service/impl/KnowledgeRagOrchestratorImpl.java`（Task 4 骨架补全为 @Service）
- Modify: `backend/src/main/java/com/wisread/repository/KnowledgeBaseRepository.java`（追加 count 方法）
- Test: `backend/src/test/java/com/wisread/service/impl/KnowledgeRagOrchestratorTest.java`

**Interfaces:**
- Consumes: Task 2 `KnowledgeRetrievalGrader`、Task 3 `rewriteForRetry`、Task 4 静态合并方法与模型；`KnowledgeVectorIndexingService.searchKnowledgeChunks(userId, kbId, float[], topK)`、`EmbeddingService.embed(List<String>, Long)`、`KnowledgeRetrievalPipeline.filterAndTruncate`。
- Produces:
  - `interface KnowledgeRagOrchestrator { RetrievalOutcome orchestrate(String query, float[] queryEmbedding, KnowledgeBase knowledgeBase, Long userId); }`（Task 6/7 唯一依赖）
  - `KnowledgeRagOrchestratorImpl` 构造注入：上述服务 + `KnowledgeBaseRepository` + `MeterRegistry` + `@Value` 配置（agentEnabled 默认 true / retryBudgetMs 默认 8000 / retryConcurrency 默认 8 / fallbackTopK 默认 10）
  - `KnowledgeBaseRepository.countIndexedChunksByKnowledgeBaseId(Long)`：`SELECT COUNT(*) FROM knowledge_chunks kc JOIN knowledge_documents kd ON kd.id = kc.document_id WHERE kc.knowledge_base_id = #{id} AND kd.status = 'INDEXED'`

- [ ] **Step 1: 写失败测试**（Mockito，直接 new Impl 传入 mock 与配置值；Micrometer 用 `SimpleMeterRegistry`）

测试方法（spec 4.6 决策表逐行 + 8.C/8.D）：
- `agentDisabledPassthroughSingleRound`：agentEnabled=false → 1 轮检索，grader 与 count **零调用**（AC1 零额外开销），rounds=1，retryStatus=null；
- `sufficientFirstRoundSkipsRetry`：grader 返回 SUFFICIENT → 无改写/embed 二次调用；
- `insufficientTriggersRetryAndMerges`：NO_HITS → rewriteForRetry 返回 REWRITTEN → embed → 二次检索 → 合并结果返回；rounds=2，retryStatus=COMPLETED；`agent_retry_total` +1；
- `insufficientFinalIncrementsMetric`：补检后 grader（对最终候选再评估）仍不充分 → `agent_insufficient_final_total` +1（但结果非空照常返回候选）；
- `smallCorpusSkipsRetry`：SMALL_CORPUS → 无改写调用，retryStatus=SKIPPED，degradationReason=small_corpus，`agent_retry_skipped_total{reason=small_corpus}` +1；
- `invalidRewriteSkipsEmbeddingAndSearch`：INVALID → embed 与二次检索零调用，reason=rewrite_invalid；
- `passthroughRewriteSkipsRetry`：PASSTHROUGH → reason=rewrite_passthrough，首轮候选原样返回；
- `embedFailureFallsBackToFirstRound`：embed mock 抛异常 → reason=embedding_failed，ERROR 日志（不打桩断言日志，断言返回首轮）；
- `budgetTimeoutReturnsPromptlyWithoutRetrySearch`：budgetMs=100，rewriteForRetry mock 内 `Thread.sleep(2000)` → 返回耗时 < 1500ms，reason=budget_exceeded，二次检索零调用；
- `concurrencyLimitSkipsRetry`：构造时 retryConcurrency=1 且测试内先 `tryAcquire` 占满 → reason=concurrency_limit；
- `graderExceptionTreatedAsSufficient`：grader 抛异常 → 按 SUFFICIENT 处理，reason=grader_error，首轮候选返回；
- `emptyFirstRoundWithFailedRetryStillRefusable`：首轮空 + 补检 PASSTHROUGH → candidates 为空列表（调用方走拒答）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=KnowledgeRagOrchestratorTest`
Expected: FAIL（接口/实现不存在）

- [ ] **Step 3: 实现编排**

流程（spec 4.2.1 六步 + 4.2.2 deadline）：

1. 开关（agentEnabled && TRUE.equals(kb.getAgentEnabled())）任一 false → 单轮检索（search + filterAndTruncate）直接返回，不跑 grader/count；
2. 首轮：`searchKnowledgeChunks` + `filterAndTruncate`，记 RetrievalIteration(round=1)；grader 输入的 count 仅在此后按需查询一次（`countIndexedChunksByKnowledgeBaseId`）；
3. SUFFICIENT → 返回；SMALL_CORPUS → SKIPPED/small_corpus；其余不充分 → 补检；
4. 补检前置：`deadlineNanos = System.nanoTime() + budgetMs*1_000_000`；`retrySemaphore.tryAcquire()` 失败 → SKIPPED/concurrency_limit；剩余预算 ≤ 0 → SKIPPED/budget_exceeded；
5. 补检各阶段（改写 → embed → 检索）在补检专用 `ExecutorService`（fixed，size=retryConcurrency）上执行：

```java
Future<...> future = retryExecutor.submit(stage);
try {
    result = future.get(remainingMs, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true);
    return degrade(BUDGET_EXCEEDED); // 后续阶段一律不再执行
}
```

每阶段 submit 前检查剩余预算；PASSTHROUGH/INVALID → SKIPPED（对应 reason）；embed 异常 → SKIPPED/embedding_failed（ERROR 日志带堆栈）；
6. 补检轮结果过 `filterAndTruncate` → `mergeWithFirstRoundQuota(first, retry, resolveSingleRoundCap(kb, fallbackTopK))`；补检已执行时对最终候选再跑一次 grader 记 `agent_insufficient_final_total`（不影响返回，AC4 非空即生成）。

指标（构造时注册）：`agent_retry_total`（Counter）、`agent_retry_skipped_total`（Counter，tag reason）、`agent_insufficient_final_total`（Counter）、`agent_retry_duration`（Timer，补检环节端到端）。
日志：每轮 INFO（round、hits、sufficient、reason、elapsedMs）；降级 WARN（reason、剩余预算）；embed 失败 ERROR 带堆栈。
已知边界（写入类 javadoc，不阻塞）：pgjdbc 不保证线程中断即取消 SQL 语句，deadline 的 cancel 释放的是请求线程；DashScope 客户端超时以实际依赖版本支持为准，实现时核实并在 javadoc 记录结论。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=KnowledgeRagOrchestratorTest`
Expected: PASS（12 个测试）

- [ ] **Step 5: 全量测试 + 提交**

Run: `mvn test`
```bash
git add backend/src/main/java/com/wisread/service/KnowledgeRagOrchestrator.java backend/src/main/java/com/wisread/service/impl/KnowledgeRagOrchestratorImpl.java backend/src/main/java/com/wisread/repository/KnowledgeBaseRepository.java backend/src/test/java/com/wisread/service/impl/KnowledgeRagOrchestratorTest.java
git commit -m "feat: 新增 KnowledgeRagOrchestrator 统一编排（deadline/并发护栏/降级收敛/指标）"
```

---

### Task 6: ChatServiceImpl 绑库分支接线 + buildPrompt 动态编号

**Files:**
- Modify: `backend/src/main/java/com/wisread/service/impl/ChatServiceImpl.java`（绑库分支约 261-262 行、searchFromKnowledgeBase 约 396-404 行、buildPrompt 约 611 行）
- Test: 无新增单测（ChatServiceImpl 现无测试文件、构造依赖 23 项，按项目现状不为此建 mock 脚手架；行为由 Task 5 编排测试 + Task 8 全量回归 + 检索测试端点人工验证覆盖）

**Interfaces:**
- Consumes: `KnowledgeRagOrchestrator.orchestrate(query, queryEmbedding, kb, userId)`（Task 5）。
- Produces: 无新接口；绑库分支行为变更（内部）。

- [ ] **Step 1: 接线绑库分支**

- 构造器追加 `KnowledgeRagOrchestrator knowledgeRagOrchestrator` 注入（字段 + 赋值，与现有注入风格一致）；
- 绑库分支替换：

```java
// 原：chunks = searchFromKnowledgeBase(conversation, queryEmbedding);
KnowledgeBase kb = knowledgeBaseService.findOwnedKnowledgeBase(conversation.getUserId(), conversation.getKnowledgeBaseId());
chunks = knowledgeRagOrchestrator
        .orchestrate(query, queryEmbedding, kb, conversation.getUserId())
        .candidates();
```

- `searchFromKnowledgeBase` 私有方法整体删除（其归属校验与检索已分别上移至调用点与 orchestrator 内部）；拒答判断（chunks.isEmpty()）、L2 记忆、chatLog、SSE 全部不动（chatLog 记录的是最终合并候选，符合 spec）。

- [ ] **Step 2: buildPrompt 动态编号**

`"引用必须使用系统提供的编号 [1]..[3]。\n"` 改为 `"引用必须使用系统提供的编号 [1]..[" + chunks.size() + "]。\n"`（spec 4.4，一行改动）。

- [ ] **Step 3: 编译 + 全量测试**

Run: `mvn clean compile && mvn test`
Expected: BUILD SUCCESS，存量测试全过（未绑库分支零改动，KnowledgeRetrievalPipelineTest 等不受影响）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/wisread/service/impl/ChatServiceImpl.java
git commit -m "feat: 绑库问答接入 RAG 编排器并动态生成引用编号"
```

---

### Task 7: 检索测试端点接线 + iterations 字段

**Files:**
- Modify: `backend/src/main/java/com/wisread/dto/KnowledgeRetrievalTestResponse.java`（record 追加组件 + 两个嵌套 record）
- Modify: `backend/src/main/java/com/wisread/service/impl/KnowledgeRetrievalTestServiceImpl.java`（召回阶段替换为 orchestrator）
- Test: `backend/src/test/java/com/wisread/service/impl/KnowledgeRetrievalTestServiceOrchestratorTest.java`（新建）

**Interfaces:**
- Consumes: `KnowledgeRagOrchestrator.orchestrate`（Task 5）。
- Produces: `KnowledgeRetrievalTestResponse` 追加尾部组件 `List<IterationInfo> iterations, AgentInfo agent`：

```java
public record IterationInfo(int round, String query, int hits, boolean sufficient, String reason, long elapsedMs) {}
public record AgentInfo(String retryStatus, String degradationReason) {}
```

（追加在 record 尾部；旧客户端对新增 JSON 字段无感，AC6）

- [ ] **Step 1: 写失败测试**（Mock 全部 6 个现有依赖 + orchestrator mock）

测试方法：
- `mapsOrchestratorOutcomeToResponse`：orchestrator 返回 2 轮 outcome → response.iterations() 大小 2 且 round/hits 映射正确；agent() 含 retryStatus；recall 为最终候选；
- `emptyCandidatesStillRejected`：outcome.candidates() 为空 → rejected=true、answer 为拒答文案、不调用答案生成（verify chatModel 零调用）；
- `agentOffStillReportsFirstRound`：orchestrator 返回单轮 outcome（iterations 仅 1 条、retryStatus=null）→ iterations 正常透出。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=KnowledgeRetrievalTestServiceOrchestratorTest`
Expected: FAIL（构造器参数不匹配）

- [ ] **Step 3: 实现**

- 构造器追加 orchestrator 依赖；
- 召回阶段（现 102-108 行）：`embed(rewrittenQuestion)` 保留 → `RetrievalOutcome outcome = orchestrator.orchestrate(rewrittenQuestion, queryEmbedding, knowledgeBase, userId); chunks = outcome.candidates();`；recallMs 覆盖编排全程；
- 两处 `new KnowledgeRetrievalTestResponse(...)` 补传 `outcome.iterations().stream().map(i -> new IterationInfo(...)).toList()` 与 `new AgentInfo(outcome.retryStatus(), outcome.degradationReason())`；
- 拒答分支与答案分支逻辑不变（空候选拒答语义保持）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=KnowledgeRetrievalTestServiceOrchestratorTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/wisread/dto/KnowledgeRetrievalTestResponse.java backend/src/main/java/com/wisread/service/impl/KnowledgeRetrievalTestServiceImpl.java backend/src/test/java/com/wisread/service/impl/KnowledgeRetrievalTestServiceOrchestratorTest.java
git commit -m "feat: 检索测试端点接入编排器并透出 iterations 轮次明细"
```

---

### Task 8: 配置落盘 + 全量回归

**Files:**
- Modify: `backend/src/main/resources/application.yml`（wisread 段追加配置）
- Test: 无新增（回归性质）

**Interfaces:**
- Consumes: Task 5 的全部 `@Value` 键。
- Produces: 配置默认值落盘。

- [ ] **Step 1: application.yml 追加**（wisread.knowledge 段；键名与 Task 5 @Value 逐字一致）

```yaml
wisread:
  knowledge:
    agent:
      enabled: true            # 全局总闸（启动期生效，发版级保险丝）
      retry-budget-ms: 8000    # 补检统一 deadline（毫秒）
      retry-concurrency: 8     # 补检独立并发护栏
      grader:
        low-hits-min: 3        # LOW_HITS 下限 L 的上限之一
        weak-top-margin: 0.05  # 弱命中余量
        weak-top-absolute: 0.5 # 未配置阈值时的绝对兜底线
```

- [ ] **Step 2: 全量回归**

Run: `mvn clean compile && mvn test`
Expected: BUILD SUCCESS，全部测试通过（含存量 KnowledgeRetrievalPipelineTest、QueryRewriteServiceTest）。

- [ ] **Step 3: 对照 spec 验收清单自检**

逐条核对 spec §8 A/B/C/D 四组清单与 AC1-AC9：每条能指到对应测试或明确标注"人工验证"（真实 PG 迁移验证、检索测试端点灰度验证属 spec §5 上线流程，不在编码范围）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/resources/application.yml
git commit -m "chore: 落盘 agent 补检配置默认值（总闸默认开、库级默认关）"
```

---

## 任务间依赖

Task 1（实体/DTO）独立；Task 2、3 相互独立可并行；Task 4 依赖 Task 2 的 `resolveSingleRoundCap`；Task 5 依赖 2/3/4；Task 6、7 依赖 5；Task 8 收尾。执行顺序建议：1 → 2 → 3 → 4 → 5 → 6 → 7 → 8。
