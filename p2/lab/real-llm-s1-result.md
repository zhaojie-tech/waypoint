# P4: S1 real-LLM smoke

- 真实 SSE 流普查（GLM-5.3-flash via litellm）：data 帧 150，内容帧 0，空 content 帧（推理）149，[DONE] 1
- 取消边界 K=149（DONE 前一帧）：原始字节前缀 55159B fsync 落盘
- 重放读回：55159B，逐字节一致，PASS
- 帧边界一致（149 帧），PASS
- 口径说明：本冒烟只校验真实 SSE 字节前缀的持久化往返，不经过恢复引擎，也没有对真模型二次调用
- S1 real-LLM smoke: PASS
