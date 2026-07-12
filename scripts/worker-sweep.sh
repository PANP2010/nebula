#!/bin/bash
# Nebula B9 D5 worker-count invariance sweep — N1.1 + N1.2
#
# PURPOSE: prove "matched==total is invariant to worker count N".
# Paper is the single-thread oracle: its /nebula diff result is ground truth.
# For each N in {2,4,8,12} (and inline-serial 0), this script:
#   1. Starts Paper with -Dnebula.dag.parallel=true -Dnebula.dag.workers=$N
#   2. Places 2 independent redstone circuits (cross-chunk for genuine concurrency)
#   3. Runs /nebula scan (populates componentMap + stateHasher.trackedPositions)
#   4. Toggles each lever ON
#   5. Runs /nebula diff — parses "matched X / total Y"
#   6. Toggles OFF, runs /nebula diff again
#   7. Stops server
#
# VERDICT per N:
#   ON and OFF both matched==total → PASS for this N
#   Any mismatch → FAIL for this N (race or correctness bug at this N)
#
# USAGE:
#   scripts/worker-sweep.sh [--workers N[,N...]] [--circuits N] [--keep-running]
#   defaults: workers=0,2,4,8,12; circuits=2
#
# Exit code: 0 if ALL N pass, 3 if ANY N fails, 4 if inconclusive.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAPER_DIR="${PAPER_DIR:-$REPO_ROOT/paper-test-server}"
JAR_SRC="$HOME/.gradle/nebula-server-build/nebula-plugin/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar"
PAPER_PLUGIN_DIR="$PAPER_DIR/plugins/nebula-plugin"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PW="${RCON_PW:-nebulatest}"
SERVER_LOG="$PAPER_DIR/server-run.log"

# Parse args
WORKERS_STR=""
CIRCUITS=2
KEEP_RUNNING=0
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        --workers) WORKERS_STR="$2"; shift 2 ;;
        --circuits) CIRCUITS="$2"; shift 2 ;;
        --keep-running) KEEP_RUNNING=1; shift ;;
        --*) echo "ERROR: unknown option $1"; exit 1 ;;
        *) POSITIONAL+=("$1"); shift ;;
    esac
done

# Default sweep: inline-serial (N=0) then N=2,4,8,12
if [ -z "$WORKERS_STR" ]; then
    WORKER_COUNTS=(0 2 4 8 12)
else
    IFS=',' read -ra WORKER_COUNTS <<< "$WORKERS_STR"
fi

R() { mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PW" "$1" 2>&1; }
strip() { sed 's/§[0-9a-fk-or;]*//g; s/\x1b\[[0-9;]*m//g'; }

command -v mcrcon >/dev/null || { echo "ERROR: mcrcon not on PATH"; exit 1; }
[ -f "$PAPER_DIR/start.sh" ] || { echo "ERROR: $PAPER_DIR/start.sh missing"; exit 1; }

echo "=== Nebula B9 D5 Worker-Count Invariance Sweep ==="
echo "Workers: ${WORKER_COUNTS[*]}"
echo "Circuits: $CIRCUITS"
echo ""

RESULT_DIR="$REPO_ROOT/bench-results/worker-sweep"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="$RESULT_DIR/sweep-$STAMP.txt"

# --- Deploy plugin jar ---
if [ -f "$JAR_SRC" ]; then
    mkdir -p "$PAPER_PLUGIN_DIR"
    cp "$JAR_SRC" "$PAPER_PLUGIN_DIR/nebula-plugin-0.1.0-SNAPSHOT.jar"
    echo "Deployed: $JAR_SRC → $PAPER_PLUGIN_DIR/"
else
    echo "WARN: plugin jar not found at $JAR_SRC"
fi

# --- Place circuits once (we'll restart the server per worker count, but
#     the world persists, so this placement is shared across all sweeps) ---
place_circuits() {
    echo "Placing $CIRCUITS redstone circuits (cross-chunk, spaced 512 blocks apart)..."
    for c in $(seq 0 $((CIRCUITS - 1))); do
        bx=$((c * 512)); bz=0
        # stone + lever (unpowered at start)
        R "setblock $bx -60 $bz minecraft:stone" >/dev/null
        R "setblock $bx -59 $bz minecraft:lever[face=floor,powered=false]" >/dev/null
        # 15-wire line
        for i in $(seq 1 15); do
            R "setblock $((bx + i)) -59 $bz minecraft:redstone_wire" >/dev/null
        done
        # redstone lamp
        R "setblock $((bx + 16)) -59 $bz minecraft:redstone_lamp" >/dev/null
        # also place a torch at the start for a second toggle source
        R "setblock $((bx + 17)) -59 $bz minecraft:redstone_torch" >/dev/null
    done
    sleep 2
    echo "Circuit placement done."
}

# --- Start Paper with given worker count ---
start_paper() {
    local workers="$1"  # 0 = inline-serial
    echo ""
    echo ">>> Starting Paper with workers=$workers"

    # Set env vars for start.sh
    if [ "$workers" -eq 0 ]; then
        export NEBULA_DAG_PARALLEL=false
        unset NEBULA_DAG_WORKERS
    else
        export NEBULA_DAG_PARALLEL=true
        export NEBULA_DAG_WORKERS="$workers"
    fi

    # Kill any existing server
    if pgrep -f "$PAPER_DIR/server.jar" >/dev/null 2>&1; then
        echo "Stopping existing Paper server..."
        R "stop" >/dev/null 2>&1 || true
        sleep 5
        # Force kill if still alive
        pkill -f "$PAPER_DIR/server.jar" 2>/dev/null || true
        sleep 3
    fi

    # Start fresh
    (
        cd "$PAPER_DIR"
        setsid ./start.sh > "$SERVER_LOG" 2>&1 < /dev/null & disown
    )

    # Wait for boot
    for i in $(seq 1 90); do
        sleep 1
        R "list" 2>/dev/null | grep -q "players online" && { echo "Booted in ~${i}s"; return 0; }
        [ "$i" = "90" ] && { echo "ERROR: Paper did not boot in 90s"; tail -10 "$SERVER_LOG"; return 1; }
    done
}

# --- Scan + diff + parse ---
# Returns: "matched=X/total=Y"
run_diff() {
    local label="$1"
    local out
    out="$(R 'nebula diff' | strip)"
    echo "[$label] /nebula diff output:"
    echo "$out"
    # Parse: look for "matched X / total Y"
    local matched total
    matched="$(echo "$out" | grep -oP 'matched\s+\K\d+' | head -1)"
    total="$(echo "$out" | grep -oP 'total\s+\K\d+' | head -1)"
    if [ -z "$matched" ] || [ -z "$total" ]; then
        echo "WARN: could not parse matched/total from output"
        matched="?"; total="?"
    fi
    echo "$matched $total"
}

# --- Main sweep loop ---
declare -A RESULTS
PASS_COUNT=0
FAIL_COUNT=0

FIRST_RUN=0

for N in "${WORKER_COUNTS[@]}"; do
    WORKER_LABEL="$N"
    [ "$N" -eq 0 ] && WORKER_LABEL="inline-serial"

    # Start Paper (first run: place circuits too)
    start_paper "$N" || { echo "ERROR: failed to start Paper for N=$N"; FAIL_COUNT=$((FAIL_COUNT+1)); continue; }

    # Scan and diff are idempotent; scan is needed after fresh start
    echo "Scanning..."
    R "nebula scan" | strip
    sleep 3

    if [ "$FIRST_RUN" -eq 0 ]; then
        place_circuits
        FIRST_RUN=1
        # Re-scan after placement
        R "nebula scan" | strip
        sleep 3
    fi

    # --- ON toggle ---
    echo ""
    echo ">>> ON toggle (workers=$WORKER_LABEL)"
    for c in $(seq 0 $((CIRCUITS - 1))); do
        bx=$((c * 512))
        R "setblock $bx -59 0 minecraft:lever[face=floor,powered=true]" >/dev/null
    done
    sleep 3  # let circuits settle

    ON_RESULT="$(run_diff "ON-N$N")"
    ON_MATCHED="${ON_RESULT%% *}"
    ON_TOTAL="${ON_RESULT#* }"

    # --- OFF toggle ---
    echo ""
    echo ">>> OFF toggle (workers=$WORKER_LABEL)"
    for c in $(seq 0 $((CIRCUITS - 1))); do
        bx=$((c * 512))
        R "setblock $bx -59 0 minecraft:lever[face=floor,powered=false]" >/dev/null
    done
    sleep 3

    OFF_RESULT="$(run_diff "OFF-N$N")"
    OFF_MATCHED="${OFF_RESULT%% *}"
    OFF_TOTAL="${OFF_RESULT#* }"

    # --- Grade ---
    ON_PASS="FAIL"; OFF_PASS="FAIL"
    if [ "$ON_MATCHED" = "$ON_TOTAL" ] && [ "$ON_MATCHED" != "?" ]; then
        ON_PASS="PASS"; PASS_COUNT=$((PASS_COUNT+1))
    else
        FAIL_COUNT=$((FAIL_COUNT+1))
    fi
    if [ "$OFF_MATCHED" = "$OFF_TOTAL" ] && [ "$OFF_MATCHED" != "?" ]; then
        OFF_PASS="PASS"; PASS_COUNT=$((PASS_COUNT+1))
    else
        FAIL_COUNT=$((FAIL_COUNT+1))
    fi

    RESULTS["$N"]="ON=$ON_MATCHED/$ON_TOTAL($ON_PASS) OFF=$OFF_MATCHED/$OFF_TOTAL($OFF_PASS)"

    echo ""
    echo ">>> N=$WORKER_LABEL: ON $ON_MATCHED/$ON_TOTAL [$ON_PASS]  OFF $OFF_MATCHED/$OFF_TOTAL [$OFF_PASS]"

    # Stop server (keep running only if --keep-running AND this is the last iteration)
    if [ "$KEEP_RUNNING" -eq 1 ] && [ "$N" = "${WORKER_COUNTS[-1]}" ]; then
        echo "Keeping server running."
    else
        echo "Stopping Paper..."
        R "stop" >/dev/null 2>&1 || true
        sleep 5
        pkill -f "$PAPER_DIR/server.jar" 2>/dev/null || true
        sleep 3
    fi
done

# --- Summary ---
echo ""
echo "=========================================="
echo "  WORKER-COUNT INVARIANCE SWEEP SUMMARY"
echo "=========================================="
echo ""
printf "%-20s %-10s %-10s\n" "Workers" "ON result" "OFF result"
printf "%-20s %-10s %-10s\n" "-----------" "---------""---------"
for N in "${WORKER_COUNTS[@]}"; do
    WORKER_LABEL="$N"
    [ "$N" -eq 0 ] && WORKER_LABEL="inline-serial"
    printf "%-20s %-10s\n" "$WORKER_LABEL" "${RESULTS[$N]}"
done
echo ""

ALL_PASS=1
for N in "${WORKER_COUNTS[@]}"; do
    if [[ "${RESULTS[$N]}" != *"FAIL"* ]]; then
        : # pass
    else
        ALL_PASS=0
    fi
done

if [ "$ALL_PASS" -eq 1 ]; then
    echo "VERDICT: ALL PASS — matched==total invariant to worker count"
    VERDICT="PASS"
    VERDICT_CODE=0
else
    echo "VERDICT: SOME FAILURES — worker-count invariant BROKEN"
    echo "  Review failures above for details."
    VERDICT="FAIL"
    VERDICT_CODE=3
fi

{
    echo "=== Nebula B9 D5 Worker-Count Invariance Sweep ==="
    echo "Date: $(date)"
    echo "Workers: ${WORKER_COUNTS[*]}"
    echo "Circuits: $CIRCUITS"
    echo ""
    printf "%-20s %-30s %-30s\n" "Workers" "ON" "OFF"
    printf "%-20s %-30s %-30s\n" "-------" "--" "---"
    for N in "${WORKER_COUNTS[@]}"; do
        WORKER_LABEL="$N"
        [ "$N" -eq 0 ] && WORKER_LABEL="inline-serial"
        printf "%-20s %-30s\n" "$WORKER_LABEL" "${RESULTS[$N]}"
    done
    echo ""
    echo "VERDICT: $VERDICT"
} | tee "$REPORT"

echo "Report: $REPORT"
exit "$VERDICT_CODE"
