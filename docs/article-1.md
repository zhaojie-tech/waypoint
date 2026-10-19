# 给 Agent 运行时立恢复契约：57 个确定性崩溃点与 13 个注入违例

> 2026-10-19 · Waypoint `v0.1.0` 研究制品记录。文中数字来自本机可复现的闸门，不是设计目标。数字的当前值以 [`README.md`](../README.md) 的冻结标记块与 `build/gate-result.json` 为准，校验脚本会比对两者。发布状态：仓库与 DOI 尚在公开发布流程中，本文不写未发布的仓库 URL，发布时补入克隆地址与 DOI。

## 问题

Agent 和工作流运行时越来越多，但「崩溃之后恢复得对不对」很少有机器检验。2026-08 的论文 [`arXiv:2608.03836`](https://arxiv.org/abs/2608.03836)（*Resume Means Resume: A Machine-Checked Conformance Contract for Checkpoint, Interrupt, and Resume Semantics in Workflow Persistence Layers*，Sajjad Khan）用 TLA+ 检查了 740 万个状态，指出多个 Python 框架在持久化/恢复语义上存在真实违例。不过它只覆盖 Python，也没有给出可被外部复用的执行级检验器。

Waypoint 的做法分三步：形式化契约，证明检查器自身有效，再测真实框架。

## 契约（9 条）与 L1

前 6 条（C1–C6）沿用论文的六个性质名：前缀续跑、副作用恰好一次、分叉确定、检查点自洽、消费一次、恢复确定。C3/C4 为执行级检验重新定义，差异写在 `docs/contracts.md`，与论文措辞并不相同。S1–S3 是 LLM 场景的扩展：流式前缀与取消边界、压缩溯源、计量一致。每条契约写明前置条件、可执行断言和观测点。另有 L1 日志完整性辅助检查（重复完成、没有 STARTED 的完成、重复 `RUN_COMPLETED`），它不是第 10 条契约。

finding 分两类。violation 要求框架文档明确承诺但实测违反，必须附 URL、pinned 版本、节和引文。gap 指未承诺但缺失；拿不出承诺原文的，只写 gap。

## Mutation 校准

检验器最常见的质疑是「自己写的检查器凭什么可信」。做法是 mutation testing：往校准床注入 13 个已知违例（effect 先执行后记日志、重试不查 reconcile、检查点伪造完成态、fork 共享游标、取消边界越界、跳过未完成 step、重放多发 chunk、重放重新计量等），检验器必须在目标契约上逐个捕获，同时健康床在 63 个 crash-run 上 0 误报。13 个 mutant 与 9 条契约的对应关系见 `docs/mutations.md`。

实测（复现：`./gate.sh`，4 核 JDK 17 约 5–10 秒含编译；数字以 `build/gate-result.json` 为准）：

```
[1] recovery determinism: 100/100 replays hash-equal
[2] torn-tail self-test: kept 9/10 intact-prefix=yes
[3] baseline specificity: 63 crash-runs, 0 contract violations
[4] mutations captured 13/13
GATE: PASS
report sha256=<见 README 冻结块 / gate-result.json>
whole-gate reproducibility: 2/2 identical (same seed, same result)
```

63 = 确定性崩溃点 + 无崩溃对照（构成与每个场景的算术见 `docs/mutations.md`）。13/13 只是入门条件，更难的是 63 个 crash-run 上的 0 误报。校准过程抓到过实现里的 bug：恢复用了过期的完成集，导致同一步执行两次；WAL 记录索引用错，恢复实例从零重数；fork 键带子进程后缀，让健康分叉天然不同。检查器先查自己，再去看别人的运行时，这一步不能省。

闸门判据是结构化的：PASS 由结果对象的数值断言决定（determinism 计数、torn-tail 存活、baseline runs/violations、mutants 捕获数），不靠报告文本；5 类退化（含 hostile locale）必须全部 exit 非 0。这条修复来自一轮评审，旧版谓词会对退化报告给 PASS。

## 真实框架观测（P2）

驱动器把外部账本（副作用真发生了几次）和轨迹做成跨进程重启存活的观测点，对框架只暴露公开 API。P2 共 10 条 findings：4 gap + 4 PASS + 2 观测。证据强度分三级：

| 强度 | 做法 | 例子 |
|---|---|---|
| **A** | 真 `SIGKILL`（exit 137），副作用已 fsync | LangGraph(Python)：node:c 账本 1→2 → C2 gap |
| **A−** | 两个真进程同时 resume | LangGraph(Python)：node:c 1→3，两进程均正常返回 → C5 gap |
| **B+** | 进程内崩溃模拟 + 真实框架 API | langgraph4j 1.9.5 / SAA 1.1.2.4：崩溃节点重跑 → C2 gap |

没有把任何一条升级为 violation：LangGraph 家族文档化语义是「恢复后可重跑」（at-least-once），恰好一次需要业务侧幂等键。SAA 的裸 `invoke` 同 threadId 静默全图重跑是文档未覆盖的观测，不是承诺违反。langgraph4j 的承诺原文已逐字核验（README v1.9.5 §Checkpoints (Persistence)：*"Resuming: Restore a graph to a previous state and continue execution."*）；SAA 三处引文目前是中文 paraphrase，发布前补英文原文。逐条 URL、commit/tag pin、节锚点、采集日与证据文件映射见 `p2/REPORT.md`。

## 适配器与流式边界（P3、P4）

P3 把 langgraph4j 与 SAA 的公开 API 观测映射为 `Checker.RunResult`，喂给真实的 `waypoint.Checker`（不是同构实现），跑 2 框架 × 4 故障形态。适配器必须声明不可观测项（C2 顺序半条、C3/C4/S1/S2 在本层不可观测；`protocolObservable=false`）。并发格是真线程竞争，报告给出 3 次运行的 `c` 值分布，避免用单次数字冒充稳定结果。合成 `ApplyRecord` 在报告中标注 synthetic。

P4 的 S1/S2 取消边界矩阵用确定性崩溃点扫 K∈{3,10,17,30}，共 188 runs（含无崩溃对照；构成见 `build/s1-matrix.md`）。188 里只有 48 个 S1-active run 真正能执行 S1 断言（40 个真实崩溃 + 8 个无崩溃对照），因此「0 violations」是对 48 说的，不是对 188 说的。矩阵带灵敏度检查：注入 M9/M12 必须出现 S1 violations（最新一轮 M9 → 24、M12 → 12），否则 gate FAIL。真实 LLM 冒烟只做真实 SSE 捕获字节的 fsync 往返，不经过恢复引擎、不对真模型二次调用，口径见 `p4/README.md`。

## 范围与限制

- 进程崩溃不等于掉电：page cache 与 fsync 丢失未建模，所有崩溃证据都是进程崩溃证据。
- 核心是单节点、单线程：并发用显式 lease/resumer 协议建模；分布式、多机、group commit 不在范围。
- 适配器覆盖是部分的：不可观测的检查记为 unobservable，不计为通过。
- P2 只测了 5 个 pinned 框架中的 3 个；AgentScope 与 Diagrid 未实测（坐标 pinned，原因写明）。
- 真实 LLM 驱动恢复引擎未测；P4 冒烟只验证字节持久化。
- JDK 17 本地验证，JDK 21 纳入 CI 矩阵（v0.1.0 尚无公开运行记录）；Windows 非原生目标（bash gates）。
- 不是生产组件：这是检验器与复现包，不要用这套代码跑 agent。

## 复现

仓库尚在公开发布流程中，本文不虚构克隆 URL，发布后补仓库地址与 DOI。无论从何处取得检出，复现只需：

```bash
cd waypoint && ./gate.sh        # P1 核心闸门：4–7 秒，exit code 即结论
./p4/gate.sh                    # P4 S1/S2 矩阵（需 JDK 17+）
make check                      # 闸门 + p4 + 文档数字校验（Makefile 由发布工程提供）
```

P3 需要 Maven（首次联网解析两个框架依赖）；P2 需要 Maven / Python venv 与网络。完整前置、预期输出与本机不可复现项清单见 [`docs/reproducibility.md`](reproducibility.md)。
