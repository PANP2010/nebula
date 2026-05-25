# Phase -1 Test World Templates

This document provides schematics and setup instructions for the standard test worlds used in Phase -1 profiling.

## World Seed

All test worlds use seed: `0x4E4542554C41L` (ASCII: "NEBULA")

## Test World List

| World Name | Purpose | Subsystem Focus |
| --- | --- | --- |
| `rs-sync-seq` | 8-bit CPU with register file and ALU | REDSTONE |
| `rs-combo` | Large redstone dust arrays | REDSTONE |
| `rs-loop` | Repeater loops, comparator feedback | REDSTONE |
| `rs-0tick` | 0-tick pulse generators | REDSTONE |
| `rs-bud` | BUD switches, block update detectors | REDSTONE |
| `ent-collision` | 100 chickens in 1x1 space | PHYSICS |
| `ent-movement` | Various entity movement patterns | PHYSICS |
| `be-hopper` | Hopper chains (100 long) | BLOCK_ENTITY |
| `be-furnace` | Furnace smelting arrays | BLOCK_ENTITY |
| `be-chest` | Large chest networks | BLOCK_ENTITY |
| `ai-villager` | Villager breeding machine | AI |
| `ai-combat` | Zombie siege arena | AI |
| `fl-water` | Water stream transportation | FLUID |
| `fl-lava` | Lava pump system | FLUID |
| `ex-tnt` | TNT chain reaction | EXPLOSION |
| `ex-ender` | End crystal chain | EXPLOSION |
| `full-integration` | All systems combined | ALL |

---

## Redstone Test Worlds

### rs-sync-seq: 8-bit CPU

**Purpose:** Test exact timing dependencies in complex sequential logic

**Components:**
1. 8-bit ALU (adder, subtractor, AND, OR, XOR)
2. 8-word register file
3. Program counter
4. Control unit with microcode ROM
5. Memory address register and data register

**Setup Commands:**
```
/setblock ~ ~ ~ minecraft:command_block{auto:0b,Command:"fill ~ ~ ~ ~10 ~10 ~10 minecraft:air"}
/gamerule randomTickSpeed 1
```

**Expected CPU Contribution:** ~2-5% of total tick time

### rs-combo: Large Redstone Dust Array

**Purpose:** Test micro-step propagation in large dust networks

**Components:**
1. 64x64 redstone dust network
2. Distributed power sources
3. Signal strength measurement points

**Setup Commands:**
```
/fill ~ ~ ~ ~63 ~1 ~63 minecraft:redstone_wire
```

**Expected CPU Contribution:** ~1-3% of total tick time

### rs-loop: Repeater Loops

**Purpose:** Test SCC contraction behavior

**Components:**
1. 16x repeater loop
2. Comparator feedback loops
3. Self-modifying circuits

**Expected CPU Contribution:** ~0.5-2% of total tick time

### rs-0tick: 0-tick Pulse Generators

**Purpose:** Test micro-step determinism with instant signals

**Components:**
1. 32x 0-tick pulse generators
2. Instant logic gates
3. Edge detectors

**Expected CPU Contribution:** ~1-2% of total tick time

### rs-bud: Block Update Detectors

**Purpose:** Test cross-subsystem interaction

**Components:**
1. Piston BUD switches
2. Observer chains
3. Stacked BUD arrays

**Expected CPU Contribution:** ~0.5-1% of total tick time

---

## Entity Test Worlds

### ent-collision: Dense Entity Test

**Purpose:** Test collision SCC behavior

**Setup Commands:**
```
/execute positioned 0 64 0 run summon minecraft:chicken ~ ~ ~ {NoAI:0b,Silent:1b}
/fill ~ ~ ~ ~10 ~1 ~10 minecraft:spawner{spawnCount:100,spawnRange:1,RequiredPlayerRange:64}
```

**Entity Count:** 100 chickens in 1x1x1 space

**Expected CPU Contribution:** ~5-10% of total tick time

### ent-movement: Movement Patterns

**Purpose:** Test MoveTask parallelization

