#!/bin/bash
# Nebula Folia-vs-Nebula divergence grader — REAL command surface, this Linux box.
#
# Purpose: run the DECISIVE experiment the driven zero-diff harness does NOT do.
# scripts/zerodiff-harness.sh --drive proves the DAG shadow is SELF-consistent
# (two same-seed runs emit byte-identical .nrp files — a Nebula-vs-Nebula check).
# It cannot tell you whether Nebula's shadow trajectory MATCHES Folia's own
# authoritative redstone. This script closes that gap using the signal
# /nebula diag already emits: each CASCADE-DIAG line carries, per seed,
# `cas=X→nms=Y (settled|dirty)`, where X is what Nebula's DAG shadow last wrote
# and Y is Folia's authoritative power freshly pulled by syncFromNms. `dirty`
# (X≠Y) is a point where the shadow disagreed with Folia.
#
# METHOD:
#   1. Place a lever-headed redstone circuit (default 1) and /nebula scan it.
#   2. /nebula diag on so every executeOwnedDag invocation logs a CASCADE-DIAG line.
#   3. Run ONE seed-deterministic driven capture (reuses `nebula capture start
#      --drive`) so the DAG runs under a reproducible live toggle stream and emits
#      a stream of per-seed cas→nms samples.
#   4. /nebula diag off, then pipe server-run.log through the TESTED grader
#      (org.nebula.replay.FoliaDivergenceGraderCli — same parse+grade the unit
#      tests exercise, NOT a bash re-implementation) which skips a cold-CAS
#      converge window and grades the residual dirty rate PASS/FAIL.
#
# HONEST LIMITATION (baked into the grader javadoc too): Nebula is observe-only on
# the redstone path, so a seed sampled AT a toggle boundary reads `dirty` from a
# one-cycle observe lag, not a bug — it catches up via syncToNms the same
# invocation. Under a driven square wave a steady stream of boundary-dirty samples
# is EXPECTED. The load-bearing signal is SUSTAINED divergence that does not settle
# after the cold-CAS transient, which is what the residual-rate grade targets. A
# low residual rate is NECESSARY-BUT-NOT-SUFFICIENT for Folia-vs-Nebula agreement;
# cleanly separating boundary lag from a real bug needs settled-state sampling (a
# later slice). Treat MAX_DIRTY_RATE as a loose starting bound, not a physics
# constant, and tune CONVERGE_WINDOW to the circuit's settle time.
#
# Usage:  scripts/divergence-grade.sh [ticks] [circuits] [--seed <n>] [--period <n>] \
#            [--converge <n>] [--max-dirty <rate>] [--keep-running]
#   ticks       driven ticks to capture (default 400 — start small, per the Next: pointer)
#   circuits    lever-headed circuits to place (default 1)
#   --seed      driver seed (default 42)
#   --period    per-source flip period in ticks (default 8)
#   --converge  leading invocations to skip as the cold-CAS transient (default 8)
#   --max-dirty residual dirty-rate pass threshold in [0,1] (default 0.05)
#   --keep-running  leave the server up at the end (default: stop cleanly)
#
# Env overrides: RCON_PORT (25576), RCON_PW (nebulatest), SERVER_DIR,
#   CAP_TIMEOUT_S (per-run wait cap; default ticks/20 * 3 + 120s).
# Exit code: mirrors the grader CLI — 0 PASS, 3 FAIL, 4 INCONCLUSIVE, 2 usage/IO.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$REPO_ROOT/folia-test-server}"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PW="${RCON_PW:-nebulatest}"
JAR_SRC="$HOME/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar"
SERVER_LOG="$SERVER_DIR/server-run.log"
JAVA21="${JAVA21:-/usr/lib/jvm/java-21-openjdk-amd64}"

# The tested grader runs from the Gradle-built class dirs (relocation-free — the
# shaded plugin jar relocates org.nebula.core, which would break the grader's
# WorldPos reference). Keep these in sync with the Gradle build output layout.
BUILD_ROOT="$HOME/.gradle/nebula-server-build/nebula-server"
GRADER_CP="$BUILD_ROOT/nebula-replay/classes/java/main:$BUILD_ROOT/nebula-core/classes/java/main"
GRADER_CLASS="org.nebula.replay.FoliaDivergenceGraderCli"

# --- Args ---
TICKS=""; CIRCUITS=""
SEED=42; PERIOD=8; CONVERGE=8; MAX_DIRTY=0.05; KEEP_RUNNING=0
args=("$@"); _i=1; _pos=0
while [ "$_i" -le "$#" ]; do
    a="${args[$((_i-1))]}"
    case "$a" in
        --seed)      _i=$((_i+1)); SEED="${args[$((_i-1))]:-}" ;;
        --period)    _i=$((_i+1)); PERIOD="${args[$((_i-1))]:-}" ;;
        --converge)  _i=$((_i+1)); CONVERGE="${args[$((_i-1))]:-}" ;;
        --max-dirty) _i=$((_i+1)); MAX_DIRTY="${args[$((_i-1))]:-}" ;;
        --keep-running) KEEP_RUNNING=1 ;;
        --*) echo "ERROR: unknown option $a"; exit 2 ;;
        *) if [ "$_pos" -eq 0 ]; then TICKS="$a"; elif [ "$_pos" -eq 1 ]; then CIRCUITS="$a"; fi
           _pos=$((_pos+1)) ;;
    esac
    _i=$((_i+1))
done
TICKS="${TICKS:-400}"
CIRCUITS="${CIRCUITS:-1}"
CAP_TIMEOUT_S="${CAP_TIMEOUT_S:-$(( TICKS / 20 * 3 + 120 ))}"

