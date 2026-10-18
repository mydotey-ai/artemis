# Phase 3：自我保护基础版 + 快照落盘回放 + readiness 门控

版本: 3.3    更新时间: 2026-10-18

## 目标

交付自我保护基础版（保护态本体与续约窗口阈值语义）、服务端注册表快照落盘与重启回放、readiness 门控（架构 §4.1 三机制），全部落在 phase-2 建成的 DDD 四层与端口体系内。

## 范围

做什么：

- **自我保护基础版**（资产 #10 继承、局限 #9 的 v0.1 阶段——按服务粒度阈值归 v0.2 自我保护 2.0）：全局续约滑动窗口统计 + 阈值判定 + 保护态本体（`isProtected()` 与统计查询接口）；保护态跳过租约过期清理；deregister 显式操作不受保护（架构 §4.1 已载边界）。
- **服务端快照**（修复局限 #5「注册数据零持久化」）：内存注册表定期落盘（快照带落盘时间戳）、重启回放**时效内**快照（默认最长 1h，超期视为无效）；损坏/缺失/超期快照统一丢弃 + WARN + 空表启动；原子写。
- **readiness 门控**（资产 #9 继承，修复原产品双重冷启动死锁）：启动就绪前不接客户端流量（gRPC UNAVAILABLE）；启动数据源**三分支优先级**（review 修订定案，架构 §4.1 v1.19）：① 有已就绪 peer → 直接全量同步，成功即 ready、失败定期重试不降级快照；② 无已就绪 peer → 回放时效内快照；③ 无有效快照 → 空表直接 ready。v0.1 无 peer 概念，分支 ① 恒假，落地 = 分支 ②/③，死锁不可能。
- 测试：内核单测 + 进程内集成测试（重启回放端到端）。

不做什么：保护态指标暴露与 Admin API 查询端点（phase-4 metrics / phase-5 状态 API，本 phase 只留查询接口）、按服务粒度保护（v0.2）、`stale` 标记出口（v0.3）、peer 校验与集群复制（v0.2）、快照的 peer 增量校验（v0.2 bootstrap）、force-up/down 运维旁路（**不继承**，D9——逃生走静态成员配置）、SCF 配置动态化（phase-4——本 phase 参数常量默认 + 构造注入可覆盖）、HTTP readiness endpoint（phase-4）、SDK 磁盘快照（phase-6，客户端侧）。

## 涉及模块

- `java/server/artemis-registry-core`：domain（SelfProtection、快照端口、ReadinessGate、InstanceRegistry 增量）、application（编解码 + 编排）、infrastructure（调度器、文件 store 实现）+ 单测。
- `java/server/artemis-registry-server`：装配（端口注入、bootstrap 回放编排）+ gRPC readiness interceptor + 重启回放 IT。

契约：**零变更**（快照为服务端内部持久化格式，复用既有契约消息 `common.v1.ServiceInstances` 做 wire 编码，不新增契约消息——冻结组不动）；新增依赖组件：无（文件 IO 用 JDK，proto 编解码已有）。

## 验收方式

对应测试文档：[phase-3-protect-snapshot-test.md](phase-3-protect-snapshot-test.md)

## 产品设计（③ 补）

### 1. 三机制关系与启动时序

```text
启动：装配构造（registry + protection + store）→ 启动数据源三分支（§4：v0.1 = 回放时效内快照或空表）→ markReady → gRPC 接流量
运行：心跳 → SelfProtection.recordRenewals（窗口计数）     定时任务 ×2：租约清理（evictExpired，保护态跳过）、快照落盘（writeSnapshot）
```

- 定期任务仍只有两个（架构 §4.1「registry 定期任务只有两个」）：租约过期清理、快照落盘。
- 三机制组合点全在应用层编排（`RegistryServiceImpl`），聚合（`InstanceRegistry`）仅新增两个正交入口（全量快照读、回放写），phase-2 已定型语义零改动。

### 2. 自我保护基础版（domain `SelfProtection`）

