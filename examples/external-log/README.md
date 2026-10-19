# External-log example: audit a run you instrumented yourself

The two directories here are complete, runnable inputs for `waypoint check`. They show the minimum an adapter has
to produce so that Waypoint's contract checker can audit a run from another runtime:

| Directory | Verdict | Why |
|---|---|---|
| [`clean/`](clean/) | exit `0`, no findings | the effect ledger matches the durable log; replay hashes are stable |
| [`violation/`](violation/) | exit `1`, one `C2` finding | the idempotency key `k_charge` was applied twice (seen by the external world) |

Each directory contains `events.jsonl` (durable event log), `ledger.jsonl` (external effect ledger) and
`meta.json` (what was already durable when recovery started). Field semantics are in
[`docs/adapter-guide.md`](../../docs/adapter-guide.md).

These inputs are synthetic and hand-written. They are the smallest logs that exercise the checker without a real
framework run, and they are not evidence about any framework.

## Running the examples

From the repository root, build once:

```bash
./mvnw -B -q package          # or: mvn -B -q package
```

Then:

```bash
# clean example (human-readable)
java -jar target/waypoint-0.1.0.jar check \
  --events examples/external-log/clean/events.jsonl \
  --ledger examples/external-log/clean/ledger.jsonl \
  --meta   examples/external-log/clean/meta.json
echo "exit=$?"        # 0

# clean example (machine-readable)
java -jar target/waypoint-0.1.0.jar check \
  --events examples/external-log/clean/events.jsonl \
  --ledger examples/external-log/clean/ledger.jsonl \
  --meta   examples/external-log/clean/meta.json \
  --format json

# violation example
java -jar target/waypoint-0.1.0.jar check \
  --events examples/external-log/violation/events.jsonl \
  --ledger examples/external-log/violation/ledger.jsonl \
  --meta   examples/external-log/violation/meta.json
echo "exit=$?"        # 1
```

If you do not have the jar yet, the same command works with the compiled classes:

```bash
java -cp target/classes waypoint.cli.Main check --events ... --ledger ... --meta ...
```

## Expected output (verified with `v0.1.0`)

`clean/`, text format:

```text
CLEAN  (0 contract violations)
scenario: examples/external-log/clean
events: 7  ledger-records: 2  model-calls: 0
unobservable: C3, S1, S3
```

`clean/`, JSON format (`--format json`):

```json
{"version":"0.1.0","scenario":"examples/external-log/clean","findings":[],"summary":{"findings":0,"clean":true,"contracts":[],"unobservable":["C3","S1","S3"],"protocolObservable":true}}
```

`violation/`, text format:

```text
FINDINGS  (1 contract violation)
scenario: examples/external-log/violation
events: 7  ledger-records: 3  model-calls: 0
unobservable: C3, S1, S3
  [1] C2 :: effect k_charge applied 2x
```

`violation/`, JSON format (`--format json`):

```json
{"version":"0.1.0","scenario":"examples/external-log/violation","findings":[{"contract":"C2","detail":"effect k_charge applied 2x"}],"summary":{"findings":1,"clean":false,"contracts":["C2"],"unobservable":["C3","S1","S3"],"protocolObservable":true}}
```

Exit codes are the contract with CI: `0` clean, `1` findings, `2` usage or IO error.

### Unobservable contracts in the clean example

The clean example lists `unobservable: C3, S1, S3` because "clean" must not be read as "all nine contracts
verified". This run has no fork (C3), no streamed generation or cancellation (S1) and no metering snapshot (S3),
so the checker skips those assertions instead of silently passing them, and reports them in
`summary.unobservable` and in the text line above. The evaluable contracts here are C1, C2, C4, C6, L1 and S2's
precondition.

If you can observe a contract, supply the evidence. If you cannot, let it appear as unobservable (or declare it
in `meta.unobservable`) rather than fabricating a pass.

## What each input contributes

- `events.jsonl`: record index = 0-based line number (in a real adapter, the position in your durable log).
  `STEP_STARTED.f2` is the idempotency key; `STEP_COMPLETED.f1` is the step path; the single `CHECKPOINT` claims
  `root/charge=COMPLETED` only after that completion exists in the prefix; `RUN_COMPLETED` appears exactly once
  (log-integrity check L1).
- `ledger.jsonl`: one record per time an effect actually reached the outside world. `startedRecordIndex` is where
  `STEP_STARTED` sits in the log, `syncedFrontierAtApply` is how many records were durable when the effect fired,
  and `executor` identifies who ran it. In `violation/`, a second `k_charge` record (from `resumer1`) makes the
  external count 2, which yields C2.
- `meta.json`: the recovery-start snapshot (`preCompletedKeys`, `preAppliedCounts`), expected steps (C1 rule 2),
  replay hashes (C6), and the run label. Omit any field you cannot observe; defaults are conservative.

Edit `clean/events.jsonl`, remove the `root/report` `STEP_COMPLETED` line and rerun. Because
`meta.expectedSteps` still requires that step, the checker reports `C1 :: skipped unfinished step root/report`
and exits `1`: the report step disappeared from a run that still claims `RUN_COMPLETED`. This is the kind of
mutation the calibration gate (`waypoint calibrate`) injects.