**Components:**
1. Walking paths
2. Swimming channels
3. Flying zones
4. Falling shafts

**Entity Count:** 200 mixed entities

---

## Block Entity Test Worlds

### be-hopper: Hopper Chain

**Purpose:** Test block entity dependency chains

**Setup Commands:**
```
/fill ~ ~ ~ ~ ~99 ~ minecraft:hopper
/execute positioned ~ ~50 ~ run fill ~ ~-50 ~ ~ ~50 ~ minecraft:hopper
```

**Chain Length:** 100 hoppers vertical

**Expected CPU Contribution:** ~3-5% of total tick time

### be-furnace: Furnace Arrays

**Purpose:** Test fuel consumption timing

**Components:**
1. 16x16 furnace array
2. Automatic fuel supply
3. Item throughput measurement

**Expected CPU Contribution:** ~2-4% of total tick time

### be-chest: Large Chest Networks

**Purpose:** Test concurrent access patterns

**Components:**
1. 64 double chests
2. Hopper feeds from all sides
3. Cross-chest item sorting

**Expected CPU Contribution:** ~1-3% of total tick time

---

## AI Test Worlds

### ai-villager: Villager Breeding

**Purpose:** Test POI interaction and AI goal selection

**Components:**
1. 32 villagers
2. Multiple workstation POIs
3. Breeding chamber

**Expected CPU Contribution:** ~5-8% of total tick time

### ai-combat: Combat Arena

**Purpose:** Test AI pathfinding and combat behavior

**Components:**
1. 50 zombies
2. 50 skeletons
3. Player combat zone
4. Pathfinding stress areas

**Expected CPU Contribution:** ~8-12% of total tick time

---

## Fluid Test Worlds

### fl-water: Water Transportation

**Purpose:** Test fluid propagation modeling

**Setup Commands:**
```
/fill ~ ~ ~ ~100 ~ ~ minecraft:water[level=0]
/setblock ~ ~-1 ~ minecraft:water[level=7]
```

**Flow Length:** 100 blocks source to destination

**Expected CPU Contribution:** ~1-2% of total tick time

### fl-lava: Lava Pump

**Purpose:** Test fluid-redstone interaction

**Components:**
1. Lava source
2. Redstone-powered flow gates
3. Collection system

**Expected CPU Contribution:** ~0.5-1% of total tick time

---

## Explosion Test Worlds

### ex-tnt: TNT Chain Reaction

**Purpose:** Test explosion sub-DAG generation

**Setup Commands:**
```
/fill ~ ~ ~ ~15 ~ ~15 minecraft:tnt
```

**TNT Count:** 256 (16x16 grid)

**Expected CPU Contribution:** ~5-15% of total tick time (during explosion)

### ex-ender: End Crystal Chain

**Purpose:** Test连锁爆炸 handling

**Components:**
1. 32 end crystals
2. Ignition system
3. Measurement area

**Expected CPU Contribution:** ~10-20% of total tick time (during explosion)

---

## Full Integration Test

### full-integration: All Systems

**Purpose:** Real-world workload simulation

**Components:**
- All redstone circuits from above (scaled down)
- 200 entities with AI
- 100 hoppers in chains
- Fluid systems
- Periodic TNT explosions

**Setup:** Combined world with all test scenarios

**Expected CPU Contribution:** Representative of real server load

---

## Profiling Commands

### Load World
```
/stop; /world load <world_name>
```

### Verify Setup
```
/tellraw @a {"text":"=== Phase -1 Test World: <world_name> ===","color":"gold"}
/list
```

### Run Profile Session
```bash
./scripts/profiler.sh start <pid> -e cpu -i 1ms
# Let server run for 1 hour minimum
./scripts/profiler.sh stop <pid>
./scripts/profiler.sh convert <pid>
```

---

## Notes

1. All worlds should be pre-generated and saved
2. Worlds are loaded with `/world load <name>`
3. Use paper.yml to configure view-distance: 10, simulation-distance: 8
4. Record any modifications made to default behavior