```java
/** 续约滑动窗口统计与保护态判定（资产 #10 继承；按服务粒度归 v0.2 自我保护 2.0）。线程安全、全方法非阻塞。 */
public final class SelfProtection {
    public SelfProtection(SelfProtectionConfig config, MonotonicClock clock) {}

    /** 记录一次心跳的续约量（逐实例计数；显式 register/update/deregister 与复制事件不计——非续约信号）。 */
    public void recordRenewals(int instanceCount) {}

    /** 保护态判定：maxCount ≥ minCount（默认 50，启用下限）且当前窗口续约量 < maxCount × factor（默认 0.85）。 */
    public boolean isProtected() {}

    /** 窗口统计快照（当前窗口量、历史 maxCount、是否保护）——供 phase-4 指标 / phase-5 状态 API 消费。 */
    public SelfProtectionStats stats() {}
}

public record SelfProtectionConfig(Duration window, double factor, int minCount) {}   // 默认 10s / 0.85 / 50
public record SelfProtectionStats(long windowRenewals, long maxCount, boolean protected_) {}
```

- **窗口实现**：按秒分桶环形数组（桶数 = window/1s，默认 10 桶）+ 每桶 `LongAdder`（guidelines §4.1「聚合统计用 LongAdder」）；写入按 `monoMillis/1000` 落桶并惰性回收过期桶（时间回退不发生——单调钟）；读取聚合当前窗口内桶。写路径每心跳一次 `add`（按请求数聚合后单次 add，非逐实例 add），读路径每轮清理一次聚合。
- **判定语义**（对齐原产品 `LeaseUpdateSafeChecker` 证据，legacy §2.3）：`maxCount` = 启动以来窗口续约量的历史最大值（只增不减）；未达 `minCount`（50）不启用判定——小规模集群/冷启动不误保护；低于 `maxCount × 0.85` 进入保护态。语义参数化继承（window/factor/minCount），**不复制全局单阈值的粒度局限**（按服务粒度 v0.2 演进，架构 §4.1 已载）。
- **作用边界**：`RegistryServiceImpl.evictExpired()` 入口判定——`if (protection.isProtected()) return 0; return registry.evictExpired();`。保护态**跳过整轮过期清理**（与原产品「跳过所有仅过期的清理」一致）；deregister 显式摘除路径不经过该判定（天然不受保护，架构 §4.1 边界）。
- **回放与计数**：回放恢复的实例不计入续约窗口（非心跳信号，从零开始统计——与原产品冷启动一致，不误触发保护）。
- **可观测预留**：`stats()` 是 phase-4 指标（`artemis.registry.selfprotection.*`）与 phase-5 状态 API 的数据源，本 phase 不接端点。

### 3. 服务端快照（修复局限 #5）

```java
// ---- domain 端口（存取 bytes；格式与编解码归 application 的 dto 边界） ----
/** 快照存取端口：read 返回最近一次成功落盘的快照字节；无快照/损坏返回 empty。 */
public interface RegistrySnapshotStore {
    Optional<byte[]> read();
    void write(byte[] snapshot);
}

// ---- domain 端口（类比 LeaseEvictionScheduler，D12 先例） ----
/** 快照落盘调度端口：周期性触发快照写。 */
public interface SnapshotScheduler extends AutoCloseable {
    void start();
    @Override void close();
}

// ---- InstanceRegistry 增量（phase-2 语义零改动，两个正交新入口） ----
/** 全量快照读（共享引用聚合，供落盘）；回放写：逐 service 重建租约，续约时刻 = now，不派发事件。 */
public List<ServiceInstances> snapshots();
public void restore(List<ServiceInstances> services);
```

