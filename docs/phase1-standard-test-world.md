# Phase -1 Standard Test World

This document defines the test scenarios for Phase -1 profiling (Week 1-2 of the Nebula Architecture).

## Overview

The standard test world is designed to exercise all major subsystems of Minecraft server logic for profiling and annotation validation purposes.

## Test Scenarios

### 1. Redstone Subsystem

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **RS-SYNC-SEQ** | 8-bit CPU with register file and ALU | Exact timing dependencies | 1x |
| **RS-COMBO** | Large redstone dust array | Micro-step propagation | 10x |
| **RS-LOOP** | Repeater loops, comparator feedback loops | SCC contraction | 5x |
| **RS-0TICK** | 0-tick pulse generators, instant logic | Micro-step determinism | 3x |
| **RS-BUD** | BUD switches, block update detectors | Cross-subsystem interaction | 2x |

### 2. Entity Physics & Collision

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **ENT-COLLISION** | 100 chickens in 1x1 space | Collision SCC | 1x |
| **ENT-MOVEMENT** | Various entity movement patterns | MoveTask parallelization | 5x |
| **ENT-CROSS-BUCKET** | Entities moving between buckets | Bucket migration | 2x |

### 3. Entity AI

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **AI-VILLAGER** | Villager breeding machine, iron golem farm | POI interaction | 3x |
| **AI-COMBAT** | Zombie siege, skeleton archery | Damage and knockback | 2x |
| **AI-PATHFIND** | Complex pathfinding scenarios | PathfindTask parallelization | 5x |

### 4. Block Entity

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **BE-HOPPER** | Hopper chains | Block entity dependency | 10x |
| **BE-FURNACE** | Furnace smelting with hoppers | Fuel consumption timing | 3x |
| **BE-CHEST** | Large chest networks | Concurrent access | 5x |

### 5. Fluid

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **FL-WATER** | Water stream transportation | Fluid propagation | 5x |
| **FL-LAVA** | Lava pump system | Fluid interaction with redstone | 3x |

### 6. Explosion

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **EX-TNT** | TNT chain reaction | Explosion sub-DAG | 5x |
| **EX-ENDER** | End crystal chain |连锁爆炸处理 | 3x |

### 7. Comprehensive

| Scenario | Description | Testing Goal | Scale |
| --- | --- | --- | --- |
| **FULL-INTEGRATION** | Automated test factory | Full system integration | 1x |

## Profiling Configuration

### async-profiler Settings

```bash
# Capture flame graph
./profiler.sh -d 3600 -i 1ms -f flamegraph.html --cstack=fp <pid>

# Capture allocation
./profiler.sh -d 3600 -i 1ms -e alloc --cstack=fp <pid>

# Capture lock contention
./profiler.sh -d 3600 -i 1ms -e lock <pid>
```

### Required Metrics

| Metric | Collection Method | Duration |
| --- | --- | --- |
| CPU flame graph | async-profiler | >= 1 hour per scenario |
| Method hot spot list | async-profiler | >= 1 hour per scenario |
| Allocation profile | async-profiler | 15 minutes per scenario |
| Lock contention | async-profiler | 15 minutes per scenario |

## Expected Outputs

### Hot Method List Format

| Rank | Method | Package | CPU % | Cumulative % | Subsystem |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | tickRedstoneWire | net.minecraft.world.level.block | 2.5% | 2.5% | REDSTONE |
| 2 | moveEntity | net.minecraft.world.entity | 2.1% | 4.6% | PHYSICS |
| ... | ... | ... | ... | ... | ... |

### DG0 Metrics

- `N`: Number of methods covering 80% cumulative CPU
- Decision thresholds from `docs/templates/dg0-decision.md`

## World Generation Seed

For reproducibility, all test worlds use seed: `0x4E4542554C41L` (ASCII: "NEBULA")
