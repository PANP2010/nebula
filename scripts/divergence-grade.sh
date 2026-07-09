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
# ============================================================================
# SETTLED MODE (--settled): the standing DG3 settled-state acceptance run.
# ============================================================================
# The default (CASCADE-DIAG) mode above grades the residual dirty rate of a
# never-settling square wave — a NECESSARY-BUT-NOT-SUFFICIENT signal that cannot
# separate one-cycle observe lag from a real bug (see the HONEST LIMITATION note).
# `--settled` runs the load-bearing complement: it drives the WHOLE multi-region
# tracked set to true quiescence, then grades a single `/nebula settled` snapshot
# of EVERY tracked position at once with the tested SettledDivergenceGrader
# (0.0 threshold — at quiescence a correct observe-only shadow must match Folia
# exactly, having had every intervening tick to catch up).
#
#   METHOD (--settled):
#     1. Place CIRCUITS lever-headed lines spaced 512 blocks apart (distinct
#        region threads), forceload + `/nebula scan` register them.
#     2. (optional) run a driven `--drive <seed>` warm-up capture so the pipeline
#        is exercised under a seed-deterministic multi-region toggle stream before
#        it is brought to rest (--warmup 0 skips it). A warm-up that does not
#        finish is a WARN, not a failure — the gate stands on the settled snapshot.
#     3. Toggle EVERY lever ON via setblock and wait SETTLE_S for Folia to drain
#        each 15-wire line to its settled 15..0 profile and the observe-only DAG
#        shadow to follow — a definite, non-trivial quiescent state (not all-off,
#        which would be a trivial nebula=0=folia=0 pass).
#     4. `/nebula settled` emits ONE SETTLED-DIAG line per world snapshotting the
#        whole tracked set; grade it with the SAME tested SettledDivergenceGraderCli
#        the unit tests exercise (exit 0 PASS / 3 FAIL / 4 INCONCLUSIVE).
#   A FAIL here (nebula!=folia at quiescence) is a GENUINE divergence — do NOT
#   raise --max-diverge to paper over it (that is the project's defining wound).
#
# Usage:  scripts/divergence-grade.sh [ticks] [circuits] [--seed <n>] [--period <n>] \
#            [--converge <n>] [--max-dirty <rate>] [--keep-running]
#         scripts/divergence-grade.sh --settled [circuits] [--seed <n>] [--period <n>] \
#            [--warmup <ticks>] [--max-diverge <rate>] [--keep-running]
#         scripts/divergence-grade.sh --be-settled [--hoppers <n>] [--feeds <n>] \
#            [--max-diverge <rate>] [--keep-running]
#   ticks       driven ticks to capture (default 400 — start small, per the Next: pointer)
#   circuits    lever-headed circuits to place (default 1; default 4 in --settled mode)
#   --settled   run the DG3 settled-state acceptance gate (see SETTLED MODE above)
#   --be-settled  run the B8 C3 block-entity settled-state gate (hopper→chest quiescence)
#   --hoppers   hopper→chest pairs to place, spaced 512 blocks apart so each lands on a
#               DISTINCT region thread (--be-settled; default 1). >1 exercises the emit's
#               concurrent region fan-in (CopyOnWriteArrayList + AtomicInteger)
#   --feeds     item stacks summoned to feed EACH hopper (--be-settled; default 6)
#   --seed      driver seed (default 42)
#   --period    per-source flip period in ticks (default 8)
#   --converge  leading invocations to skip as the cold-CAS transient (default 8)
#   --max-dirty residual dirty-rate pass threshold in [0,1] (default 0.05; default mode)
#   --warmup    driven warm-up ticks before settling (--settled mode; default 200, 0 skips)
#   --max-diverge settled divergence-rate pass threshold in [0,1] (--settled/--be-settled; default 0.0)
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
SETTLED_GRADER_CLASS="org.nebula.replay.SettledDivergenceGraderCli"
BE_SETTLED_GRADER_CLASS="org.nebula.replay.BlockEntitySettledGraderCli"

