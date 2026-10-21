# Phase 2：变更事件扇出

版本: 3.2    更新时间: 2026-10-21

## 目标

把 v0.1 预留的 `ChangeListener` 变更事件扇出到全部 peer：有界队列异步投递 + 增量批量报文 + 指数退避重试（抖动）+ 复制指标，达成 3 节点最终一致；副本租约语义继承原产品设计（续约复制维持、清理不扇出）。

## 范围

- **做**：副本租约语义（原产品继承，见 §0）；RENEW 变更类型与纯续约事件；入站 `Replicate` 用例（校验 + 复制写零事件 + 缺席补注册）；出站扇出管线（全局有界队列 → 积压聚合成批 + 同实例合并 → per-peer 串行投递 + 指数退避抖动重试）；复制指标；出站 gRPC 端口实现（channel 管理 + 内部认证凭证）。
- **不做**：peer 全量 bootstrap 与 readiness 分支①（phase-3）；自我保护 2.0（phase-4，含自我保护对复制的抑制）；SDK 容灾与 TTL 轮换（phase-5）；优雅停机（phase-6）；摘除记录扇出（ADR-016 不做）。

## 涉及模块

- `proto/replication/v1/replication.proto`：`ChangeType` 追加 `CHANGE_TYPE_RENEW = 5`（enum 只增，老端映射 UNKNOWN，兼容）
- `java/server/artemis-registry-core`：domain（`ChangeType` 加 RENEW、`InstanceRegistry` 复制写路径与纯续约事件、过期清理不发复制事件）、infrastructure（扇出编排）、application（`ReplicationService.replicate` 入站用例 + proto 门面/mapper 扩展）
- `java/server/artemis-registry-server`：grpc（`Replicate` handler）、出站 gRPC 传输实现、装配（`ChangeListener` 条件替换 no-op）

## 验收方式

对应测试文档：[phase-2-replication-fanout-test.md](phase-2-replication-fanout-test.md)

## 产品设计（③ 补）

### 0. 副本租约语义（继承原产品设计，源码取证）

**原产品机制**（`~/Projects/mydotey/artemis`，源码证据）：

| 原产品设计 | 源码位置 | 采纳 |
|---|---|---|
| 副本 = 真实租约，**续约复制**维持：对端收到复制心跳直接 `lease.renew()` | `RegistryReplicationServiceImpl.heartbeat` → `_repository.heartbeat` → `Lease.renew()` | ✅ RENEW 事件扇出 |
| **TTL 清理不复制**：过期清理只发本地推送流变更，不产生复制任务；删除不跨节点传播，各节点独立过期 | `onLeaseClean` 仅 `addInstanceChange(DELETE)`；`UnregisterTask` 仅产生于 SDK 入口 `RegistryServiceImpl.unregister` | ✅ `evictExpired` 不发复制事件 |
| **副本缺席补注册**：复制心跳 renew 失败（副本已被本地清理）→ 立即 register 重建 | `RegistryReplicationServiceImpl.heartbeat`：`!success → _repository.register(instance)` | ✅ 入站 RENEW 缺席 → upsert |
| **防回环**：复制入站与 SDK 入口两条路径，入站不产生复制任务 | `RegistryReplicationServiceImpl` 各方法无 `replicate(...)` 调用 | ✅ `applyReplicated` 零事件（同 `restore` 不变量） |
| 心跳复制批量 + 任务短 TTL 合并 | `HeartbeatTask`：`batching-enabled`（250/批）、`task-ttl` 5s | ✅ 同构为组批 + 同实例合并（§3） |
| 清理与并发重注册竞态用 creationTime 新旧比较保护 | `LeaseManager.clean` / `onLeaseClean` 的 `creationTime() >` 比较 | ❌ 不继承——v0.1 holder 锁模型（锁内全同步 + 乐观重试）已结构性消除该竞态 |

**结论**：副本与事实源是**同一种租约**（`renewalTimeMs`，无哨兵、无事实权标记、无删除保护）——副本靠续约复制保活，全集群租约同频过期、各节点独立清理。所有故障场景天然收敛：

| 场景 | 行为 |
|---|---|
| 稳态（客户端连 node-a 心跳） | a 批量扇出 RENEW → b/c 副本续约；流量 ∝ 进程数/心跳周期 ×(N-1)，与实例数解耦 |
| 客户端猝死 | a 真实租约过期本地清理（不扇出）；b/c 副本收不到 RENEW 相继过期各自清理 → 全集群清除，无残留 |
| 事实源节点+客户端双死 | 同上——比「副本豁免 TTL」方案干净（无永久残留） |
| 客户端轮换 a→b | b 首次心跳命中副本（同种租约）→ renew + RENEW 扇出；a 残留过期本地清理不扇出 → 无误删风险 |
| 网络分区恢复 | 副本已在 b/c 本地过期清理；a 的 RENEW 到达 → 缺席 → 补注册 upsert 重建 |
| 全集群同时重启 | 各自回放快照（真实租约、renewalTime=now）→ 客户端重连心跳续约/补注册 → 收敛（v0.1 语义原样） |

