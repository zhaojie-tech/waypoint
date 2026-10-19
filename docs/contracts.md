# Waypoint 恢复契约（9 条）与 L1 日志完整性

版本 v3-R4，2026-10-19。C1 到 C6 的性质名取自 arXiv 2608.03836，出处见 §3；S1 到 S3 是为 LLM 场景增加的契约。C3 和 C4 按本检查器的观测方式重新定义，跟论文措辞不完全一致。

每条契约写四个字段：前置条件、断言、观测点、实现状态。实现状态取 full、conditional、unobservable-by-adapter 之一。无法观测的契约由适配器显式声明，检查器不会因为缺少证据就判 clean。

finding 分两类。violation 指框架文档明确承诺该性质而实测违反，需要附上承诺原文、版本和出处行。gap 指框架没有承诺、实测也缺失，标为未承诺，不写成违反。

partial 是第三种状态，用于一条契约里只有部分规则拿到证据的情况。比如适配器没有外部 ledger、但提供了 `expectedSteps`，此时 C1 的跳过检测能跑，重跑检测跑不了，记入 `RunResult.partial`，报告写成 `partially observable`。它既不算 clean，也不算 unobservable；`RunResult.unobservable` 表示整条契约都没有证据。

## 1. 契约表

| # | 契约 | 前置条件 | 断言 | 观测点 | 实现状态 |
|---|---|---|---|---|---|
| C1 | Prefix continuation（前缀续跑） | run 在任意操作边界崩溃后恢复 | ①恢复不重跑已完成的 step（外部分类账增量 ∩ 崩溃前已完成键 = ∅）；②`expectedSteps` 非空且日志出现 `RUN_COMPLETED` 时，每个期望 step 必须有 `STEP_COMPLETED`（不得**跳过**未完成 step） | 外部分类账 + `RunResult.expectedSteps` + 日志前缀 | **full**（规则①始终启用；规则②要求适配器提供 `expectedSteps`，否则记为 unobservable） |
| C2 | Effect exactly-once（幂等键下恰好一次） | effect 声明幂等键并支持 reconcile | ①同一幂等键的外部生效次数 ≤ 1；②「STEP_STARTED 先落盘（synced）→ effect 后执行」的顺序不变式成立 | 外部分类账 `appliedCounts` + `ApplyRecord(startedRecordIndex, syncedFrontierAtApply)` | **full**（顺序断言在 `protocolObservable=false` 的适配器上自动跳过并计入 `unobservable`，不伪造通过） |
| C3 | Fork determinism（分叉确定） | 同一 checkpoint 分叉出 ≥2 个子 run，相同输入与顺序种子 | 子 run 的 **canonical trace**（`Event.canonical()` 对 per-child 键后缀 `#cN` 做掩码，排序后为多重集）相同 | 适配器导出 `childEvents0/1`（优先，Checker 自算 trace）或回退 `childTrace0/1` 哈希 | **full**（由适配器提供子日志；两者都缺 → unobservable，不报 clean） |
| C4 | Checkpoint validity（检查点自洽） | 存在 checkpoint 记录 | snapshot 声明的**每个** COMPLETED，在其**日志前缀**内必须有对应 `STEP_COMPLETED`；`f2` 以 `;` 分隔多组 `path=status` | CHECKPOINT 事件 + 前缀扫描 | **full**（多状态快照逐条校验；单状态快照为其特例） |
| C5 | Consume-once（消费一次） | 两个恢复实例竞争同一 run | ①同一幂等键由 ≥2 个**不同 executor** 生效 → C5；②事件流中某 path 先有 `LEASED(path,owner)`，其后同 path 的 effect 由非 owner 执行 → C5；③`twoResumers=true` 但日志无 LEASED 且无 executor 证据 → unobservable | `ApplyRecord.executor` + `LEASED` 事件 + 外部分类账 | **full**（要求适配器导出 executor/lease 观测；否则 unobservable） |
| C6 | Recovery determinism（恢复确定） | 同一日志恢复多次；同一逻辑工作流在不同调度顺序下运行 | ①`replayHashes`（≥2 次恢复的状态哈希）全等；②`orderTraces`（跨顺序种子的 canonical trace）规范化多重集相等 | 状态哈希 + 跨顺序种子 canonical trace | **full**（需适配器提供两组观测；缺 → unobservable） |
| S1 | Stream prefix & cancellation boundary（流式前缀与取消边界） | LLM 流式响应被客户端在第 k 个 chunk 取消 | ①持久化 chunk 数 == 日志 K；②`configuredCancelAt >= 0` 时与日志 K 一致；③`replayChunksEmitted >= 0` 时必须 == 日志 K；④取消后同一 subject 的 model `gen` 调用 >1 → S1；⑤`CANCELLED` 之后不得再出现同 subject 的 `CHUNK`（乱序越界） | CHUNK/CANCELLED 事件 + model spy + `Bed.replayStream()` | **full**（重放恰 K 由独立重放观测；并校验取消边界之后的乱序 chunk） |
| S2 | Compaction provenance（压缩溯源） | 上下文压缩已作为 step 完成并持久化 | 摘要已在日志时，重放不得重新调用模型；摘要内容/模型版本/prompt hash 可追溯 | COMPACTION 事件 + model spy 增量 | **full** |
| S3 | Metering consistency（计量一致） | Waypoint 侧仅验「重放不重新计费」；「真实 usage 锚定」超出本仓范围（归姊妹网关计量项目） | ①`meteringPreEntries > 0` 时 `meteringFinalEntries` 不得增加（重放路径零新增计量分录）；②中断流分类 ⊆ {COMPLETED, ESTIMATED, UNKNOWN} | 计量分录账本（`Model.MeteringLedger`）+ 中断分类 | **conditional**：仅覆盖「重放不重新计费」与分类合法性；`meteringPre` 必须在「不会发生合法新执行」的窗口设置（否则合法新工作会像违例）；真实 usage 锚定为显式 out-of-scope |

