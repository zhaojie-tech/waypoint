# P2 状态：真实框架对照（v0.1.1 版）

> 本文件说明哪些框架真的测了、测到什么强度、哪些没测、为什么。
> 正式报告：[`REPORT.md`](REPORT.md)（10 findings，逐条附引文/证据/复现）。采集日 2026-10-19，观测快照 `v0.1.0`（结果文件自该版本起未变）。

## 框架实测状态

| 框架 | 坐标（pinned） | 实测状态 | 结果（详见 REPORT.md） |
|---|---|---|---|
| LangGraph4j | `org.bsc.langgraph4j:langgraph4j-core:1.9.5` | 已实测（Java 驱动器，证据强度 B+） | 4 场景：C1 PASS、C2 gap（崩溃节点重跑，账本 1→2）、链/确定性 PASS；承诺原文逐字核验（v1.9.5 README） |
| Spring AI Alibaba Graph | `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.4-security-fix` | 已实测（Java 驱动器，B+） | C1 PASS（显式 checkpoint）、C2 gap（崩溃节点重跑）、裸 `invoke` 同 threadId 静默全图重跑（安全观测）；引文为 paraphrase，待补原文 |
| LangGraph (Python) | `langgraph==1.2.16` + `langgraph-checkpoint-sqlite==3.1.2` | 已实测（真 SIGKILL，A；真并发，A−） | C2 gap（node:c 1→2）、C5 gap（双进程并发 1→3）、检查点回退窗口观测（probe 已提交=2，含 run-to-run 方差） |
| AgentScope Java | `io.agentscope:agentscope-core:2.1.0-BETA1` | 未实测（架构级观测） | `javap` 侦察：session 级 `AgentStateStore`，非 per-step checkpoint 链 → 不适用逐 step 契约；无 findings |
| Diagrid Catalyst | `io.diagrid:diagrid-spring-ai-starter:0.4.0` | 受阻（需 Catalyst 云） | 官方博客承诺原文已逐字采集（"completed steps were never re-executed"），是未来 C2 检验标的 |

## 本轮收口完成的事

1. 10 条 findings 全部重写为可审计格式：每条含分类（gap/violation/观测）、证据强度（A/A−/B/B+）、
   引文 URL + pin + 节锚点 + 采集日、观测、证据文件与复现命令；见 [`REPORT.md`](REPORT.md) §2–§4。
2. 引文核验状态逐条写出：langgraph4j / LangGraph / Diagrid / arXiv 逐字核验；SAA 三处标注 `paraphrase`
   （客户端渲染页面，英文原文未归档），发布前补录。
3. 表述收窄：撤回"三方互证、三运行时 consume-once 全部失效"这类强合并结论；P2 只对双进程真并发
   （#9）下"同一节点被多执行"负责，P3 的 Java 线程并发结论由 `p3/README.md` 单独声明。
4. 未测项逐项写出：AgentScope / Diagrid 不作 findings，也不静默跳过；掉电语义、真 LLM 恢复引擎不在 P2 范围。

## 与 P1 检验器的对接

P3 已把驱动器观测映射为 `Checker.RunResult` 并喂给真实的 `waypoint.Checker`（非同构实现）；P2 lab 仍是
独立的框架驱动器 + 外部账本观测，用于回答"真实框架行为如何"，不依赖 P1 内部实现。两层的证据口径不同，见
[`p3/README.md`](../p3/README.md) 与 [`docs/architecture.md`](../docs/architecture.md) §5。

## 未做 / 不在本仓库做

- 掉电语义：需要内核级故障注入（dm-flakey / dm-log-writes）或 VM 快照；本机无 root。当前全部结论限定为
  进程崩溃语义。
- 分布式/多机恢复：超出范围（lease 为单节点模拟）。
- 真 LLM 驱动恢复引擎：P4 的真实 SSE 冒烟只做捕获字节的持久化往返，不经过恢复引擎、不对真模型二次调用。
- AgentScope / Diagrid 驱动器：需要新增 Runner API 侦察 / Catalyst 云环境，可按
  [`CONTRIBUTING.md`](../CONTRIBUTING.md) 的"新增框架适配"流程贡献。

## 新增框架的最小检查单

1. 新增框架必须 pin 到确切坐标/版本，并记录 JDK/Python/OS 环境。
2. 每条 finding 先分类：有框架承诺原文才允许 violation，否则 gap。
3. 证据强度必须标注（真 SIGKILL / 真并发 / 进程内崩溃 / 仅文档）。
4. 结果文件随仓（原始账本/轨迹/检查点数量），并给出一条可复制命令。
5. 运行 `./gate.sh` 与 `make check` 必须通过；不要修改他人 P2 结果文件。

正式报告：[`REPORT.md`](REPORT.md)（2026-10-19，v0.1.1，10 findings，SAA 引文待逐字补录）。