**架构修订点**（review 定案后同步，§6）：架构 §4.1「稳态心跳（纯续约）零复制流量」与 D12 表述「内容相同纯续约零事件」按本设计修订——纯续约在集群模式发 RENEW 事件（单节点 listener 为 no-op，零事件零开销不变）。代价评估：稳态复制流量 ∝ 进程数/心跳周期，批量合并后与实例数解耦（原产品 10 万级同模型验证）；换取的是副本语义零特例（无豁免/标记/保护规则）与全场景无残留。

### 1. 领域模型变更（registry-core domain）

```java
// ChangeType 追加 RENEW（domain 枚举与 proto CHANGE_TYPE_RENEW 对齐）
public enum ChangeType { NEW, UPDATE, DELETE, RENEW }   // RENEW = 内容未变的纯续约

// InstanceRegistry 变更：
// 1. 复制写路径（零事件——与 restore 同不变量，防回环）
public int applyReplicated(List<InstanceChange> changes);
//    NEW/UPDATE → 覆盖写（renewalTimeMs = now，真实租约起算）
//    RENEW      → 命中续约（renewalTimeMs = now）；缺席 → 补注册 upsert（建租约发终态同 NEW 语义，但不发事件）
//    DELETE     → 幂等移除
//    逐 holder 锁内应用：重建快照、回收空 holder、不派发事件
// 2. heartbeatUpsert 纯续约分支：内容相同 → renewalTimeMs = now 并发 RENEW 事件
//    （v0.1 零事件行为改为此；单节点 no-op listener 吞掉，语义不变）
// 3. evictExpired：过期摘除照旧（快照重建、本地清理）但**不发复制事件**
//    （原产品「清理不复制」；发现侧推送流对过期 DELETE 的消费归 v0.3 投影设计，本 phase 不涉及）
// Lease 结构不变（无副本标记）
```

### 2. 入站 Replicate 用例（application）

`ReplicationService` 接口扩展：

```java
Ack replicate(ReplicationBatch batch);   // record ReplicationBatch(String sourceNodeId, List<InstanceChange> changes)
```

- 校验（信任边界一次完成）：`sourceNodeId` 非空、逐变更 `instanceId` 非空、DELETE 外 instance 完整——失败整批拒绝（`ResponseStatus.ACK_FAIL + ErrorInfo(INVALID_ARGUMENT)`，契约注释语义）。不校验 source 是否在成员表（静态成员表滚动变更期新旧并存，过度严格制造运维脆弱性；内部端口认证是安全边界）。
- 通过 → `instanceRegistry.applyReplicated(...)` → `ACK_SUCCESS`。
- proto 门面/mapper 扩展：`ReplicateRequest` ↔ domain `ReplicationBatch`（ServiceChange 按 key 展平）。
- gRPC handler 覆写 `replicate`（现有 `ReplicationGrpcService`），绑定既有内部端口拦截器链（无 readiness——复制在未就绪时仍可应答，phase-3 bootstrap 依赖）。

### 3. 出站扇出管线（registry-core infrastructure + server 传输）

domain 出站端口（传输无关）：

```java
public interface PeerReplicator extends AutoCloseable {
    void send(ClusterMember peer, ReplicationBatch batch);   // 阻塞语义，成功返回，失败抛异常
}
```

`ReplicationFanout implements ChangeListener`（infrastructure，编排主体）：

```text
onChange(change)                      ← 聚合锁内回调，非阻塞
  └─ 全局有界队列 offer；满 → 丢当条 + WARN + 指标（自愈链兜底）
聚合线程（单虚拟线程）                 ← 专属 executor，命名
  └─ take 首条 → drainTo 当前积压 → 同 (key, instanceId) 后到覆盖先到（产生序内后到 = 更新终态；
       连续 RENEW 压缩为一条；RENEW 被 NEW/UPDATE/DELETE 覆盖）→ 按 key 分组保持产生序
  └─ 超过 maxBatchChanges 拆批 → 逐 peer 队列投放；peer 队列满 → 丢最旧 + 计数（落后 peer 由自愈链恢复）
peer worker（每 peer 一个虚拟线程）    ← 串行投递、失败保序重试
  └─ take → replicator.send(peer, batch) → 成功取下一条
  └─ 失败 → Thread.sleep(退避) → 重发同批（虚拟线程 sleep 不占 carrier）
       退避 = random(0, min(retryMaxMs, retryInitialMs × 2^attempts))（全抖动，attempts 成功后清零）
```

