## 真 SIGKILL 驱动结果（langgraph 1.2.16）
- record: node:c 副作用 fsync 后进程自杀（exit 137）
- resume（新进程, SqliteSaver, invoke(None)）后账本: {"node:a": 1, "node:b": 2, "node:c": 2}
- node:c 生效次数: 2（>1 = at-least-once 重跑）
- checkpoints: 5
- 全部节点终态: True
