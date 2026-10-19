# Mutation 清单（13 个）与 63 点基线

每个 mutant 是校准床的一个开关（`Bed.Config` 布尔位），对应一种已知的契约违反。校准判据是结构化的，不看报告文本：门禁要求该 mutant 被抓到目标契约的 finding（13/13），同时健康床在 63 个 crash-run 上 0 误报。本文数字来自 `./gate.sh` 写出的 `build/gate-result.json` 与报告实测（2026-10-19）；report sha 见 `results/gate.json` 和 README 的冻结标记。

## 1. 63 点基线构成

| 场景 | 点 | 说明 |
|---|---|---|
| `S_W1` | **20** | 控制点 k=-1（不崩溃）+ 崩溃点 1..19（W1 共 19 个 op） |
| `S_W1_CKPT` | **22** | 控制点 + 1..21（含 checkpoint 的 21 op） |
| `S_W1_RESUME2` | **8** | 控制点 + 双实例窗口 8..14 |
| `S_W2` | **11** | 控制点 + 10 个崩溃点：36,37,38（生成段中段，CANCELLED 未落盘）+ 43..49（压缩已持久化后的尾部） |
| `S_W3` | **2** | 控制点 + k=0（k=0 实际不触发：op 计数从 1 开始，作为第二次对照） |
| **合计** | **63** | 20 + 22 + 8 + 11 + 2 = **63** ✓ |

`S_W2` 的缺口：崩溃点 40..42 落在 CANCELLED 已持久化、compaction 还没持久化的窗口，恢复会合法地重新执行 summarize（新执行，不属于重放）。S3 只看重放是否新增计量，把这 3 个点算进基线会把合法新执行当成 S3 违例，所以基线取 36..38 来覆盖生成段中段的恢复。P4 的 188 点 S1 矩阵覆盖了这个缺口（p4 不启用 S3 观测，见 `p4/README.md`）。

## 2. active/total

下表是 63 个基线 run 中各契约真正可断言的数量。

| 契约 | active/total | 说明（当前基线里契约何时「可断言」） |
|---|---|---|
| C1 | **63/63** | 每个 run 都填 `expectedSteps` 且恢复后必有 `RUN_COMPLETED` |
| C2 | **63/63** | 每个 run 都有外部 effect 记录 |
| C3 | **2/63** | 只有 W3 fork 场景导出子 run 观测（其余 run 该契约 unobservable，不报 clean） |
| C4 | **11/63** | 只有 checkpoint 记录已 durable 的 run（S_W1_CKPT 中 k≥12 的 10 点 + 控制点）可断言 |
| C5 | **8/63** | 只有双恢复实例场景（S_W1_RESUME2 全部 8 点） |
| C6 | **63/63** | 每个 run 都做 3 次 FULL 恢复 + 状态哈希比较 |
| S1 | **8/63** | 只有 CANCELLED 已 durable 的 run（S_W2 的 43..49 + 控制点）；生成段中段崩溃无取消记录，不适用 |
| S2 | **8/63** | 只有恢复开始时 compaction 已 durable 的 run |
| S3 | **11/63** | 只有恢复前计量分录 > 0 的 run（W2 全部 11 点） |

未 active 的 run 不算通过，只是不适用或 unobservable。Checker 把跳过的契约记进 `RunResult.unobservable`，报告里用 active/total 说明，不把没查过的说成干净。

## 3. 13 个 mutant（每个契约至少 1 个专属 mutant）

