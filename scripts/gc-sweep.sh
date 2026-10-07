#!/usr/bin/env bash
#
# gc-sweep.sh — run several variants several times, in shuffled order (pareto-frontier.md: "Repeat
# each variant at least 3 times, in shuffled order across variants").
#
#   RATE=40 P99_LIMIT_MS=180 REPEATS=3 scripts/gc-sweep.sh baseline serial-explicit parallel g1 zgc shenandoah
#
# Shuffling spreads slow drift (node noise, Cloud SQL, cache state) over the variants instead of
# letting it favour whichever ran last. All gc-run.sh settings pass through the environment. The
# cluster is handed back to main at the end (also when a run fails), unless KEEP=1.
set -euo pipefail
cd "$(dirname "$0")/.."

(( $# > 0 )) || { echo "usage: RATE=<n> [REPEATS=3] $0 <variant>..." >&2; exit 1; }
REPEATS="${REPEATS:-3}"

order=$(
  for _ in $(seq 1 "$REPEATS"); do printf '%s\n' "$@"; done \
    | python3 -I -c 'import random,sys; v=sys.stdin.read().split(); random.shuffle(v); print("\n".join(v))'
)
echo "run order:" $order

if [[ "${KEEP:-0}" != "1" && "${DRY_RUN:-0}" != "1" ]]; then
  trap 'scripts/gc-run.sh --restore' EXIT
fi
for variant in $order; do
  scripts/gc-run.sh "$variant"
done
