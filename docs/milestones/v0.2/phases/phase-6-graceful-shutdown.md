# Phase 6：优雅停机与集群验收收尾

版本: 1.4    更新时间: 2026-10-23

## 目标

实现 registry 节点优雅停机（先标记自身下线再停止服务，客户端凭容灾换址），并收口 roadmap v0.2 端到端验收场景。

## 范围

- **做**：优雅停机序列（标记 DOWN → drain 等待 → 终刷快照 → 交还 Spring 关端口）；`ReadinessGate` 三态扩展（兑现 v0.2 预留「markReady 后无回退再议」）；**starter 优雅下线时序修正**——`ContextClosedEvent` 监听自动 close（注销提前到 context close 第一时机、程序还可用时主动执行）；停机节点重启后 bootstrap 恢复与全集群一致的端到端场景 IT；运维说明（产品文档）。
- **不做**：连接/流迁移（架构定案：客户端凭容灾换址）；gRPC GOAWAY 主动断流（端口关闭即断流信号，drain 期间已建流完整服务）；DOWN 广播推送（无推送通道——peer 探测 5s 周期观测收敛 + SDK 寻址刷新/熔断触发重寻址承接）；单节点特殊分支（「单节点 = 1 成员集群」原则，同一停机序列）；artemis-client 改动（SDK close 语义 v0.1 已交付——停心跳/在途屏障/deregisterAllBestEffort/幂等，`DefaultArtemisClient.close()`；phase-5 容灾能力承接换址）；starter 新配置项（时序修正是行为修正，非功能开关）。

## 涉及模块

- `java/server/artemis-registry-core`：`application/ReadinessGate`（三态）、`application/ReplicationServiceImpl.nodeStatus()`（self state DOWN 优先级）。
- `java/server/artemis-registry-server`：新增停机钩子（`ApplicationListener<ContextClosedEvent>`）+ `RegistryConfiguration` 装配 + IT。
- `java/client/spring-boot-starter-artemis-client`：自动装配加 `ContextClosedEvent` 监听（close 时序修正）+ 单测。
- `java/client/artemis-client`：零改动。
- `proto/`：契约零变更（`NODE_STATE_DOWN` phase-3 已预留「显式下线」，本 phase 消费）。

## 验收方式

对应测试文档：[phase-6-graceful-shutdown-test.md](phase-6-graceful-shutdown-test.md)

IT：停机前节点标记 down、客户端凭容灾换址；3 节点集群故障/恢复端到端场景全绿；运维说明同步。

## 产品设计（③ 补）

### 行为序列（优雅停机）

停机钩子（`GracefulShutdownHook`，registry-server，`ApplicationListener<ContextClosedEvent>`，`AtomicBoolean` 一次性防重复 close）在 context close 第一时机执行，完成后交还 Spring 正常关闭：

1. **标记 DOWN**：`readinessGate.markDown()`。效果（全部由既有 readiness 联动承接，零新门控逻辑）：
   - 节点状态 API self state = `DOWN`（`nodeStatus()` self 判定优先级 `DOWN > (ready ? UP : STARTING)`）——`GetNodeStatus` 始终应答（观测面豁免照旧），peer 后台探测（5s 周期）原样传播自报 state → 集群节点状态 API 与 SDK `GET /cluster/nodes`（同源同构）均见 DOWN，SDK 选址降权（phase-5：`UP > UNKNOWN/STARTING > DOWN`）。
   - `isReady()` 翻 false：`ReadinessInterceptor` 拒**新**外部 rpc（UNAVAILABLE）——新开心跳流被拒，SDK `markBroken` 快速换址；`Replicate`/`Bootstrap` 返回 UNAVAILABLE（peer 发送侧按暂时失败保序重试 / bootstrap 候选轮换）；`/readyz`、`/cluster/nodes` 返回 503（未就绪节点不作寻址应答者）；快照周期调度停刷（not ready 不落盘）。
   - **已建立的心跳流不受影响**（interceptor 只拦新 rpc）：drain 期间现有连接完整服务、续约照常 ack——这是 drain 的语义：不接新流量、存量连接服务到端口关闭。
2. **drain 等待**：`artemis.registry.shutdown.drainMs`（默认 10000，值域 [0, 600000]，0 = 不等待）。窗口供 peer 探测观测 DOWN、SDK 冷启动/重寻址避开本节点、运维摘流量；停机时刻读一次，无热更需求。
3. **终刷快照**：**仅当节点曾就绪**时 `registryService.writeSnapshot()` 显式调用一次（不受 readiness 门控——终刷时注册表是运行时全量数据，非启动期空表场景；未就绪节点在分支① 同步重试/启动失败中注册表为空或残缺，写盘会覆盖可回退的旧快照，故跳过——与快照调度器「not ready 不落盘」同一理由）。重启走分支②/③时数据新鲜（租约多数仍在 TTL 内），收敛快于回放长龄快照；分支①（有已就绪 peer）不受影响。
4. **交还 Spring**：gRPC server 由 spring-grpc starter 的 SmartLifecycle（phase = MAX_VALUE）停端口 → 已建流断开 → SDK onError → 换址重连（exclude 当前地址）→ 新节点开流全量上报即修复该节点数据（客户端轮换即分布式对账，ADR-006）→ 各 bean destroy（扇出/清理/探测调度器关停）。

