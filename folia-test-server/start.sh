#!/bin/bash
# Folia Server Start Script with Nebula Agent

# Java configuration
JAVA_25=/usr/lib/jvm/java-25-openjdk-amd64/bin/java

# Memory settings
MIN_RAM=2G
MAX_RAM=4G

# JVM flags for Folia
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

# Nebula agent
AGENT_FLAGS="-javaagent:nebula-agent.jar -Dnebula.entity.divergence=true -Dnebula.entity.writeback.vertical=true -Dnebula.rw.guard=true"

# Check for server jar
if [ ! -f "server.jar" ]; then
    echo "Error: server.jar not found!"
    echo "Please download Folia 26.1.2 and place it as server.jar"
    exit 1
fi

# Check for agent jar
if [ ! -f "nebula-agent.jar" ]; then
    echo "Warning: nebula-agent.jar not found!"
    echo "Agent instrumentation will not be available."
    AGENT_FLAGS=""
fi

# Accept EULA automatically for test server
echo "eula=true" > eula.txt

# Start server
echo "Starting Folia server with Nebula..."
echo "Java: $JAVA_25"
echo "Memory: $MIN_RAM - $MAX_RAM"
echo "Agent: $AGENT_FLAGS"
echo

$JAVA_25 $JVM_FLAGS $AGENT_FLAGS \
    -Xms$MIN_RAM -Xmx$MAX_RAM \
    -jar server.jar \
    --nogui
