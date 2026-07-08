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
# It measures NEBULA's DAG tick cost. A true baseline-Folia comparison needs a
# nebula-off mode that does not exist yet (see LIMITATIONS at bottom) — this
# slice establishes the repeatable Nebula-side measurement first.
#
# Usage:  scripts/perf-harness.sh [circuits] [toggles] [--keep-running]
#   circuits  number of independent redstone lines to place (default 8)
#   toggles   number of on/off toggle pairs to drive after warmup (default 60)
#   --keep-running  leave the server up at the end (default: stop it cleanly)
#
# Env overrides: RCON_PORT (25576), RCON_PW (nebulatest), SERVER_DIR.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$REPO_ROOT/folia-test-server}"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PW="${RCON_PW:-nebulatest}"
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

# LIMITATIONS (honest):
#  - This measures NEBULA's DAG tick cost only. A real baseline-Folia-vs-Nebula
#    MSPT comparison needs a "nebula off" mode (vanilla Folia redstone with the
#    plugin inert) that does not exist yet — that is the NEXT slice.
#  - RCON setblock does not fire BlockPlaceEvent, hence the /nebula scan step.
#  - "Regions" spacing assumes Folia assigns distant chunks to distinct region
#    threads; with no players, idle-region gating may reduce ticking. Treat the
#    circuit count as a workload knob, not a guaranteed region count.