# --- Args ---
# In --settled mode the FIRST positional is CIRCUITS (there is no capture-ticks
# arg — the snapshot is a single instant, not a driven window); in default mode
# it is TICKS then CIRCUITS. SETTLED is parsed first so the positional split knows
# which arm it is on.
TICKS=""; CIRCUITS=""
SEED=42; PERIOD=8; CONVERGE=8; MAX_DIRTY=0.05; KEEP_RUNNING=0
SETTLED=0; WARMUP=200; MAX_DIVERGE=0.0
BE_SETTLED=0; FEEDS=6; HOPPERS=1
args=("$@"); _i=1; _pos=0; _POS=()
while [ "$_i" -le "$#" ]; do
    a="${args[$((_i-1))]}"
    case "$a" in
        --settled)   SETTLED=1 ;;
        --be-settled) BE_SETTLED=1 ;;
        --feeds)     _i=$((_i+1)); FEEDS="${args[$((_i-1))]:-}" ;;
        --hoppers)   _i=$((_i+1)); HOPPERS="${args[$((_i-1))]:-}" ;;
        --seed)      _i=$((_i+1)); SEED="${args[$((_i-1))]:-}" ;;
        --period)    _i=$((_i+1)); PERIOD="${args[$((_i-1))]:-}" ;;
        --converge)  _i=$((_i+1)); CONVERGE="${args[$((_i-1))]:-}" ;;
        --max-dirty) _i=$((_i+1)); MAX_DIRTY="${args[$((_i-1))]:-}" ;;
        --warmup)    _i=$((_i+1)); WARMUP="${args[$((_i-1))]:-}" ;;
        --max-diverge) _i=$((_i+1)); MAX_DIVERGE="${args[$((_i-1))]:-}" ;;
        --keep-running) KEEP_RUNNING=1 ;;
        --*) echo "ERROR: unknown option $a"; exit 2 ;;
        *) _POS[$_pos]="$a"; _pos=$((_pos+1)) ;;
    esac
    _i=$((_i+1))
done
if [ "$SETTLED" -eq 1 ]; then
    # --settled: [circuits] only.
    CIRCUITS="${_POS[0]:-4}"
    TICKS="$WARMUP"
elif [ "$BE_SETTLED" -eq 1 ]; then
    # --be-settled: no lever circuits, no capture window — a hopper+chest workload
    # driven to quiescence, then one BE-SETTLED snapshot. Positional is ignored.
    CIRCUITS=0
    TICKS=0
else
    # default: [ticks] [circuits].
    TICKS="${_POS[0]:-400}"
    CIRCUITS="${_POS[1]:-1}"
fi
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
# Skipped in --be-settled mode, which drives a hopper+chest block-entity workload
# (no redstone toggle sources) built in its own arm below.
if [ "$BE_SETTLED" -ne 1 ]; then
echo "Placing $CIRCUITS lever-headed redstone circuit(s)..."
for c in $(seq 0 $((CIRCUITS - 1))); do
    bx=$((c * 512)); bz=0
    # Forceload the WHOLE build+clear footprint (incl. z out to +12) so the air-fill
    # below actually applies — fill is a no-op on unloaded chunks, and stray redstone
    # a prior cycle left at e.g. z=10 sits outside the tight z=-2..2 tick footprint.
    R "forceload add $((bx - 1)) $((bz - 4)) $((bx + 20)) $((bz + 12))" >/dev/null
    # Clear the whole build volume (incl. z!=0) first: the flat test world PERSISTS
    # across cycles, so stray redstone a prior run left in this footprint would be
    # picked up by /nebula scan and inflate the tracked set with unrelated positions
    # (observed: leftover wire at z=10 graded as spurious divergence). fill air is a
    # no-op where the world is already air.
    R "fill $((bx - 1)) -60 $((bz - 4)) $((bx + 17)) -60 $((bz + 12)) minecraft:air" >/dev/null
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
fi

