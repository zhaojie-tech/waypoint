#!/usr/bin/env bash
# Verify that README marker blocks match the structured gate result.
#
# The gate writes build/gate-result.json (frozen schema). README files embed
# values of that file in marker blocks so prose and machine output cannot drift:
#
#   <!-- gate:determinism -->100/100<!-- /gate:determinism -->
#   <!-- gate:tornTail -->9/10<!-- /gate:tornTail -->
#   <!-- gate:baseline -->63 crash-runs, 0 contract violations<!-- /gate:baseline -->
#   <!-- gate:mutants -->13/13<!-- /gate:mutants -->
#   <!-- gate:sha --><64 lowercase hex><!-- /gate:sha -->
#
# Optional keys: pass (PASS/FAIL), version (0.1.1).
set -uo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Usage: scripts/verify-docs.sh [options]

Runs ./gate.sh (unless --skip-gate) and verifies that the README marker blocks
match build/gate-result.json.

Checked keys: determinism, tornTail, baseline, mutants, sha
Optional keys (verified when present): pass, version

Options:
  --skip-gate        Do not run ./gate.sh; verify the existing build/gate-result.json
  --en-only          Only verify README.md (skip README.zh-CN.md when present)
  --json FILE        Read the structured gate result from FILE
                     (default: build/gate-result.json)
  -h, --help         Show this help

Exit codes:
  0  documentation matches the gate result
  1  missing/duplicate marker or value mismatch
  2  prerequisite missing (gate script, gate-result.json, python3)

Examples:
  ./scripts/verify-docs.sh
  ./gate.sh && ./scripts/verify-docs.sh --skip-gate
EOF
}

RUN_GATE=1
EN_ONLY=0
JSON="build/gate-result.json"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-gate) RUN_GATE=0 ;;
    --en-only)   EN_ONLY=1 ;;
    --json)
      shift
      [ "$#" -gt 0 ] || { echo "ERROR: --json requires a file argument" >&2; exit 2; }
      JSON="$1"
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 not found (required by scripts/verify-docs.sh)" >&2
  exit 2
}

if [ "$RUN_GATE" -eq 1 ]; then
  [ -x ./gate.sh ] || { echo "ERROR: ./gate.sh is missing or not executable" >&2; exit 2; }
  echo ">> running ./gate.sh"
  if ! ./gate.sh; then
    echo "ERROR: ./gate.sh failed — fix the gate before verifying documentation" >&2
    exit 1
  fi
fi

if [ ! -f "$JSON" ]; then
  echo "ERROR: structured gate result not found: $JSON" >&2
  echo "       run ./gate.sh first, or pass --skip-gate after a gate run" >&2
  exit 2
fi

python3 - "$JSON" "$EN_ONLY" <<'PY'
import json
import re
import sys

json_path = sys.argv[1]
en_only = sys.argv[2] == "1"

try:
    with open(json_path, encoding="utf-8") as fh:
        data = json.load(fh)
except (OSError, json.JSONDecodeError) as exc:
    print(f"ERROR: cannot read {json_path}: {exc}", file=sys.stderr)
    sys.exit(2)

try:
    expected = {
        "determinism": f'{data["determinism"]["equal"]}/{data["determinism"]["total"]}',
        "tornTail": f'{data["tornTail"]["survived"]}/10',
        "baseline": f'{data["baseline"]["runs"]} crash-runs, '
                    f'{data["baseline"]["violations"]} contract violations',
        "mutants": f'{data["mutants"]["captured"]}/{data["mutants"]["total"]}',
        "sha": str(data["reportSha256"]).lower(),
        "pass": "PASS" if data.get("pass") else "FAIL",
        "version": str(data.get("version", "")),
    }
except (KeyError, TypeError) as exc:
    print(f"ERROR: {json_path} does not match the frozen gate-result schema: {exc}", file=sys.stderr)
    sys.exit(2)

if data.get("pass") is not True:
    print(f"ERROR: {json_path} is a FAILING gate verdict (pass={data.get('pass')!r}); "
          "run ./gate.sh to produce a passing verdict before verifying documentation, "
          "or use --skip-gate only after a passing gate run", file=sys.stderr)
    sys.exit(2)

required = ["determinism", "tornTail", "baseline", "mutants", "sha"]
known = set(expected)

MARKER = re.compile(r"<!--\s*gate:([A-Za-z0-9_.-]+)\s*-->(.*?)<!--\s*/gate:\1\s*-->", re.S)


def parse_value(key, text):
    """Normalise a marker value; returns a comparable object or None."""
    value = text.strip()
    if key in ("determinism", "tornTail", "mutants"):
        m = re.fullmatch(r"(\d+)\s*/\s*(\d+)(?:\s+.*)?", value)
        return (int(m.group(1)), int(m.group(2))) if m else None
    if key == "baseline":
        m = re.fullmatch(r"(\d+)\s*/\s*(\d+)", value)
        if m:
            return (int(m.group(1)), int(m.group(2)))
        m = re.fullmatch(r"(\d+)\s+(?:crash-runs|crash runs|runs)\b[^0-9]*(\d+).*", value)
        return (int(m.group(1)), int(m.group(2))) if m else None
    if key == "sha":
        return value.lower() if re.fullmatch(r"[0-9a-fA-F]{64}", value) else None
    if key == "pass":
        return value.upper() if value.upper() in ("PASS", "FAIL") else None
    if key == "version":
        return value.lstrip("vV") if re.fullmatch(r"[vV]?\d+\.\d+\.\d+", value) else None
    return None


def check_file(path):
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        return [f"{path}: cannot read: {exc}"]

    errors = []
    found = {}
    for match in MARKER.finditer(text):
        key, value = match.group(1), match.group(2)
        if key in found:
            errors.append(f"{path}: duplicate marker for key '{key}'")
            continue
        found[key] = value

    for key in required:
        if key not in found:
            errors.append(f"{path}: missing required marker '<!-- gate:{key} -->...<!-- /gate:{key} -->'")

    for key, value in found.items():
        if key not in known:
            print(f"WARN {path}: unknown marker key '{key}' (ignored)", file=sys.stderr)
            continue
        want = parse_value(key, expected[key])
        got = parse_value(key, value)
        if got is None:
            errors.append(f"{path}: gate:{key} value {value!r} is not in a recognised format")
        elif got != want:
            errors.append(f"{path}: gate:{key} expected {expected[key]!r}, found {value.strip()!r}")

    if not errors:
        shown = " ".join(f"{k}={found[k].strip()}" for k in required if k in found)
        print(f"OK  {path}: {shown}")
    return errors


files = ["README.md"]
if not en_only:
    try:
        with open("README.zh-CN.md", encoding="utf-8"):
            files.append("README.zh-CN.md")
    except OSError:
        pass

all_errors = []
for path in files:
    all_errors.extend(check_file(path))

if all_errors:
    print("", file=sys.stderr)
    for err in all_errors:
        print(f"FAIL {err}", file=sys.stderr)
    print("", file=sys.stderr)
    print(f"verify-docs: {len(all_errors)} documentation mismatch(es) — "
          "update the README marker values (or run ./scripts/update-results.sh "
          "after a legitimate gate change)", file=sys.stderr)
    sys.exit(1)

print(f"verify-docs: OK — {len(files)} file(s) match {json_path} (pass={expected['pass']}, sha={expected['sha']})")
PY
