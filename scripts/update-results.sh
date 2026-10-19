#!/usr/bin/env bash
# Refresh committed reproducibility evidence in results/ from the latest gate run.
#
# Writes:
#   results/gate.txt   human-readable gate output
#   results/gate.json  canonical JSON of build/gate-result.json (sorted keys, 2-space indent)
#   results/p3-conformance.md / results/s1-matrix.md  when the corresponding gate
#                                                     outputs exist under build/
# CI re-runs this script and fails if `git diff --exit-code results/` is dirty, so
# a legitimate number change must be committed together with its results/ update.
set -uo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Usage: scripts/update-results.sh [options]

Runs ./gate.sh and refreshes results/ from build/gate-result.json.

Options:
  --skip-gate   Do not run ./gate.sh; reuse the existing build/gate-result.json
                (results/gate.txt is left untouched in this mode)
  -h, --help    Show this help

Exit codes:
  0  results updated
  1  gate failed (results are not updated when the gate fails)
  2  prerequisite missing (./gate.sh, build/gate-result.json, python3, results/)
EOF
}

RUN_GATE=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-gate) RUN_GATE=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 not found (required by scripts/update-results.sh)" >&2
  exit 2
}
[ -x ./gate.sh ] || { echo "ERROR: ./gate.sh is missing or not executable" >&2; exit 2; }
mkdir -p results || { echo "ERROR: cannot create results/" >&2; exit 2; }

# Snapshot p3/p4 reports first: ./gate.sh rebuilds build/ from scratch and would
# delete them. They are optional; only refreshed when the corresponding gate has
# run since the last cleanup.
snap_dir="$(mktemp -d)"
trap 'rm -rf "$snap_dir"' EXIT
snap_p3=0
snap_p4=0
if [ -f build/p3-report.md ]; then cp build/p3-report.md "$snap_dir/p3-report.md"; snap_p3=1; fi
if [ -f build/s1-matrix.md ]; then cp build/s1-matrix.md "$snap_dir/s1-matrix.md"; snap_p4=1; fi

if [ "$RUN_GATE" -eq 1 ]; then
  gate_out="$(mktemp)"
  trap 'rm -f "$gate_out"; rm -rf "$snap_dir"' EXIT
  echo ">> running ./gate.sh"
  if ! ./gate.sh | tee "$gate_out"; then
    echo "ERROR: gate failed — results/ was not updated" >&2
    exit 1
  fi
  if [ ! -f build/gate-result.json ]; then
    echo "ERROR: gate succeeded but build/gate-result.json is missing — results/ was not updated" >&2
    exit 2
  fi
  cp "$gate_out" results/gate.txt
  chmod 0644 results/gate.txt
fi

if [ ! -f build/gate-result.json ]; then
  echo "ERROR: build/gate-result.json not found; run ./gate.sh first" >&2
  exit 2
fi

if ! python3 - build/gate-result.json results/gate.json <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    data = json.load(fh)
if data.get("pass") is not True:
    print(f"ERROR: {sys.argv[1]} is a FAILING verdict (pass={data.get('pass')!r}); "
          "refusing to update results/ — run ./gate.sh first", file=sys.stderr)
    sys.exit(2)
with open(sys.argv[2], "w", encoding="utf-8") as fh:
    fh.write(json.dumps(data, indent=2, sort_keys=True) + "\n")
PY
then
  echo "ERROR: results/ was not updated" >&2
  exit 2
fi
chmod 0644 results/gate.json

echo ">> wrote results/gate.txt results/gate.json"
p3_src="build/p3-report.md"
p4_src="build/s1-matrix.md"
if [ "$RUN_GATE" -eq 1 ]; then
  p3_src="$snap_dir/p3-report.md"
  p4_src="$snap_dir/s1-matrix.md"
fi
for pair in "$p3_src:results/p3-conformance.md" "$p4_src:results/s1-matrix.md"; do
  src="${pair%%:*}"
  dst="${pair##*:}"
  if [ -f "$src" ]; then
    cp "$src" "$dst"
    chmod 0644 "$dst"
    echo ">> wrote $dst (snapshot of a p3/p4 run)"
  fi
done

echo ">> results/ refreshed; commit it together with any legitimate gate change"
echo "   CI check: git diff --exit-code results/"
