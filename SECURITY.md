# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | Yes |

Waypoint is a research artifact and developer tool, not a production service. Security fixes are applied to the
latest released version.

## What this project is and is not

- It is a deterministic checker and gate that runs locally: `./gate.sh`, `./p4/gate.sh`, the jar, and the Maven
  build. There is no network listener, daemon, database or account system.
- It is not a sandbox. The CLI (`waypoint.cli.Main check ...`) parses untrusted event/ledger/model JSON files, so
  treat inputs from unknown parties as potentially malicious (see below).
- The simulated crash model covers process-crash semantics only. It does not model power loss, filesystem
  durability or hostile filesystems.

## Reporting a vulnerability

Do not open a public issue for a security problem.

- Once the repository has a public URL, use its private vulnerability reporting or Security Advisory feature.
- Until then, contact the maintainer through the contact information published with the repository (see the
  `LICENSE` copyright holder and repository metadata). Do not put secrets or exploit details in a public
  channel.

Please include the affected version and commit, JDK/OS, a minimal reproduction, and the impact you believe it
has. We aim to acknowledge reports within 14 days and to agree on a disclosure timeline; please allow up to 90
days before public disclosure.

## Threat model and in-scope issues

In scope:

- Gate bypass or false PASS. The gate's purpose is a trustworthy verdict, so a report or input that makes a
  degraded run pass is a security-relevant correctness bug (for example a verdict computed from human-readable
  text, locale-dependent numbers, or an unchecked metric).
- Checker trust violations. The checker must not accept evidence that the observed runtime did not actually
  produce; fabricating "clean" results for unobservable contracts is in scope.
- Parser and CLI hardening. Crashes, unbounded memory, path traversal or output injection when `check` parses
  malicious JSON/JSONL, or when file paths are attacker-controlled. Malformed input should fail with exit code 2
  and a clear message, not a stack trace.
- Build and release integrity. A dependency, wrapper or CI change that makes the published artifact differ from
  the tested source.
- Secret exposure. Committed credentials, tokens, endpoints, or captured traffic containing them.

Out of scope (known, documented limitations rather than vulnerabilities):

- In-process crash injection is simulated; there is no real SIGKILL harness for the Java bed (the Python lab
  driver does use a real SIGKILL, see `p2/REPORT.md`).
- No power-loss semantics, distributed log or multi-node lease.
- Findings about third-party frameworks are reproducible observations on pinned versions, not a security
  assessment of those frameworks.

## Handling of captured data and credentials

- The repository must contain no API keys, bearer tokens, endpoints or private data. The captured SSE fixture
  `p2/lab/real-llm-sse.txt` contains no `Authorization` header, no provider URL and no user data; see
  `THIRD_PARTY.md` for provenance.
- If you believe a credential was committed by mistake, report it privately and do not copy it into an issue.

## Dependency policy

The runtime jar has zero third-party dependencies. JUnit 5 is test-only. The experiment-only frameworks under
`p2/` are pinned and are not redistributed; their licenses and versions are listed in `THIRD_PARTY.md`. Dependency
updates that change the released artifact must pass all gates on JDK 17 and 21.