RESULT_DIR="$REPO_ROOT/bench-results"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULT_FILE="$RESULT_DIR/divergence-$STAMP.txt"

R() { mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PW" "$1" 2>&1; }
strip() { sed 's/§[0-9a-fk-or;]*//g; s/\x1b\[[0-9;]*m//g'; }

command -v mcrcon >/dev/null || { echo "ERROR: mcrcon not on PATH"; exit 2; }
[ -f "$SERVER_DIR/start.sh" ] || { echo "ERROR: $SERVER_DIR/start.sh missing"; exit 2; }
[ -d "$BUILD_ROOT/nebula-replay/classes/java/main" ] || {
    echo "ERROR: grader classes not built. Build first:"
    echo "  JAVA_HOME=$JAVA21 ./gradlew :nebula-replay:classes"
    exit 2
}

# --- Deploy the freshest shaded jar (built separately with Java 21). ---
if [ -f "$JAR_SRC" ]; then
    cp "$JAR_SRC" "$SERVER_DIR/plugins/nebula-plugin-0.1.0-SNAPSHOT.jar"
    echo "Deployed fresh plugin jar."
else
    echo "WARN: built jar not found at $JAR_SRC — using whatever is in plugins/."
    echo "      Build first: JAVA_HOME=$JAVA21 ./gradlew :nebula-plugin:shadowJar"
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
        [ "$i" = "90" ] && { echo "ERROR: server did not boot in 90s"; tail -5 "$SERVER_LOG"; exit 2; }
    done
fi

# --- Build a lever-headed workload (a real toggle source per region). ---
echo "Placing $CIRCUITS lever-headed redstone circuit(s)..."
for c in $(seq 0 $((CIRCUITS - 1))); do
    bx=$((c * 512)); bz=0
    R "forceload add $bx $((bz - 2)) $((bx + 20)) $((bz + 2))" >/dev/null
    R "setblock $bx -61 $bz minecraft:stone" >/dev/null
    R "setblock $bx -60 $bz minecraft:lever[face=floor,powered=false]" >/dev/null
    for i in $(seq 1 15); do
        R "setblock $((bx + i)) -60 $bz minecraft:redstone_wire" >/dev/null
    done
    R "setblock $((bx + 16)) -60 $bz minecraft:redstone_lamp" >/dev/null
done
sleep 2

echo "Scanning for components..."
R "nebula scan" | strip
sleep 3
STATUS_RAW="$(R 'nebula status' | strip)"
echo "$STATUS_RAW"

if echo "$STATUS_RAW" | grep -qE "Toggle sources.*: 0$"; then
    echo "ERROR: /nebula scan registered 0 toggle sources — cannot drive. Aborting."
    [ "$KEEP_RUNNING" -eq 0 ] && [ "$started_here" -eq 1 ] && R stop >/dev/null
    exit 4
fi

# --- Enable the divergence diagnostic, then run one driven capture. ---
echo "Enabling cascade diagnostic..."
R "nebula diag on" | strip
MARKER_BEFORE="$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)"

echo "Driving one $TICKS-tick capture (seed=$SEED period=$PERIOD)..."
R "nebula capture start $TICKS --drive $SEED --period $PERIOD" | strip

done_ok=0
for _ in $(seq 1 "$CAP_TIMEOUT_S"); do
    sleep 1
    if tail -n +"$MARKER_BEFORE" "$SERVER_LOG" 2>/dev/null \
         | grep -q "FoliaCaptureHarness stopped: captured $TICKS ticks"; then
        done_ok=1; break
    fi
done
R "nebula capture stop" | strip >/dev/null 2>&1
echo "Disabling cascade diagnostic..."
R "nebula diag off" | strip

if [ "$done_ok" -ne 1 ]; then
    echo "ERROR: driven capture did not reach $TICKS ticks within ${CAP_TIMEOUT_S}s."
    [ "$KEEP_RUNNING" -eq 0 ] && [ "$started_here" -eq 1 ] && R stop >/dev/null
    exit 4
fi

# --- Slice out just this run's CASCADE-DIAG lines and grade them. ---
DIAG_SLICE="$RESULT_DIR/divergence-$STAMP.diaglog"
tail -n +"$MARKER_BEFORE" "$SERVER_LOG" | grep "CASCADE-DIAG" > "$DIAG_SLICE" || true
DIAG_COUNT="$(wc -l < "$DIAG_SLICE" | tr -d ' ')"
echo "Captured $DIAG_COUNT CASCADE-DIAG lines from this run."

echo "Grading with the tested FoliaDivergenceGrader (converge=$CONVERGE max-dirty=$MAX_DIRTY)..."
GRADE_OUT="$("$JAVA21/bin/java" -cp "$GRADER_CP" "$GRADER_CLASS" "$DIAG_SLICE" "$CONVERGE" "$MAX_DIRTY" 2>&1)"
grade_code=$?

{
    echo "=== Nebula Folia-vs-Nebula divergence grade ==="
    echo "Date:     $(date)"
    echo "Ticks:    $TICKS driven (seed=$SEED period=$PERIOD)"
    echo "Circuits: $CIRCUITS  (lever-headed)"
    echo "Diag lines graded: $DIAG_COUNT  (raw slice: $DIAG_SLICE)"
    echo
    echo "--- /nebula status (post-scan) ---"
    echo "$STATUS_RAW"
    echo
    echo "$GRADE_OUT"
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
exit "$grade_code"
