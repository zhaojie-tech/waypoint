# Reproducibility (v0.1.0)

A stranger should be able to verify every published number in minutes, and to tell which numbers are not reproducible on their machine and why. README numbers are frozen marker blocks validated by `scripts/verify-docs.sh` against `build/gate-result.json`; this document explains how to obtain them.

## 1. Environment matrix

| Environment | JDK | OS / CPU | Python | Used for |
|---|---|---|---|---|
| **CI workflow (GitHub Actions)**, configured, not yet run publicly at v0.1.0 | Temurin **17** and **21** | ubuntu-latest | 3.9.9 (wrapper) | `./mvnw -B -q verify`, `./gate.sh`, `./p4/gate.sh`, `./scripts/verify-docs.sh` |
| **Review/authoring host** | OpenJDK **17.0.20.1** | Linux 5.15, 4 cores | 3.10.12 | all local runs recorded in this document; P2 lab drivers |
| **P2 lab runs (recorded in `p2/`)** | Temurin **21.0.12.1+1** | Linux 5.15, 4 cores | 3.10.12 (venv) | langgraph4j / spring-ai-alibaba / LangGraph(Python) drivers, real SIGKILL |
| **JDK 21 locally** | not available on the authoring host at the time of v0.1.0 | n/a | n/a | JDK 17 is verified locally; JDK 21 is configured in the CI workflow only (no public run yet) |

The gate is designed to be independent of the JDK version. It compiles with `--release 17`, uses no preview APIs, no wall clock, no randomness and no locale-sensitive formatting (`Locale.ROOT` everywhere). The report sha256 should be identical across JDK 17/21 and across locales; the CI workflow is configured to check JDK 21, and the gate's negative-control tests check locale independence.

## 2. Prerequisites

| Gate / target | Needs | Offline? |
|---|---|---|
| `./gate.sh` (P1 core) | JDK 17+ (`javac`, `java`), bash, `find` | **yes**, zero third-party dependencies |
| `./p4/gate.sh` (S1/S2 matrix) | same as above; real-LLM smoke additionally needs `p2/lab/.venv-py` (Python 3 + deps) | yes (smoke is skipped with a clear message if the venv is absent) |
| `./p3/gate.sh` (2 real frameworks) | JDK 17+, **Maven** + network on first run (resolves `langgraph4j-core 1.9.5` and `spring-ai-alibaba-graph-core 1.1.2.4-security-fix`) | no; framework jars are **not** vendored (`p2/lab/libs*` is git-ignored); missing Maven exits `2` with a prerequisite message |
| `./mvnw -B verify` | JDK 17+, network on first run (Maven Wrapper downloads Maven) | first run no, afterwards yes |
| `p2/lab/*` drivers | Maven (Java drivers) or Python 3 + `pip install -r requirements.txt` (Python drivers) + network | no |
| `scripts/verify-docs.sh` | `python3` + `build/gate-result.json` (or it runs `./gate.sh` first) | yes if the JSON already exists (`--skip-gate`) |

Windows is not a native target. The gates are bash scripts (`bash`, `find`); use WSL or Git Bash. There is no `.bat` launcher, and the project is developed and tested on Linux.

## 3. The three gates

### 3.1 `./gate.sh` (P1 core gate)

```bash
cd waypoint
./gate.sh        # rebuilds ./build/ from src/main/java, then runs waypoint.Calibrate
echo $?          # 0 = PASS, 1 = FAIL, 2 = missing JDK
```

Expected output (numbers come from `build/gate-result.json`; the README frozen blocks are the published values):

```
[1] recovery determinism: 100/100 replays hash-equal
[1] same-seed reproducibility: stateHash identical
[2] torn-tail self-test: kept 9/10 intact-prefix=yes (truncate-at-first-bad-record)
[3] S_W1          runs=20  violations=0
[3] S_W1_CKPT     runs=22  violations=0
[3] S_W1_RESUME2  runs=8   violations=0
[3] S_W2          runs=11  violations=0
[3] S_W3          runs=2   violations=0
[3] contract activity: C1 63/63 C2 63/63 C3 2/63 C4 11/63 C5 8/63 C6 63/63 S1 8/63 S2 8/63 S3 11/63
[3] baseline specificity: 63 crash-runs, 0 contract violations (must be 0)
[4] mutation calibration
[4]   ... one row per mutant (13), target contract + captured=yes + findings count ...
[4] mutations captured 13/13
[5] locale independence: report formatting stable across ar-EG/tr-TR (Locale.ROOT)
GATE: PASS | mutations 13/13 required | determinism/reproducibility required | baseline 0 false positives required | locale-independent formatting required
report sha256=<64 hex>
whole-gate reproducibility: 2/2 identical (same seed, same result)
gate-result: build/gate-result.json
```

`[3] contract activity` is the active/total disclosure. An inactive contract is unobservable, not clean; the per-contract denominators are in `docs/mutations.md` §2.

Runtime on the authoring host (4 cores, JDK 17) is about 5–10 s including `javac`; a cold page cache can add a few seconds. The tool itself runs in well under a second, so the 30-second quickstart bound is generous.

Five negative controls must all exit 1: determinism equal-count reduced, torn-tail prefix broken, baseline violations > 0, a mutant not captured, and non-`Locale.ROOT` formatting or a hostile JVM locale. `GateVerdictTest` covers them, and they can be reproduced with `WAYPOINT_FAULT=determinism|torn-tail|baseline|mutants|locale ./gate.sh` (see `docs/mutations.md` §5). `calibrate --json` prints the same structured result as `build/gate-result.json`.

