# Third-party notices, licenses and data provenance

Waypoint itself is MIT licensed (see `LICENSE`). The sections below list:

1. the test-only dependencies of the released build,
2. the pinned experiment-only dependencies under `p2/` and `p3/` (not redistributed), and
3. the provenance of committed captured data and quotations.

The runtime jar has zero third-party dependencies.

## Build and test dependencies

| Component | Coordinate | Version | License | Scope |
|---|---|---|---|---|
| JUnit 5 (Jupiter) | `org.junit.jupiter:junit-jupiter` | 5.11.4 | EPL-2.0 | test only |
| Maven (wrapper) | Apache Maven | 3.9.9 | Apache-2.0 | build tool, not bundled |

## Experiment-only dependencies (not part of the released artifact)

These are fetched at experiment time (`p2/lab/libs*` and `.venv-py` are gitignored) and are not redistributed
with the release.

| Component | Coordinate / source | Version | License (as verified 2026-10-19) | Used by |
|---|---|---|---|---|
| langgraph4j core | `org.bsc.langgraph4j:langgraph4j-core` | 1.9.5 | MIT (declared in `langgraph4j-parent` POM) | `p2/lab/pom.xml`, p3 |
| SLF4J API (transitive) | `org.slf4j:slf4j-api` | 2.0.9 | MIT (jar `Bundle-License`) | langgraph4j |
| Spring AI Alibaba Graph | `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core` | 1.1.2.4-security-fix | Apache-2.0 (POM `<licenses>`) | `p2/lab/pom-saa.xml`, p3 |
| AgentScope Java core | `io.agentscope:agentscope-core` | 2.1.0-BETA1 | Apache-2.0 (jar embedded POM) | coordinate reconnaissance only (no driver) |
| Diagrid Spring AI starter | `io.diagrid:diagrid-spring-ai-starter` | 0.4.0 | not vendored and not verified in this repository; check Maven Central metadata before redistribution | coordinate reconnaissance only (Catalyst required, not run) |
| LangGraph (Python) | PyPI `langgraph` | 1.2.16 | MIT (`License-Expression: MIT` in the installed dist-info) | `p2/lab/requirements.txt` |
| Python experiment stack | see `p2/lab/requirements.txt` | pinned per line | licenses per PyPI metadata; verify with `pip show <pkg>` before redistributing | `p2/lab/*.py` |

Notes:

- Versions are pinned (Maven coordinates and `requirements.txt` use exact versions). License identifiers above
  were read from the dependency's own POM, jar manifest or installed package metadata. They are provided for
  convenience and are not a legal opinion.
- Transitive dependencies of the Java frameworks (Jackson, SLF4J, Apache Commons and others) are fetched by
  Maven and are not committed; their licenses travel with the fetched artifacts. Run `mvn dependency:tree` in
  `p2/lab` before redistributing any vendored copy.
- Framework findings in `p2/REPORT.md` and `p3/` are reproducible observations on pinned versions, not security
  assessments.

## Captured data

| File | What it is | Provenance / license notes |
|---|---|---|
| `p2/lab/real-llm-sse.txt` | 55,159-byte raw Server-Sent-Events capture used as a stream/replay fixture | Captured 2026-10-19 through a self-hosted LiteLLM-compatible gateway; the payload identifies the model as `glm-5.3-flash`. The file contains no `Authorization` header, no provider endpoint URL, no API key and no user data. Model output remains subject to the model provider's terms; it is included solely as a minimal reproducibility fixture. The executable driver that consumes it is `p2/lab/real_llm_s1_smoke.py`. |
| `p2/lab/s1-raw-prefix.bin` | Derived byte prefix of the capture above | Removed from version control in v0.1.0; regenerate deterministically with `p2/lab/real_llm_s1_smoke.py`. |
| `p2/lab/*-result.md`, `p2/lab/driver-result.md` | Generated observations from pinned framework runs | Produced by the drivers in this repository; the environment line records the JDK/runtime used. |
| `p3-report` / `s1-matrix` outputs | Generated matrices | Now written under `build/` and committed under `results/` as reproducibility evidence. |

## Quotations and references

- `docs/contracts.md` states that contracts C1–C6 are named after arXiv 2608.03836 and that C3/C4 are this
  project's log-level adaptations. The arXiv paper is cited, not reproduced; only short identifying facts
  (title, authors, property names) are referenced. No paper text is bundled.
- `p2/REPORT.md` contains short quotations from framework documentation with URLs and versions; passages that
  could not be verified verbatim are marked as paraphrase. Those quotations remain the property of their
  respective projects and are used for identification and criticism (fair use or fair dealing as applicable
  locally).
- `docs/article-1.md` is an original draft article by the project maintainer.

## License of this repository

MIT (see `LICENSE`). Copyright (c) 2026 Jie Zhao (zhaojie.tech).