- **编排**（application，`RegistryServiceImpl` 增量）：
  - `writeSnapshot()`：`registry.snapshots()` → 编码为 bytes（含落盘时间戳，墙钟 `java.time.Clock` 取 now）→ `store.write(bytes)`（供调度器周期触发与 IT 手动触发）。
  - `restoreFromSnapshot()`：`store.read()` → 解码 → **时效判定**（落盘时间戳距今 ≤ maxAge，默认 1h）→ `registry.restore(services)` → 返回恢复实例数；空/损坏/**超期**快照记 WARN 返回 0（**丢弃不拒绝启动**——回退等客户端重注册，与原语义一致；readiness 不受影响，见 §4）。
- **文件格式**（编解码在 application 手写，~20 行；不新增契约消息）：

  ```text
  magic "ARTSNAP" + 1 字节格式版本(1) + 8 字节落盘时间戳(epoch millis，墙钟) + 4 字节条目数 + 逐条 [protobuf delimited 帧（varint 长度前缀）+ common.v1.ServiceInstances bytes]
  ```

  复用既有契约消息 `ServiceInstances` 做 wire 编码（proto 前向兼容免费获得；mapper 复用 `RegistryProtoMapper` 的既有转换）。**快照是服务端内部持久化格式而非 wire 契约**——格式版本只管容器结构，条目演化靠 proto 字段兼容；时间戳字段为容器结构一部分（本 phase 格式尚未产出过，无兼容包袱，直接定入版本 1）。
- **原子写**（infrastructure `FileRegistrySnapshotStore`，实现 `RegistrySnapshotStore`）：写临时文件 → `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` → fsync 目录；读侧长度/magic 校验失败按损坏丢弃。**路径静态配置**（guidelines §10.2 快照路径属静态项），本 phase 常量默认 `data/registry-snapshot.bin` + 装配可注入；**时效阈值 maxAge 同承载**（常量默认 1h + 装配可注入）。
- **调度**（infrastructure `ScheduledSnapshotScheduler`，先例 `ScheduledLeaseEvictionScheduler`）：命名单平台线程 `artemis-snapshot-writer`、`scheduleWithFixedDelay`、任务兜底 `Throwable`、`close()` 释放；**周期可配置**（常量默认 **5min** + 装配注入覆盖，SCF 动态化归 phase-4）——最坏丢失窗口 = 一个快照周期的重注册，快照只是减少重注册风暴而非零丢失。
- **回放语义**：`restore` 续约时刻取回放时刻 `now`——TTL 从回放起算（重启宽限，客户端断连期间的过期不在回放时结算）；**不派发事件**（v0.2 起复制 listener 在位，回放事件若扇出会放大到 peer——bootstrap 全量/增量校验归 v0.2，本阶段先固化「回放零事件」不变量）。
- **时效语义**（review 修订新增）：只回放「最近且有效」的快照——落盘时间戳距今超过 maxAge（默认 1h）即无效，与缺失/损坏同处置。理由：快照周期 5min（可配），正常陈旧度 ≈ 周期 + 停机时长；停机超 1h 后实例大多已消亡/换址，回放陈旧数据的收益低于让客户端重注册（心跳即注册，自愈快）。判定含时钟回拨宽容：`elapsed = now − snapshotTime` 在 `[-1min, 0)` 时（小回拨）视为时效内，避免误杀快照；超前超过 1min（损坏为大正数或异常快进时钟写入）按超期处置——单向宽容会让此类快照恒通过时效检查。单快照文件设计下「最近」即最近一次成功落盘，无多版本选择问题。时间戳用**墙钟**而非单调钟：时效判定跨重启比较（落盘进程与回放进程不同），单调钟仅进程内可比、重启即失效——与 ADR-015「内部时长判定单调钟、跨进程时间戳墙钟」的端口分型一致（租约 TTL/保护窗口是前者，快照时间戳是后者）。

### 4. readiness 门控（资产 #9 继承，修复空集群死锁）

```java
// application（用例编排级状态；无依赖，纯 JDK）
/** 就绪门：启动步骤（快照回放 + 存在已就绪 peer 时的 peer 全量同步）完成前不接客户端流量。 */
public final class ReadinessGate {
    public boolean isReady() {}
    public void markReady() {}   // 单向（无回退——v0.2 优雅停机再议）
}
```

- **ready 条件（长期语义，review 二轮修订）**：启动数据源按优先级**三分支**，取得启动数据即 ready：
  - **① 有已就绪 peer**：直接从任一已就绪 peer 全量同步（**跳过本地快照回放**——peer 是更新真相源，同步数据重建注册表）；同步成功即 ready（不设数据非空门槛，0 实例也算成功）；**同步失败定期重试、保持不 ready、不降级本地快照**——数据源存在时带旧数据服务客户端是错误行为，等待最终可满足，不构成死锁（重试由 v0.2 bootstrap 编排承载）。
  - **② 无已就绪 peer**：回放本地**时效内**快照（§3 时效语义；超期/损坏/缺失均视为无效）。
  - **③ 无有效快照**：空表直接 ready（心跳即注册，客户端自愈补齐）。
  - 对原产品双重冷启动死锁（legacy runtime §3.3）的修复落点：
    - 死锁一（无 UP peer 永不就绪）：等待条件依赖「∃ 已就绪 peer」——全冷启动时条件为假，走 ②/③ 直接 ready，单节点/空集群不存在等待条件；
    - 死锁二（`replicationInstanceCount > 0` 空数据门槛）：分支 ① 同步成功即达标——peer 数据为空（0 实例）也算同步成功，数据非空校验是被修复的缺陷而非被继承的语义。
- **v0.1 落地形态**：无集群成员概念，分支 ① 恒为假 → v0.1 落地 = 分支 ②/③（回放时效内快照或空表，含无快照/损坏/超期快照丢弃后的空表）；分支 ①（peer 全量同步 + 失败定期重试）在 v0.2 集群复制在位时插入 `markReady()` 之前，本语义无需再改（「回放零事件」不变量 D4 同时约束 bootstrap 同步写入路径）。
- **force 开关族不继承**（review 修订补处置，原产品 force-up/force-down 6 键旁路，legacy replication-cluster-logic §7.3–7.6 / runtime §3.4）：原产品 force-up 是空集群死锁的引导补丁，且行级证据判其为缺陷簇——§7.4「跳过全部初始同步、未同步节点直接服务、属危险开关」、§7.5 force 后两套判定口径不等价、§7.6 DOWN 粘性无恢复；发布样例配置常开 force-up，门控实践形同虚设。新设计处置：
  - 死锁根因消除（无已就绪 peer → 直接 ready）后，force-up 的引导必要性消失；
  - 唯一长等待场景（有已就绪 peer 但同步持续失败 = 分区/成员配置错）的逃生路径为**调整静态成员配置移除故障 peer**——重启后按「无已就绪 peer」直接 ready：与 force-up 效果等同，但收进自洽的单节点语义；force-up 形态（留在集群但跳过同步）在 v0.2 复制在位后必然产生写分叉与复制风暴，是被排除的形态；
  - force-down 的运维意图（临时摘除服务）由进程停机/成员配置移除承载，DOWN 粘性缺陷（§7.6）不复制；
  - 语义可推理性：ready 为确定性谓词（本地步骤 + peer 条件），无人工旁路时行为可从配置与集群状态完整推出——历史证据（样例常开）表明旁路开关会被滥用于规避而非逃生。
- **流量拦截**：server 模块 gRPC global server interceptor（spring-grpc 官方注册机制）逐 rpc 检查 `gate.isReady()`，未就绪 `UNAVAILABLE`（错误码语义「服务暂不可用」，客户端 SDK 重试语义天然覆盖）——比「控制启动顺序先回放再 bind」可靠（gRPC server 生命周期由 starter 管理，装配顺序不受控；interceptor 同时天然覆盖 v0.2 peer 校验窗口）。
- **时序**：bootstrap（`RegistryConfiguration` 或 `ArtemisRegistryServer`）：构造 → `restoreFromSnapshot()` → `markReady()`——同步在 context 刷新完成前执行，正常路径 interceptor 永远放行；interceptor 的拒绝窗口只出现在异常装配/测试注入场景，行为仍须可测（单测 + IT 可控 gate）。

### 5. 涉及模块与分层落点

| 层 | 增量 |
|---|---|
| domain | `SelfProtection`（+config/stats record）、端口 `RegistrySnapshotStore`/`SnapshotScheduler`、`InstanceRegistry.snapshots()/restore()` |
| application | `RegistryServiceImpl` 增量（evictExpired 保护判定、heartbeat 计数、writeSnapshot/restoreFromSnapshot、快照编解码）、`ReadinessGate` |
| infrastructure | `FileRegistrySnapshotStore`（原子写）、`ScheduledSnapshotScheduler` |
| server | 装配增量（端口注入 + bootstrap 回放编排 + interceptor）、`ReadinessInterceptor` + 重启回放 IT |

- 聚合不感知保护（判定在应用编排）；保护计数不进聚合锁路径（应用层请求数聚合后单次 add——高频路径零锁开销）。

### 6. 关键设计决策（review 确认项）与架构同步落点

| # | 决策 | 触及的原始文档与同步落点 | 状态 |
|---|---|---|---|
| D1 | 保护判定与计数在**应用层编排**（`evictExpired` 入口判定、heartbeat 请求数聚合计数），聚合零改动——直喂聚合的 v0.2 复制路径天然不计入续约统计（复制事件非续约信号，语义正确） | 本节 §2/§5；架构 §4.1 已载保护边界，无需改 | 无需改原始文档 |
| D2 | 保护语义参数继承原产品证据（window 10s / factor 0.85 / minCount 50，legacy §2.3），`maxCount` 只增不减、未达 minCount 不判定 | **产品文档**（⑩ 配置默认值登记） | ⑩ 执行 |
| D3 | 快照容器格式自定（magic + 版本 + 落盘时间戳 + 条目数 + protobuf delimited 帧 `common.v1.ServiceInstances`），**不新增契约消息**（快照是内部持久化格式非 wire 契约，冻结组不动）；编解码归 application dto 边界，store 端口只存取 bytes | 本节 §3；**proto-contract.md 无需改**（契约零变更） | 无需改原始文档 |
| D4 | `restore` 不派发事件、续约时刻 = 回放时刻（TTL 从回放起算）——「回放零事件」为 v0.2 复制在位后的不变量 | 本节 §3；ADR-012 无涉（回放非客户端操作） | 无需改原始文档 |
| D5 | readiness 语义 = 启动数据源**三分支优先级**：① 有已就绪 peer → 直接全量同步（跳过本地快照回放），成功即 ready（不设数据非空门槛），失败定期重试、不降级快照；② 无已就绪 peer → 回放时效内快照；③ 无有效快照 → 空表直接 ready——原产品双重冷启动死锁（无 peer 永不就绪 + 空数据门槛）修复落点，review 二轮修订定案；拦截用 gRPC global interceptor + UNAVAILABLE | 本节 §4；**架构 §4.1**（「readiness 门控（继承）」句三分支重写 + 持久化节快照时效，v1.19 已同步） | 已同步 |
| D6 | 损坏/缺失/**超期**快照统一丢弃 + WARN + 空表启动（不拒绝启动、不影响 ready） | 本节 §3；产品文档（⑩ 行为登记） | ⑩ 执行 |
| D7 | 快照端口设计：`RegistrySnapshotStore`（bytes 存取）+ `SnapshotScheduler`（调度端口，D12 先例）；周期可配置（默认 5min）、原子写（tmp + ATOMIC_MOVE） | 本节 §3；**ADR-015**（端口枚举补两端口，v1.8 已同步） | 已同步 |
| D8 | `SelfProtection.stats()` 为 phase-4/5 可观测数据源，本 phase 不接端点（plan.md phase-3/4 分工说明已载） | plan.md 已载，无需改 | 无需改原始文档 |
| D9 | **force 开关族不继承**（force-up/force-down 6 键旁路）：死锁引导必要性随 D5 语义消除；逃生路径 = 调整静态成员配置（移除故障 peer → 重启后走 ②/③ 直接 ready，单节点语义自洽）；DOWN 粘性/口径分裂缺陷不复制（legacy §7.3–7.6） | 本节 §4；**架构 §4.1**（readiness 句补「不继承 force 开关族」+ 逃生路径）与 **§8 资产 #9 行**（处置标注），v1.18 已同步 | 已同步 |
| D10 | **快照时效判定**（review 二轮修订新增）：落盘时间戳入容器格式（epoch millis，墙钟 `java.time.Clock`——跨进程语义，ADR-015 端口约定）；回放校验 `now − snapshotTime ≤ maxAge`（默认 1h，常量 + 装配注入）；时钟回拨宽容有界（elapsed ∈ [-1min, 0) 视为时效内，超前 > 1min 按超期丢弃——防损坏/快进时钟使 maxAge 失效）；理由：停机超 1h 后陈旧数据回放收益低于客户端重注册自愈 | 本节 §3；**架构 §4.1** 持久化节（快照时效句，v1.19 已同步）；产品文档（⑩ maxAge 默认值登记） | 已同步（产品文档 ⑩ 执行） |

不新增 ADR（三机制均为架构 §4.1 已载机制的实现细化；无否决备选量级的架构决策——快照格式/interceptor 形态为实现决策，记本表）。

## 实现结果（⑦ 补）

实现要点与 review 修复记录。

### code-review 终审修复（22 条：3 high / 6 medium / 13 low）

| # | finding | 处置 |
|---|---|---|
| 1 | `SelfProtection` 首调从 0 逐秒追赶 O(主机 uptime)（30 天实测 ~0.4s） | 构造时以 `clock.monoMillis()/1000` 初始化游标；补「大原点不追赶」「负单调秒」两个回归用例 |
| 2 | codec 按损坏长度分配内存 → OOME 拒绝启动（违 D6） | 改用 protobuf delimited 帧（`parseDelimitedFrom` 自带长度校验）；条目数先与剩余字节数校验再分配；补损坏长度用例 |
| 3 | 裸文件名路径 `getParent()` NPE → 快照静默永不落盘 | 目标路径先 `toAbsolutePath()` 再取父目录；补裸文件名用例 |
| 4 | 回放绕过校验/归一门，语义损坏条目直入注册表 | 回放前走既有 `validateServices`（空 instanceId/空 key 丢弃、weight 归一）；补用例 |
| 5 | 快照路径走 Spring `@Value`，违反「配置一律走 SCF」 | 生产装配回归常量默认；IT 以 `@Primary` 测试 bean + 测试侧 `@Value` 指向 `@TempDir` |
| 6 | maxAge 只查上界，未来时间戳恒通过 | 双向时效：超前超过 1min 回拨宽容按超期丢弃；补远未来用例（D10 语义同步） |
| 7 | rename 前 temp 未 fsync，断电半写 | `FileChannel.write` + `force(true)` 后再 move |
| 8 | ⑪.3 自动 push 与 git.md 冲突 | 不改 git.md；`CLAUDE.md` 补全局规则优先级条款（工作流明确要求优先于通用规则） |
| 9 | 固定 `.tmp` 名并发写互相撕裂 | 临时文件名加 UUID；补并发写不撕裂用例 |
| 10 | ① 幂等续用会误接已完工分叉分支 | `.claude/commands/dev-java.md` ① 补 guard：先报告分支/完工状态、由用户决定，不静默续用 |
| 11 | 设计文档两处 `registry.v1` 与实现 `common.v1` 不符 | 两处回写为 `common.v1.ServiceInstances` |
| 12 | D14 墙钟注释未划出快照时效例外 | `RegistryConfiguration.clock()` 注释补 D10 例外 |
| 13 | `ApplicationContextTest` 未隔离快照文件系统 | 补 `@Primary InMemorySnapshotStore` |
| 14 | 清理调度器可先于 restore 启动（靠锁巧合） | `@DependsOn("registryBootstrap")` 显式化顺序 |
| 15 | 负单调秒 % 取负索引 → AIOOBE | 环形索引用 `Math.floorMod`；`sumWindow` 去掉 0 下界假设 |
| 16 | 跨轮迟到写者污染未来窗口统计 | 超出窗口的迟到写直接丢弃；Javadoc 修正 |
| 17 | heartbeat 冗余守卫与被调方 no-op 重复 | 删除 `if (renewals > 0)` 包裹 |
| 18 | 心跳测试样板第三份拷贝 | 抽出包内 `Heartbeats.collect`，三处调用点复用 |
| 19 | 自制 4B 长度帧可用 protobuf delimited 替代 | 已随 #2 改为 delimited 帧（设计 §3/D3 同步） |
| 20 | 读失败记为 missing，运维诊断误导 | 端口 javadoc 声明读写语义；store 对不可读与缺失分别记日志 |
| 21 | NFS 等不支持原子 rename → 快照永久失效 | 捕获 `AtomicMoveNotSupportedException` 降级为普通 move |
| 22 | IT setUp 不重置 `SelfProtection`，计数跨用例累积 | 补 `SelfProtection.reset()` 测试钩子并在 `RegistryGrpcIT.setUp` 调用 |

- 未改 `git.md`（用户定案）：冲突经 `CLAUDE.md` 全局「规则优先级」条款消解。
- 设计语义唯一变更：D10 时钟回拨宽容由「无界」收为「1min 有界」，防损坏/快进时间戳使 maxAge 失效；架构 §4.1 未载回拨细节，无需改。

## 文档同步（⑩ 填）

- **产品文档 `docs/product/artemis.md`（v1.2）**：新增 phase-3 交付节——自我保护参数（10s / 85% / 下限 50）、快照落盘回放（5min、maxAge 1h、默认路径、回拨宽容 1min）、readiness 门控与 UNAVAILABLE 语义；后续能力归属更新为 phase-4/5/6。
- **架构 `docs/arch/artemis-next-architecture.md`（v1.20）**：§4.1 持久化节补「时间戳超前 now >1min 视为无效」（D10 回拨宽容有界）。
- **ADR**：无新增（三机制均为 §4.1 已载机制的实现细化；D9 force 开关族处置已随设计阶段入 §4.1/§8，无新架构决策）。
- **API 文档**：无变更（本 phase 契约零变更）。
- **索引**：`docs/milestones/v0.1/phases/README.md` 已于开工登记两份 phase-3 文档；无新增文档，各级索引无需变更。
- **CLAUDE.md**：当前状态/构建命令/模块结构未因本 phase 过时；另已补「规则优先级」全局条款（review 修复 finding 8）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.3 | 2026-10-18 | ⑨ 验证通过；⑩ 文档同步落点登记（产品 v1.2、架构 v1.20、无新 ADR/API 变更） |
| 3.2 | 2026-10-18 | ⑦ review 修复：22 条 findings 处置记录入「实现结果」；D10 回拨宽容收为 1min 有界；D3/§3 快照条目改 protobuf delimited 帧、`common.v1` 回写；规则冲突经 CLAUDE.md 优先级条款消解 |
| 3.1 | 2026-10-15 | review 三轮修订：快照落盘周期可配置、默认 30s → **5min**（D7）；时效语义补墙钟选择理由（跨重启比较、单调钟仅进程内可比） |
| 3.0 | 2026-10-15 | review 二轮修订（启动恢复语义重设计）：D5 改三分支优先级——① 有已就绪 peer 直接全量同步（失败定期重试不降级快照）② 无 peer 回放**时效内**快照 ③ 均无空表 ready；新增 D10 快照时效判定（时间戳入容器格式、maxAge 默认 1h、回拨宽容）；架构 §4.1 v1.19 同步 |
| 2.3 | 2026-10-15 | D7 架构同步落盘：ADR-015 端口枚举补 `RegistrySnapshotStore`/`SnapshotScheduler`（v1.8），review 对照补齐 |
| 2.2 | 2026-10-14 | review 修订补 D9：显式处置原产品 force 开关族——不继承（缺陷簇证据、死锁引导必要性消除、逃生走静态成员配置）；架构 §4.1 + §8 资产 #9 同步（v1.18） |
| 2.1 | 2026-10-14 | review 修订 D5：ready 条件改为「本地步骤完成 AND（∃ 已就绪 peer → 成功全量同步一次，不设数据非空门槛）；无已就绪 peer 不等待」；架构 §4.1 同步（v1.17） |
| 2.0 | 2026-10-14 | ③ 设计定稿（初版，待人工 review） |
| 1.0 | 2026-10-14 | 开工骨架 |
