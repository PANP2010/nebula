#!/bin/bash
# Nebula DAG perf harness — REAL command surface, this Linux box.
#
# Purpose: drive a repeatable, scalable redstone workload on the Folia test
# server and dump aggregated per-tick DAG cost from `/nebula perf`. This is the
# honest measurement path the "Next:" handoff asked for — a first slice toward a
# baseline-vs-Nebula MSPT comparison.
#
# Unlike the older run-smoke/folia-vs-nebula.sh (which targets commands that do
# not exist: `nebula stress-spawn`, `nebula bench`, a NebulaTickProbe, and
# hardcoded macOS paths), this script uses only the commands that actually
# exist: /nebula scan, /nebula perf. Redstone is placed and toggled via RCON.
#
# It measures NEBULA's DAG shadow overhead and GRADES it against the redefined
# DG1 Criterion 3 (docs/PROJECT_STATUS.md, decided 2026-07-08 — Path 2). Nebula
# is observe-only, so the old "≥30% MSPT reduction" is unreachable by
# construction; instead we grade the DAG shadow's ADDED per-tick cost against a
# budget: p99 DAG tick time must stay under OVERHEAD_BUDGET_MS (default 3ms) at
# the driven workload. There is no "nebula off" comparison because there is
# nothing the shadow replaces — see the DG1 Criterion 3 note in docs/PROJECT_STATUS.md.
#
# Usage:  scripts/perf-harness.sh [circuits] [toggles] [--keep-running]
#   circuits  number of independent redstone lines to place (default 8)
#   toggles   number of on/off toggle pairs to drive after warmup (default 60)
#   --keep-running  leave the server up at the end (default: stop it cleanly)
#
# Env overrides: RCON_PORT (25576), RCON_PW (nebulatest), SERVER_DIR,
#   OVERHEAD_BUDGET_MS (3.0 — the p99 budget; 6% of the 50ms/20-TPS tick,
#   tightened from the original 5ms placeholder after two verified runs both
#   showed p99 ~2ms; see the DG1 Criterion 3 note in docs/PROJECT_STATUS.md).
# Exit code: 0 if the verdict is PASS, 3 if FAIL, 4 if INCONCLUSIVE (no p99).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$REPO_ROOT/folia-test-server}"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PW="${RCON_PW:-nebulatest}"
OVERHEAD_BUDGET_MS="${OVERHEAD_BUDGET_MS:-3.0}"
JAR_SRC="$HOME/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar"

CIRCUITS="${1:-8}"
TOGGLES="${2:-60}"
KEEP_RUNNING=0
for a in "$@"; do [ "$a" = "--keep-running" ] && KEEP_RUNNING=1; done

RESULT_DIR="$REPO_ROOT/bench-results"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULT_FILE="$RESULT_DIR/perf-$STAMP.txt"

R() { mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PW" "$1" 2>&1; }

command -v mcrcon >/dev/null || { echo "ERROR: mcrcon not on PATH"; exit 1; }
[ -f "$SERVER_DIR/start.sh" ] || { echo "ERROR: $SERVER_DIR/start.sh missing"; exit 1; }

# --- Deploy the freshest shaded jar (built separately with Java 21) ---
if [ -f "$JAR_SRC" ]; then
    cp "$JAR_SRC" "$SERVER_DIR/plugins/nebula-plugin-0.1.0-SNAPSHOT.jar"
    echo "Deployed fresh plugin jar."
else
    echo "WARN: built jar not found at $JAR_SRC — using whatever is in plugins/."
    echo "      Build first: JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :nebula-plugin:shadowJar"
fi

# --- Is the server already up? If not, start it detached. ---
started_here=0
if R "list" | grep -q "players online"; then
    echo "Server already running — reusing it."
else
    echo "Starting Folia test server (detached)..."
    ( cd "$SERVER_DIR" && setsid ./start.sh > server-run.log 2>&1 < /dev/null & disown )
    started_here=1
    for i in $(seq 1 90); do
        sleep 1
        R "list" 2>/dev/null | grep -q "players online" && { echo "Booted in ~${i}s."; break; }
        [ "$i" = "90" ] && { echo "ERROR: server did not boot in 90s"; tail -5 "$SERVER_DIR/server-run.log"; exit 2; }
    done
fi

# --- Build a scalable multi-region workload. ---
# Each circuit is a redstone_block source + a 15-long dust line + lamp, placed
# far apart (spaced by 512 blocks) so Folia assigns them to different regions.
# The source block is what we toggle; y=-60 sits on the flat-world floor.
echo "Placing $CIRCUITS redstone circuits (spaced across regions)..."
declare -a SRC_X SRC_Z
for c in $(seq 0 $((CIRCUITS - 1))); do
    bx=$((c * 512))
    bz=0
    SRC_X[$c]=$bx
    SRC_Z[$c]=$bz
    R "forceload add $bx $((bz - 2)) $((bx + 20)) $((bz + 2))" >/dev/null
    R "setblock $bx -60 $bz minecraft:redstone_block" >/dev/null
    for i in $(seq 1 15); do
        R "setblock $((bx + i)) -60 $bz minecraft:redstone_wire" >/dev/null
    done
    R "setblock $((bx + 16)) -60 $bz minecraft:redstone_lamp" >/dev/null
done
sleep 2

echo "Scanning for components..."
R "nebula scan" | sed 's/§[0-9a-fk-or;]*//g'
sleep 3
echo "Status after scan:"
R "nebula status" | sed 's/§[0-9a-fk-or;]*//g'

# --- Warm up the JIT, then reset metrics so the cold-start outlier is excluded. ---
echo "Warmup toggles (10 pairs)..."
for _ in $(seq 1 10); do
    for c in $(seq 0 $((CIRCUITS - 1))); do
        R "setblock ${SRC_X[$c]} -60 ${SRC_Z[$c]} minecraft:air" >/dev/null
        R "setblock ${SRC_X[$c]} -60 ${SRC_Z[$c]} minecraft:redstone_block" >/dev/null
    done
done
R "nebula perf reset" >/dev/null
echo "Metrics reset after warmup."

# --- Measured window. ---
echo "Driving $TOGGLES toggle pairs across $CIRCUITS circuits..."
for _ in $(seq 1 "$TOGGLES"); do
    for c in $(seq 0 $((CIRCUITS - 1))); do
        R "setblock ${SRC_X[$c]} -60 ${SRC_Z[$c]} minecraft:air" >/dev/null
        R "setblock ${SRC_X[$c]} -60 ${SRC_Z[$c]} minecraft:redstone_block" >/dev/null
    done
done
sleep 2

# --- Collect. ---
PERF_RAW="$(R 'nebula perf' | sed 's/§[0-9a-fk-or;]*//g')"
STATUS_RAW="$(R 'nebula status' | sed 's/§[0-9a-fk-or;]*//g')"

{
    echo "=== Nebula DAG perf harness ==="
    echo "Date:     $(date)"
    echo "Circuits: $CIRCUITS  (spaced 512 blocks apart)"
    echo "Toggles:  $TOGGLES pairs (post-warmup, post-reset)"
    echo
    echo "--- /nebula status ---"
    echo "$STATUS_RAW"
    echo
    echo "--- /nebula perf ---"
    echo "$PERF_RAW"
} | tee "$RESULT_FILE"

# --- Grade against the redefined DG1 Criterion 3 (shadow-overhead budget). ---
# Pull the p99 figure out of the "p50 X  p95 Y  p99 Z ms" line. Color codes were
# already stripped from PERF_RAW above.
# mcrcon renders Minecraft §-codes as ANSI terminal codes, so strip ANSI here
# (the §-sed above does not touch them) before matching numbers.
PERF_CLEAN="$(printf '%s\n' "$PERF_RAW" | sed 's/\x1b\[[0-9;]*m//g')"
P99="$(printf '%s\n' "$PERF_CLEAN" | grep -oE 'p99[[:space:]]+[0-9]+\.[0-9]+' | grep -oE '[0-9]+\.[0-9]+' | tail -1)"
TICKS="$(printf '%s\n' "$PERF_CLEAN" | grep -oE 'Ticks recorded:[[:space:]]+[0-9]+' | grep -oE '[0-9]+' | tail -1)"

verdict=""
verdict_code=0
if [ -z "$P99" ] || [ -z "$TICKS" ] || [ "${TICKS:-0}" -eq 0 ]; then
    verdict="INCONCLUSIVE — no DAG ticks recorded (did /nebula scan find components? was the workload toggled?)"
    verdict_code=4
elif awk "BEGIN{exit !($P99 < $OVERHEAD_BUDGET_MS)}"; then
    verdict="PASS — p99 DAG shadow overhead ${P99}ms < budget ${OVERHEAD_BUDGET_MS}ms (${TICKS} ticks, $CIRCUITS circuits)"
    verdict_code=0
else
    verdict="FAIL — p99 DAG shadow overhead ${P99}ms >= budget ${OVERHEAD_BUDGET_MS}ms (${TICKS} ticks, $CIRCUITS circuits)"
    verdict_code=3
fi

{
    echo
    echo "--- DG1 Criterion 3 verdict (shadow-overhead budget, Path 2) ---"
    echo "Budget:  p99 DAG tick < ${OVERHEAD_BUDGET_MS} ms"
    echo "Verdict: $verdict"
} | tee -a "$RESULT_FILE"

# --- Teardown. ---
if [ "$KEEP_RUNNING" -eq 0 ] && [ "$started_here" -eq 1 ]; then
    echo "Stopping server cleanly..."
    R "stop" >/dev/null
    for i in $(seq 1 40); do
        sleep 1
        pgrep -f "$SERVER_DIR.*server.jar" >/dev/null 2>&1 || { echo "Stopped."; break; }
    done
fi

echo "Result written to: $RESULT_FILE"
exit "$verdict_code"

# LIMITATIONS (honest):
#  - This measures NEBULA's DAG shadow overhead and grades it against a budget.
#    It deliberately does NOT attempt a baseline-Folia-vs-Nebula MSPT comparison:
#    per the DG1 Criterion 3 decision (Path 2, 2026-07-08, docs/PROJECT_STATUS.md),
#    Nebula is observe-only, so the shadow replaces no serial work and there is
#    nothing to compare against — only added overhead to bound.
#  - The p99 budget (OVERHEAD_BUDGET_MS, default 3ms = 6% of the 50ms tick) is a
#    starting bound, not a physics constant; tighten it further as optimization
#    lands. It was lowered from 5ms to 3ms on 2026-07-08 after two verified runs
#    (small: p99 2.002ms/1200 ticks; large: p99 1.914ms/7097 ticks, 16 circuits)
#    both landed near 2ms, leaving 5ms too loose to catch a real regression.
#  - RCON setblock does not fire BlockPlaceEvent, hence the /nebula scan step.
#  - "Regions" spacing assumes Folia assigns distant chunks to distinct region
#    threads; with no players, idle-region gating may reduce ticking. Treat the
#    circuit count as a workload knob, not a guaranteed region count.
