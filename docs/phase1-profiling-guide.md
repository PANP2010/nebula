# Phase -1 Profiling Guide

Guide for running CPU profiling on Minecraft server scenarios.

## Prerequisites

### Software Requirements

- async-profiler 4.0+ (https://github.com/async-profiler/async-profiler)
- Java 25 toolchain
- Folia server build 26.1.2+
- Linux/macOS with JDK debug symbols

### System Requirements

- 16+ CPU cores recommended for parallel execution
- 8GB+ RAM for profiling session
- Local filesystem with 10GB+ free space for flame graphs

## Profiling Workflow

### 1. Server Preparation

```bash
# Build Folia with instrumentation
./gradlew clean build -x test

# Start server with profiling enabled
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+Debugging \
     -jar folia-server.jar \
     --nogui
```

### 2. Attach Profiler

```bash
# Find server PID
pgrep -f "folia-server.jar"

# Start CPU profiling
./profiler.sh start <pid> -e cpu -i 1ms

# Or for allocation profiling
./profiler.sh start <pid> -e alloc -i 1ms
```

### 3. Run Test Scenarios

For each scenario in `docs/phase1-standard-test-world.md`:

```bash
# Load test world
/stop; /world load <scenario>

# Run for duration
/sleep 3600000  # 1 hour

# Collect interim snapshot
./profiler.sh snapshot <pid>
```

### 4. Stop and Analyze

```bash
# Stop profiling
./profiler.sh stop <pid>

# Generate flame graph
./profiler.sh convert <pid> flame.svg

# Generate text summary
./profiler.sh summary <pid> > profile-summary.txt

# List top methods
./profiler.sh top <pid> -n 100 > hot-methods.txt
```

## Scenario-Specific Commands

### Redstone Testing

```bash
# Load redstone test world
/world load rs-sync-seq

# Enable redstone tick acceleration (if available)
/gamerule randomTickSpeed 100

# Monitor redstone activity
/nebula dag
```

### Entity Density Testing

```bash
# Spawn dense entities
/summon minecraft:chicken ~ ~ ~ {NoAI:0b}

/spawner set minecraft:chicken 100
```

### Block Entity Testing

```bash
# Build hopper chain
/execute positioned ~ ~ ~ run fill ~ ~ ~-100 ~ ~ ~-1 minecraft:hopper
```

## Data Collection

### Per-Scenario Requirements

| Scenario | Duration | Snapshots | Notes |
| --- | ---: | ---: | --- |
| Redstone | 60 min | 2 | Warmup + steady state |
| Entity Physics | 30 min | 2 | After spawn stabilization |
| Block Entity | 30 min | 2 | After chain completion |
| AI | 60 min | 2 | Full day/night cycle |
| Fluid | 30 min | 2 | After flow stabilization |
| Explosion | 15 min | 3 | During chain reactions |

### Required Outputs

1. **Flame graph** (SVG format)
2. **Hot method list** (top 500 methods)
3. **N value** (methods for 80% CPU)
4. **Scenario summary** (performance characteristics)

## Analysis

### Extract Hot Methods

```bash
# Extract top methods for N calculation
cat hot-methods.txt | \
  awk 'NR>1 && $2 ~ /^[0-9.]+%/ {gsub(/%/,"",$2); sum+=$2; if(sum<=80) print NR, $0}' | \
  wc -l
```

### Identify Blockers

For each method in top N:

1. Check if method has JNI calls
2. Check for reflection access
3. Check for global singleton dependencies
4. Classify blocker type in `docs/templates/annotation-sampling-report.md`

## Troubleshooting

### Profiler Doesn't Attach

```bash
# Check if JVM supports dynamic attach
java -version

# Enable attach if needed
echo 1 | sudo tee /proc/sys/kernel/yama/ptrace_scope
```

### High Overhead

- Reduce sampling interval: `-i 2ms` instead of `-i 1ms`
- Profile only specific packages: `--alloc-methods "net.minecraft.*"`

### Missing Debug Symbols

```bash
# Install debug symbols (Debian/Ubuntu)
sudo apt install openjdk-25-dbg

# macOS: symbols included in JDK
```