## 2. L1 日志完整性

L1 是辅助日志完整性检查，不是第 10 条契约。它不描述框架语义，只看日志自身有没有明显损坏，正常基线应为 0 条。

1. 同一 path 出现多条 `STEP_COMPLETED`（重复完成）。
2. `STEP_COMPLETED` 前面没有持久化的 `STEP_STARTED`。
3. `RUN_COMPLETED` 出现超过一次（恢复非幂等的典型症状）。

崩溃模型和文档不一致的问题由 L1 兜底。修复前 63 个基线 run 里有 7 个出现重复 `STEP_COMPLETED`，63/63 出现多条 `RUN_COMPLETED`，Checker 当时报 0 违例。加上 `discardUnsynced()`、torn-tail 截断、`appended = live.size()`，再把 `RUN_COMPLETED` 做成幂等之后，基线 L1 为 0。`BedCrashTest.calibrationBaselineHasNoLogIntegrityViolations` 和 gate 都会检查这一点。

## 3. 与论文的对应和差异

C1–C6 的属性族与 arXiv 2608.03836 的六个性质一一对应，S1–S3 是本项目增加的。

C3：论文讨论分叉子 run 的确定性，本仓库把它落到适配器能导出的 canonical trace 多重集比较上，用 `#cN` 掩码消掉 per-child 键的命名差异。这个定义可以直接吃外部框架日志，但不覆盖进程或调度层面的不确定性，那部分归 C6。

C4：论文用运行时不变量表述 checkpoint 自洽；这里改成静态可审计的「snapshot 声明 ⊆ 日志前缀」断言，并支持 `path=status;path=status` 多状态快照，单状态快照是它的特例。

C5：论文的 consume-once 依赖并发原语；P1 校准床是单线程，用双实例串行化协议加 LEASED/executor 观测来逼近。真并发放在 P2（Python 双进程）和 P3（Java 双线程）的适配器实验里，分层情况见 §5 第 3 条。

S3：「真实 usage 锚定」要有上游网关的 usage 回执，本仓库不建模，只验证重放不新增计量。

## 4. 适配器义务

`Checker.RunResult` 新增的字段都带默认值，保持向后兼容。

```java
public List<String> expectedSteps = List.of();      // C1 规则②
public List<Model.Event> childEvents0 = List.of();  // C3（优先于 childTrace0/1）
public List<Model.Event> childEvents1 = List.of();
public boolean protocolObservable = true;           // C2 顺序断言
public List<String> replayHashes = List.of();       // C6 ①
public List<String> orderTraces = List.of();        // C6 ②
public int configuredCancelAt = -1;                 // S1 ②
public int replayChunksEmitted = -1;                // S1 ③
public int meteringPreEntries = 0;                  // S3 ①
public int meteringFinalEntries = 0;
public Set<String> interruptionKinds = Set.of();    // S3 ②
public Set<String> unobservable = Set.of();         // 明确标注不可观测的契约 id
```

适配器无法观测某条契约时，把契约 id 放进 `unobservable`；C2 的顺序不可观测则用 `protocolObservable=false` 表达。Checker 会跳过这些契约并写进 `RunResult.unobservable`，不会在缺证据时给出 clean。接入步骤、字段语义和每条断言需要的观测见 `docs/adapter-guide.md`（30 分钟接入指南）。

## 5. 故障矩阵的三个类

1. SIGKILL-after-effect：副作用已经对外生效，完成记录还没落盘，对应 C2 的危险窗口。
2. double-resume：同一 run 被两个实例先后 resume，对应 C5。
3. concurrent resume：两个实例同时恢复，论文在 consume-once 上测到饱和违例。

覆盖分三层。P1 校准床用双实例串行化模拟，单线程，确定性可控。P2 和 P3 在真实框架层做真并发：P2 是 LangGraph Python 双进程（`p2/REPORT.md` #9，带真 SIGKILL 和双进程证据），P3 是 langgraph4j 双线程（`c=2/3` 竞态，见 `p3/README.md`），两者都观测到 consume-once 失效。C5 里的 lease owner 在 p3 适配器中仍不可观测，有 `unobservable` 声明，所以 C5 的 lease 断言只在 P1 校准床执行，那里 LEASED 和 executor 都能看到。

C2 的 exactly-once 只在 effect 提供幂等键并支持 reconcile 时成立。不支持 reconcile 的 effect 会进入 UNKNOWN 终态，run 停止，不自动重试。框架不保证 exactly-once，这里也不替它保证。
