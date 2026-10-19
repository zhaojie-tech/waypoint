#!/usr/bin/env bash
# P3 gate: fault-matrix x 2 real frameworks, audited by the real waypoint.Checker.
# Exit codes: 0 = gate PASS, 1 = gate FAIL, 2 = missing prerequisite / build error.
# Output: build/p3-report.md (untracked; regenerated on every run).
# The gate is SELF-CONTAINED: it compiles the checker core + adapter into its own
# ignored directory (p3/classes), so it does not race with the root gate's build/.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "ERROR: $*" >&2; exit 2; }

command -v javac >/dev/null 2>&1 || die "javac not found — install JDK 17+ and put it on PATH (or set JAVA_HOME)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"; JAVAC="${JAVAC:-javac}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}";   JAVA="${JAVA:-java}"

# --- framework dependencies (gitignored, fetched by Maven or vendored) ---
if [ ! -d p2/lab/libs ] || [ ! -d p2/lab/libs-saa ]; then
  command -v mvn >/dev/null 2>&1 || die \
"p3 prerequisites missing: p2/lab/libs (langgraph4j) and/or p2/lab/libs-saa (spring-ai-alibaba-graph) are absent,
  and Maven is not on PATH. Fix one of:
    (a) install Maven 3.9+ and rerun ./p3/gate.sh (it will fetch deps via p2/lab/pom.xml and pom-saa.xml);
    (b) pre-vendor the jars into p2/lab/libs/ and p2/lab/libs-saa/ (both are gitignored; versions in the poms)."
  ( cd p2/lab && mvn -q -f pom.xml dependency:copy-dependencies -DoutputDirectory=libs ) \
    || die "Maven dependency resolution failed for langgraph4j (p2/lab/pom.xml)"
  ( cd p2/lab && mvn -q -f pom-saa.xml dependency:copy-dependencies -DoutputDirectory=libs-saa ) \
    || die "Maven dependency resolution failed for spring-ai-alibaba-graph (p2/lab/pom-saa.xml)"
fi

# --- compile checker core + adapter into the gate's own ignored dir ---
rm -rf p3/classes && mkdir -p p3/classes
$JAVAC --release 17 -encoding UTF-8 -cp "p2/lab/libs/*:p2/lab/libs-saa/*" -d p3/classes \
  $(find src/main/java -name '*.java') p3/Conformance.java \
  || die "failed to compile checker core (src/main/java) + p3/Conformance.java against the framework jars (check p2/lab/pom*.xml versions)"

# Expected crash-injection stack traces from framework loggers go to stderr; keep
# them out of the report and out of version control (p3/classes is ignored).
mkdir -p build
set +e
$JAVA -cp "p3/classes:p2/lab/libs/*:p2/lab/libs-saa/*" waypoint.p3.Conformance 2>p3/classes/p3-runtime.log
rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  echo "ERROR: p3 matrix process exited $rc — see build/p3-report.md (if written) and last stderr lines:" >&2
  tail -n 20 p3/classes/p3-runtime.log >&2 || true
  exit "$rc"
fi
