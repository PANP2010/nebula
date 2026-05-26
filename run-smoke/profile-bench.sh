#!/bin/bash
# Profile bench — boots server with async-profiler, captures flamegraph + wall-clock stats.
#
# Usage: profile-bench.sh <label> [extra-jvm-flags...]
#
# Produces:
#   bench-results/<label>-flamegraph.html   — collapsed stack flamegraph
#   bench-results/<label>-profile.txt       — top methods by sample count
#   bench-results/<label>.txt               — nebula metrics (same as bench.sh)
#
# Profiled window: after warmup, for $PROFILE_SECONDS.
# Requires: /Users/panjiyang/async-profiler/async-profiler-4.4-macos/bin/asprof
set -uo pipefail

LABEL="${1:?usage: profile-bench.sh <label> [jvm-flags...]}"
shift
JVM_EXTRA=("$@")

ASPROF="/Users/panjiyang/async-profiler/async-profiler-4.4-macos/bin/asprof"
SANDBOX="/Users/panjiyang/nebula_server/run-smoke"
JAVA_HOME="/Users/panjiyang/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
RUN_SECONDS="${RUN_SECONDS:-120}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
PROFILE_SECONDS="${PROFILE_SECONDS:-60}"
RESULT_DIR="$SANDBOX/bench-results"
RESULT_FILE="$RESULT_DIR/${LABEL}.txt"
LOG_FILE="$RESULT_DIR/${LABEL}.log"
FLAMEGRAPH="$RESULT_DIR/${LABEL}-flamegraph.html"
PROFILE_TOP="$RESULT_DIR/${LABEL}-profile.txt"

cd "$SANDBOX" || exit 1
mkdir -p "$RESULT_DIR"

# Kill any previous nebula instance and clear stale session lock.
pkill -f "nebula.jar" 2>/dev/null
sleep 2
rm -f world/session.lock 2>/dev/null

# Refresh FIFO + writer holder.
[ -p cmd.fifo ] && rm -f cmd.fifo
mkfifo cmd.fifo
( sleep 86400 > cmd.fifo ) &
HOLDER_PID=$!

# Boot the server with -XX:+PreserveFramePointer so async-profiler can walk frames.
"$JAVA_HOME/bin/java" -Xms2G -Xmx4G \
    -XX:+PreserveFramePointer \
    -Dpaper.disableChannelLimit=true \
    -Dnebula.mode=true \
    ${JVM_EXTRA[@]+"${JVM_EXTRA[@]}"} \
    -jar nebula.jar --nogui < cmd.fifo > "$LOG_FILE" 2>&1 &
SERVER_PID=$!

cmd() { echo "$1" > cmd.fifo; }

# Wait for "Done" line.
echo "[profile:$LABEL] waiting for server boot..."
for i in $(seq 1 120); do
    if grep -qE "INFO\]: Done \(" "$LOG_FILE" 2>/dev/null; then
        echo "[profile:$LABEL] server up after ${i}s"
        break
    fi
    sleep 1
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo "[profile:$LABEL] server died during boot — see $LOG_FILE"
        kill $HOLDER_PID 2>/dev/null
        exit 2
    fi
done

# Stress setup.
cmd "forceload add -64 -64 64 64"
sleep 3
cmd "nebula stress-mode on"
sleep 1
cmd "nebula stress-spawn 250"
sleep 5

echo "[profile:$LABEL] warming up ${WARMUP_SECONDS}s..."
sleep "$WARMUP_SECONDS"

cmd "nebula reset"
sleep 1

# Start async-profiler in wall-clock mode for the profiling window.
echo "[profile:$LABEL] profiling for ${PROFILE_SECONDS}s (wall-clock mode)..."
"$ASPROF" -d "$PROFILE_SECONDS" -o collapsed -e wall "$SERVER_PID" \
    > "$RESULT_DIR/${LABEL}-collapsed.txt" 2>/dev/null &
ASPROF_PID=$!

echo "[profile:$LABEL] measuring ${RUN_SECONDS}s (nebula metrics)..."
sleep "$RUN_SECONDS"

# Capture nebula metrics.
cmd "nebula stress-mode"
sleep 1
cmd "nebula stats"
sleep 1
cmd "nebula parallel"
sleep 1
cmd "nebula guard"
sleep 3

# Wait for async-profiler to finish.
wait $ASPROF_PID 2>/dev/null

# Generate flamegraph HTML from collapsed stacks.
if [ -f "$RESULT_DIR/${LABEL}-collapsed.txt" ] && [ -s "$RESULT_DIR/${LABEL}-collapsed.txt" ]; then
    "$ASPROF" -d 0 -o flamegraph -i "$RESULT_DIR/${LABEL}-collapsed.txt" \
        > "$FLAMEGRAPH" 2>/dev/null || echo "(flamegraph generation skipped)"
fi

# Extract top-30 hot methods from collapsed stacks.
if [ -f "$RESULT_DIR/${LABEL}-collapsed.txt" ]; then
    awk '{sum[$1]+=$2} END{for(s in sum) print sum[s], s}' \
        "$RESULT_DIR/${LABEL}-collapsed.txt" | sort -rn | head -30 \
        > "$PROFILE_TOP"
fi

# Save metrics + tail of log.
{
    echo "=== profile config: $LABEL ==="
    echo "JVM flags: ${JVM_EXTRA[*]:-(none)}"
    echo "Run seconds: $RUN_SECONDS | Profile seconds: $PROFILE_SECONDS"
    echo
    echo "--- nebula metrics ---"
    grep -E "Nebula DAG|Nebula parallel|Nebula RW-Guard|stress-mode" "$LOG_FILE" | tail -10
    echo
    echo "--- top hot methods (wall-clock samples) ---"
    cat "$PROFILE_TOP" 2>/dev/null || echo "(no profile data)"
    echo
    echo "--- last 50 log lines ---"
    tail -50 "$LOG_FILE"
} > "$RESULT_FILE"

echo "[profile:$LABEL] stopping server..."
cmd "stop"

for i in $(seq 1 30); do
    if ! kill -0 $SERVER_PID 2>/dev/null; then break; fi
    sleep 1
done
kill $SERVER_PID 2>/dev/null
kill $HOLDER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
wait $HOLDER_PID 2>/dev/null

echo "[profile:$LABEL] done."
echo "  Metrics:   $RESULT_FILE"
echo "  Flamegraph: $FLAMEGRAPH"
echo "  Top-30:    $PROFILE_TOP"
