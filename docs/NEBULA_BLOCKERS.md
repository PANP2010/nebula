# Nebula Blockers — 诚实评估与修复计划

**最后更新**: 2026-06-24
**当前分支**: feat/fix-folia-scheduler-v2
**远程测试机**: kuli@192.168.31.110 (/home/kuli/nebula)

---

## 一、总体评估

### 实际完成度：约 35%

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | ★★★★★ | 白皮书 1546 行，形式化定义完整 |
| 代码量 | ★★★★☆ | 187 个源文件，17,305 行主代码，16,326 行测试 |
| 单元测试 | ★★★★☆ | 105 个测试类，659 个测试，全部通过 |
| 构建工具链 | ★★★★☆ | Gradle 多模块、shadow jar、agent 均正常 |
| Folia 集成 | ★★☆☆☆ | 加载成功但核心 DAG 执行路径从未触发 |
| 端到端可用性 | ★☆☆☆☆ | 在真实 Folia 服务器上 DAG 不执行 |

### 核心问题

**项目有大量高质量代码和测试，但核心执行路径在真实 Folia 服务器上从未被触发。** 
`FoliaRegionTickExecutor.executeTasks()` 被注册为 `RedstoneTickHook.TickExecutor`，但 `RedstoneTickHook.endTick()` 从未被调用——因为没有任何机制将 Folia 的 region tick 生命周期连接到 Nebula 的 tick hook。

---

## 二、阻塞项清单

### P0 — 阻塞一切验证

#### B1. RedstoneTickHook 生命周期从未触发

**问题**: `RedstoneTickHook.beginTick()` / `endTick()` 从未被调用。
`NebulaFoliaBootstrap.activate()` 设置了 executor 和 resolver，但 `beginTick`/`endTick` 需要被 Folia 的 region tick 循环调用——目前没有任何代码调用它们。

**根因**: `NebulaPlugin.onEnable()` 创建了 `FoliaRegionTickExecutor` 并注册为 `TickExecutor`，但 `RedstoneTickHook` 的 `beginTick`/`endTick` 生命周期方法没有被任何 Folia 调度器或 listener 触发。

**修复方案**: 
- 在 `NebulaPlugin` 中注册一个 `GlobalRegionScheduler` 的 `runAtFixedRate` 任务，在每个 global tick 调用 `RedstoneTickHook.beginTick()` 和 `RedstoneTickHook.endTick()`
- 或者通过 ASM 字节码注入到 Folia 的 `RegionizedWorldServer.tick()` 方法中

**影响**: 阻塞所有 DAG 执行、capture、零差异验证

---

#### B2. componentMap 在 DAG 执行时可能为空

**问题**: `WorldRedstoneScanner` 在 `onEnable()` 中通过 `GlobalRegionScheduler.runDelayed(100 ticks)` 扫描已加载区块。但如果服务器启动时没有已加载区块（新世界），或者扫描完成前红石 tick 已经开始，`componentMap` 为空导致 `RedstoneTaskGenerator` 无法生成任务。

**根因**: 扫描是异步的（通过 `RegionScheduler.execute` 分发到各 region 线程），而 Folia 的 redstone tick 可能在扫描完成前就开始。

**修复方案**: 
- 在 `onEnable()` 中立即扫描已加载区块（同步），而不是延迟 100 ticks
- 添加 `componentMap` 是否为空的检查，在为空时记录警告

**影响**: 导致 DAG 无任务可执行

---

#### B3. NeighborUpdateInterceptor 的 BLOCK_UPDATE 拦截未验证

**问题**: `NeighborUpdateInterceptor.onNeighborUpdate()` 通过 ASM 注入到 `CollectingNeighborUpdater` 中。服务器日志显示 "Instrumenting CollectingNeighborUpdater via agent" 成功，但实际拦截是否生效、是否将 BLOCK_UPDATE 事件传递到 `RedstoneTickHook.recordUpdate()` 未经验证。

**根因**: Agent 的字节码注入在 `NebulaClassFileTransformer` 中实现，但注入后的行为没有集成测试覆盖。

**修复方案**: 
- 在 Folia 测试服务器上放置红石元件，观察 `RedstoneTickHook.dirtyCount()` 是否增长
- 添加集成测试验证拦截链路

**影响**: 即使 B1 修复了，如果拦截不工作，DAG 仍然没有输入

---

### P1 — 阻塞性能验证和 DG1/DG2 闭合

#### B4. executeOwnedDag() 的 Phase 1/3 NMS 同步开销未测量

**问题**: `NebulaPlugin.executeOwnedDag()` 在每个 tick 对每个 task 执行 `blockBridge.syncFromNms()` 和 `blockBridge.syncToNms()`。对于大量 task，这会产生巨大的 NMS 同步开销。

