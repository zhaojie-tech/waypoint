# P3 conformance matrix: real frameworks × real waypoint.Checker

生成：`./p3/gate.sh`（每次运行重算；产物 `build/p3-report.md`，不写入版本库）。本矩阵验证适配器与检验器的联调，不是对框架语义的独立复证。真实观测只有外部账本计数与检查点链。

## 可达性（adapter → Checker）

| 契约 | 本适配器可见性 | 观测/限制 |
|---|---|---|
| C1（不重跑已完成） | 可达 | 检查点链（preCompletedKeys）+ 外部账本增量 |
| C2（生效计数 ≤1） | 可达 | 外部账本计数（由真实框架驱动） |
| C2（STARTED 先 synced 的顺序不变式） | 不可观测 | 框架不暴露日志记录号 → `protocolObservable=false` |
| C5（consume-once） | 部分可达 | 计数为真实观测；executor 标签为 synthetic；lease 语义不可观测 |
| C3（fork determinism） | 不可观测 | 写入 `RunResult.unobservable` |
| C4（checkpoint validity） | 不可观测 | 同上（无日志前缀索引） |
| S1（流前缀/取消边界） | 不可观测 | 同上（框架层无 chunk 日志） |
| S2（压缩溯源） | 不可观测 | 同上（无 compaction 记录） |
| C6 / S3 / L1 | 未覆盖 | 本适配器不提供 replayHashes / metering / 日志完整性观测 |

## 矩阵（第 1 次运行；每格 findings 由真实 waypoint.Checker 计算）

| framework | fault | checker findings | detail |
|---|---|---|---|
| langgraph4j | CRASH_AFTER_EFFECT | C2 | c=2 b=1 :: C2 :: effect node:c applied 2x |
| saa-graph | CRASH_AFTER_EFFECT | C2 | c=2 b=1 :: C2 :: effect node:c applied 2x |
| langgraph4j | CRASH_BEFORE_EFFECT | clean | c=1 b=1 |
| saa-graph | CRASH_BEFORE_EFFECT | clean | c=1 b=1 |
| langgraph4j | DOUBLE_RESUME_SAME_CP | clean | c=1 b=1 |
| saa-graph | DOUBLE_RESUME_SAME_CP | clean | c=1 b=1 |
| langgraph4j | CONCURRENT_RESUME | C2+C5 | c=3 b=1 [threads-errors=0] :: C2 :: effect node:c applied 3x |
| saa-graph | CONCURRENT_RESUME | C2+C5 | c=3 b=1 [threads-errors=0] :: C2 :: effect node:c applied 3x |

## 并发格 3 次分布（真线程竞态）

| framework | c samples (3 runs) | findings across runs | grade consistent | thread errors |
|---|---|---|---|---|
| langgraph4j | [3, 3, 3] | C2+C5 | yes | 0 |
| saa-graph | [3, 3, 3] | C2+C5 | yes | 0 |

## 合成观测声明（不得当成 clean）

- 账本计数是真实框架观测；`ApplyRecord(startedRecordIndex=0, syncedFrontierAtApply=1)` 是 synthetic 占位，且 C2 顺序断言已由 `protocolObservable=false` 关闭，故这些字段不构成任何通过证据。
- 并发格 executor 标签（runner/resumer1/resumer2）由账本计数增量推导（synthetic），仅用于让 Checker 走 C5 的 executor 多样性规则；lease 语义不可观测。
- 事件流由已观测节点集合合成（无 CHECKPOINT/CHUNK/CANCELLED/COMPACTION 记录）；C3/C4/S1/S2 因此在 `unobservable` 中，报告不会把它们计为 clean。

## gate 判据（全部必须满足）

1. ≥2 个框架出现 findings（真实 Checker 判定）；
2. 并发格 findings 定级跨 3 次运行一致；
3. 3 次运行累计 threads-errors = 0。

gate: PASS: frameworks-with-findings=2; concurrent-grade-consistent=true; thread-errors=0