- **聚合 = 当前积压**（take + drainTo），不加时间窗口：变更稀疏时批次小、延迟≈处理耗时；密集时自然攒批 + 同实例合并（批量贯穿资产 #11；原产品 250/批 + task-ttl 合并的同构简化——先做同批合并，跨批丢弃去重视稳态流量实测再议）。
- **per-peer 独立队列 + worker**：peer 失败退避停住不影响其它 peer；同一 peer 严格 FIFO + 失败重发同批 = 契约「串行投递、重试保序」。
- peer 队列满丢最旧与 SDK 回调队列纪律同构（guidelines §9）：落后已发生，保新弃旧，自愈链兜底。
- **单节点不装配**：`ClusterMembers.self()` 缺席（静态配置，装配期判定）→ `ChangeListener` 保持 no-op，零开销；有成员身份才建管线。纯续约 RENEW 事件由 no-op listener 吞掉，`InstanceRegistry` 无需感知模式。
- 关闭：`AutoCloseable`——停止接收、`shutdownNow` 丢弃未投递事件（重启后自愈链恢复）；channel 释放随 server 侧 `PeerReplicator.close()`。

**gRPC 传输实现（server）**：`PeerReplicator` 实现持有 per-peer `ManagedChannel`（惰性建、`usePlaintext`；内部认证启用时附 Basic 头，凭证复用 `artemis.registry.internal.auth.*`——集群统一内部认证配置的对称假设）；stub `withDeadlineAfter(sendTimeoutMs)` 动态读取；响应 `ACK_FAIL` 或 gRPC error → 抛异常交由 worker 退避。

### 4. 配置项（SCF，键定义在使用类内）

| 键 | 默认 | 型 | 说明 |
|---|---|---|---|
| `artemis.registry.replication.queueCapacity` | 16384 | 静态 | 全局事件队列容量（建队参数） |
| `artemis.registry.replication.peerQueueCapacity` | 4096 | 静态 | per-peer 批队列容量 |
| `artemis.registry.replication.maxBatchChanges` | 1024 | 动态 | 单批变更数上限（超限拆批） |
| `artemis.registry.replication.retryInitialMs` | 1000 | 动态 | 退避基数 |
| `artemis.registry.replication.retryMaxMs` | 60000 | 动态 | 退避上限 |
| `artemis.registry.replication.sendTimeoutMs` | 5000 | 动态 | 单次投递 RPC deadline |

全部走 `RangeValueConfig` 值域过滤（非法回落默认）；动态项消费循环内每次读取。

### 5. 复制指标（就地定义，架构 §6「复制 v0.2」）

| 指标 | 类型 | 位置 |
|---|---|---|
| `artemis.registry.replication.batches.sent`（tag `peer`） | Counter | `ReplicationFanout` |
| `artemis.registry.replication.batches.failed`（tag `peer`） | Counter | `ReplicationFanout` |
| `artemis.registry.replication.events.dropped` | Counter | `ReplicationFanout`（全局队列满） |
| `artemis.registry.replication.peer.events.dropped`（tag `peer`） | Counter | `ReplicationFanout`（peer 队列丢最旧） |

入站侧（`RegistryServiceImpl` 既有模式）：`artemis.registry.replication.batches.received` / `applied.changes` Counter。

### 6. 设计决策与同步落点

- **副本租约语义继承原产品**（续约复制维持 + 清理不扇出 + 缺席补注册 + 入站零事件）→ 同步 `docs/arch/artemis-next-architecture.md` §4.1（review 通过后立即修订）：「变更事件扇出」节补副本租约语义与 RENEW 事件；「稳态心跳（纯续约）零复制流量」表述修订为「稳态心跳复制流量 ∝ 进程数/心跳周期（RENEW 批量合并，与实例数解耦）」；D12 相应表述（纯续约零事件 → 集群模式发 RENEW、单节点零事件）。
  - 本 phase 草案 v2.0 曾定「副本豁免 TTL + sourceNodeId 事实权 + 删除保护」，review 修订为原产品继承方案：特例语义全部取消，全场景（含双死）无残留；代价为稳态续约复制流量（原产品 10 万级同模型验证）。
  - 不新增 ADR：ADR-004（全对全 + 增量报文）语义内的机制细化，续约亦是增量事件；架构 §4.1 扇出节承载。