**代码位置**: `NebulaPlugin.java:267-306`

**影响**: 可能使 MSPT 无法满足 DG1 Criterion 3（≥30% 降低）

---

#### B5. 测试服务器缺少红石测试世界 (已修复)

**问题**: DG1 验收标准需要 "标准红石测试世界" 来运行 10k tick 零差异验证。当前 `folia-test-server` 使用默认生成的平坦世界，没有红石电路。

**修复**: 使用 `folia-test-server/place-redstone-circuit.py` 脚本在 chunk (0,0) 的 y=72 高度放置了红石测试电路：
- 拉杆 (lever) 在 (0, 72, 0)，初始为 ON
- 红石线 (redstone_wire) 从 (1, 72, 0) 到 (15, 72, 0)
- 红石灯 (redstone_lamp) 在 (16, 72, 0)
- 电路直接写入 region 文件，服务器启动即生效

**影响**: 已解决。服务器启动后 DAG 引擎将有红石活动可处理。

---

#### B6. Capture Harness 从未在真实服务器上运行

**问题**: `FoliaCaptureHarness` 使用 `GlobalRegionScheduler.runAtFixedRate()` 注册了一个每 tick 回调，但：
1. 依赖于 B1 修复（RedstoneTickHook 生命周期）
2. `WorldStateHasher.hashState()` 在真实 Folia 世界上的行为未经验证

**影响**: 零差异验证框架从未端到端验证过

---

### P2 — 技术债和优化

#### B7. 构建环境 JDK 路径硬编码

**问题**: `gradle.properties` 硬编码了 Linux 路径，在 Windows 上无法构建。

---

#### B8. @NebulaRW 标注覆盖率低

**问题**: 架构文档要求约 200 个函数标注，当前只有 7 个 `@NebulaRW` 注解。

---

## 三、修复计划（按优先级）

### Phase 1: 让 DAG 在真实服务器上执行

| 步骤 | 描述 | 预计工时 |
|------|------|---------|
| **1.1** | 修复 RedstoneTickHook 生命周期——在 NebulaPlugin 中注册 GlobalRegionScheduler 定时任务调用 beginTick/endTick | 2h |
| **1.2** | 修复 WorldRedstoneScanner 同步扫描——在 onEnable 中立即扫描已加载区块 | 1h |
| **1.3** | 在 Folia 测试服务器上部署验证——放置红石粉，观察 DAG 是否执行 | 2h |
| **1.4** | 验证 NeighborUpdateInterceptor 拦截链路——检查 dirtyCount 是否增长 | 1h |

### Phase 2: 运行验收测试

| 步骤 | 描述 | 预计工时 |
|------|------|---------|
| **2.1** | 在测试服务器上创建红石测试世界（红石线、火把、中继器、比较器） | 2h |
| **2.2** | 运行 DG1 10k tick 零差异测试 | 2h |
| **2.3** | 测量 MSPT，评估 DG1 Criterion 3 | 1h |
| **2.4** | 运行 Capture Harness 端到端验证 | 2h |

### Phase 3: 修复和优化

| 步骤 | 描述 | 预计工时 |
|------|------|---------|
| **3.1** | 优化 executeOwnedDag() 的 NMS 同步（批量操作、去重） | 3h |
| **3.2** | 添加 componentMap 空值保护和日志 | 1h |
| **3.3** | 修复 gradle.properties 使其可移植 | 1h |

---

## 四、当前远程环境状态

```
主机: kuli@192.168.31.110
项目路径: /home/kuli/nebula
JDK: /usr/lib/jvm/java-21-openjdk-amd64 (编译), /usr/lib/jvm/java-25-openjdk-amd64 (运行)
Folia 服务器: /home/kuli/nebula/folia-test-server/
  - server.jar: Folia 26.1.2-8
  - plugins/nebula-plugin-0.1.0-SNAPSHOT.jar: 538KB
  - nebula-agent.jar: 26KB
  - 服务器日志显示 plugin 加载成功，扫描到 20 个红石组件
  - 但 DAG 从未执行（无相关日志）
测试: 659 tests, 0 failures (全部通过)
```

---

## 五、诚实结论

**Nebula 项目有扎实的架构设计和大量的高质量代码，但当前状态不是一个 "playable release"。** 

核心问题不是代码质量——而是**集成缺口**：DAG 执行引擎、MicroStepScheduler、Capture Harness 等核心组件在真实 Folia 服务器上从未被触发过。代码在单元测试中通过了自一致性验证，但端到端执行路径从未运行。

修复计划的核心是 **Phase 1**：让 DAG 在真实 Folia 服务器上执行一次。一旦这个目标达成，后续的验收测试和性能测量就可以顺利进行。
