#!/bin/bash
# Paper Server Start Script — B9 single-thread differential oracle (D4)
# NO javaagent: on Paper the Bukkit RedstoneEventListener fallback seeds dirty
# positions, and the inline shadow executor drives the DAG on the main thread.

# Java configuration (same runtime as folia-test-server)
JAVA_25=/usr/lib/jvm/java-25-openjdk-amd64/bin/java

# Memory settings
MIN_RAM=2G
MAX_RAM=4G

# JVM flags
JVM_FLAGS="-XX:+UseG1GC \
-XX:+ParallelRefProcEnabled \
-XX:MaxGCPauseMillis=200 \
-XX:+UnlockExperimentalVMOptions \
-XX:+DisableExplicitGC \
-XX:+AlwaysPreTouch \
-XX:G1NewSizePercent=30 \
-XX:G1MaxNewSizePercent=40 \
-XX:G1HeapRegionSize=8M \
-XX:G1ReservePercent=20 \
-XX:G1HeapWastePercent=5 \
-XX:G1MixedGCCountTarget=4 \
-XX:InitiatingHeapOccupancyPercent=15 \
-XX:G1MixedGCLiveThresholdPercent=90 \
-XX:G1RSetUpdatingPauseTimePercent=5 \
-XX:SurvivorRatio=32 \
-XX:+PerfDisableSharedMem \
-XX:MaxTenuringThreshold=1"

if [ ! -f "server.jar" ]; then
    echo "Error: server.jar not found (expected Paper 26.1.2)!"
    exit 1
fi

echo "eula=true" > eula.txt

# B9 D5: set NEBULA_DAG_PARALLEL=true to run the redstone DAG on the worker pool
# (-Dnebula.dag.parallel). Unset/false keeps the D4 inline-serial baseline. The
# single-thread-oracle diff (/nebula diff) must report matched==total either way.
NEBULA_PROPS=""
if [ "${NEBULA_DAG_PARALLEL:-false}" = "true" ]; then
    NEBULA_PROPS="-Dnebula.dag.parallel=true"
    echo "NEBULA: DAG parallel worker pool ENABLED (-Dnebula.dag.parallel=true)"
fi

echo "Starting Paper server with Nebula (single-thread oracle, no agent)..."
echo "Java: $JAVA_25"
echo

$JAVA_25 $JVM_FLAGS $NEBULA_PROPS \
    -Xms$MIN_RAM -Xmx$MAX_RAM \
    -jar server.jar \
    --nogui
