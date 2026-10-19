# Waypoint FAQ

## Overview

Waypoint is a verification tool for agent and workflow runtimes. It defines nine recovery contracts (prefix continuation, effect exactly-once, fork determinism, checkpoint validity, consume-once, recovery determinism, stream prefix, compaction provenance, metering consistency) and checks them with an implementation that owns nothing of the runtime under test. The checker is calibrated against injected violations first, then run against real frameworks. It is a research and verification artifact, not a runtime component.

## Production status

No. Waypoint does not execute agents; it audits logs. The repository contains a checker, a deterministic calibration bed and framework drivers, published as the `v0.1.1` research artifact. The rule for claims is simple: every number has a reproduction path, and a violation needs the framework's own quoted promise.

## Gate verdict

Yes. Since `v0.1.0` the PASS is a structured numeric assertion over the result object: determinism 100/100, the torn tail 9/10 with an intact prefix, the baseline 63 runs with 0 violations, and all mutants captured. Five degenerate variants are tested as negative controls and must exit non-zero. `./gate.sh` returns 0 only when all of them hold, and writes the same result to `build/gate-result.json`.

## Gap and violation

A violation means the framework's documentation explicitly promises the property and the measured behavior contradicts it. The row must carry the quoted promise with URL, pinned version and section.

A gap means the framework never promised the property and the behavior is missing or weaker. Record it as a gap, not as a bug in the framework. Without a quote, the finding goes in as a gap; that rule keeps the P2 report useful to framework maintainers.

## Mutation calibration

A checker written by the same person who wrote the runtime under test can quietly pass everything. Waypoint injects a catalogue of known violations into the calibration bed (see `docs/mutations.md`) and requires the checker to report the target contract for each one, while the healthy bed must produce zero violations across 63 crash runs. A checker that cannot catch an injected bug should not be trusted to audit someone else's runtime.

## Numbers: 63, 188, 10/13, 52

| Number | Meaning | Caveat |
|---|---|---|
| **100/100** | repeated recoveries of the same crashed log produce an identical state hash | determinism of the model, not of a real framework |
| **9/10** | torn-tail self-test keeps the 9 intact records and rejects the corrupted last record | a unit self-test of the WAL, not a crash campaign |
| **63** | healthy-baseline crash runs (deterministic crash points + no-crash controls; composition in `docs/mutations.md`) | all on the calibration bed |
| **13** (was 10) | injected mutants; every contract has at least one dedicated mutant | see `docs/mutations.md` for the current catalogue and per-contract mapping |
| **188 / 48** | P4 S1/S2 cancellation-boundary runs; only the **48** S1-active runs can actually execute the S1 assertion (40 real crash runs + 8 no-crash controls) | "0 violations" over 48 active runs is not the same as over 188; see `p4/README.md` for the per-boundary table and the sensitivity section |

## Baseline size: 63 crash runs

The crash points are deterministic operation boundaries, not random fuzzing. The same seed and enumeration always produce the same 63 runs and the same report hash. Reproducibility of the evidence matters more than volume here. Where coverage is partial (W2 stream generation middle sections, for example), the docs say so instead of padding the number.

## External logs

Yes. The CLI and API read your event and external-ground-truth files in the documented JSONL format:

```bash
java -jar target/waypoint-0.1.1.jar check \
  --events my-events.jsonl --ledger my-ledger.jsonl --meta my-meta.json
```

Adapters must declare what they cannot observe (`unobservable`, `protocolObservable=false`). Such a check is reported as unobservable and never counted as a pass. A minimal runnable example is in `examples/external-log/`.

## Adapter coverage

Fully checkable with the right observations: C1, C3, C6, S1 (with a replay path), S2, S3 (conditional). Partially checkable: C2 (the "STEP_STARTED durable before effect" half needs record indices), C4 (needs checkpoint snapshots containing step IDs), C5 (needs lease/executor evidence; otherwise it is reported unobservable). The mapping table in `docs/architecture.md` and the `unobservable` field in `docs/adapter-guide.md` are the authoritative version.

## Relation to arXiv 2608.03836

No. The paper (`arXiv 2608.03836`, *Resume Means Resume*, 2026-08) formalizes six properties and checks them with TLA+ for Python runtimes. Waypoint takes the six property names as a starting point (C1–C6, with a re-defined C3/C4 adapted to this checker), adds three LLM-specific properties (S1–S3), and implements an executable checker, mutation calibration and real-framework observations. It is an independent implementation and does not claim to reproduce the paper's TLA+ results.

## Implementation language

The checker is a pure deterministic function over logs: no third-party runtime dependencies, no threads, wall clock or randomness in the core, `--release 17`, and a single `javac` command. Framework drivers use the frameworks' native languages (Java/Python) and stay separated under `p2/lab/`.

## Platforms: JDK 21 and Windows

JDK 17 is what is verified locally. JDK 21 is verified by CI (https://github.com/zhaojie-tech/waypoint/actions/runs/39228659294). Both work because the gates compile with `--release 17`, and the published report sha is identical across them.

Windows is not a native target. The gates are bash scripts, so use WSL or Git Bash. There is no `.bat` launcher.

## L1

L1 is an auxiliary log-integrity check: duplicate completion, completion without a start, a second `RUN_COMPLETED`. It catches bed-level log problems. L1 is not the 10th contract; the nine contracts remain C1–C6 and S1–S3.

## Citation

See `CITATION.cff` in the repository root. Cite the `v0.1.1` release or the concept DOI `10.5281/zenodo.23470386`. For the paper that inspired C1–C6, cite `arXiv 2608.03836` directly.

## Third-party findings

Publish framework findings as gaps unless the framework's own docs promise the property. Quote the promise with URL and pinned version, and prefer opening a minimal reproduction upstream before or alongside publishing. See `CONTRIBUTING.md` and `SECURITY.md`. P2's findings were produced against pinned versions and their evidence files are tracked so maintainers can reproduce them.

## README languages

`README.md` is the English primary document. `README.zh-CN.md` is a Chinese mirror. `scripts/verify-docs.sh` validates both against the same frozen gate markers, so the numbers cannot drift between them.

## Repository URL and badges

Yes. The public repository is https://github.com/zhaojie-tech/waypoint; the CI badge and clone command are enabled. `CITATION.cff` carries the repository URL, and a Zenodo DOI is added when the record is minted.
