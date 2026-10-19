#!/usr/bin/env python3
"""P4 — S1 smoke on REAL LLM SSE bytes (GLM-5.3-flash via litellm).

Scope (honest): validates PERSISTENCE ROUND-TRIP of a real captured stream —
a raw byte prefix of the actual SSE response is fsynced as a checkpoint and
must read back byte-identically. This does NOT exercise a recovery engine and
does NOT re-call the model (stated in README).

Stream census is reported truthfully: reasoning models emit many empty-content
frames before the answer frame.
"""
import json, os, sys

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "real-llm-sse.txt")
raw = open(SRC, "rb").read()
lines = raw.split(b"\n")
data_lines = [l for l in lines if l.startswith(b"data:")]
content_frames = 0; empty_content = 0; done = 0
for l in data_lines:
    p = l[5:].strip()
    if p == b"[DONE]": done += 1; continue
    try:
        d = json.loads(p)
        c = d["choices"][0]["delta"].get("content")
        if c: content_frames += 1
        else: empty_content += 1
    except Exception: pass

K = max(1, len(data_lines) - 1)   # cancel before the final DONE frame
prefix = b"\n".join(data_lines[:K]) + b"\n"
with open("s1-raw-prefix.bin", "wb") as f:
    f.write(prefix); f.flush(); os.fsync(f.fileno())

readback = open("s1-raw-prefix.bin", "rb").read()
ok_bytes = readback == prefix
ok_split = len(readback.split(b"\n")) - 1 == K

results = [
    f"真实 SSE 流普查（GLM-5.3-flash via litellm）: data 帧 {len(data_lines)}，"
    f"含内容帧 {content_frames}，空 content 帧（推理） {empty_content}，[DONE] {done}",
    f"取消边界 K={K}（DONE 前一帧）：原始字节前缀 {len(prefix)}B fsync 落盘",
    f"重放读回：{len(readback)}B，逐字节一致 → {'PASS' if ok_bytes else 'FAIL'}",
    f"帧边界一致（{K} 帧）→ {'PASS' if ok_split else 'FAIL'}",
    "诚实口径：本冒烟仅校验真实 SSE 字节前缀的持久化往返；未经恢复引擎，未对真模型二次调用",
]
verdict = "PASS" if (ok_bytes and ok_split) else "FAIL"
results.append("S1 real-LLM smoke: " + verdict)
print("\n".join(results))
open("real-llm-s1-result.md", "w", encoding="utf-8").write(
    "# P4 — S1 real-LLM smoke\n\n" + "\n".join("- " + r for r in results) + "\n")
sys.exit(0 if verdict == "PASS" else 1)
