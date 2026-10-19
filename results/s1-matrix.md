# P4: S1/S2 cancellation-boundary matrix

生成：`./p4/gate.sh`（每次运行重算；产物 `build/s1-matrix.md`，不写入版本库）。
流长度 = K：`Bed.doGen()` 恰写 K 个 chunk 后写 `CANCELLED(K)`；不存在「30-chunk 流」。

| cancelAt | crash-runs | S1-active | S1 violations | S2 violations | other findings |
|---|---|---|---|---|---|
| 3 | 23 | 12 | 0 | 0 | 0 |
| 10 | 37 | 12 | 0 | 0 | 0 |
| 17 | 51 | 12 | 0 | 0 | 0 |
| 30 | 77 | 12 | 0 | 0 | 0 |

S1-active = 恢复后日志中存在 CANCELLED 的 run（边界断言可执行）；无 CANCELLED 的 run 不适用该断言。合计 188 runs / S1-active 48（其中真实崩溃 run 40；无崩溃对照 8）。
S1 violations 覆盖：日志 chunk 数 != K、配置 K != 日志 K、`replayStream()` 发射数 != K、取消后重复 gen。

## 灵敏度（注入已知 S1 违例，必须被捕获）

| 注入 | 机制 | runs | S1 violations | 期望 |
|---|---|---|---|---|
| M9 | cancel-boundary-overrun（日志多记 1 chunk） | 53 | 24 | >0 |
| M12 | replay-emits-wrong-chunks（replayStream 发射数错误） | 51 | 12 | >0 |

## 口径与限制

- 本矩阵是校准床上的回归/灵敏度测试，不是对真实框架 S1 语义的独立证据。
- S2 在本矩阵中没有可失败路径（只有 M10 注入会让重放重调 `summarize`），因此「S2 0 违例」不作为 S2 成立的独立证据。
- S3 不在本矩阵断言（p4 只主张 S1/S2）：中途崩溃后恢复会合法地重调 `summarize`，把 `meteringPre/FinalEntries` 接通会产生假阳性。
- replay 断言使用 `Bed.replayStream()`（不调用模型、不写日志）；`preAppliedCounts` 取恢复前快照（C1 前置条件可成立）。

gate: PASS: matrix-0-violations=true; sensitivity-M9=true; sensitivity-M12=true
