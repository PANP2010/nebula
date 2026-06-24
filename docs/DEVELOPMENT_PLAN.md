# Nebula 项目开发计划

**版本**: v1.0
**日期**: 2026-06-24
**当前分支**: feat/fix-folia-scheduler-v2
**远程测试机**: kuli@192.168.31.110 (/home/kuli/nebula)

---

## 一、项目现状摘要

### 实际完成度：约 35%

| 维度 | 状态 |
|------|------|
| 架构设计 | 完整（1546 行白皮书） |
| 代码量 | 187 源文件 / 17,305 行主代码 / 16,326 行测试 |
| 单元测试 | 659 个测试，全部通过 |
| 构建工具链 | Gradle 多模块、shadow jar、agent 均正常 |
| **端到端可用性** | **DAG 执行路径在真实 Folia 服务器上从未触发** |

### 核心问题

**代码质量扎实，但集成缺口导致核心功能在真实服务器上不可用。** 三个关键阻塞项：

1. **B1**: RedstoneTickHook 生命周期（beginTick/endTick）从未被调用 → DAG 不执行
2. **B2**: componentMap 在 DAG 执行时可能为空 → 无任务可执行
3. **B3**: NeighborUpdateInterceptor 的 BLOCK_UPDATE 拦截链路未验证

---

## 二、总体路线图



---

## 三、Phase 1：让 DAG 在真实服务器上跑起来

### 1.1 修复 RedstoneTickHook 生命周期

**问题**: RedstoneTickHook.beginTick() / endTick() 从未被调用。
NebulaFoliaBootstrap.activate() 设置了 executor 和 resolver，但生命周期方法没有被触发。

**已做**: 在 NebulaPlugin.onEnable() 中添加了 GlobalRegionScheduler.runAtFixedRate 定时任务，每 tick 调用 beginTick/endTick。

**待验证**:
- [ ] 部署到 Folia 服务器，确认 endTick 被调用
- [ ] 确认 endTick 中的 dirtyTasks 不为空（当有红石活动时）
- [ ] 确认 TickExecutor.executeTasks() 被调用

**风险**: GlobalRegionScheduler 的 tick 和 Folia 的 region tick 可能不同步。beginTick 在 global tick 上调用，但 recordUpdate 在 region 线程上调用——可能导致 dirtyPositions 的竞态条件。

**备选方案**: 如果 global scheduler 方案不行，通过 ASM 注入到 RegionizedWorldServer.tick() 中。

---

### 1.2 修复 WorldRedstoneScanner 同步扫描

**问题**: componentMap 在 onEnable() 时为空，延迟 100 ticks 的异步扫描可能在红石 tick 开始后才完成。

**已做**: 在 onEnable() 中添加了同步扫描逻辑，在注册任何定时任务之前扫描已加载区块。

**待验证**:
- [ ] 确认服务器启动时 componentMap 非空（日志显示 Initial sync scan complete: N components）
- [ ] 确认 componentMap 包含正确的红石元件类型

---

### 1.3 部署验证：放置红石 → DAG 执行

**步骤**:
1. [ ] 构建 shadow jar 并部署到 folia-test-server/plugins/
2. [ ] 启动 Folia 服务器
3. [ ] 通过 RCON 连接
4. [ ] 放置红石粉
5. [ ] 检查日志中是否有 DAG tick 或 executeOwnedDag 输出
6. [ ] 检查 componentMap 是否包含新放置的红石粉

**预期结果**: 日志中出现 DAG tick: N tasks, M microsteps in Xms

---

### 1.4 验证 NeighborUpdateInterceptor 拦截链路

**问题**: Agent 的 ASM 注入是否真的将 BLOCK_UPDATE 事件传递到 RedstoneTickHook.recordUpdate()？

**验证方法**:
1. [ ] 在服务器运行时，通过 RCON 执行 /setblock 放置/破坏红石元件
2. [ ] 检查 RedstoneTickHook.dirtyCount() 是否增长
3. [ ] 如果拦截不工作，检查 Agent 的 NeighborUpdateHooks 是否正确注册

**调试**: 在 NeighborUpdateInterceptor.onNeighborUpdate() 中添加临时日志，确认被调用。

---

## 四、Phase 2：运行验收测试

### 2.1 创建红石测试世界

**需求**: 一个包含标准红石电路的世界，用于 DG1 验收。

**电路清单**:
- [ ] 16 格红石线（直线传播）
- [ ] 红石火把反馈电路（火把 + 线）
- [ ] 中继器延迟线（4 档延迟）
- [ ] 比较器模式（比较/减法）
- [ ] 简单的 0-tick 脉冲发生器

**方法**: 通过 RCON 使用 /setblock 和 /fill 命令创建，或使用结构文件加载。

---

### 2.2 DG1 10k tick 零差异测试

**前置**: Phase 1 完成，DAG 正常执行。

**步骤**:
1. [ ] 启动 Capture Harness：/nebula capture start 10000
2. [ ] 等待 10000 ticks（约 8 分钟 @ 20 TPS）
3. [ ] 停止 Capture：/nebula capture stop
4. [ ] 检查 ReplayVerifier 输出：所有帧的哈希一致

**验收标准**: 10,000 tick 零差异

---

### 2.3 MSPT 测量

**步骤**:
1. [ ] 在无 Nebula 的情况下运行 Folia，测量基线 MSPT（使用 spark）
2. [ ] 在有 Nebula 的情况下运行相同负载，测量 MSPT
3. [ ] 计算 MSPT 降低率

**验收标准**: MSPT 降低 ≥ 30%（DG1 Criterion 3）

---

### 2.4 Capture Harness 端到端验证