- **`CHANGE_TYPE_RENEW = 5`** → `proto/replication/v1/replication.proto`（enum 只增，老端映射 UNKNOWN；④ 实现落地）；proto 契约规范 §2 兼容规则内变更，无需评审流程外动作。
- **v0.2 plan phase-2 验收表述**「稳态心跳零复制流量」→ 同步修订为「稳态心跳 RENEW 批量复制、流量与实例数解耦」（review 通过后）。
- **出站端口/编排分层**（domain `PeerReplicator` 端口 + core infrastructure 编排 + server gRPC 传输）→ guidelines §1 既有纪律的执行，无文档同步义务。
- **单节点不装配扇出管线** → 产品文档随 ⑩ 登记（部署形态行为差异）。

## 实现结果（⑦ 补）

- **domain**：`ChangeType` 追加 `RENEW`；`InstanceRegistry.applyReplicated`（分组锁内应用、零事件、no-op 空分组不留空 holder）、纯续约分支发 RENEW、`evictExpired` 清理不发复制事件（快照重建/回收照旧）、`commit` 仅结构性变更（非 RENEW）重建快照（保持 v0.1 稳态零快照分配）；`ReplicationBatch` / `PeerReplicator` / `PermanentReplicationException` 领域类型。
- **application**：`ReplicationService.replicate`（信任边界校验 + 零事件应用，入站指标 `batches.received` / `applied.changes`）；mapper 补 ChangeType 双向（UNRECOGNIZED → null 走整批拒绝）、`ReplicationBatch` 双向与出站 `toReplicateRequest`（按 key 聚合保序）。
- **infrastructure**：`ReplicationFanout`——全局有界队列 + 单聚合虚拟线程（drain 积压、同实例 last-wins 合并、超限拆批）+ per-peer 有界队列与串行 worker（失败全抖动指数退避重发同批；**确定性失败**——`PermanentReplicationException`（整批 ACK_FAIL / UNIMPLEMENTED / UNAUTHENTICATED / PERMISSION_DENIED）丢批续投，防滚动升级窗口队头永久阻塞）。
- **server**：`GrpcPeerReplicator`（per-peer 惰性 channel、deadline 与认证头每次现取、`headerValueOrNull` 防残缺凭证、gRPC status 分类永久/暂时失败）；`ReplicationGrpcService.replicate` handler；装配：多节点成员身份 → `ReplicationFanout` 作 `ChangeListener`（单节点 no-op），`GrpcPeerReplicator` 独立 bean（close 释放 channel）。
- **proto**：`CHANGE_TYPE_RENEW = 5`（enum 只增）。
- **code-review 修复（4 条）**：入站补 ServiceKey blank 校验（防空键服务残留快照/查询面）；永久失败丢批续投（新增 `batches.rejected{peer}` 指标）；认证头改 `headerValueOrNull`；`peer.events.dropped` 改名 `batches.dropped{peer}`（按批计数语义对齐）。
- 测试结果：`dev-test.sh all` 全绿（⑤ 首跑 + ⑧ 回归）。

## 文档同步（⑩ 填）

- 产品文档 `docs/product/artemis.md` v1.10：新增 v0.2 phase-2 交付节（副本租约语义、扇出管线、复制配置与指标）；修正 v0.1 phase-2 节「事件出口 no-op」过时表述。
- 架构 `docs/arch/artemis-next-architecture.md` v1.28：③ review 定案时已同步（§4.1 扇出节改写、RENEW/零复制流量表述修订）。
- plan `docs/milestones/v0.2/plan.md` v1.1：③ 定案时已同步（phase-2 说明与验收方式）。
- 契约：`proto/replication/v1/replication.proto` 追加 `CHANGE_TYPE_RENEW = 5`（enum 只增，proto 契约规范 §2 兼容规则内，无流程外动作）。
- API：内部 peer 协议非对外 API（`docs/api/` 不新增）；复制指标经既有 `/actuator/prometheus` 出口，产品文档登记。
- `CLAUDE.md`：构建命令与模块结构未变，进度以 dev-state 为准，无需修改。
- 索引：`phases/README.md` 已登记本 phase 两份文档（①）；其余各级索引无变更。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.2 | 2026-10-21 | ⑨ 人工验证通过；⑩ 文档同步清单回填（产品文档 v1.10 phase-2 交付节） |
| 3.1 | 2026-10-21 | ④⑤⑥⑦⑧：实现结果回填；code-review 4 findings 修复（ServiceKey 校验 / 永久失败丢批 / headerValueOrNull / 指标改名） |
| 3.0 | 2026-10-21 | review 修订：副本租约语义改为继承原产品设计（续约复制、清理不扇出、缺席补注册；取消豁免 TTL/事实权标记/删除保护），新增 RENEW 变更类型 |
| 2.0 | 2026-10-21 | ③ 设计定稿：副本租约语义（豁免 TTL + sourceNodeId 方案）、入站用例、扇出管线、配置与指标 |
| 1.0 | 2026-10-21 | 开工骨架（① worktree 开工） |
