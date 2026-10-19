# P4: S1/S2 cancellation-boundary matrix and real-LLM SSE smoke

The matrices here check the stream-related contracts on the deterministic calibration bed: S1 (stream prefix and
cancellation boundary) and S2 (compaction provenance). A sensitivity section shows the matrix can fail. An
optional smoke test runs on real captured model stream bytes; it is a persistence round-trip only, not a
recovery-engine test.

Scope: the matrix is a regression and property test on the calibration bed, not independent evidence about a
real framework's S1 semantics. The real-LLM smoke shows only that a real SSE byte prefix survives an `fsync`
round-trip byte-identically.

## Running the gate

```bash
./p4/gate.sh        # matrix + sensitivity (+ smoke if a Python venv is present)
echo $?             # 0 = PASS, 1 = FAIL, 2 = missing prerequisite / build error
```

Prerequisites: JDK 17+. The real-LLM smoke additionally needs `p2/lab/.venv-py` (Python 3 plus dependencies).
When it is absent the gate prints `skip real-LLM smoke ...` and still passes; the smoke is evidence, not a gate
requirement.

Outputs, untracked and under `build/`: `s1-matrix.md`, `real-llm-s1-result.md`, `s1-raw-prefix.bin`. No tracked
file is rewritten.

## Matrix

Cancellation boundaries `K ∈ {3, 10, 17, 30}`. Every operation boundary at and after the durable prefix is a
crash point. Latest run:

| cancelAt | crash-runs | S1-active | S1 violations | S2 violations | other findings |
|---|---|---|---|---|---|
| 3 | 23 | 12 | 0 | 0 | 0 |
| 10 | 37 | 12 | 0 | 0 | 0 |
| 17 | 51 | 12 | 0 | 0 | 0 |
| 30 | 77 | 12 | 0 | 0 | 0 |

Totals: 188 runs / S1-active 48 (40 of them real crash runs, 8 no-crash controls).

Read the denominator, not just the zero. `S1-active` counts runs whose recovered log contains `CANCELLED`,
meaning runs where the boundary assertion can actually execute. "0 S1 violations" is a statement about 48 runs,
not about 188; the other 140 runs cannot exercise that assertion. A matrix whose assertion never executes would
also show "0 violations", which is why the active column exists.

S1 assertions exercised on active runs:

1. logged chunk count != the configured cancellation boundary K;
2. configured K != K recorded in the log;
3. `Bed.replayStream()` emitted chunk count != K (replay emits exactly the durable prefix);
4. a second model `gen` call after cancellation.

## Sensitivity

Injected known S1 violations must produce S1 findings; otherwise the gate FAILs:

| Injection | Mechanism | runs | S1 violations | Expected |
|---|---|---|---|---|
| M9 | cancel-boundary-overrun (log records one extra chunk) | 53 | 24 | > 0 |
| M12 | replay-emits-wrong-chunks (`replayStream` emits the wrong count) | 51 | 12 | > 0 |

Latest gate: PASS with `matrix-0-violations=true; sensitivity-M9=true; sensitivity-M12=true`.

## Changes from earlier drafts

- No "30-chunk stream". `Bed.doGen()` writes exactly `K` chunks and then `CANCELLED(K)`; the earlier description
  was wrong. The equality "persisted chunks == K" is true by construction on the bed, which is why the matrix
  should not be presented as independent evidence.
- The replay assertion now exists. Replay is executed by `Bed.replayStream()` (no model call, no log write) and
  the emitted count is compared with K.
- `preAppliedCounts` is snapshotted before recovery, so C1's precondition can hold. Earlier it was taken after
  recovery, which made C1 structurally unable to fire.
- Products are untracked under `build/`, and the old tracked `p3-report.md`/`s1-matrix.md` files are gone.

## S2 and S3 coverage

S2 has no failing path in this matrix unless `M10` is injected (replay re-invokes `summarize`). "0 S2 violations"
in the matrix is therefore not independent evidence that S2 holds; it is a coverage statement, and M10 in the P1
mutation calibration is the actual S2 sensitivity check.

S3 is not asserted here. P4 claims only S1 and S2. After a mid-stream crash, recovery may legitimately call
`summarize` again; wiring metering counts into this matrix would produce false positives. S3 is checked at the
checker level with the metering ledger instead.

## Real-LLM SSE smoke

When `p2/lab/real-llm-sse.txt` (a real captured stream) and `p2/lab/.venv-py` are present:

- stream census: 150 `data:` frames = 149 empty-content reasoning frames plus `[DONE]` (reasoning model);
- cancellation boundary `K=149` (the frame before `[DONE]`); the raw byte prefix is 55,159 B and is `fsync`ed;
- read-back is byte-identical and frame-boundary-consistent (149 frames), so the smoke passes.

Provenance, capture date, model and licence notes are in `p2/lab/captures/README.md`. The smoke does not go
through a recovery engine and does not call the model a second time; it says nothing about real-model recovery
behaviour. S1's replay and cancel properties are tested on the deterministic bed above, not here.

## Reproduce

```bash
./p4/gate.sh                                    # matrix + sensitivity (+ smoke when possible)
cat build/s1-matrix.md                          # the exact numbers above, regenerated
```

The matrix numbers are regenerated on every run; the values above are from the latest `v0.1.0` gate run.
