# Waypoint P2 对照报告：真实框架实测（v0.1.0 版）

> 检验器：Waypoint `v0.1.0`（P1 gate：mutation 校准全部捕获，健康基线 0 误报；复现：根目录 `./gate.sh`）。
> 观测快照：`v0.1.0`。P2 驱动器与结果文件与采集时一致，v0.1.0 只清理了未使用产物。
> §1 说明证据强度分级，每条 finding 的复现命令、证据文件与引文出处（URL、pin、节锚点、采集日）见 §2 与 §3。
> 写作规则：没有实测不写结论；没有框架承诺原文不写 violation，只写 gap；引文无法逐字核对时标
> `paraphrase` 或 `unverified-url`，不写成已验证。

## 1. 版本矩阵与证据强度

| 项 | 值 |
|---|---|
| Waypoint 观测快照 | `v0.1.0` |
| 观测机器 | Linux 5.15（4C），JDK Temurin 21.0.12.1+1，Python 3.10.12（venv） |
| langgraph4j | `org.bsc.langgraph4j:langgraph4j-core:1.9.5` |
| Spring AI Alibaba Graph | `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.4-security-fix` |
| LangGraph (Python) | `langgraph==1.2.16` · `langgraph-checkpoint-sqlite==3.1.2` · `langchain-core==1.6.12`（`pip install -r p2/lab/requirements.txt`） |
| AgentScope Java | `io.agentscope:agentscope-core:2.1.0-BETA1`（坐标已确认，未适配驱动器，未实测） |
| Diagrid Catalyst | `io.diagrid:diagrid-spring-ai-starter:0.4.0`（坐标已确认，运行时需 Catalyst 云，未实测） |
| 采集日 | 2026-10-19（All framework sources captured/verified on this date unless noted） |

证据强度分级如下，§2 的 `证据` 列逐条标注：

| 级别 | 含义 | 对应实现 |
|---|---|---|
| A | 真实进程死亡：副作用 fsync 后进程被 `SIGKILL`（exit 137），新进程恢复 | `p2/lab/langgraph_sigkill_driver.py` |
| A− | 真实并发进程：两个进程同时恢复同一 run | `p2/lab/lg_concurrent_resume.py` |
| B | 进程内崩溃模拟：`RuntimeException` 丢弃框架内存状态，账本跨"重启"存活（语义等价于进程崩溃的内存侧，不覆盖进程死亡对存储层的影响） | `p2/lab/src/`、`p2/lab/src2/` |
| B+ | 进程内崩溃 + 真实框架公开 API 观测（Java 驱动器） | 同 B |
| C | 仅文档承诺或架构级观测，无驱动器实测 | AgentScope / Diagrid |

## 2. Findings 总表

