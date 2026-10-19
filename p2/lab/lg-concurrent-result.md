## 真并发 resume 结果（2 真进程同时 invoke(None)）
- 崩溃后已提交检查点数（probe）: 2
- 终态账本: {"node:a": 1, "node:b": 3, "node:c": 3}
- node:c = 3（崩溃前 1 + 两进程各重跑 1 = 3 → 并发下 consume-once 失效）
