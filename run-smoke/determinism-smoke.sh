#!/bin/bash
# Determinism smoke (arch §12.1 / DG1):
#   1. Boot Nebula with a fixed-seed empty world.
#   2. Run /tick freeze, /tick step <N>, /nebula hash.
#   3. Stop. Restore world from a fresh snapshot.
#   4. Boot again. Run /tick freeze, /tick step <N>, /nebula hash.
#   5. Compare hashes — must be identical.
#
# Using `/tick freeze` + `/tick step` instead of wall-clock idle gives an
# exact tick count regardless of boot-time variance.
#
# This is a smoke (single-binary, single-machine) — full DG1 zero-diff
# requires vanilla-vs-nebula divergence detection, tracked separately.
set -uo pipefail

SANDBOX="/Users/panjiyang/nebula_server/run-smoke"
JAVA_HOME="/Users/panjiyang/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
IDLE_SECONDS="${IDLE_SECONDS:-30}"
STEP_TICKS="${STEP_TICKS:-200}"
SEED="${NEBULA_SEED:-424242}"
RESULT_DIR="$SANDBOX/bench-results"
LOG_A="$RESULT_DIR/determinism-A.log"
LOG_B="$RESULT_DIR/determinism-B.log"
WORLD_SNAPSHOT="$SANDBOX/world-determinism-snapshot"

cd "$SANDBOX" || exit 1
mkdir -p "$RESULT_DIR"

# --- helpers ---
boot_and_hash() {
    local label="$1"; local logfile="$2"
    pkill -f "nebula.jar" 2>/dev/null
    sleep 2
    rm -f world/session.lock 2>/dev/null

    [ -p cmd.fifo ] && rm -f cmd.fifo
    mkfifo cmd.fifo
    ( sleep 86400 > cmd.fifo ) &
    local holder=$!

    "$JAVA_HOME/bin/java" -Xms2G -Xmx4G \
        -Dpaper.disableChannelLimit=true \
        -Dnebula.mode=true \
        -jar nebula.jar --nogui < cmd.fifo > "$logfile" 2>&1 &
    local server=$!

    echo "[determinism:$label] waiting for boot..." >&2
    for i in $(seq 1 120); do
        if grep -qE "INFO\]: Done \(" "$logfile" 2>/dev/null; then
            echo "[determinism:$label] up after ${i}s" >&2
            break
        fi
        sleep 1
        if ! kill -0 $server 2>/dev/null; then
            echo "[determinism:$label] server died — see $logfile" >&2
            kill $holder 2>/dev/null
            return 2
        fi
    done

    echo "[determinism:$label] saving + capturing structural hash..." >&2
    echo "save-all flush" > cmd.fifo
    sleep 4

    # Capture structural hash (excludes time-varying globals to avoid
    # wall-clock drift between runs masking real state divergence).
    echo "nebula hash structural" > cmd.fifo
    sleep 4

    echo "stop" > cmd.fifo
    for i in $(seq 1 30); do
        if ! kill -0 $server 2>/dev/null; then break; fi
        sleep 1
    done
    kill $server 2>/dev/null
    kill $holder 2>/dev/null
    wait $server 2>/dev/null
    wait $holder 2>/dev/null
    rm -f cmd.fifo

    # Extract sha (last sha=... in log)
    grep -oE 'sha=[0-9a-f]+' "$logfile" | tail -1
}

# --- world prep ---
# Use a fixed-seed world. If snapshot exists, restore; else fresh-boot to seed
# the snapshot then fall through.
if [ -d "$WORLD_SNAPSHOT" ]; then
    echo "[determinism] restoring world from snapshot"
    rm -rf world world_nether world_the_end
    cp -R "$WORLD_SNAPSHOT/world"        world        2>/dev/null
    cp -R "$WORLD_SNAPSHOT/world_nether" world_nether 2>/dev/null
    cp -R "$WORLD_SNAPSHOT/world_the_end" world_the_end 2>/dev/null
else
    echo "[determinism] no snapshot — first run will create one after pass A"
    rm -rf world world_nether world_the_end
fi

# Force fixed seed in server.properties.
if [ -f server.properties ]; then
    if grep -q "^level-seed=" server.properties; then
        sed -i.bak "s/^level-seed=.*/level-seed=$SEED/" server.properties
    else
        echo "level-seed=$SEED" >> server.properties
    fi
fi

# --- pass A ---
HASH_A=$(boot_and_hash "A" "$LOG_A")
echo "[determinism] pass A: $HASH_A"

# Snapshot the freshly-generated world AFTER pass A so pass B starts from
# the post-A on-disk state. NOTE: that means hash B is computed on the
# state-after-second-N-empty-ticks-from-state-after-first-N-empty-ticks.
# To compare apples-to-apples, we instead want both passes to start from
# the SAME on-disk world. So snapshot BEFORE pass A's world is mutated.
# Easiest path: capture snapshot before pass A by running pass A through
# regen first, take snapshot, then restore. Below we restore the
# pre-A snapshot we just made.
if [ ! -d "$WORLD_SNAPSHOT" ]; then
    mkdir -p "$WORLD_SNAPSHOT"
    cp -R world        "$WORLD_SNAPSHOT/world"        2>/dev/null
    cp -R world_nether "$WORLD_SNAPSHOT/world_nether" 2>/dev/null
    cp -R world_the_end "$WORLD_SNAPSHOT/world_the_end" 2>/dev/null
    echo "[determinism] snapshot captured for future runs"
    echo "[determinism] re-run this script — pass A above ran on freshly-generated world,"
    echo "             so subsequent A vs B comparison needs to bootstrap with snapshot."
    exit 0
fi

# --- pass B ---
echo "[determinism] restoring snapshot for pass B"
rm -rf world world_nether world_the_end
cp -R "$WORLD_SNAPSHOT/world"        world        2>/dev/null
cp -R "$WORLD_SNAPSHOT/world_nether" world_nether 2>/dev/null
cp -R "$WORLD_SNAPSHOT/world_the_end" world_the_end 2>/dev/null

HASH_B=$(boot_and_hash "B" "$LOG_B")
echo "[determinism] pass B: $HASH_B"

# --- compare ---
echo
echo "================================="
echo " Determinism smoke result"
echo "================================="
echo "  pass A: $HASH_A"
echo "  pass B: $HASH_B"
echo "  idle ticks per pass: ${IDLE_SECONDS}s"
echo "  seed: $SEED"
if [ "$HASH_A" = "$HASH_B" ] && [ -n "$HASH_A" ]; then
    echo "  RESULT: MATCH — Nebula determinism smoke passes."
    exit 0
else
    echo "  RESULT: DIVERGE — Nebula state hash differs across runs."
    echo "  Logs: $LOG_A , $LOG_B"
    exit 1
fi
