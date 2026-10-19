#!/usr/bin/env bash
# Waypoint gate: one command, prints the numbers, and the exit code is the
# verdict. Requires JDK 17+ (release target 17) on PATH or at $JAVA_HOME.
#
# The verdict is computed inside waypoint.Calibrate from measured values, not
# from this text output: determinism 100/100, torn-tail 9/10 with an intact
# prefix, baseline 63 crash-runs and 0 violations, mutants 13/13, stable C6
# replay and ordering, and locale-independent formatting. The gate writes
# build/gate-result.json (frozen schema; scripts/verify-docs.sh reads it).
#
# Test hook (not used in normal runs or CI): WAYPOINT_FAULT=determinism|
# torn-tail|baseline|mutants|locale|c6 perturbs one measured value, so the gate
# exits 1. See docs/mutations.md section 5.
set -euo pipefail
cd "$(dirname "$0")"

command -v javac >/dev/null || { echo "ERROR: javac not found — install JDK 17+ (release target 17)"; exit 2; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"; JAVAC="${JAVAC:-javac}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}";   JAVA="${JAVA:-java}"

rm -rf build
mkdir -p build
# shellcheck disable=SC2046
$JAVAC --release 17 -encoding UTF-8 -d build $(find src/main/java -name '*.java')

$JAVA -cp build waypoint.Calibrate "$@"
