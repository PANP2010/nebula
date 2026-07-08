#!/bin/bash
# ralph.sh — Ralph cycle runner for Nebula.
#
# Each iteration launches a FRESH `claude -p` process with NO --continue/--resume,
# so it starts with an empty context and must orient purely from disk + git (as
# ralph_prompt.txt instructs). This is the whole point: real cycles, real fresh
# context, not one long conversation. The previous stop-hook approach kept every
# "cycle" in the same window, which defeated that.
#
# Usage:
#   ./ralph.sh [max_cycles] [sleep_seconds]
#     max_cycles     number of iterations (default: 5; use 0 for unbounded)
#     sleep_seconds  pause between cycles (default: 10)
#
# Env overrides:
#   PROMPT_FILE   path to the cycle prompt (default: ralph_prompt.txt)
#   CLAUDE_BIN    claude executable (default: claude)
#
# Stop with Ctrl-C. A cycle that makes no commit is still a valid cycle (the
# agent may have judged there was nothing safe/valuable to do, or hit a blocker).

set -uo pipefail
cd "$(dirname "$0")" || exit 1

MAX_CYCLES="${1:-5}"
SLEEP_SECS="${2:-10}"
PROMPT_FILE="${PROMPT_FILE:-ralph_prompt.txt}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
LOG_DIR="ralph-logs"

if [ ! -f "$PROMPT_FILE" ]; then
    echo "ERROR: prompt file '$PROMPT_FILE' not found." >&2
    exit 1
fi
if ! command -v "$CLAUDE_BIN" >/dev/null 2>&1; then
    echo "ERROR: '$CLAUDE_BIN' not on PATH." >&2
    exit 1
fi
mkdir -p "$LOG_DIR"

PROMPT="$(cat "$PROMPT_FILE")"
echo "Ralph runner: prompt=$PROMPT_FILE  max_cycles=$MAX_CYCLES  sleep=${SLEEP_SECS}s"
echo "Each cycle is a fresh 'claude -p' with no shared context. Ctrl-C to stop."

cycle=0
while :; do
    cycle=$((cycle + 1))
    if [ "$MAX_CYCLES" -ne 0 ] && [ "$cycle" -gt "$MAX_CYCLES" ]; then
        echo "Reached max_cycles=$MAX_CYCLES. Stopping."
        break
    fi

    ts="$(date +%Y%m%d-%H%M%S)"
    log="$LOG_DIR/cycle-$cycle-$ts.log"
    head_before="$(git rev-parse HEAD 2>/dev/null || echo none)"

    echo "=================================================================="
    echo "Ralph cycle #$cycle  ($ts)  ->  $log"
    echo "=================================================================="

    # Fresh context each cycle: no --continue, no --resume.
    # --dangerously-skip-permissions is what makes it autonomous (runs on a
    # dedicated dev box; the agent can edit files, run gradle, and git push).
    "$CLAUDE_BIN" -p "$PROMPT" \
        --dangerously-skip-permissions \
        2>&1 | tee "$log"
    rc="${PIPESTATUS[0]}"

    head_after="$(git rev-parse HEAD 2>/dev/null || echo none)"
    if [ "$rc" -ne 0 ]; then
        echo "Cycle #$cycle: claude exited non-zero ($rc). See $log"
    elif [ "$head_before" != "$head_after" ]; then
        echo "Cycle #$cycle committed: ${head_before:0:9} -> ${head_after:0:9}"
    else
        echo "Cycle #$cycle: no commit (blocked, empty, or working-tree-only change)."
    fi

    if [ "$MAX_CYCLES" -eq 0 ] || [ "$cycle" -lt "$MAX_CYCLES" ]; then
        echo "Sleeping ${SLEEP_SECS}s before next cycle... (Ctrl-C to stop)"
        sleep "$SLEEP_SECS"
    fi
done