**gRPC 关停宽限期（实现定案）**：spring-grpc 默认 `spring.grpc.server.shutdown.grace-period = 30s`，且已建心跳流不会自行结束（客户端持续续约）——默认值下停机总时长 = drain + 30s，超出常见 K8s 默认终止宽限期。排水窗口已承担「存量流量服务到底」职责，`application.properties` 显式设 **1s**（只给在途调用收尾，随后强制断流触发客户端换址）。K8s `terminationGracePeriodSeconds` ≥ drainMs + 1s + 余量。

**顺序依据**：`AbstractApplicationContext.doClose` 先发布 `ContextClosedEvent` 再执行 SmartLifecycle stop——事件监听确保停机序列（标记/等待/终刷）在 gRPC 端口关闭之前完成；SmartLifecycle phase 已被 starter 占用 MAX_VALUE，无法再插入更高 phase，事件监听是唯一先行落点。

**杀节点（非优雅停机）对照**：kill 无停机钩子——客户端靠断流熔断换址（phase-5）、数据靠 peer 副本 + 客户端重注册收敛；优雅停机只是加速收敛（DOWN 提前避开 + 终刷快照）+ 运维观测信号，两条恢复路径同构。

### ReadinessGate 三态

```
NOT_READY --markReady()--> READY --markDown()--> DOWN
NOT_READY --markDown()--> DOWN        （未就绪即停机：直接 DOWN）
```

- `markDown()` 幂等（重复调用无效果）；`markReady()` 在 DOWN 后无效（不复活）。
- `isReady()` = `state == READY`（既有调用方零改动）；新增 `isDown()`。
- 状态用单个 `volatile` 字段发布，读侧无锁（现有模式）。

### 接口签名（core，实现细节 ④ 定）

```java
public final class ReadinessGate {
    public boolean isReady();   // = state == READY
    public void markReady();    // NOT_READY → READY
    public boolean isDown();
    public void markDown();     // * → DOWN（幂等）
}

// server：停机钩子（编排，无新抽象端口）
final class GracefulShutdownHook implements ApplicationListener<ContextClosedEvent> {
    // 序列：markDown → drain(drainMs) → writeSnapshot；一次性执行，异常不阻断后续关闭
}
```

### 配置键（1 个新键）

| 键 | 默认 | 值域 | 语义 |
|---|---|---|---|
| `artemis.registry.shutdown.drainMs` | 10000 | [0, 600000] | DOWN 标记后停端口前的排水等待（0 = 不等待） |

K8s 部署下 `terminationGracePeriodSeconds` 须 ≥ drainMs + 端口关闭耗时（运维说明落产品文档）。

### starter 优雅下线时序修正（SDK 侧）

两条优雅停机链正交，同一原则（**关闭发生在程序还可用时的主动动作**，两条链的注销/标记都在 `ContextClosedEvent` 第一时机执行）：

| | server 节点停机（本 phase） | SDK 停机（v0.1 close 语义 + 本 phase 时序修正） |
|---|---|---|
| 触发 | SIGTERM → context close | 业务调 `close()` / starter 自动 |
| 动作 | 标记 DOWN → drain → 终刷 → 停端口 | 停心跳 → 等在途上报屏障 → deregister 全部实例（尽力而为）→ 释放资源 |
| 实例去向 | 不下线——连接层换址，业务无感续约 | 立即下线（deregister）/ 兜底 TTL 过期 |

starter 现状 `@Bean(destroyMethod="close")` 使注销发生在 **bean 销毁阶段**（context close 流程末段：宿主 web/gRPC server 已在 SmartLifecycle 阶段停流、bean 逆依赖销毁顺序不可控）——注销在「程序半死」状态下尽力而为，失败退化为 TTL 30s 下线；且宿主排水期间实例仍被调用方发现，时序倒置。修正：

- 自动装配注册 `ApplicationListener<ContextClosedEvent>`（`ObjectProvider<ArtemisClient>` 惰性解析），事件即调 `close()`——注销提前到第一时机（bean 全活、宿主尚未停流，deregister 在网络/依赖完全可用时发出）。
- `destroyMethod="close"` 保留为兜底（`close()` 幂等，正常路径事件已关闭、destroy 阶段为 no-op；事件漏网的非标准关闭路径仍有保障）。

### 观测

- log：INFO 三条（标记 DOWN 含 nodeId、drain 完成、终刷实例数）——停机是状态变迁，符合 guidelines §6 级别语义。
- 零新指标：进程即将退出 counter 无消费意义；DOWN 态经节点状态 API / peer 观测既有通路可查。
- 复用既有：`/readyz` 503、`artemis.registry.cluster.peers.up`（peer 侧本节点退出 UP 计数）。

