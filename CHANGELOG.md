# Changelog

All notable changes to Waypoint are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [0.1.0] - 2026-10-19

First public release of Waypoint as a research artifact and developer tool. The release concentrates on
reproducible evidence and explicit boundaries: partial or unobservable contracts are labelled, and the gate
verdict is a structured assertion rather than a text match.

### Added

- Maven build (`tech.zhaojie:waypoint:0.1.0`, `--release 17`, zero runtime
  dependencies) with a checked-in Maven wrapper (`mvnw`, Maven 3.9.9) and a
  `Makefile` (`build`/`test`/`gate`/`check`).
- JUnit 5 + Surefire test suite covering the gate verdict, crash semantics and
  contract checks.
- Executable jar (`target/waypoint-0.1.0.jar`, `Main-Class: waypoint.cli.Main`)
  with `calibrate`, `check`, `version` and `help` subcommands, plus a Java API
  facade for embedding the checker.
- Dependency-free JSON event/ledger format, `examples/external-log/` runnable
  examples, and `docs/adapter-guide.md` for feeding external runtime
  observations into the checker.
- Structured gate result `build/gate-result.json` (frozen schema) and
  `scripts/verify-docs.sh`, which verifies README marker blocks against that
  file so prose and machine output cannot drift.
- `scripts/update-results.sh` and committed `results/` evidence; CI fails when
  a metric changes without a corresponding `results/` update.
- A 13-mutant calibration catalogue (each contract has at least one dedicated
  mutant), including skip-unfinished-step (C1), replay-emits-wrong-chunks (S1)
  and metering-on-replay (S3).
- GitHub Actions CI (JDK 17 and 21), issue/PR templates, and the community and
  compliance files: `CHANGELOG`, `CONTRIBUTING`, `CODE_OF_CONDUCT`,
  `SECURITY`, `SUPPORT`, `CITATION.cff`, `.zenodo.json`, `THIRD_PARTY.md`,
  `.gitattributes`, `.editorconfig`.

### Changed

- Source layout is now the Maven standard `src/main/java` (tests in
  `src/test/java`); gates compile with `javac --release 17`.
- p3/p4 now write their reports to `build/` instead of tracked repository
  files, so running the gates leaves the working tree clean.
- README (English) and `README.zh-CN.md` were rewritten with an accurate status
  section, reproduction matrix, architecture diagram and boundaries; contract
  pages gained an implementation-status column.
- `p2/REPORT.md`, `p3/README.md` and `p4/README.md` now separate measured
  coverage from claims (active/total assertions, race-sample distributions,
  paraphrased vs verbatim quotations).

### Fixed

- Gate verdict: `GATE: PASS` and the exit code now come from numeric
  assertions over every metric (determinism, torn tail, baseline specificity,
  mutants, C6), and the five degraded scenarios exit non-zero. Previously a
  text-matching predicate could print `PASS` for a degraded report, including
  reports ending in `10 contract violations`.
- Locale-dependent output: all formatted output uses `Locale.ROOT`, so
  non-Latin-digit locales no longer produce a false FAIL or a drifting report
  hash.
- Crash semantics: the simulated disk now discards the unsynced tail on
  recovery (`discardUnsynced`/`truncateDurableAt`), so a record that was
  written but not synced can no longer be resurrected by the first recovery
  sync; appending after a torn tail is now visible.
- Recovery idempotency: a second full recovery no longer appends a duplicate
  `RUN_COMPLETED`; log-integrity checks (L1) flag duplicate completions.
- Checker: C1 skipped-step detection, C4 multi-status checkpoints, C5
  executor/lease checks, C6 replay/order determinism, self-computed C3 traces,
  S1 configured-K and replay-emission checks, and S3 metering consistency are
  implemented; contracts that an adapter cannot observe are reported as
  unobservable rather than silently passing.
- p4 claims corrected: the "30-chunk stream" description was wrong, S1 is now
  reported as active/total, and the matrix includes a sensitivity check that
  injected violations must be detected.
- p3 claims corrected: concurrent-resume cells report their `c=2`/`c=3`
  distribution over repeated runs and findings are classified as comparable
  across runs; "three runtimes all fail" was replaced by the evidence-backed
  "two stable + one race" statement.
- Removed stale tracked artifacts (`p3-report.md`, `s1-matrix.md`,
  `p3/p3-report.md`, `p3/run.err`, `p2/lab/s1-raw-prefix.bin`,
  `p2/lab/s1-checkpoint.json`); real-SSE capture provenance added under
  `p2/lab/captures/`.

### Security

- `SECURITY.md` documents the threat model, private disclosure channel and
  scope; the CLI treats event/ledger files as untrusted input.
- No credentials, endpoints or API keys are stored in the repository; the
  captured model output contains no `Authorization` header or provider URL.
  See `THIRD_PARTY.md` for third-party and data provenance.

<!-- Comparison/tag URLs are intentionally omitted: this repository has no
     public URL yet. Add them at release time; do not invent a placeholder domain. -->
