# Waypoint P2 driver : spring-ai-alibaba-graph-core 1.1.2.4-security-fix (pinned)

环境：JDK 21.0.12.1+1-LTS；外部账本/轨迹跨『进程重启』存活，框架状态丢弃。

## 场景 1 : 检查点链（健康全量运行）
- checkpoint 数 = 4，nodeId 链 = [c, b, a, __START__]
- 外部账本 = {node:a=1, node:b=1, node:c=1}

## 场景 2 : 完成后以【显式 checkPointId】再 invoke（C1 前缀续跑）
- 外部账本增量 = 0，轨迹增量 = 0 → PASS（零重复执行）

## 场景 2b : 完成后【裸 invoke】（不带 checkPointId，同 threadId）
- 外部账本增量 = 3，轨迹 = [a, b, c, a, b, c] → 全图静默重跑（安全观测：文档只描述了带 checkPointId 的重放路径）

## 场景 3 : 副作用后崩溃 + 【显式 checkPointId】resume（SIGKILL-after-effect 类）
- 崩溃时：checkpoint 数 = 3，node:c 已生效 = 1
- 再 invoke 后：node:c 生效 = 2（重跑崩溃节点 → at-least-once）
- resume 后 checkpoint 链长 = 4（崩溃时 3，单调不回退）
- 外部账本终态 = {node:a=1, node:b=1, node:c=2}
- 分类：文档明示「checkpoint_id 之后的所有步骤都将被执行」（java2ai.com persistence 文档），重跑=文档化行为 → gap

## 场景 4 : 双全量运行确定性（C6）
- 轨迹一致 = true，checkpoint 数一致 = true → PASS