## 实现结果（⑦ 补）

实现要点：

- core：`ReadinessGate` 三态（`ready`/`down` 双 volatile，`isReady = ready && !down`；`markReady` 在 DOWN 后 no-op；`markDown` 幂等）；`ReplicationServiceImpl.selfState()`（DOWN > UP > STARTING）供 `nodeStatus()`/`clusterNodes()` 共用。
- server：`GracefulShutdownHook`（`ApplicationListener<ContextClosedEvent>`，AtomicBoolean 一次性；序列 markDown → drain sleep → 终刷）；`RegistryConfiguration` 注册 bean（ApplicationListener 类型 bean 自动注册为事件监听器）。
- starter：`ArtemisClientAutoConfiguration` 增 `ContextClosedEvent` 监听 bean（`ObjectProvider<ArtemisClient>` 惰性解析 → `close()`），`destroyMethod="close"` 保留幂等兜底。
- 测试设施：registry-server pom 给 surefire/failsafe 配 `artemis.registry.shutdown.drainMs=0`（测试 JVM 全局不等待；需要排水窗口的用例以节点启动参数覆盖——命令行属性优先级更高）。

code-review 修复（5 findings 全处理）：

1. [high] 未就绪节点停机也终刷 → 空表覆盖可回退旧快照（分支① 重试窗口/启动失败）→ 终刷前捕获 `wasReady`，未就绪跳过；补 `should_skipFinalSnapshot_when_neverReady`。
2. [medium] `RegistryBootstrap.attempt()` 只判 `isReady()`，DOWN 后仍会回放快照并与终刷并发替换注册表 → 增加 `isDown()` 早退；补 `should_skip_bootstrap_when_markedDown`。
3. [medium] drain IT `closer.join` 后不校验线程结束 → 停机挂死会假绿 → 补 `isAlive` 断言。
4. [low] 重启回放 IT 复用同组端口 → 改用新预分配端口。
5. [low] IT 手写断言/全限定类名/冗余 `throws` → 统一 AssertJ + import 收敛。
6. [high，⑧ 回归发现] 停机线程 20s 未结束：spring-grpc 默认关停宽限 **30s** 与 drain 叠加（已建心跳流不自行结束）→ `application.properties` 显式 `spring.grpc.server.shutdown.grace-period=1s`，排水窗口承担存量服务职责；架构/产品文档同步。

## 文档同步（⑩ 填）

- `docs/arch/artemis-next-architecture.md`（v1.32，④ 期同步）：§4.1 新增「优雅停机」段（ContextClosedEvent 序列、就绪门三态与 DOWN 优先级、drainMs、gRPC 关停宽限 1s、无连接迁移、starter 自动注销）；更新历史同步。
- `docs/product/artemis.md`（v1.14）：v0.2 phase-6 交付节（停机序列/DOWN 传播/终刷与重启恢复/SDK 时序/部署注意/工程形态）。
- `docs/api/sdk-java.md`（v1.3）：§6 生命周期补「Spring Boot 容器关闭」段；starter 说明改为 ContextClosedEvent 主动注销 + destroyMethod 兜底。
- `docs/milestones/v0.2/plan.md`：无需改动（phase-6 说明已覆盖范围；验收对照表在版本收尾填写）。
- 本 phase 无新 ADR（停机策略为 roadmap v0.2 既定能力，落地细节记架构 §4.1，遵循 phase-5 先例）。
- `.claude/commands/dev-java.md`（⑥ 节）：补技能选择（内置 `code-review` vs 同类插件技能）、审查口径与「已知环境故障与兜底」条款（子 agent 无内置工具时改主会话内联 review）。
- CLAUDE.md：无需更新（构建命令与模块结构无变化）。
- 各级索引：phase 文档已在开工时登记 `v0.2/phases/README.md`，无新增文档。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-23 | ⑧ 回归发现并修复：gRPC 关停宽限期默认 30s 与 drain 叠加 → 显式 1s（设计/架构/产品文档同步） |
| 1.3 | 2026-10-23 | ⑦ 实现结果：code-review 5 findings 全修复（未就绪不终刷、bootstrap DOWN 早退、IT 假绿/端口/断言）；终刷条件同步设计节 |
| 1.2 | 2026-10-23 | review 修订（用户）：starter 优雅下线时序修正——ContextClosedEvent 自动 close（注销提前到程序可用时，destroyMethod 保留幂等兜底）；范围节补 SDK close v0.1 既有事实 |
| 1.1 | 2026-10-23 | 设计定稿：ContextClosedEvent 停机序列（DOWN → drain → 终刷）、ReadinessGate 三态、drainMs 配置、零 proto/零 client 改动 |
| 1.0 | 2026-10-23 | 开工骨架（worktree 用户预建、main 83bebf6 起续用） |