# Clean teardown helper shared by both arms.
teardown() {
    if [ "$KEEP_RUNNING" -eq 0 ] && [ "$started_here" -eq 1 ]; then
        echo "Stopping server cleanly..."
        R "stop" >/dev/null
        for i in $(seq 1 40); do
            sleep 1
            pgrep -f "$SERVER_DIR.*server.jar" >/dev/null 2>&1 || { echo "Stopped."; break; }
        done
    fi
}

# ============================================================================
# BE-SETTLED MODE (--be-settled) — the block-entity settled-state gate (B8 C3).
# ============================================================================
# The block-entity twin of --settled: it drives one or more hopper→chest transfers
# to quiescence, then grades ONE /nebula be-settled snapshot comparing each tracked
# hopper's CAS inventory count (nebula=) against Folia's live count (folia=) with
# the tested BlockEntitySettledGraderCli (0.0 threshold — at quiescence a correct
# observe-only shadow must match Folia exactly, having had every intervening tick
# to catch up; see memory block-entity-dag-live-verified for why per-tick sampling
# cannot catch a mid-cooldown move).
#
#   METHOD (--be-settled):
#     1. Build --hoppers hopper[facing=down]-over-chest pairs, spaced 512 blocks
#        apart on the X axis so each lands on a DISTINCT Folia region thread, and
#        forceload each chunk. With --hoppers>1 the snapshot's per-position samples
#        arrive from SEPARATE region threads, exercising the emit's concurrent
#        region fan-in (CopyOnWriteArrayList + AtomicInteger + sort-by-pos) that a
#        single tracked hopper never does.
#     2. Feed each hopper by summoning item entities just above it (--feeds times);
#        each fires InventoryMoveItemEvent, seeding the block-entity DAG. RCON has
#        NO working container-fill command on this Folia build (memory), so summon
#        is the only way to load a hopper with no players online.
#     3. Wait for the feed to drain and the hoppers' self-slots to stabilise
#        (quiescence — the item stream has stopped and Folia is no longer moving
#        items), then /nebula be-settled emits ONE BE-SETTLED line per world listing
#        every tracked position.
#     4. Grade it with BlockEntitySettledGraderCli (exit 0 PASS / 3 FAIL / 4 INCONCLUSIVE).
#   A FAIL here (nebula!=folia at quiescence) is a GENUINE divergence — do NOT
#   raise --max-diverge to paper over it.
if [ "$BE_SETTLED" -eq 1 ]; then
    SETTLE_S="${SETTLE_S:-6}"
    HY=64               # hopper Y; chest sits at HY-1
    HZ=0
    REGION_STRIDE=512   # 512 blocks = 32 chunks apart → distinct region threads

    echo "Building $HOPPERS hopper→chest pair(s), spaced ${REGION_STRIDE} blocks apart..."
    for h in $(seq 0 $((HOPPERS - 1))); do
        HX=$((h * REGION_STRIDE))
        R "forceload add $((HX - 2)) $((HZ - 2)) $((HX + 2)) $((HZ + 2))" >/dev/null
        R "setblock $HX $((HY - 1)) $HZ minecraft:chest" >/dev/null
        R "setblock $HX $HY $HZ minecraft:hopper[facing=down]" >/dev/null
    done
    sleep 2

    echo "Feeding each hopper with $FEEDS item stack(s) (summon above the hopper)..."
    for _ in $(seq 1 "$FEEDS"); do
        for h in $(seq 0 $((HOPPERS - 1))); do
            HX=$((h * REGION_STRIDE))
            R "summon item $HX.5 $((HY + 1)).2 $HZ.5 {Item:{id:\"minecraft:cobblestone\",count:32}}" >/dev/null
        done
        sleep 1
    done

    echo "Settling for ${SETTLE_S}s (feed stopped; hoppers drain to quiescence)..."
    sleep "$SETTLE_S"

    echo "Emitting /nebula be-settled over the tracked block entities..."
    BE_MARK="$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)"
    R "nebula be-settled" | strip
    be_ok=0
    for _ in $(seq 1 60); do
        sleep 1
        if tail -n +"$BE_MARK" "$SERVER_LOG" 2>/dev/null | grep -q "BE-SETTLED:"; then
            be_ok=1; break
        fi
    done

    BE_SLICE="$RESULT_DIR/divergence-$STAMP.beSettledlog"
    tail -n +"$BE_MARK" "$SERVER_LOG" 2>/dev/null | grep "BE-SETTLED:" > "$BE_SLICE" || true
    BE_COUNT="$(wc -l < "$BE_SLICE" | tr -d ' ')"
    echo "Captured $BE_COUNT BE-SETTLED line(s) from this run."

    if [ "$be_ok" -ne 1 ] || [ "$BE_COUNT" -eq 0 ]; then
        echo "ERROR: no BE-SETTLED line emitted within 60s — the hopper never seeded a"
        echo "       block-entity task (no InventoryMoveItemEvent fired?), or the plugin"
        echo "       emit is absent. Cannot grade the block-entity settled gate."
        teardown
        exit 4
    fi

    echo "Grading with the tested BlockEntitySettledGrader (max-diverge=$MAX_DIVERGE)..."
    GRADE_OUT="$("$JAVA21/bin/java" -cp "$GRADER_CP" "$BE_SETTLED_GRADER_CLASS" "$BE_SLICE" "$MAX_DIVERGE" 2>&1)"
    grade_code=$?

    {
        echo "=== Nebula B8 C3 BLOCK-ENTITY SETTLED-state acceptance grade ==="
        echo "Date:      $(date)"
        echo "Mode:      --be-settled (hopper→chest at quiescence)"
        echo "Workload:  $HOPPERS hopper[facing=down]-over-chest pair(s) spaced ${REGION_STRIDE} apart, fed $FEEDS x count:32 each"
        echo "Settle:    ${SETTLE_S}s after the last feed"
        echo "BE-SETTLED lines graded: $BE_COUNT  (raw slice: $BE_SLICE)"
        echo
        echo "$GRADE_OUT"
    } | tee "$RESULT_FILE"

    teardown
    echo "Result written to: $RESULT_FILE"
    exit "$grade_code"
