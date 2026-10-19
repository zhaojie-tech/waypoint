# 框架恢复契约对照报告（模板）

> 规则：框架承诺原文列要给出 URL、版本和原话；给不出的条目只写 gap，不写 violation。
> 报告附版本矩阵和一键复现方式，每个 finding 都能独立复现。

版本矩阵：

| 项 | 值 |
|---|---|
| 被测框架 + 版本 | （pin，如 langgraph4j-core 1.9.5） |
| JDK / OS | 21.0.12 / Linux 5.15 |
| 检验器 | waypoint checker @ <commit sha> |
| 复现 | `cd p2/lab && <一条命令>`（seed 固定） |

## Finding 模板（每条一行）

| # | 契约 | 分类（violation/gap） | 框架承诺原文（URL、版本、出处行） | 观测（确定性崩溃点 × 结果） | 最小复现 |
|---|---|---|---|---|---|
| 1 | C2 | gap | 待补：LangGraph 家族文档中 interrupted nodes re-run on resume 的原文链接 | SIGKILL-after-effect 类：node c 副作用已生效（账本=1），resume 后重跑（账本=2），checkpoint 链单调不回退 | `lab/src/LangGraph4jDriver.java` 场景 3 |
| 2 | C1 | PASS | 无（观测项） | 完成后 resume：账本和轨迹增量 0 | 同上 场景 2 |
| ... | | | | | |

## 报告发布前检查单

- [ ] 每条 violation 的承诺原文列非空（URL 可访问 + 引文逐字核对）
- [ ] 每条 finding 的复现命令在干净环境跑通（≥2 次，结果一致）
- [ ] 版本矩阵完整（框架版本、JDK、OS、commit sha）
- [ ] gap 与 violation 未混写，未承诺不写成违反
- [ ] 上游 issue（若有）先于公开报告提交，并附最小复现
- [ ] Limitations 节列出没测的内容（并发 resume、掉电语义、真实 LLM）
