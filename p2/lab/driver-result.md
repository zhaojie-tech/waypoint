# Waypoint P2 driver : langgraph4j-core 1.9.5 (pinned)

环境：JDK 21.0.12.1+1-LTS；外部账本/轨迹跨『进程重启』存活，框架状态丢弃；releaseThread(false) 保留已完成 run 的检查点。

## 场景 1 : 检查点链完整性（健康全量运行）
- checkpoint 数 = 4（期望 4：3 节点 + __START__）→ PASS
- nodeId 链（新→旧）= [c, b, a, __START__] → PASS
- 外部账本 = {node:a=1, node:b=1, node:c=1} → PASS

## 场景 2 : 完成后 resume 零重复执行（C1 前缀续跑）
- 从最新检查点 resume：外部账本增量 = 0，轨迹增量 = 0 → PASS（无重复执行）

## 场景 3 : 副作用后崩溃 + resume（SIGKILL-after-effect 类，C2 语义）
- 崩溃时：checkpoint 数 = 3，node:c 已生效次数 = 1
- 从最新检查点 resume 后：node:c 生效次数 = 2（重跑了崩溃节点 → at-least-once 语义）
- resume 后 checkpoint 链长度 = 4（单调不回退）
- 外部账本终态 = {node:a=1, node:b=1, node:c=2}
- 分类（对照文档承诺）：LangGraph 家族文档化的行为是节点恢复后可重跑（at-least-once），
  本观测与之相符 → gap（未承诺恰好一次），非 violation；副作用恰好一次需业务侧幂等键。

## 场景 4 : 两次全新全量运行的确定性（C6）
- 轨迹一致 = true，账本一致 = true，checkpoint 数一致 = true → PASS