| # | 名称 | 机制 | 违反契约 | 检测器 | 校准结果（`./gate.sh` 实测 findings 数） |
|---|---|---|---|---|---|
| M1 | effect-before-started-sync | 先执行副作用、后写 STEP_STARTED | C2（顺序不变式） | `ApplyRecord.startedRecordIndex == -1` | yes，基线即捕获（63） |
| M2 | started-not-synced | 写 STEP_STARTED 但不 sync 就执行副作用 | C2（顺序不变式） | `startedRecordIndex >= syncedFrontierAtApply`；崩溃后副作用重复 | yes，基线即捕获（42） |
| M3 | recovery-retry-without-reconcile | 恢复时对 Started-无-Completed 直接重试，不查 reconcile | C2 | 外部分类账计数 > 1 | yes，k=6 起捕获（6） |
| M4 | replay-re-executes-completed | 重放时对已完成 step 重新执行 effect | C2 | 外部增量 ∩ 崩溃前已完成键 | yes，基线即捕获（84） |
| M5 | completion-order-step-ids | step 标识由完成顺序决定而非编排结构 | C6（跨顺序标识不稳） | 跨顺序种子 canonical trace 多重集 | yes，双种子捕获（1） |
| M6 | checkpoint-fabricates-completed | 检查点把 in-flight step 写成 COMPLETED | C4 | 多状态快照每条 COMPLETED 的前缀校验 | yes，基线即捕获（11） |
| M7 | fork-shares-cursor | fork 子进程共享父日志游标（无隔离） | C3 | 子事件流 canonical trace（#cN 掩码） | yes，捕获（4） |
| M8 | second-resumer-ignores-lease | 第二恢复实例无视 lease 执行 pending step | C5（+C2 级联） | 非 owner executor 执行 leased path / 不同 executor 数 ≥2 | yes，窗口 10..12 捕获（7） |
| M9 | cancel-boundary-overrun | 取消在第 K chunk，日志却记到 K+1 | S1 | chunk 数 ≠ 取消边界；model gen 重调 | yes，基线即捕获（18） |
| M10 | replay-regenerates-summary | 摘要已持久化，重放仍重新调用模型 | S2（+S3 级联） | 摘要在场时 model spy 增量 > 0 | yes，基线即捕获（16） |
| **M11** | skip-unfinished-step | 恢复直接补 `RUN_COMPLETED`，跳过未完成 step | **C1** | `expectedSteps` 中缺失 `STEP_COMPLETED` | yes，捕获（36） |
| **M12** | replay-emits-wrong-chunks | 重放多发射 1 个 chunk | **S1** | `replayChunksEmitted != 日志 K` | yes，捕获（8） |
| **M13** | metering-on-replay | 重放路径为每个 chunk 新增计量分录 | **S3** | `meteringFinalEntries > meteringPreEntries` | yes，捕获（11） |

total = 13，active = 13，没有休眠的 mutant。专属对应关系：C1→M11，C2→M1/M2/M3/M4，C3→M7，C4→M6，C5→M8，C6→M5，S1→M9/M12，S2→M10，S3→M13。

M8 同时触发 C2、M10 同时触发 S3，这是契约语义本身蕴含的级联，不算误报；目标契约仍按专属行判定。

## 4. 校准中发现的真实 bug

这些不是注入出来的问题，是校准过程中检查器或基线自己抓到的。

1. 未 sync 的崩溃尾部会复活。`MemoryDisk` 原先没有 discard 和截断，崩溃前未 sync 的 `STEP_COMPLETED` 会在恢复期第一次 sync 时变成 durable，crash@7 能看到 `ok:k_charge` 和 `ok:k_charge:reconciled` 并存。修法是恢复第一行调用 `discardUnsynced()`。
2. torn-tail 之后继续 append 的记录不可见。解析遇到坏记录只停不截断，后来加了 `truncateDurableAt(n)`。
3. Wal 记录索引跨实例错位。恢复实例从 disk 总写入数续接索引，丢弃尾部后编号会超过 durable 前缀长度。改成 `appended = live.size()`。
4. 恢复非幂等。每次 `recover(FULL)` 都会追加 `RUN_COMPLETED`，63/63 个 run 出现多条。改成已 durable 就不再追加。
5. C6 orderTraces 判据自比较。初版 Checker 拿 orderTraces 和自身比，M5 抓不到。mutation 校准当场把它压到 12/13，改成规范化多重集比较后才过。先证明检查器有效，再谈别人的违例。

## 5. 退化判据

`./gate.sh` 的 PASS 来自结构化数值断言：determinism 100/100、torn-tail 9/10 且 prefix intact、baseline 63/0、mutants 13/13、C6 replay/order stable、locale 独立、2/2 可复现。审计时可以人为退化任一测量值，确认 gate 变红：

```bash
cd "$(git rev-parse --show-toplevel)"
for f in determinism torn-tail baseline mutants locale c6; do
  WAYPOINT_FAULT=$f ./gate.sh >/dev/null 2>&1; echo "$f RC=$?"   # 期望全部 RC=1
done
unset WAYPOINT_FAULT
./gate.sh; echo "normal RC=$?"                                    # 期望 RC=0
```

六类钩子（determinism、torn-tail、baseline、mutants、locale、c6）都只改测量值，不改谓词，跟直接在 /tmp 副本里改计算值等价。它们只用于审计，正常路径和 CI 不设置。locale 在正常环境下另外验证：`-Duser.language=ar -Duser.country=EG` 与 `tr-TR` 下 gate 仍 PASS（全部格式化使用 `Locale.ROOT`）。
