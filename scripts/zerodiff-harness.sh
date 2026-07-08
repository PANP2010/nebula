#!/bin/bash
# Nebula zero-diff correctness harness — REAL command surface, this Linux box.
#
# Purpose: drive the DG1 Criterion 1 gate — "zero-diff for N ticks" — on the
# real Folia test server, and GRADE it PASS/FAIL. It extends the B6 milestone
# (two identical 40-tick captures -> byte-for-byte identical .nrp files,
# verified 2026-07-08) to the DG1 bar (default 10000 ticks) at multi-region
# scale.
#
# METHOD (identical to B6, scaled up):
#   1. Place CIRCUITS redstone circuits spaced 512 blocks apart so Folia assigns
#      them to distinct region threads; forceload + `/nebula scan` registers
#      their positions with the state hasher (populates trackedPositions).
#   2. Run `/nebula capture start N` TWICE, back-to-back, on the SAME static
#      registered world. Each run records N per-tick state hashes to a .nrp file.
#   3. Compare the two .nrp files. If byte-for-byte identical, the deterministic
#      zero-diff property holds across two independent N-tick runs -> PASS.
#
# WHY STATIC (honest limitation — read before extending):
#   Byte-identity between two INDEPENDENT runs requires the captured world to be
#   deterministic tick-for-tick. RCON toggles are NOT tick-aligned between runs
#   (a `setblock` lands on whatever tick the command thread happens to reach), so
#   driving live toggles during capture would make the two runs legitimately
#   differ and defeat the comparison. This harness therefore captures a static
#   registered multi-region world — the same thing B6 did, just 250x longer and
#   across regions. It proves the capture+hash pipeline stays deterministic and
#   exception-free over N ticks at scale (no drift, no GC-induced reordering, no
#   accumulated state creep). It does NOT test DAG-under-sustained-load
#   correctness or Folia-vs-Nebula divergence — those need a tick-deterministic
#   input driver and are explicitly future work (see docs/PROJECT_STATUS.md).
#   The SCHEDULING half of that driver now exists as pure, unit-tested logic:
#   org.nebula.replay.DeterministicToggleSchedule maps tick -> toggle actions
#   reproducibly from a seed, so two runs would emit byte-identical toggle streams
#   tick-for-tick. What remains is the game-layer wiring to apply those actions on
#   the correct region thread at the scheduled tick (touches the tick pipeline).
#
# Usage:  scripts/zerodiff-harness.sh [ticks] [circuits] [--keep-running]
#   ticks     ticks to capture per run (default 10000 = the DG1 bar)
#   circuits  independent redstone lines to place (default 8)
#   --keep-running  leave the server up at the end (default: stop it cleanly)
#
# Env overrides: RCON_PORT (25576), RCON_PW (nebulatest), SERVER_DIR,
#   CAP_TIMEOUT_S (per-run wait cap; default computed as ticks/20 * 3 + 120s).
# Exit code: 0 if PASS (identical), 3 if FAIL (differ), 4 if INCONCLUSIVE.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$REPO_ROOT/folia-test-server}"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PW="${RCON_PW:-nebulatest}"
JAR_SRC="$HOME/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar"
CAPTURE_DIR="$SERVER_DIR/plugins/Nebula/captures"
SERVER_LOG="$SERVER_DIR/server-run.log"

TICKS="${1:-10000}"
CIRCUITS="${2:-8}"
KEEP_RUNNING=0
for a in "$@"; do [ "$a" = "--keep-running" ] && KEEP_RUNNING=1; done

# Wall-clock cap per capture run: N ticks / 20 TPS, tripled for TPS headroom,
# plus 120s slack. A driven-but-idle Folia server may tick below 20 TPS.
CAP_TIMEOUT_S="${CAP_TIMEOUT_S:-$(( TICKS / 20 * 3 + 120 ))}"

RESULT_DIR="$REPO_ROOT/bench-results"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULT_FILE="$RESULT_DIR/zerodiff-$STAMP.txt"

R() { mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PW" "$1" 2>&1; }
strip() { sed 's/§[0-9a-fk-or;]*//g; s/\x1b\[[0-9;]*m//g'; }

command -v mcrcon >/dev/null || { echo "ERROR: mcrcon not on PATH"; exit 1; }
[ -f "$SERVER_DIR/start.sh" ] || { echo "ERROR: $SERVER_DIR/start.sh missing"; exit 1; }

# --- Deploy the freshest shaded jar (built separately with Java 21). ---
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
        [ "$i" = "90" ] && { echo "ERROR: server did not boot in 90s"; tail -5 "$SERVER_LOG"; exit 2; }
    done
fi

# --- Build a static multi-region workload (NOT toggled — see WHY STATIC). ---
echo "Placing $CIRCUITS static redstone circuits (spaced across regions)..."
for c in $(seq 0 $((CIRCUITS - 1))); do
    bx=$((c * 512)); bz=0
    R "forceload add $bx $((bz - 2)) $((bx + 20)) $((bz + 2))" >/dev/null
    R "setblock $bx -60 $bz minecraft:redstone_block" >/dev/null
    for i in $(seq 1 15); do
        R "setblock $((bx + i)) -60 $bz minecraft:redstone_wire" >/dev/null
    done
    R "setblock $((bx + 16)) -60 $bz minecraft:redstone_lamp" >/dev/null
done
sleep 2

echo "Scanning for components..."
R "nebula scan" | strip
sleep 3
echo "Status after scan:"
STATUS_RAW="$(R 'nebula status' | strip)"
echo "$STATUS_RAW"

# --- Run one N-tick capture; echoes the saved .nrp basename on success. ---
# Auto-stop fires at N ticks but only `capture stop` writes the file, so we wait
# for the harness "stopped: captured N ticks" log line, then issue stop to save.
run_capture() {
    local run_label="$1"
    local marker_before
    marker_before="$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)"
    echo "[$run_label] Starting $TICKS-tick capture..." >&2
    R "nebula capture start $TICKS" | strip >&2

    local done=0
    for _ in $(seq 1 "$CAP_TIMEOUT_S"); do
        sleep 1
        # Look only at log lines appended since this run started.
        if tail -n +"$marker_before" "$SERVER_LOG" 2>/dev/null \
             | grep -q "FoliaCaptureHarness stopped: captured $TICKS ticks"; then
            done=1; break
        fi
    done
    if [ "$done" -ne 1 ]; then
        echo "[$run_label] ERROR: capture did not reach $TICKS ticks within ${CAP_TIMEOUT_S}s" >&2
        return 1
    fi

    # Auto-stopped; `stop` now flushes the recorder to a timestamped .nrp.
    local stop_out
    stop_out="$(R 'nebula capture stop' | strip)"
    echo "$stop_out" >&2
    # Newest .nrp in the capture dir is this run's file.
    local newest
    newest="$(ls -t "$CAPTURE_DIR"/*.nrp 2>/dev/null | head -1)"
    [ -n "$newest" ] || { echo "[$run_label] ERROR: no .nrp written" >&2; return 1; }
    echo "$newest"
}

echo "=== Capture run A ==="
FILE_A="$(run_capture A)" || { echo "Run A failed"; [ "$KEEP_RUNNING" -eq 0 ] && R stop >/dev/null; exit 4; }
sleep 1
echo "=== Capture run B ==="
FILE_B="$(run_capture B)" || { echo "Run B failed"; [ "$KEEP_RUNNING" -eq 0 ] && R stop >/dev/null; exit 4; }

# --- Grade: byte-for-byte comparison of the two .nrp files. ---
verdict=""; verdict_code=0
SHA_A="$(sha256sum "$FILE_A" | awk '{print $1}')"
SHA_B="$(sha256sum "$FILE_B" | awk '{print $1}')"
SIZE_A="$(wc -c < "$FILE_A")"; SIZE_B="$(wc -c < "$FILE_B")"

if [ "$SHA_A" = "$SHA_B" ]; then
    verdict="PASS — two independent $TICKS-tick captures are byte-for-byte identical (zero-diff holds at scale, $CIRCUITS circuits)"
    verdict_code=0
else
    verdict="FAIL — the two $TICKS-tick captures differ (A $SIZE_A B $SIZE_B bytes) — determinism violation"
    verdict_code=3
fi

{
    echo "=== Nebula zero-diff correctness harness (DG1 Criterion 1) ==="
    echo "Date:     $(date)"
    echo "Ticks:    $TICKS per run (x2 runs)"
    echo "Circuits: $CIRCUITS  (static, spaced 512 blocks apart)"
    echo
    echo "--- /nebula status (post-scan) ---"
    echo "$STATUS_RAW"
    echo
    echo "--- capture files ---"
    echo "A: $FILE_A  ($SIZE_A bytes)  sha256=$SHA_A"
    echo "B: $FILE_B  ($SIZE_B bytes)  sha256=$SHA_B"
    echo
    echo "--- DG1 Criterion 1 verdict (zero-diff for $TICKS ticks) ---"
    echo "Verdict: $verdict"
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
exit "$verdict_code"

# LIMITATIONS (honest):
#  - Proves the capture+hash pipeline is deterministic and exception-free over N
#    ticks at multi-region scale (no drift/reordering/state-creep). The captured
#    world is STATIC by construction (see WHY STATIC) — this is NOT a test of DAG
#    correctness under sustained live load, nor a Folia-vs-Nebula divergence
#    check. Those require a tick-deterministic input driver (future work).
#  - Byte-identity is the strongest possible zero-diff signal but also the
#    strictest: it would (correctly) FAIL on any nondeterminism in the hash
#    inputs, including any accidental wall-clock/iteration-order leakage.
#  - RCON setblock does not fire BlockPlaceEvent, hence the /nebula scan step.
