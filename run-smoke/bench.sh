#!/bin/bash
# TPS bench harness — runs one configuration, captures /nebula stats afterward.
# Usage: bench.sh <label> [extra-jvm-flags...]
#
# Boots nebula.jar with the given flags, forceloads spawn chunks, spawns
# stress entities, idles for $RUN_SECONDS while metrics accumulate, then
# captures /nebula {stats,parallel,guard} and shuts down cleanly.
#
# NOTE: empty-world bench. /nebula stress-spawn drops mobs into the
# overworld but Folia regions only run entity ticks when a real player is
# in chunk-tracking range. Without a fake-player connection, entity tasks
# stay at ~1/tick (just entity_activation). This still measures parallel/
# guard/scheduler overhead on the global+BE path. Use JMH benchmarks
# (TickPipelineBenchmark, RWGuardBenchmark) for entity-scale measurements.
set -uo pipefail

LABEL="${1:?usage: bench.sh <label> [jvm-flags...]}"
shift
JVM_EXTRA=("$@")

SANDBOX="/Users/panjiyang/nebula_server/run-smoke"
JAVA_HOME="/Users/panjiyang/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
RUN_SECONDS="${RUN_SECONDS:-90}"
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
RESULT_DIR="$SANDBOX/bench-results"
RESULT_FILE="$RESULT_DIR/${LABEL}.txt"
LOG_FILE="$RESULT_DIR/${LABEL}.log"

cd "$SANDBOX" || exit 1
mkdir -p "$RESULT_DIR"

# Kill any previous nebula instance and clear stale session lock.
pkill -f "nebula.jar" 2>/dev/null
sleep 2
rm -f world/session.lock 2>/dev/null

# Refresh FIFO + writer holder.
[ -p cmd.fifo ] && rm -f cmd.fifo
mkfifo cmd.fifo

# Hold the writer end open so 'echo > cmd.fifo' from this script doesn't block.
( sleep 86400 > cmd.fifo ) &
HOLDER_PID=$!

# Boot the server.
"$JAVA_HOME/bin/java" -Xms2G -Xmx4G \
    -Dpaper.disableChannelLimit=true \
    -Dnebula.mode=true \
    ${JVM_EXTRA[@]+"${JVM_EXTRA[@]}"} \
    -jar nebula.jar --nogui < cmd.fifo > "$LOG_FILE" 2>&1 &
SERVER_PID=$!

cmd() { echo "$1" > cmd.fifo; }

# Wait for "Done" line.
echo "[bench:$LABEL] waiting for server boot..."
for i in $(seq 1 120); do
    if grep -qE "INFO\]: Done \(" "$LOG_FILE" 2>/dev/null; then
        echo "[bench:$LABEL] server up after ${i}s"
        break
    fi
    sleep 1
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo "[bench:$LABEL] server died during boot — see $LOG_FILE"
        kill $HOLDER_PID 2>/dev/null
        exit 2
    fi
done

# Stress setup: forceload spawn area, enable stress-mode (forces full DAG
# decompose ignoring emptyTime), then queue 250 entity spawns onto the
# correct region threads via the new /nebula stress-spawn command (avoids
# the console-thread getCurrentWorldData() null path).
cmd "forceload add -64 -64 64 64"
sleep 3
cmd "nebula stress-mode on"
sleep 1
cmd "nebula stress-spawn 250"
sleep 5

echo "[bench:$LABEL] warming up ${WARMUP_SECONDS}s..."
sleep "$WARMUP_SECONDS"

cmd "nebula reset"
sleep 1

echo "[bench:$LABEL] measuring ${RUN_SECONDS}s..."
sleep "$RUN_SECONDS"

cmd "nebula stress-mode"
sleep 1
cmd "nebula stats"
sleep 1
cmd "nebula parallel"
sleep 1
cmd "nebula guard"
sleep 3

# Save metrics + tail of log.
{
    echo "=== bench config: $LABEL ==="
    echo "JVM flags: ${JVM_EXTRA[*]:-(none)}"
    echo "Run seconds: $RUN_SECONDS"
    echo
    echo "--- nebula metrics ---"
    grep -E "Nebula DAG|Nebula parallel|Nebula RW-Guard|stress-mode" "$LOG_FILE" | tail -10
    echo
    echo "--- last 50 log lines ---"
    tail -50 "$LOG_FILE"
} > "$RESULT_FILE"

echo "[bench:$LABEL] stopping server..."
cmd "stop"

# Wait for clean shutdown (max 30s), then kill if needed.
for i in $(seq 1 30); do
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        break
    fi
    sleep 1
done
kill $SERVER_PID 2>/dev/null
kill $HOLDER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
wait $HOLDER_PID 2>/dev/null

echo "[bench:$LABEL] done. Result: $RESULT_FILE"