**步骤**:
1. [ ] 启动 Capture Harness
2. [ ] 验证 WorldStateHasher.hashState() 在真实 Folia 世界上工作
3. [ ] 验证 ReplayRecorder 正确记录帧
4. [ ] 验证 ReplayVerifier 可以比较两段录制的哈希

---

## 五、Phase 3：修复性能问题和代码债

### 3.1 优化 NMS 同步

**问题**: executeOwnedDag() 对每个 task 执行 syncFromNms 和 syncToNms。对于大量 task，NMS 同步开销可能超过 DAG 执行本身。

**优化方案**:
- [ ] 对 ownedTasks 按 WorldPos 去重（多个 task 可能操作同一位置）
- [ ] 批量 NMS 操作（如果 bridge 支持）
- [ ] 仅在 power level 实际变化时执行 syncToNms

---

### 3.2 添加守护和日志

- [ ] componentMap 空值保护（已做基础版本）
- [ ] DAG 执行超时保护
- [ ] 微步骤溢出优雅降级
- [ ] 更详细的诊断日志（可通过配置开关）

---

### 3.3 构建环境可移植

**问题**: gradle.properties 硬编码了 Linux JDK 路径。

**方案**:
- [ ] 使用 org.gradle.java.installations.fromEnv 环境变量
- [ ] 添加 local.properties 支持（被 gitignore）
- [ ] 文档化构建要求

---

### 3.4 实体 DAG 集成

**问题**: EntityTickExecutor 和 CompositeTaskRunner 已创建但从未在真实服务器上执行。

**步骤**:
- [ ] 验证实体 task 生成逻辑
- [ ] 在 Folia 服务器上测试实体 tick
- [ ] 集成到 executeOwnedDag() 或独立的 tick 路径

---

### 3.5 扩展 @NebulaRW 标注

**问题**: 当前只有 7 个 @NebulaRW 注解，架构文档要求约 200 个。

**优先级**: 低（不影响核心功能验证，影响 DG3 验收）

---

## 六、Phase 4：DG2/DG3 验收

### 4.1 实体 50k tick 零差异

- [ ] 在测试世界中生成实体（动物、怪物）
- [ ] 运行 50k tick 回放
- [ ] 验证零差异

### 4.2 Random 预算验证

- [ ] 验证 RandomBudget 在真实负载下工作
- [ ] 验证超预算降级机制

### 4.3 VAP 兼容性测试

- [ ] 在 Folia 服务器上加载主流插件
- [ ] 验证 Level 0 兼容性

### 4.4 负载测试

- [ ] 实现模拟玩家基础设施
- [ ] 100 玩家负载测试
- [ ] 验证 20 TPS 稳定性

---

## 七、当前阻塞项优先级

| 优先级 | ID | 描述 | 状态 | 负责人 |
|--------|----|------|------|--------|
| **P0** | B1 | RedstoneTickHook 生命周期未触发 | 🔧 已修复，待验证 | - |
| **P0** | B2 | componentMap 可能为空 | 🔧 已修复，待验证 | - |
| **P0** | B3 | NeighborUpdateInterceptor 拦截链路未验证 | 📝 待验证 | - |
| **P1** | B4 | executeOwnedDag NMS 同步开销 | 📝 待优化 | - |
| **P1** | B5 | 缺少红石测试世界 | 📝 待创建 | - |
| **P1** | B6 | Capture Harness 从未端到端运行 | 📝 待验证 | - |
| **P2** | B7 | 构建环境 JDK 路径硬编码 | 📝 待修复 | - |
| **P2** | B8 | @NebulaRW 标注覆盖率低 | 📝 待扩展 | - |

---

## 八、每日工作建议

### Day 1: Phase 1 验证
1. 构建 shadow jar 并部署
2. 启动 Folia 服务器
3. 通过 RCON 放置红石粉
4. 检查 DAG 是否执行
5. 如果 DAG 不执行，调试拦截链路

### Day 2: Phase 1 完成 + Phase 2 开始
1. 确保 DAG 稳定执行
2. 创建红石测试世界
3. 启动 Capture Harness

### Day 3-4: Phase 2 验收
1. 运行 DG1 10k tick 测试
2. 测量 MSPT
3. 记录结果

### Day 5+: Phase 3 优化
1. NMS 同步优化
2. 实体 DAG 集成
3. 代码债清理

---

## 九、验收标准汇总

| 门禁 | 标准 | 当前状态 | 目标日期 |
|------|------|---------|---------|
| **DG1** | 红石 10k tick 零差异 | ❌ DAG 不执行 | TBD |
| **DG1** | 微步骤 ≤ 256 | ❌ 未验证 | TBD |
| **DG1** | MSPT 降低 ≥ 30% | ❌ 未测量 | TBD |
| **DG2** | 实体 50k tick 零差异 | ❌ 未验证 | TBD |
| **DG2** | Random 超预算率 < 1% | ✅ 单元测试通过 | TBD |
| **DG3** | 全系统零差异 | ❌ 未验证 | TBD |
| **DG3** | 插件 Level 0 ≥ 80% | ❌ 未测试 | TBD |
| **DG3** | 100 玩家 20 TPS | ❌ 未测试 | TBD |

---

## 十、风险登记

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| GlobalRegionScheduler 和 region tick 不同步 | 中 | 高 | 备选：ASM 注入到 RegionizedWorldServer.tick() |
| Agent 字节码注入在 Java 25 上不兼容 | 低 | 高 | 测试 Java 25 兼容性，备选：纯 Java 实现 |
| NMS bridge 在真实世界上行为异常 | 中 | 中 | 添加更多日志，逐步验证 |
| 红石测试世界创建复杂 | 低 | 中 | 使用结构文件或脚本自动生成 |
| MSPT 不达标 | 中 | 中 | 优化 NMS 同步，减少不必要的操作 |
