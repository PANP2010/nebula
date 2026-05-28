#!/bin/bash
# Folia vs Nebula head-to-head bench harness.
#
# Boots the SAME jar twice with identical workloads:
#   1) Folia path  (no -Dnebula.mode flag)
#   2) Nebula path (-Dnebula.mode=true -Dnebula.parallel=true)
#
# Both runs execute the same /nebula stress-spawn N command. The
# NebulaTickProbe records per-region tick wall-clock time in BOTH modes
# (it hooks MinecraftServer.tickChildren). After RUN_SECONDS,
# /nebula bench dumps avg / p50 / p95 / p99 / max MSPT.
#
# Output: bench-results/folia-vs-nebula.txt
#
# Caveats:
#   - Folia idle-region optimisations may make the Folia number better
#     when there are no real players (ticking entities are gated). The
#     stress mode forces full ticks in Nebula but Folia's tickChildren
#     still runs every region tick, so wall-clock samples are valid for
#     both. Just be aware Folia's path may execute much less work per
#     tick under no-player conditions.
#   - This script does NOT spawn fake players. Use a fake-player tool
#     (eg. ProtocolLib bot or jstub) for a load-realistic comparison.
#
# Usage: folia-vs-nebula.sh [<entity-count>] [<run-seconds>]
set -uo pipefail

ENTITY_COUNT="${1:-250}"
RUN_SECONDS="${2:-60}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"

SANDBOX="/Users/panjiyang/nebula_server/run-smoke"
JAVA_HOME="/Users/panjiyang/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
RESULT_DIR="$SANDBOX/bench-results"
RESULT_FILE="$RESULT_DIR/folia-vs-nebula.txt"

cd "$SANDBOX" || exit 1
mkdir -p "$RESULT_DIR"

run_one() {
    local label="$1"
    local scheduler_type="$2"  # NEBULA | EDF | WORK_STEALING
    shift 2
    local jvm_flags=("$@")
    local log_file="$RESULT_DIR/${label}.log"

    pkill -f "nebula.jar" 2>/dev/null
    sleep 2
    rm -f world/session.lock 2>/dev/null

    # Switch the threaded-regions scheduler in paper-global.yml so this run
    # actually exercises the requested code path.
    if [ -f config/paper-global.yml ]; then
        sed -i.bak -E "s/^(  scheduler:).*/\\1 ${scheduler_type}/" config/paper-global.yml
    fi

    [ -p cmd.fifo ] && rm -f cmd.fifo
    mkfifo cmd.fifo
    ( sleep 86400 > cmd.fifo ) &
    local holder_pid=$!

    "$JAVA_HOME/bin/java" -Xms2G -Xmx4G \
        -Dpaper.disableChannelLimit=true \
        ${jvm_flags[@]+"${jvm_flags[@]}"} \
        -jar nebula.jar --nogui < cmd.fifo > "$log_file" 2>&1 &
    local server_pid=$!

    cmd() { echo "$1" > cmd.fifo; }

    echo "[$label] waiting for server boot..."
    for i in $(seq 1 120); do
        if grep -qE "INFO\]: Done \(" "$log_file" 2>/dev/null; then
            echo "[$label] booted in ${i}s"
            break
        fi
        sleep 1
        if ! kill -0 $server_pid 2>/dev/null; then
            echo "[$label] server died during boot — see $log_file"
            kill $holder_pid 2>/dev/null
            return 2
        fi
    done

    cmd "forceload add -64 -64 64 64"
    sleep 3
    # nebula commands work in both modes (in folia mode they print
    # "Nebula mode is disabled" but probe still records). The
    # stress-spawn in Folia mode just adds entities normally.
    cmd "nebula stress-mode on"
    sleep 1
    cmd "nebula stress-spawn $ENTITY_COUNT"
    sleep 5

    echo "[$label] warming up ${WARMUP_SECONDS}s..."
    sleep "$WARMUP_SECONDS"

    cmd "nebula reset"
    sleep 1

    echo "[$label] measuring ${RUN_SECONDS}s..."
    sleep "$RUN_SECONDS"

    cmd "nebula bench"
    sleep 2
    cmd "nebula stats"
    sleep 1

    echo "[$label] stopping server..."
    cmd "stop"
    for i in $(seq 1 30); do
        if ! kill -0 $server_pid 2>/dev/null; then break; fi
        sleep 1
    done
    kill $server_pid 2>/dev/null
    kill $holder_pid 2>/dev/null
    wait 2>/dev/null

    # Capture bench probe output.
    grep -E "Nebula tick probe|Nebula DAG metrics" "$log_file" | tail -5
}

{
    echo "=== Folia vs Nebula head-to-head ==="
    echo "Entity count: $ENTITY_COUNT, run seconds: $RUN_SECONDS, warmup: ${WARMUP_SECONDS}s"
    echo "Date: $(date)"
    echo

    echo "--- Folia path (paper-global.yml scheduler=EDF) ---"
    folia_out=$(run_one folia-baseline EDF)
    echo "$folia_out"
    echo

    echo "--- Nebula path (paper-global.yml scheduler=NEBULA + -Dnebula.parallel=true) ---"
    nebula_out=$(run_one nebula-best NEBULA -Dnebula.mode=true -Dnebula.parallel=true)
    echo "$nebula_out"
    echo

    echo "--- Comparison ---"
    folia_avg=$(echo "$folia_out" | grep -oE "avg=[0-9.]+ms" | head -1 | grep -oE "[0-9.]+")
    nebula_avg=$(echo "$nebula_out" | grep -oE "avg=[0-9.]+ms" | head -1 | grep -oE "[0-9.]+")
    if [ -n "$folia_avg" ] && [ -n "$nebula_avg" ]; then
        echo "Folia avg MSPT:  ${folia_avg}ms"
        echo "Nebula avg MSPT: ${nebula_avg}ms"
        delta=$(awk "BEGIN { printf \"%.1f\", ($nebula_avg - $folia_avg) / $folia_avg * 100 }")
        echo "Delta: ${delta}% (Nebula vs Folia, lower is better)"
    fi
} > "$RESULT_FILE"

echo "Result: $RESULT_FILE"
cat "$RESULT_FILE"
