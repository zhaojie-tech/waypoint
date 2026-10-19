# Contributing to Waypoint

Waypoint is a research artifact and a small developer tool. Contributions are welcome. The main rule is that
claims need evidence: if something is not observed, label it unobservable; if it is partial, say so in the docs.

## Development setup

Requirements:

- JDK 17 or newer (the code targets `--release 17`; CI also builds on JDK 21).
- Bash, `git`, and `python3` (used by `scripts/verify-docs.sh`).
- Network access on the first build (the Maven wrapper downloads Maven 3.9.9 and dependencies). The runtime jar
  itself has zero third-party dependencies.

```bash
git clone <repository-url>      # placeholder: fill in after the public URL exists
cd waypoint
./mvnw -B -q -DskipTests package   # or: make build
./mvnw -B -q test                  # JUnit 5
./gate.sh                          # calibration gate; exit code is the verdict
./p4/gate.sh                       # P4 S1/S2 matrix (no network needed)
./scripts/verify-docs.sh --skip-gate
```

`make check` runs the gate, the P4 matrix and the README-marker check in one go. `make help` lists all targets.

## Repository layout

| Path | Contents |
|---|---|
| `src/main/java/waypoint/` | checker, calibration bed, WAL model, gate, JSON, CLI, API |
| `src/test/java/waypoint/` | JUnit 5 tests (contracts, crash semantics, gate verdict, CLI/JSON) |
| `docs/` | contracts, mutations, architecture, adapter guide, FAQ, reproducibility, article draft |
| `p2/`, `p3/`, `p4/` | real-framework comparison, conformance adapter prototype, S1/S2 matrix |
| `scripts/` | `verify-docs.sh`, `update-results.sh` |
| `results/` | committed gate evidence (generated; do not hand-edit) |

## Ground rules

1. Runtime jar stays dependency-free. Tests may use JUnit 5 (test scope only).
2. Determinism. No wall-clock, randomness, thread scheduling or map iteration order may influence gate output.
   Format numbers with `Locale.ROOT`.
3. Gate verdict changes need negative controls. A metric that can fail must be shown to fail: add or extend a
   mutant, a degraded-report test, or a gate test.
4. Claims and docs.
   - A contract is `full`, `partial` or `unobservable-by-adapter`, never silently "clean".
   - Quote framework documentation verbatim with URL, version and section, or mark it as paraphrase.
   - README numbers live in marker blocks: `<!-- gate:key -->value<!-- /gate:key -->`. They are checked by
     `scripts/verify-docs.sh` against `build/gate-result.json`.
5. Generated files stay out of git. Gate and test output goes to `build/`; only `results/` is committed, and
   only via `scripts/update-results.sh`.
6. No secrets. Never commit tokens, provider endpoints, private data, or captured traffic containing credentials.

## Adding a contract or a mutant

A complete contract change touches at minimum:

1. `docs/contracts.md`: precondition, assertion, observation point, implementation status.
2. `Checker`: the assertion, including an explicit "unobservable" path.
3. `Bed`: a mutant switch that encodes exactly one violation of the contract.
4. `Calibrate`: a dedicated sweep if the mutant needs one, plus the gate result schema.
5. `docs/mutations.md` and `src/test/java/waypoint/`: catalogue row and a regression test.
6. `results/` and README marker blocks, if a headline metric changes.

## Developer Certificate of Origin (DCO)

Every commit must be signed off, certifying the Developer Certificate of Origin 1.1
(<https://developercertificate.org/>):

```bash
git commit -s
```

The sign-off is a `Signed-off-by: Your Name <you@example.org>` trailer matching the commit author. No separate
CLA is required.

## Pull request process

- Keep PRs small and focused: one logical change per PR.
- Fill in `.github/PULL_REQUEST_TEMPLATE.md`, including the exact commands you ran.
- All gates must be green: `./mvnw -B verify`, `./gate.sh`, `./p4/gate.sh`,
  `./scripts/verify-docs.sh --skip-gate`.
- If a gate metric changed legitimately, run `./scripts/update-results.sh` and commit `results/` in the same PR;
  the CI workflow checks `git diff --exit-code results/`.
- Explain what was measured, not only what was changed. Commit messages should describe verified facts.
- Reviews may ask for an additional negative control before accepting a "fixed" claim. That is the point of this
  project.

## Reporting bugs and security issues

- Bugs and feature requests: use the issue templates.
- Security issues: follow `SECURITY.md`; do not open a public issue.
- Questions: see `SUPPORT.md`.

## Code of conduct

By participating you agree to the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md).