| # | 被测（pin） | 契约 | 分类 | 证据 | 框架承诺（引文 / 出处 / 采集日） | 观测 | 证据文件 · 复现 |
|---|---|---|---|---|---|---|---|
| 1 | langgraph4j 1.9.5 | C2 | gap | B+ | 逐字：*"Resuming: Restore a graph to a previous state and continue execution."* 出处：[`langgraph4j` README §Checkpoints (Persistence)](https://github.com/langgraph4j/langgraph4j/blob/v1.9.5/README.md#checkpoints-persistence)，pin `v1.9.5`（commit `a23cf6d51afd2ed26c3477a571ac547f6e62aef8`），采集 2026-10-19（GitHub API 逐字核验）。未承诺副作用恰好一次 | SIGKILL-after-effect 类：node:c 副作用已生效（账本 1）→ 从最新检查点 resume → 节点重跑（账本 2）；检查点链单调不回退 | `p2/lab/driver-result.md` §场景3 · `p2/lab/src/LangGraph4jDriver.java` |
| 2 | langgraph4j 1.9.5 | C1 | PASS | B+ | 同上（承诺 continue execution；实测零重复） | 完成后从最新检查点 resume：账本/轨迹增量 0 | `driver-result.md` §场景2 |
| 3 | langgraph4j 1.9.5 | 运行级确定性 / 链完整性（观测） | PASS | B+ | 非契约号断言 | 检查点链 4 条（含 `__START__`）单调；两次全量运行轨迹/账本/链长全等 | `driver-result.md` §场景1、4 |
| 4 | SAA Graph 1.1.2.4 | C1 | PASS | B+ | paraphrase（中文转述；原文英文未随仓归档）："带 thread_id + checkpoint_id 调用 invoke 时，重放 checkpoint_id 之前的步骤，且不重新执行该步骤。" 出处：[graph-core《Persistence》](https://spring-ai-alibaba.github.io/docs/frameworks/graph-core/core/persistence)（URL 取自 java2ai.com sitemap，2026-10-19 HTTP 200；页面为客户端渲染，原文未能逐字抓取） | 显式 `checkPointId` resume：完成后零重复（账本增量 0） | `p2/lab/saa-driver-result.md` §场景2 · `p2/lab/src2/SaGraphDriver.java` |
| 5 | SAA Graph 1.1.2.4 | C2 | gap | B+ | paraphrase：同页"checkpoint_id 之后的所有步骤都将被执行"；且该文档没有副作用幂等要求（原始英文待逐字补录） | 崩溃节点 resume 重跑（账本 1→2），仅重跑 c（a=1,b=1,c=2）；resume 后链长 4（崩溃时 3，单调不回退） | `saa-driver-result.md` §场景3 |
| 6 | SAA Graph 1.1.2.4 | 安全性观测，非契约断言 | 行为差异 | B+ | paraphrase：同页仅描述带 `checkpoint_id` 的重放路径 | 裸 `invoke(Map, 同 threadId)`（不带 checkPointId）静默全图重跑：账本增量 3，轨迹 `[a,b,c,a,b,c]` | `saa-driver-result.md` §场景2b |
| 7 | LangGraph (Python) 1.2.16 | C2 | gap（独立复现论文方向） | A（真 SIGKILL） | 逐字（宣传句，非幂等承诺）：*"…automatically resuming from exactly where they left off."* 出处：[`langchain-ai/langgraph` README @ `37014c0`](https://github.com/langchain-ai/langgraph/blob/37014c09b9fafbfec9f336da80d8624d75a9ff7f/README.md#why-use-langgraph)，采集 2026-10-19。另核验 [Durable execution 文档页](https://docs.langchain.com/oss/python/langgraph/durable-execution)（2026-10-19）：抓取文本中 "exactly once/idempotent/duplicate/twice" 命中 0 次。包 pin：PyPI `langgraph 1.2.16`（2026-10-16 上传） | 真 SIGKILL（exit 137）：node:c 副作用 fsync 生效 → 新进程 `invoke(None)` + SqliteSaver resume → node:c 重跑（账本 1→2）；与论文对 LangGraph 的发现同向吻合（独立实现，非 TLA+ 复现） | `p2/lab/langgraph-sigkill-result.md` · `p2/lab/langgraph_sigkill_driver.py` |
| 8 | LangGraph (Python) 1.2.16 | 运行级观测 | PASS | A | 非契约号断言 | resume 正常完成（all-terminal），检查点 5 条 | 同上 |
| 9 | LangGraph (Python) 1.2.16 | C5（consume-once，真并发） | gap | A− | 同 #7（无 consume-once 承诺） | 双进程同时 `invoke(None)` resume：node:c 账本 1→3（崩溃前 1 + 两进程各重跑 1），两进程均正常返回 → 并发下 consume-once 失效，无冲突检测/lease 挡住双跑 | `p2/lab/lg-concurrent-result.md` · `p2/lab/lg_concurrent_resume.py` |
| 10 | LangGraph (Python) 1.2.16 | 检查点持久性（机械观测，无契约号） | 观测 | A（含竞态） | 无对应框架承诺，不作 violation | SIGKILL 时最后 super-step 的检查点事务未提交 → 恢复点回退：probe 实证崩溃后已提交检查点 = 2；b/c 被重跑（b 1→3、c 1→3）；存在 run-to-run 方差（单恢复场景一次 b=1、一次 b=2），回退窗口是竞态 | `p2/lab/lgc-probe.txt`（`{"committed_checkpoints":2}`）· `langgraph_sigkill_driver.py`（b=2 变体） |

统计：10 条 = 4 gap（#1、#5、#7、#9）+ 4 PASS（#2、#3、#4、#8）+ 2 观测/行为差异（#6、#10）。
没有一条被升级为 violation。框架没有承诺的性质只记为 gap。

## 3. 结论 → 证据文件映射（可脱离本报告复核）

| 结论 | 证据文件 | 原始数据/命令 |
|---|---|---|
| langgraph4j 恢复后崩溃节点重跑（C2 gap） | `p2/lab/driver-result.md` §场景3 | `p2/lab/src/LangGraph4jDriver.java`（`mvn -f pom.xml dependency:copy-dependencies ...` 后 `java -cp "classes:libs/*" LangGraph4jDriver`） |
| langgraph4j 完成后 resume 零重复（C1 PASS） | `driver-result.md` §场景2 | 同上 |
| langgraph4j 检查点链/确定性（观测 PASS） | `driver-result.md` §场景1、4 | 同上 |
| SAA 显式 checkpoint 零重复（C1 PASS） | `p2/lab/saa-driver-result.md` §场景2 | `p2/lab/src2/SaGraphDriver.java`（`pom-saa.xml`） |
| SAA 崩溃节点重跑（C2 gap） | `saa-driver-result.md` §场景3 | 同上 |
| SAA 裸 invoke 静默全图重跑（安全观测） | `saa-driver-result.md` §场景2b | 同上 |
| LangGraph 真 SIGKILL 后重跑（C2 gap） | `p2/lab/langgraph-sigkill-result.md` | `p2/lab/langgraph_sigkill_driver.py`（init/record/resume；record 进程 `os.kill(SIGKILL)` → exit 137） |
| LangGraph resume 完成（观测 PASS） | `langgraph-sigkill-result.md` | 同上 |
| LangGraph 并发 resume 失效（C5 gap） | `p2/lab/lg-concurrent-result.md` | `p2/lab/lg_concurrent_resume.py`（init/record/probe/concurrent） |
| LangGraph 检查点回退窗口（观测） | `p2/lab/lgc-probe.txt` | `lg_concurrent_resume.py probe` |
| CSV/JSON 原始输出 | `p2/lab/driver-result.md`、`saa-driver-result.md`、`langgraph-sigkill-result.md`、`lg-concurrent-result.md` | 结果文件随仓，可仅阅读复核；重跑需 Maven/PyPI 网络 |

## 4. 引文核验状态（逐条可审计）

| 来源 | URL | pin | 节锚点 | 采集日 | 状态 |
|---|---|---|---|---|---|
| langgraph4j README | https://github.com/langgraph4j/langgraph4j/blob/v1.9.5/README.md | tag `v1.9.5` · commit `a23cf6d51afd2ed26c3477a571ac547f6e62aef8` | `#checkpoints-persistence` | 2026-10-19 | 逐字已核验（GitHub contents API 拉取 v1.9.5 README 原文） |
| LangGraph README | https://github.com/langchain-ai/langgraph/blob/37014c09b9fafbfec9f336da80d8624d75a9ff7f/README.md | commit `37014c09b9fafbfec9f336da80d8624d75a9ff7f`（README 最后修改 2026-05-05）；包 pin PyPI `langgraph 1.2.16`（2026-10-16） | `#why-use-langgraph` | 2026-10-19 | 逐字已核验（GitHub contents API） |
| LangGraph durable-execution 文档 | https://docs.langchain.com/oss/python/langgraph/durable-execution | 文档站点（随 `langgraph 1.2.16` 使用） | 无 | 2026-10-19 | 已检索：抓取文本中无 exactly-once/idempotent/duplicate/twice 表述（客户端渲染页面，检索基于抓取 HTML 文本） |
| SAA Graph 文档 | https://spring-ai-alibaba.github.io/docs/frameworks/graph-core/core/persistence | Maven `1.1.2.4-security-fix` | `core/persistence` | 2026-10-19 | paraphrase：URL 来自 java2ai.com sitemap，HTTP 200；页面为客户端渲染，英文原文未归档 → 本报告 #4/#5/#6 引文为中文转述，发布前需补逐字原文 |
| Diagrid 博客 | https://www.diagrid.io/blog/durable-execution-spring-ai-agents | 文章 2026-08-24（Javier Aliaga） | 正文 | 2026-10-19 | 逐字已核验：*"The completed steps were never re-executed. Only the interrupted tool call ran again."* |
| 论文 | https://arxiv.org/abs/2608.03836 | v3 | 无 | 2026-10-19 | 已核验（arXiv export API）：*Resume Means Resume: A Machine-Checked Conformance Contract for Checkpoint, Interrupt, and Resume Semantics in Workflow Persistence Layers*，Sajjad Khan，2026-08-04 |

## 5. 未测 / 受阻（pinned 但未实测，不含 findings）

| 框架 | 坐标 | 状态 | 原因 |
|---|---|---|---|
| Diagrid Catalyst | `io.diagrid:diagrid-spring-ai-starter:0.4.0` | 未实测 | 运行时需要 Catalyst 云项目；本机无 Docker/Catalyst。官方博客承诺原文已逐字采集（见 §4），未来可实测时是 C2 的直接检验标的 |
| AgentScope Java | `io.agentscope:agentscope-core:2.1.0-BETA1` | 架构级观测，未适配驱动器 | `javap` 侦察：持久化是 session 级 `AgentStateStore`（非 per-step checkpoint 链），恢复 = 从 session 状态整体重建；适配需先侦察 Runner/Agent 执行 API。当前无 findings |

## 6. 结论边界

1. 不声称"三方互证、三个运行时 consume-once 全部失效"。P2 能证明的是：LangGraph(Python) 在双进程真并发下同一节点被多执行（账本总数 > 1）；P3 能证明的是两个 Java 框架在真线程并发下出现同类 C2/C5 finding。三者方向一致，但 P2/P3 的观测口径不同（见 `p3/README.md` 的 `unobservable` 与 synthetic 声明），不合并成一句强结论。
2. 不声称 P2 复现了论文的 TLA+ 结果。这是独立的运行时观测，只与论文方向同向吻合。
3. 不声称 Java 驱动器等价于真 SIGKILL。Java 驱动器是进程内崩溃模拟（强度 B）；行内 #7/#9/#10 才是真 SIGKILL / 真并发（A/A−）。
4. 不把 #6 写成 violation：SAA 文档只描述带 `checkpoint_id` 的重放路径，裸 invoke 的行为属于文档未覆盖的观测，不是承诺违反。
5. 不隐藏 #10 的 run-to-run 方差：单恢复场景曾出现 b=1 与 b=2 两种结果，回退窗口是竞态；这是观测事实，不是稳定数值。
6. 不声称 SAA 引文已逐字核验：#4/#5/#6 为中文 paraphrase，§4 已标注，发布前需补英文原文。

## 7. 复现

```bash
cd p2/lab
# langgraph4j 1.9.5（需 Maven + 网络）
mvn -f pom.xml dependency:copy-dependencies -DoutputDirectory=libs
javac -cp "libs/*" -d classes src/LangGraph4jDriver.java
java -cp "classes:libs/*" LangGraph4jDriver
# spring-ai-alibaba-graph-core 1.1.2.4-security-fix
mvn -f pom-saa.xml dependency:copy-dependencies -DoutputDirectory=libs-saa
javac -cp "libs-saa/*" -d classes2 src2/SaGraphDriver.java
java -cp "classes2:libs-saa/*" SaGraphDriver
# LangGraph (Python)：真 SIGKILL / 真并发（需 venv + 网络）
python3 -m venv .venv-py && ./.venv-py/bin/pip install -r requirements.txt
./.venv-py/bin/python langgraph_sigkill_driver.py init
INJECT_CRASH=1 ./.venv-py/bin/python langgraph_sigkill_driver.py record
./.venv-py/bin/python langgraph_sigkill_driver.py resume
./.venv-py/bin/python lg_concurrent_resume.py init
./.venv-py/bin/python lg_concurrent_resume.py record
./.venv-py/bin/python lg_concurrent_resume.py probe
./.venv-py/bin/python lg_concurrent_resume.py concurrent
```

前置与本机不可复现项（真实模型端点、掉电语义、Catalyst 云）见
[`docs/reproducibility.md`](../docs/reproducibility.md)。驱动器的设计边界见 [`docs/architecture.md`](../docs/architecture.md) §5。
