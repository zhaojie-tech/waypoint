# Support

Waypoint is maintained on a best-effort basis as a research artifact and developer tool. There is no SLA and no
commercial support.

## Before asking

1. Run the release acceptance locally and include the output:

   ```bash
   ./mvnw -B -q verify
   ./gate.sh
   ./p4/gate.sh
   ./scripts/verify-docs.sh --skip-gate
   ```

2. Read `docs/faq.md`, `docs/reproducibility.md`, and the known limitations in `README.md`.
3. Search existing issues for the same symptom.

## Where to ask

| Kind | Channel |
|---|---|
| Bug report | Issue template "Bug report" (include JDK/OS and full gate output) |
| Feature or hardening request | Issue template "Feature or hardening request" |
| Security vulnerability | Follow `SECURITY.md`; do not open a public issue |
| Framework-specific finding | See `p2/REPORT.md`; such findings are pinned-version observations, not upstream security assessments |
| General question | GitHub Discussions (when enabled for the public repository); until then use the issue tracker and mark the issue as a question |

## What to include

- Waypoint version and commit (`git describe --tags --always` once tagged; otherwise `git rev-parse HEAD`) and
  how you obtained it.
- JDK version (`java -version`) and OS/architecture.
- Whether the problem reproduces on a clean clone with `./gate.sh` and/or `./mvnw -B verify`.
- For checker/CLI issues: the input files (redacted) and the exact command.
- Do not include credentials, tokens, provider endpoints or third-party private data. Redact captured traffic
  before attaching it.

## Scope

Maintainers help with defects and documentation gaps in this repository. They cannot provide support for
third-party frameworks, cloud durable-execution services, Maven or Python environments, or production
deployments built on top of this research artifact.
