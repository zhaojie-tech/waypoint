#!/usr/bin/env bash
# P4 gate: S1/S2 cancellation-boundary matrix + sensitivity + optional real-LLM smoke.
# Exit codes: 0 = gate PASS, 1 = gate FAIL, 2 = missing prerequisite / build error.
# Outputs (untracked): build/s1-matrix.md, build/real-llm-s1-result.md, build/s1-raw-prefix.bin
# The gate is SELF-CONTAINED: it compiles the checker core + matrix into its own
# ignored directory (p4/classes), so it does not race with the root gate's build/.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "ERROR: $*" >&2; exit 2; }

command -v javac >/dev/null 2>&1 || die "javac not found — install JDK 17+ and put it on PATH (or set JAVA_HOME)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"; JAVAC="${JAVAC:-javac}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}";   JAVA="${JAVA:-java}"

# --- compile checker core + matrix into the gate's own ignored dir ---
rm -rf p4/classes && mkdir -p p4/classes
$JAVAC --release 17 -encoding UTF-8 -d p4/classes \
  $(find src/main/java -name '*.java') p4/CancelBoundaryMatrix.java \
  || die "failed to compile checker core (src/main/java) + p4/CancelBoundaryMatrix.java"

mkdir -p build
$JAVA -cp "p4/classes" waypoint.p4.CancelBoundaryMatrix

# Optional real-LLM SSE smoke: prints evidence and writes its products under build/.
if [ -f p2/lab/real-llm-sse.txt ] && [ -x p2/lab/.venv-py/bin/python ]; then
  ( cd build && ../p2/lab/.venv-py/bin/python ../p2/lab/real_llm_s1_smoke.py )
else
  echo "skip real-LLM smoke: p2/lab/real-llm-sse.txt 或 p2/lab/.venv-py 不在场"
  echo "  （捕获与冒烟方法见 p2/lab/README.md；冒烟产物默认写入 build/）"
fi
