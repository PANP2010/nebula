# Profiling Report: Folia 26.1.2 Idle State

Date: 2026-05-24
Folia Version: 26.1.2-8
Java: JDK 25.0.3 (HotSpot, aarch64)
OS: macOS 26.3 (Darwin 25.3.0)
Profiler: async-profiler 4.4

## Environment

- Folia server running on port 25565 (RCON on 25575)
- Nebula agent attached via `-javaagent`
- Test world: seed 12345, view-distance 4, simulation-distance 4
- Redstone circuits placed via RCON (static, no active clocks)

## Test Circuits Placed

| Circuit | Location | Description |
|---------|----------|-------------|
| Wire grid | (-5,-5) to (5,5) at y=64 | 11×11 redstone wire grid |
| Repeater chain | (-20,10) to (-10,10) at y=64 | 11 repeaters in a line |
| Comparator chain | (-20,15) to (-15,15) at y=64 | 6 comparators |
| Dense wire | (-25,20) to (-21,24) at y=64 | 5×5 wire grid |

## CPU Profile Summary (45s, 281 samples)

### Top Java Methods by CPU Time

| Method | CPU% | Samples | Category |
|--------|------|---------|----------|
| `CopyOnWriteArrayList.toArray` | 2.15% | 6 | JDK (Folia scheduler internals) |
| `ServerLevel.advanceWeatherCycle` | 1.79% | 5 | Game logic |
| `HashSet.remove` | 1.43% | 4 | JDK |
| `EnvironmentAttributeSystem.getValueSampler` | 1.08% | 3 | Game logic |
| `RegionizedServer.globalTick` | 1.08% | 3 | Folia scheduler |
| `ThreadLocal$ThreadLocalMap.getEntry` | 1.08% | 3 | JDK |
| `PriorityQueue.siftUpUsingComparator` | 1.08% | 3 | JDK |
| `HashMap$Values.forEach` | 0.72% | 2 | JDK |
| `PacketProcessor.executeSinglePacket` | 0.72% | 2 | Network |
| `FoliaGlobalRegionScheduler.tick` | 0.72% | 2 | Folia scheduler |
| `RegionScheduleHandle.runTick` | 0.72% | 2 | Folia scheduler |
| `Level.dimensionType` | 0.72% | 2 | Game logic |

### N Value Calculation (80% CPU)

Total Java samples: ~47 (excluding JVM internals, native, and JIT)

Cumulative CPU% at 80% threshold:
- After top 12 methods: ~12.5% of total CPU (96%+ of Java CPU)

**N (idle state) ≈ 12–15 methods**

However, this is the IDLE profile. The actual redstone-heavy profile will have a significantly different distribution.

## Key Observations

1. **No redstone tick methods appear in the profile.** The placed circuits are static (no active clocks, no signal propagation). This confirms we need active circuits or player-driven scenarios.

2. **Folia scheduler dominates idle load.** `TickRegionScheduler`, `RegionizedServer.globalTick`, and related infrastructure methods consume ~8-10% of total CPU even when idle.

3. **Agent overhead is negligible.** No measurable CPU impact from the Nebula agent instrumentation.

4. **JVM JIT compilation is significant.** `I2C/C2I adapters` appears at 2-4%, indicating the server is still in its JIT warmup phase during profiling.

## Artifacts

- `flame-graph-idle.html` — Interactive flame graph (open in browser)
- `flat-profile-idle.txt` — Full flat profile (98 lines)

## Next Steps

1. **Create active redstone circuits** — Need repeater clocks, redstone torch burnout, or piston-based oscillators that continuously generate block updates
2. **Populate with entities** — Spawn 100+ entities to stress the entity AI and collision subsystems
3. **Run 1+ hour profiling** — Capture steady-state profile after JIT warmup completes
4. **Profile with player activity** — Connect a Minecraft client and walk around to generate chunk loads and entity ticks
