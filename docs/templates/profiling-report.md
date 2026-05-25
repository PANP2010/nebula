# Phase -1 Profiling Report

## Executive Summary

> Fill in: Brief summary of profiling results across all 4 scenarios.

## Scenario Matrix

| Scenario | Server Type | Duration | Profiler | Sampling Interval | Date | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| RS-PROFILE | Redstone | >= 1h | async-profiler | 1ms | TBD | 生电复杂电路 |
| SURV-PROFILE | Survival | >= 1h | async-profiler | 1ms | TBD | 生存服典型负载 |
| MG-PROFILE | Minigame | >= 1h | async-profiler | 1ms | TBD | BedWars/SkyWars等 |
| RPG-PROFILE | RPG | >= 1h | async-profiler | 1ms | TBD | 大量AI实体 |

## Profiling Configuration

### Environment Setup

```bash
# Download async-profiler
curl -L -o async-profiler.tar.gz https://github.com/async-profiler/async-profiler/releases/latest/download/async-profiler-3.0-linux-x64.tar.gz
tar -xzf async-profiler.tar.gz

# Attach to Folia server
./profiler.sh -d 3600 -i 1ms -f flamegraph.html --cstack=fp <pid>

# For allocation profiling (optional)
./profiler.sh -d 3600 -i 1ms -e alloc --cstack=fp <pid>
```

### Test World Configuration

- World seed: `0x4E4542554C41L` (ASCII: "NEBULA")
- View distance: 10
- Simulation distance: 8
- Player count: Simulate typical load (e.g., 20 players)

## Hot Method Summary

| Rank | Method | Package | CPU % | Cumulative % | Subsystem | Annotation Priority |
| ---: | --- | --- | ---: | ---: | --- | ---: |
| 1 | TBD | TBD | TBD | TBD | REDSTONE | P0 |
| 2 | TBD | TBD | TBD | TBD | PHYSICS | P0 |
| 3 | TBD | TBD | TBD | TBD | AI | P0 |
| 4 | TBD | TBD | TBD | TBD | BLOCK_ENTITY | P1 |
| 5 | TBD | TBD | TBD | TBD | FLUID | P2 |

*Subsystem categories: REDSTONE, PHYSICS, AI, BLOCK_ENTITY, FLUID, EXPLOSION, LIGHTING, OTHER*

## Method Count for 80% CPU

### Cumulative Chart

```
Cumulative % |
    100% |                          ************
     90% |                     ******
     80% |------------------**      (N = TBD methods)
     70% |                **
     60% |             **
     50% |           **
     40% |         **
     30% |       **
     20% |     **
     10% |   **
      0% | **
         +--+--+--+--+--+--+--+--+--+--+--> Method Rank
           10  20  30  40  50  60  70  80  90 100
```

### DG0 Metric

- **N = TBD** (number of methods required to cover 80% cumulative CPU)

### Decision Thresholds

| N Value | Decision |
| --- | --- |
| N <= 250 | Pass DG0, enter Phase 0 |
| 250 < N <= 500 | Extended Phase 0 (adjusted schedule/budget) |
| N > 500 | Trigger Contingency B or stop project |

## Per-Scenario Breakdown

### Redstone (RS-PROFILE)

**Test Setup:**
- 8-bit CPU with register file
- Large redstone dust arrays
- Repeater loops and comparator feedback
- 0-tick pulse generators
- BUD switches

**Key Hot Methods:**
| Method | CPU % | Notes |
| --- | ---: | --- |
| TBD | TBD | TBD |

### Survival (SURV-PROFILE)

**Test Setup:**
- Active player farms
- Entity-dense areas
- Redstone contraptions
- Fluid systems

**Key Hot Methods:**
| Method | CPU % | Notes |
| --- | ---: | --- |
| TBD | TBD | TBD |

### Minigame (MG-PROFILE)

**Test Setup:**
- Multiple concurrent game instances
- Player combat scenarios
- Projectile-heavy gameplay

**Key Hot Methods:**
| Method | CPU % | Notes |
| --- | ---: | --- |
| TBD | TBD | TBD |

### RPG (RPG-PROFILE)

**Test Setup:**
- Dense AI entity populations
- Complex pathfinding scenarios
- Villager trading systems

**Key Hot Methods:**
| Method | CPU % | Notes |
| --- | ---: | --- |
| TBD | TBD | TBD |

## Allocation Profile (Optional)

| Rank | Type | Allocations % | Cumulative % |
| ---: | --- | ---: | ---: |
| TBD | TBD | TBD | TBD |

## Lock Contention (Optional)

| Rank | Lock | Wait Time % | Contention Events |
| --- | --- | ---: | ---: |
| TBD | TBD | TBD | TBD |

## Raw Data

Attach full method list from async-profiler output here.

## Notes and Observations

- TBD
