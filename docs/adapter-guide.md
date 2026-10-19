# Adapter guide

This guide is for engineers who run an agent or workflow runtime and want to know whether its recovery path satisfies the nine recovery contracts (C1–C6, S1–S3). The integration below takes about 30 minutes. You do not need Waypoint's calibration bed or its mutants. You bring JSON files produced by your own runtime, and `waypoint check` reports which contracts are violated, which are clean, and which cannot be observed with your instrumentation.

Requirements and inputs:

- Java 17+, with no third-party runtime dependencies.
- Input files are UTF-8 JSON or JSONL; formats are in [File formats](#2-file-formats).
- Run `java -jar waypoint-0.1.1.jar check ...` or use the Java API [`waypoint.api.Waypoint`](../src/main/java/waypoint/api/Waypoint.java).
- Runnable examples are in [`examples/external-log/`](../examples/external-log/).
- Contract definitions are in [`docs/contracts.md`](contracts.md).

The checker compares two things: the durable event log your runtime recorded, and the apply ledger of effects that actually reached the outside world. Findings come from that comparison.

## 1. 30-minute integration

### 0–5 min: pick the run and its recovery boundary

Decide which run you are auditing and where recovery starts. A single `RunResult` describes one run: the durable log after the final recovery, plus the external effects and the snapshot of what was already durable when recovery began. If your runtime recovers several times, audit one recovery window per `RunResult`.

Name it with `meta.json` `scenario`, for example `"orders-worker/crash-at-7"`.

### 5–15 min: emit the durable event log (`events.jsonl`)

Append one JSON object per durable record, in durable-log order. Record index is the 0-based position in the log; see the [record index rules](#record-index-rules). Use the same numbering for `startedRecordIndex`.

| Event | When to emit | Fields |
|---|---|---|
| `RUN_STARTED` | run begins | `f1`=workflow/scenario name, `f2`=schema version (`"v1"`) |
| `STEP_STARTED` | **before** the step's side effect, after the record is durable | `f1`=step path (structural id), `f2`=idempotency key |
| `STEP_COMPLETED` | when the step is durable-complete | `f1`=step path, `f2`=`"ok:<key>"` (free-form suffix is fine) |
| `STEP_UNKNOWN` | effect outcome unknown and the run stops | `f1`=step path |
| `CHUNK` | each streamed model chunk persisted | `f1`=stream path, `f2`=1-based chunk index, `f3`=payload (optional) |
| `CANCELLED` | client cancelled the stream at K chunks | `f1`=stream path, `f2`=K |
| `COMPACTION` | context summary persisted | `f1`=compaction path, `f2`=summary hash, `f3`=prompt hash, `f4`=model id |
| `CHECKPOINT` | a recovery checkpoint is persisted | `f1`=checkpoint id, `f2`=`path=status;path=status` |
| `LEASED` | resumer instance claims a pending step | `f1`=step path, `f2`=owner id |
| `RUN_COMPLETED` | run is durable-complete (emit **once**) | none |

`f1..f4` are strings. Omit unused fields or set them to `null`. Unknown event `type` values are rejected, so a typo cannot silently disable an assertion.

### 10–20 min: record external effects (`ledger.jsonl`)

This file is the ground truth for C2 and C5, and one side of C1. Record one line at the moment the effect actually takes effect, from outside the workflow runtime (the SDK wrapper, the activity that performs the HTTP call, and so on):

```json
{"key":"k_charge","startedRecordIndex":1,"syncedFrontierAtApply":2,"executor":"runner"}
```

| Field | Meaning |
|---|---|
| `key` | idempotency key of the effect (must match `STEP_STARTED.f2`) |
| `startedRecordIndex` | index of the matching `STEP_STARTED` in the durable log, or `-1` if you never observed one |
| `syncedFrontierAtApply` | number of durable records at the instant the effect fired (`0..N`) |
| `executor` | who executed it: `"runner"`, `"resumer1"`, `"resumer2"`, or your own label |

An empty ledger makes C2 (entirely effect-based) unobservable, and C5 has no evidence either. C1 is special: its skipped-unfinished-step rule only needs `expectedSteps` plus the event log, so C1 stays active but is reported under `partial`, because the ledger-based re-execution rule could not be evaluated. The checker does not invent a pass for missing evidence.

### 20–25 min: model spy and recovery snapshot (`model.jsonl`, `meta.json`)

`model.jsonl` records upstream model calls, which S1, S2 and S3 use:

```json
{"op":"gen","subject":"root/gen"}
{"op":"summarize","subject":"root/compact"}
```

`meta.json` is a single JSON object with the observations the checker cannot derive from the other files. See the [field table](#metajson-fields). A minimum useful version:

```json
{
  "scenario": "orders-worker/crash-at-7",
  "expectedSteps": ["root/charge", "root/report"],
  "preCompletedKeys": ["k_charge"],
  "preAppliedCounts": {"k_charge": 1},
  "protocolObservable": true,
  "unobservable": []
}
```

### 25–30 min: run the checker and declare what you cannot see

```bash
java -jar waypoint-0.1.1.jar check \
  --events events.jsonl --ledger ledger.jsonl \
  --model model.jsonl --meta meta.json --format json
```

Use the exit code in CI: `0` clean, `1` findings, `2` usage or IO error. Then read `summary.unobservable`: those contracts were skipped, not verified. The [unobservable obligations](#4-unobservable-contracts) explain what you may and may not declare.

## 2. File formats

All files are UTF-8. `events.jsonl`, `ledger.jsonl` and `model.jsonl` accept either one JSON object per line (blank lines are ignored) or a single JSON array of objects. `meta.json` is a single JSON object.

Rules enforced by the loader:

- Unknown fields in any file are rejected with an error that names the field. An adapter cannot claim an observation the checker ignores.
- An event `type` must be one of the enum names (`STEP_STARTED`, and so on).
- `ledger.jsonl` requires `key`, `startedRecordIndex` and `syncedFrontierAtApply`; `executor` defaults to `"runner"`.
- `startedRecordIndex >= -1`, `syncedFrontierAtApply >= 0`, counts `>= 0`.
- JSON errors report the file, line and column, for example `events.jsonl:3: line 1, column 9 (offset 8): ...`.

### meta.json fields

| Field | Type | Default | Used by | Meaning |
|---|---|---|---|---|
| `scenario` | string | `""` | output | run label |
| `expectedSteps` | string[] | `[]` | C1 rule 2 | every structural step path the run must complete; assertion is active only when non-empty **and** the log contains `RUN_COMPLETED` |
| `preCompletedKeys` | string[] | `[]` | C1 rule 1 | idempotency keys already durable-COMPLETED when recovery started |
| `preAppliedCounts` | object<string,int> | `{}` | C1 rule 1 | external applied count per key at recovery start |
| `twoResumers` | boolean | `false` | C5 | a second resumer instance competed for this run |
| `protocolObservable` | boolean | `true` | C2 | `false` = you cannot observe "STEP_STARTED synced before effect" ordering; the count check still runs |
| `preCompactionDurable` | boolean | `false` | S2 | the `COMPACTION` record was durable when recovery started |
| `preSummarizeCalls` | int | `0` | S2 | `summarize` calls before recovery start |
| `configuredCancelAt` | int | `-1` | S1 | configured cancellation boundary K; `-1` = not asserted |
| `replayChunksEmitted` | int | `-1` | S1 | chunks emitted by the replay path; `-1` = not observable |
| `meteringPreEntries` | int | `0` | S3 | metering-ledger entries at recovery start |
| `meteringFinalEntries` | int | `0` | S3 | metering-ledger entries at end of run |
| `interruptionKinds` | string[] | `[]` | S3 | classifications of interrupted streams; must be ⊆ `{COMPLETED, ESTIMATED, UNKNOWN}` |
| `unobservable` | string[] | `[]` | all | contract ids your adapter cannot observe (see below) |
| `replayHashes` | string[] | `[]` | C6 | state hashes from ≥2 recoveries of the same durable log |
| `orderTraces` | string[] | `[]` | C6 | canonical traces from ≥2 scheduling/order seeds |
| `childEvents0` | event[] | `[]` | C3 | full event stream of fork child 0 (preferred over hashes) |
| `childEvents1` | event[] | `[]` | C3 | full event stream of fork child 1 |
| `childTrace0` | string | `""` | C3 | precomputed canonical trace hash of child 0 (fallback only) |
| `childTrace1` | string | `""` | C3 | precomputed canonical trace hash of child 1 (fallback only) |
| `forkScenario` | boolean | `false` | n/a | kept for compatibility; current checker keys C3 off the evidence above |
| `cancelAt` | int | `17` | n/a | legacy field kept for compatibility with the calibration bed |
| `stateHash` | string | `""` | n/a | informational; not used by the checker |

`unobservable` and the other fields can be omitted. Omitted means "not observable or not asserted", never "verified".

#### Record index rules

`startedRecordIndex` and `syncedFrontierAtApply` use the same numbering as the `events.jsonl` record positions: the 0-based index in the durable log. `syncedFrontierAtApply` is a count (how many records were durable), not an index. The C2 ordering assertion is `0 <= startedRecordIndex < syncedFrontierAtApply`: the started record exists and was already durable when the effect fired.

With the log `RUN_STARTED(0), STEP_STARTED(1), STEP_COMPLETED(2) ...`, an effect whose `STEP_STARTED` is record 1 and which fires after record 1 was synced has `startedRecordIndex=1, syncedFrontierAtApply=2` (records `0,1` durable).

## 3. Contract observations

Each contract needs specific observations. Missing evidence is skipped and added to `unobservable`; it is never treated as clean. C2's ordering half is the most common case where a runtime cannot provide the observation.

| Contract | Needs | Skip condition / notes |
|---|---|---|
| **C1** prefix continuation | `ledger.jsonl`, `meta.preCompletedKeys`, `meta.preAppliedCounts`; for rule 2 also `meta.expectedSteps` + `RUN_COMPLETED` | rule 1: if no completion snapshot, cannot detect re-execution. rule 2: off unless `expectedSteps` non-empty |
| **C2** effect exactly-once | `ledger.jsonl` (counts + `startedRecordIndex`/`syncedFrontierAtApply`), `meta.protocolObservable` | declaring C2 unobservable skips **both** count and ordering; don't do it if you have a ledger |
| **C3** fork determinism | `meta.childEvents0/1` (preferred) or `childTrace0/1` | neither → C3 appears in `unobservable` |
| **C4** checkpoint validity | `events.jsonl` `CHECKPOINT` records (`path=status;...`) and the log prefix | nothing to skip: no checkpoint → nothing to assert |
| **C5** consume-once | `meta.twoResumers`, `ledger.jsonl.executor`, `LEASED` events | two resumers but no lease **and** no non-`runner` executor → C5 in `unobservable` |
| **C6** recovery determinism | `meta.replayHashes` (≥2) and/or `meta.orderTraces` (≥2) | neither → C6 in `unobservable` |
| **S1** stream/cancel boundary | `CHUNK`/`CANCELLED` events, `model.jsonl` gen calls, `meta.configuredCancelAt`, `meta.replayChunksEmitted` | no durable cancel and both meta fields `-1` → S1 in `unobservable` |
| **S2** compaction provenance | `COMPACTION` event, `model.jsonl` summarize calls, `meta.preCompactionDurable`, `meta.preSummarizeCalls` | only evaluated when `preCompactionDurable=true`; otherwise out of scope for that run |
| **S3** metering consistency | `meta.meteringPreEntries/FinalEntries`, `meta.interruptionKinds` | `meteringPreEntries=0` → S3 in `unobservable`. Only set a non-zero `meteringPreEntries` for a replay window where no legitimate new work can occur; a resume that *executes* a pending step legitimately adds metering entries and would look like a violation |
| **L1** log integrity | `events.jsonl` only | always runs; duplicate `STEP_COMPLETED`, completion without preceding `STEP_STARTED`, or >1 `RUN_COMPLETED` |

L1 is an auxiliary log-integrity check, not a tenth contract. A healthy run must have zero L1 findings.

## 4. Unobservable contracts

1. Declare, don't fake. If your instrumentation cannot produce an observation, put the contract id in `meta.unobservable`, or leave the field absent. Do not synthesise `childTrace0/1`, executor names, replay hashes or metering counts. Do not set `meteringPreEntries` around a window where recovery may legally execute new steps that are not replays; that would be a false positive. The checker reports what it can verify, and fabricated observations turn it into a rubber stamp.
2. The checker adds its own skips. After `check()` the set is the union of your declaration and the contracts the checker had to skip. The CLI prints it as `unobservable:` and in `summary.unobservable`. Read it after calling `check()`.
3. A `clean` result with a non-empty `unobservable` list is not "all contracts pass". It means no finding among the contracts that could be evaluated; report it that way. The `clean/` example prints `unobservable: C3, S1, S3` for this reason.
4. `protocolObservable=false` is narrow. It skips only the C2 started-before-effect ordering assertion, for frameworks whose durability is server-side and not observable from the client. The C2 count assertion still runs, and C2 still appears in `unobservable` because half of it was skipped. Read that list as "not all of this contract was verified".
5. Do not declare a contract unobservable to silence a finding you already have. If the observations exist, the adapter stays observable.
6. S2 needs a positive precondition. When `preCompactionDurable` is false, the checker cannot distinguish "no compaction" from "not observable", so the run is out of S2's scope rather than clean by absence. Say so in your own reporting.

## 5. CLI usage

```bash
waypoint check --events F --ledger F [--model F] [--meta F] [--format text|json]
waypoint calibrate      # repository's own mutation-calibration gate
waypoint version
waypoint help [check|calibrate]
```

`--format text` (default) prints the human report. `--format json` prints a stable machine-readable shape:

```json
{"version":"0.1.1","scenario":"...","findings":[{"contract":"C2","detail":"..."}],
 "summary":{"findings":1,"clean":false,"contracts":["C2"],
            "unobservable":["C3","S1","S3"],"protocolObservable":true}}
```

Exit codes: `0` clean, `1` findings, `2` usage or IO error (bad JSON, unknown field, missing file). Errors go to stderr and name the file and line. The `--events=FILE` and `--format=json` forms are accepted for scripting.

CI snippet:

```yaml
- run: java -jar target/waypoint-0.1.1.jar check \
         --events build/events.jsonl --ledger build/ledger.jsonl \
         --meta build/meta.json --format json > build/check.json
  # non-zero fails the job: 1 = contract findings, 2 = bad inputs
```

## 6. Java API

Coordinate: `tech.zhaojie:waypoint:0.1.1` (`./mvnw -B -q install` while it is not in a public repository). Requires Java 17+ and no runtime dependencies.

```java
import waypoint.Checker;
import waypoint.api.Waypoint;

import java.nio.file.Path;
import java.util.List;

// High level: load files → run the checker → rendered report.
Waypoint.Report report = Waypoint.analyze(
        Path.of("events.jsonl"),
        Path.of("ledger.jsonl"),
        Path.of("model.jsonl"),   // optional, may be null
        Path.of("meta.json"));    // optional, may be null

if (!report.clean()) {
    for (Checker.Finding f : report.findings()) {
        System.err.println(f.contract + " :: " + f.detail);
    }
}
String text = report.text();     // same text the CLI prints
String json = report.json();     // same JSON the CLI prints
System.out.println(report.run().unobservable);  // contracts that were skipped
```

Lower-level composition, for cases where you already build a `Checker.RunResult` in memory:

```java
Checker.RunResult run = Waypoint.loadResult(events, ledger, model, meta); // Paths or InputStreams
List<Checker.Finding> findings = Waypoint.check(run);                    // Checker.check semantics
String text = Waypoint.formatText(run, findings);
String json = Waypoint.formatJson(run, findings);
```

Notes on the API:

- `loadResult(Path...)` throws `java.io.IOException` for I/O errors or `Waypoint.FormatException` for malformed adapter input; the message includes the file and line. `loadResult(InputStream...)` reads to EOF and does not close the streams; `model` and `meta` may be `null`.
- `Waypoint.check` returns an unmodifiable list. It may add skipped contracts to `run.unobservable`, so read that field after the call.
- `formatText` and `formatJson` are deterministic and locale-independent.
- The facade is stateless and thread-safe.

## 7. Mapping real frameworks

The repository's framework evidence lives in `p3/` and its generated `build/p3-report.md`. This section is a starting-point sketch and not a tested claim. No adapter for the frameworks below ships in this repository.

| Framework | Where the observations come from | Expected unobservable |
|---|---|---|
| **Temporal** | Workflow history for step started/completed events; activity attempts + idempotency keys for the ledger; server durability means client-side sync frontier is not observable | C2 ordering (`protocolObservable=false`); often C3/C6 unless you export replay history |
| **LangGraph4j / Spring AI** | graph node callbacks → `STEP_STARTED`/`STEP_COMPLETED`; checkpoint saver → `CHECKPOINT`; `StreamingChatModel` chunks → `CHUNK`/`CANCELLED`; model callbacks → `model.jsonl` | C5 unless two-resumer evidence exists; S3 unless you have a metering ledger |
| **LangChain4j** | chain/node listeners + `StreamingChatLanguageModel` callbacks; effect ledger must be recorded in the tool/HTTP layer, not in the chain | C3/C5/S3 likely |
| **Generic Java runtime** | wrap your append-only log: return the record index on append and a durable-frontier counter; record external effects in the same wrapper | depends entirely on your instrumentation |

Practical mapping rules:

- `startedRecordIndex` is the index of `STEP_STARTED` in your durable log; make append return the index. If your log is segmented, choose a stable global numbering and keep it across recoveries.
- `syncedFrontierAtApply` is the number of records durable when the effect fired. If the framework does not expose durability, set `protocolObservable=false` instead of guessing a number.
- Executor identity is a resumer instance id. Without it C5 cannot be evaluated.
- Record effects in the layer that actually performs the external call. A ledger written from inside the workflow state is not external truth.

## 8. Troubleshooting

| Symptom (exit 2) | Cause / fix |
|---|---|
| `file not found: ...` | wrong path or `--events`/`--ledger` omitted |
| `events.jsonl:3: line 1, column ...` | malformed JSON on that line; each JSONL line must be one complete object |
| `unknown field "f5"` | typo or a field this version ignores; remove it or upgrade |
| `unknown event type "..."` | use one of the `Model.Type` names listed in the table above |
| `field "startedRecordIndex": number "..." is not a 64-bit integer` | counts must be JSON integers, not strings/floats |
| `expected STEP_COMPLETED but found ...` in C4 | checkpoint claims a completion that is not in the log prefix before it |

Notes:

- A `C2 :: effect ... applied Nx` finding means the outside world saw it N times. Investigate the retry or reconcile path, not the checker.
- An `L1` finding means the log itself is inconsistent (duplicate or orphan completion). Fix logging before interpreting other contracts.
- `C1 :: skipped unfinished step ...` means the run claimed `RUN_COMPLETED` while an expected step never completed, the classic silent-skip bug.

## 9. Adapter checklist

- [ ] `events.jsonl` contains every durable record in order, with the single `RUN_COMPLETED` at the end.
- [ ] `ledger.jsonl` is written by the effect-performing layer, with `startedRecordIndex` and `syncedFrontierAtApply` consistent with the log.
- [ ] `model.jsonl` covers model calls you want S1/S2/S3 to see.
- [ ] `meta.json` has the recovery-start snapshot and every observation you can produce; omit the rest.
- [ ] `waypoint check` exits `0` on a known-good run and `1` on a broken one (e.g. duplicate an effect in the ledger).
- [ ] You have read `summary.unobservable` and can explain each skipped contract to a reviewer.
- [ ] CI fails on exit code `1` and treats `2` as an infrastructure bug.