fi

# ============================================================================
# SETTLED MODE — the standing DG3 settled-state acceptance gate.
# ============================================================================
if [ "$SETTLED" -eq 1 ]; then
    SETTLE_S="${SETTLE_S:-5}"

    # 1. (optional) Exercise the whole tracked set under a seed-deterministic
    #    multi-region toggle stream before bringing it to rest. This is a WARM-UP,
    #    not the graded signal — the gate stands on the settled snapshot in step 3.
    if [ "${WARMUP:-0}" -gt 0 ]; then
        echo "Warm-up: driving a $WARMUP-tick capture (seed=$SEED period=$PERIOD)..."
        WARM_MARK="$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)"
        R "nebula capture start $WARMUP --drive $SEED --period $PERIOD" | strip
        warm_ok=0
        for _ in $(seq 1 "$CAP_TIMEOUT_S"); do
            sleep 1
            if tail -n +"$WARM_MARK" "$SERVER_LOG" 2>/dev/null \
                 | grep -q "FoliaCaptureHarness stopped: captured $WARMUP ticks"; then
                warm_ok=1; break
            fi
        done
        R "nebula capture stop" | strip >/dev/null 2>&1
        [ "$warm_ok" -eq 1 ] || echo "WARN: warm-up did not reach $WARMUP ticks — continuing to the settled snapshot anyway."
    else
        echo "Warm-up skipped (--warmup 0)."
    fi

    # 2. Drive EVERY lever through a real OFF->ON transition, then settle to a
    #    definite, NON-TRIVIAL quiescent state (each 15-wire line at 15..0). The
    #    OFF pass first is load-bearing: `setblock lever[powered=true]` on a lever
    #    the warm-up square wave already left ON is a NO-OP that fires no
    #    BLOCK_UPDATE, so the observe-only shadow would never re-seed that line and
    #    it would read a stale value at snapshot time (a false divergence that is a
    #    harness artifact, not a Nebula bug). Forcing OFF first guarantees every
    #    lever makes a false->true transition, which fires the neighbour update that
    #    seeds each circuit's DAG cascade. All-off would be a trivial
    #    nebula=0=folia=0 pass, so we settle ON on purpose.
    echo "Forcing all $CIRCUITS lever(s) OFF, then settling for ${SETTLE_S}s..."
    for c in $(seq 0 $((CIRCUITS - 1))); do
        bx=$((c * 512))
        R "setblock $bx -60 0 minecraft:lever[face=floor,powered=false]" >/dev/null
    done
    sleep "$SETTLE_S"
    echo "Toggling all $CIRCUITS lever(s) ON, then settling for ${SETTLE_S}s..."
    for c in $(seq 0 $((CIRCUITS - 1))); do
        bx=$((c * 512))
        R "setblock $bx -60 0 minecraft:lever[face=floor,powered=true]" >/dev/null
    done
    sleep "$SETTLE_S"

    # 3. Snapshot the WHOLE tracked set at quiescence and grade the one line.
    echo "Emitting /nebula settled over the whole tracked set..."
    SETTLED_MARK="$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)"
    R "nebula settled" | strip
    # The snapshot lines are logged asynchronously once each owning region reports;
    # wait until at least one SETTLED-DIAG line appears (or time out).
    settled_ok=0
    for _ in $(seq 1 60); do
        sleep 1
        if tail -n +"$SETTLED_MARK" "$SERVER_LOG" 2>/dev/null | grep -q "SETTLED-DIAG:"; then
            settled_ok=1; break
        fi
    done

    SETTLED_SLICE="$RESULT_DIR/divergence-$STAMP.settledlog"
    tail -n +"$SETTLED_MARK" "$SERVER_LOG" 2>/dev/null | grep "SETTLED-DIAG:" > "$SETTLED_SLICE" || true
    SETTLED_COUNT="$(wc -l < "$SETTLED_SLICE" | tr -d ' ')"
    echo "Captured $SETTLED_COUNT SETTLED-DIAG line(s) from this run."

    if [ "$settled_ok" -ne 1 ] || [ "$SETTLED_COUNT" -eq 0 ]; then
        echo "ERROR: no SETTLED-DIAG line emitted within 60s — cannot grade the settled gate."
        teardown
        exit 4
    fi

    echo "Grading with the tested SettledDivergenceGrader (max-diverge=$MAX_DIVERGE)..."
    GRADE_OUT="$("$JAVA21/bin/java" -cp "$GRADER_CP" "$SETTLED_GRADER_CLASS" "$SETTLED_SLICE" "$MAX_DIVERGE" 2>&1)"
    grade_code=$?

    {
        echo "=== Nebula DG3 SETTLED-state acceptance grade ==="
        echo "Date:      $(date)"
        echo "Mode:      --settled (whole tracked set at quiescence, all levers ON)"
        echo "Circuits:  $CIRCUITS  (lever-headed, spaced 512 blocks / distinct regions)"
        echo "Warm-up:   $WARMUP driven ticks (seed=$SEED period=$PERIOD)"
        echo "Settle:    ${SETTLE_S}s after toggling all levers ON"
        echo "SETTLED-DIAG lines graded: $SETTLED_COUNT  (raw slice: $SETTLED_SLICE)"
        echo
        echo "--- /nebula status (post-scan) ---"
        echo "$STATUS_RAW"
        echo
        echo "$GRADE_OUT"
    } | tee "$RESULT_FILE"

    teardown
    echo "Result written to: $RESULT_FILE"
    exit "$grade_code"
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
teardown

echo "Result written to: $RESULT_FILE"
exit "$grade_code"
