# Captures: provenance for recorded 3rd-party model output

This directory documents captured (non-code) evidence used by the P4 real-LLM smoke.
The capture itself stays at `../real-llm-sse.txt` because `real_llm_s1_smoke.py`
locates its input via `__file__`; do not move it without updating the script.

## `p2/lab/real-llm-sse.txt`

| field | value |
|---|---|
| what | raw SSE (text/event-stream) response body of one streaming chat completion |
| model | `glm-5.3-flash` (reasoning-type model), served through a litellm gateway |
| captured | 2026-10-19 (per `p2/lab/README.md` and the P2/P4 work log; capture date recorded when the file was committed) |
| size / shape | 55,159 bytes; 150 `data:` frames = 149 empty-`delta.content` reasoning frames + 1 `[DONE]`; zero frames with non-empty `content` |
| prompt | short, generic: ask the model to explain checkpointing in three sentences |
| used by | `p2/lab/real_llm_s1_smoke.py` (invoked by `./p4/gate.sh`): takes a raw byte prefix of the stream, writes it with `fsync`, reads it back, and checks byte identity and frame-boundary identity |
| NOT used by | the recovery engine (no checkpoint/replay code runs in that smoke); the model is never re-called |
| products | smoke output now goes to `build/real-llm-s1-result.md` and `build/s1-raw-prefix.bin` (untracked), not back into the repo |

## Sensitivity / licensing notes

- No API key, `Authorization` header, endpoint URL, or user data is present in the file.
  The gateway host/port and virtual key are supplied at capture time and never committed
  (see `p2/lab/README.md`).
- The bytes are model output, not project source. They are kept only as a realistic
  streaming fixture for the persistence round-trip check. Redistribution terms of the
  model provider may apply; if that is a concern for your use, delete the file and
  re-capture with your own gateway; the smoke is optional (`./p4/gate.sh` skips it when
  the file or the Python venv is absent).
- The content is not reproducible run-to-run (sampling); only its byte-prefix
  persistence round-trip is asserted. Numbers quoted by the smoke (frame counts, size)
  are properties of *this* capture and are recomputed from it on every run.

## Re-capture (illustrative; supply your own endpoint/key)

```bash
curl -s -N http://<litellm-host>:<port>/v1/chat/completions \
  -H "Authorization: Bearer <key>" -H "Content-Type: application/json" \
  -d '{"model":"<model>","stream":true,"max_tokens":150,
       "messages":[{"role":"user","content":"用三句话解释 checkpoint"}]}' \
  -o p2/lab/real-llm-sse.txt
```