### 3.2 `./p4/gate.sh` (S1/S2 matrix)

```bash
./p4/gate.sh     # writes build/s1-matrix.md
echo $?          # 0 = PASS, 1 = FAIL, 2 = missing JDK
```

Expected shape: per-cancel-point rows with total crash-runs, S1-active runs (the assertions actually executable at that boundary) and violations, followed by a sensitivity section. The sensitivity check injects the S1 mutants `M9`/`M12` and requires S1 violations; if either produces none, the gate fails.

Latest `v0.1.0` run: 4 boundaries (`K ∈ {3,10,17,30}`), 188 runs and 48 S1-active runs (40 real crash runs + 8 no-crash controls); 0 S1/S2 violations over the active denominator; sensitivity M9 → 24 S1 findings in 53 runs, M12 → 12 S1 findings in 51 runs; gate PASS. Full table and the denominator caveat: [`p4/README.md`](../p4/README.md).

If `p2/lab/.venv-py` and `p2/lab/real-llm-sse.txt` are present, a real-LLM SSE byte-prefix persistence round-trip runs. Otherwise it prints `skip real-LLM smoke ...` and the gate still passes; the smoke is evidence, not a gate requirement.

### 3.3 `./p3/gate.sh` (2 real frameworks, 4 fault forms, needs Maven)

```bash
./p3/gate.sh     # writes build/p3-report.md
echo $?          # 0 = PASS, 1 = FAIL, 2 = missing prerequisite (JDK/Maven/deps)
```

First run downloads two framework jars via Maven; after that it is offline. The report includes reachability per check, the adapter `unobservable` declarations (`C3`, `C4`, `S1`, `S2`, plus C2's ordering half), 3 repeated runs of the concurrent cell, and a pass criterion based on finding classes (stable across 3 runs) rather than unstable ledger detail. The concurrent cell's ledger count is race-dependent (`c` is 2 or 3). Example 3-run samples: langgraph4j `[2,2,2]` to `[2,3,3]`, SAA `[3,3,3]` (observed 2026-10-19; the exact sample changes between runs, so a re-run may differ; only the finding grade is claimed). A recorded run: 8/8 cells, findings `C2` on CRASH_AFTER_EFFECT and `C2+C5` on CONCURRENT_RESUME for both frameworks, clean on CRASH_BEFORE_EFFECT and DOUBLE_RESUME_SAME_CP; gate PASS. See [`p3/README.md`](../p3/README.md).

## 4. P2 lab drivers

P2 is evidence, not part of the default gate. It produced the 10 findings in `p2/REPORT.md` and needs network and third-party deps.

```bash
cd p2/lab
# langgraph4j 1.9.5 (JDK 17+, Maven):
mvn -f pom.xml dependency:copy-dependencies -DoutputDirectory=libs
javac -cp "libs/*" -d classes src/LangGraph4jDriver.java
java -cp "classes:libs/*" LangGraph4jDriver          # -> driver-result.md
# spring-ai-alibaba-graph-core 1.1.2.4-security-fix:
mvn -f pom-saa.xml dependency:copy-dependencies -DoutputDirectory=libs-saa
javac -cp "libs-saa/*" -d classes2 src2/SaGraphDriver.java
java -cp "classes2:libs-saa/*" SaGraphDriver          # -> saa-driver-result.md
# LangGraph (Python), real SIGKILL (exit 137):
python3 -m venv .venv-py && ./.venv-py/bin/pip install -r requirements.txt
./.venv-py/bin/python langgraph_sigkill_driver.py init
INJECT_CRASH=1 ./.venv-py/bin/python langgraph_sigkill_driver.py record
./.venv-py/bin/python langgraph_sigkill_driver.py resume
./.venv-py/bin/python lg_concurrent_resume.py init
./.venv-py/bin/python lg_concurrent_resume.py record
./.venv-py/bin/python lg_concurrent_resume.py probe
./.venv-py/bin/python lg_concurrent_resume.py concurrent
```

The recorded environment is in [`p2/REPORT.md`](../p2/REPORT.md) (JDK 21.0.12.1+1, Python 3.10.12, pinned versions). Result files are tracked so the claims can be checked without re-running the frameworks; re-running needs network access to Maven Central and PyPI.

## 5. CLI quick verification (no third-party deps)

```bash
# build a jar (Maven) or use the classpath from ./gate.sh
./mvnw -B -q -DskipTests package
java -jar target/waypoint-0.1.0.jar version
java -jar target/waypoint-0.1.0.jar check \
  --events examples/external-log/violation/events.jsonl \
  --ledger examples/external-log/violation/ledger.jsonl \
  --meta   examples/external-log/violation/meta.json
echo $?   # 1 = one C2 finding (use examples/external-log/clean/ for exit 0); 2 = bad input
```

`examples/external-log/` contains one clean example and one that must trigger `C2`. The expected findings are in that directory's README.

## 6. Not reproducible here

- JDK 21 locally: configured in CI only (see matrix). There is no public CI run or local JDK 21 verification at v0.1.0.
- Real LLM calls: no key or endpoint is shipped. The captured SSE file is a fixed fixture, and the smoke does not call a model. See `p2/lab/captures/README.md` for provenance.
- Power-loss and fsync-loss semantics: needs kernel fault injection (dm-flakey/dm-log-writes) or VM snapshots; not modeled. All crash evidence is process-crash evidence.
- Distributed and multi-node behaviour: out of scope; `lease` is single-node and simulated.
- Diagrid Catalyst and AgentScope Java: coordinates are pinned but no offline or cloud environment exists; they remain documented as untested (P2 report, "not tested" section).
