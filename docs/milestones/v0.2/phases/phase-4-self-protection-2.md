# Phase 4：自我保护 2.0（按服务粒度）

版本: 3.2    更新时间: 2026-10-21

## 目标

把全局单阈值自我保护升级为**节点级兜底 + 服务级精确判定**的两层保护：服务级保护态是一等显式状态（状态迁移、指标、API 可查），并在节点健康时通过基线回落限制单服务误保护的陈旧数据；集群各节点独立判定。

## 范围

- **做**：节点级窗口（沿用 v0.1 算法，作为网络分区/小服务兜底）+ 每服务窗口；显式 `NORMAL/PROTECTING` 状态机（含 `since`、迁移计数与日志）；按服务跳过过期清理及节点级兜底规则；周期评估（复用 1s 清理调度）；有界指标（聚合 + 仅保护中服务带 `namespace`/`service` 标签）；独立保护状态查询 RPC 与 Admin 状态端点；窗口/gauge 生命周期回收；3 节点进程内 IT 验证各节点独立判定与超时退出。
- **不做**：`stale` 数据标记的消费方出口（v0.3 discovery，本版只保证保护态可观测）；按服务差异化阈值配置（沿用全局三个阈值键，作用到节点级与每个服务）；保护态跨节点复制/持久化（各节点独立判定，进程内状态，重启重置）；复制路径的保护态抑制（原产品无此行为，见 §9）；扩展现有 `GetLeaseStatus` 响应（保持 v0.1 语义，新 API 独立）。

## 涉及模块

- `proto/management/v1/management.proto`：新增 `GetSelfProtectionStatus` RPC 与消息（只增，wire 兼容）；`management.v1` 不冻结，但仍守 `proto-contract.md` §2。
- `java/server/artemis-registry-core`：domain（`SelfProtection` 双层窗口/状态机、`ServiceProtectionStatus`/`SelfProtectionStatus`、`InstanceRegistry.evictExpired(Predicate)`/`serviceKeys()`）、application（心跳按服务计数、清理编排、查询用例与 proto 门面/mapper）。
- `java/server/artemis-management-core`：domain（保护状态模型）、`RegistryQueryPort`/`ManagementService` 扩展、proto mapper。
- `java/server/artemis-management-server`：`RegistryQueryGrpcClient` 新方法、状态 Controller 新端点。
- `java/server/artemis-registry-server`：装配签名基本不变（`SelfProtection` bean 不变）；新 gRPC 方法由既有 `RegistryQueryGrpcService` 委派。
- 测试：registry-core 单测 + registry-server/management-server 服务级 IT + 3 节点集群语义 IT。

## 验收方式

对应测试文档：[phase-4-self-protection-2-test.md](phase-4-self-protection-2-test.md)

## 产品设计（③ 补）

### 1. 两层判定模型

保留 v0.1 的全局窗口作为**节点级层**，新增每服务窗口作为**服务级层**：

| 层 | 窗口 | 作用 |
|---|---|---|
| 节点级 | 全节点心跳续约量（沿用 v0.1 算法） | 网络分区/节点级故障兜底；覆盖小服务与无本地窗口的副本 |
| 服务级 | 每 `ServiceKey` 独立窗口 | 精确识别单个服务的续约骤降，避免「局部异常导致全节点停摘」或「全局阈值放过局部异常」 |

**过期清理判定**（`SelfProtection.isProtected(ServiceKey)`）：

```text
serviceWindow = windows[service]
if serviceWindow 存在 且 serviceWindow.maxCount >= minCount:
    return serviceWindow.protecting          // 服务级已具备判定资格，以服务级为准
else:
    return nodeProtecting                    // 未达服务级启用门槛（小服务/副本/新服务）→ 节点级兜底
```

- 服务级窗口达到 `minCount` 后，**服务级状态权威**：即使节点级保护中，该服务若续约正常，其过期租约照常清理（精度收益）。
- 无本地心跳窗口（仅副本）或历史峰值未达 `minCount` 的服务，沿用节点级保护——避免纯按服务粒度丢失原产品「节点级网络故障兜底」能力。节点健康时，未达门槛的小服务不做服务级保护（与 v0.1 全局门槛一致，避免小样本噪声误判）。
- 显式 `deregister` 仍不受任何保护拦截（架构 §4.1 / ADR-012）。

### 2. 每服务窗口与显式状态机

窗口沿用 v0.1 算法（1s 分桶环形数组 + `LongAdder`、`maxCount` 历史峰值、`minCount` 启用门槛、`factor` 阈值），作用域改为 `ServiceKey`。每个窗口维护显式状态：

```java
enum ProtectionState { NORMAL, PROTECTING }

final class Window {
    LongAdder[] buckets;               // 按秒分桶
    AtomicLong maxCount;               // 历史峰值
    volatile long maxCountUpdatedAtMs; // 峰值最近抬升（或回落）的单调时刻
    volatile ProtectionState state;    // 显式状态
    volatile long protectingSinceMs;   // PROTECTING 起始单调时刻
    volatile long lastWindowRenewals;  // 最近一次评估的窗口续约量（查询只读）
}
```

- **计数**：只有心跳中的有效实例计入续约量（与原产品 `Lease.renew()` 逐实例 `markUpdate` 一致）；`register`/`update`/`deregister`、复制入站（`applyReplicated`）、快照/peer bootstrap 回放均不计入。
- **判定**：`maxCount >= minCount` 且当前窗口续约量 `< maxCount × factor` → `PROTECTING`。
- **迁移**：状态只在周期评估中迁移；`NORMAL → PROTECTING` / 反向迁移各记一次迁移计数与结构化日志（含 namespace/service、windowRenewals、maxCount）。服务级保护 gauge 在进入时注册、退出时移除。
- **状态查询**：`protectingForMs = now - protectingSinceMs`（单调钟，跨进程不可比，故 API 暴露时长而非时间戳，D14）。

### 3. 周期评估（显式状态的唯一写入口）

`RegistryServiceImpl.evictExpired()` 每秒由既有清理调度触发，先评估后清理：

```java
public int evictExpired() {
    protection.evaluate(registry.serviceKeys());   // 节点级 + 服务级状态迁移、窗口/gauge 回收
    int evicted = registry.evictExpired(protection::isProtected);
    evictions.increment(evicted);
    return evicted;
}
```

`SelfProtection.evaluate(Set<ServiceKey> liveServices)`（同步、单写者）：

1. 评估节点级窗口 → `nodeProtecting`；
2. 逐服务窗口评估：推进分桶、更新峰值/窗口续约量、按 §1 规则计算新状态并迁移；
3. 回收不在 `liveServices` 中的服务窗口（若处于保护态先移除 gauge），防止服务 churn 无界增长。

**为何不在查询/抓取路径现算**：查询与 Prometheus 抓取只读 volatile 状态；状态迁移、指标注册/移除、窗口回收全部收敛到单线程评估器，消除并发注册/移除竞态与查询副作用。评估成本 O(服务数 × 窗口桶数)/秒（10k 服务 × 10 桶 ≈ 10 万桶求和/秒，可接受）。

### 4. 基线回落与误保护限制

历史峰值只增不减在按服务粒度下会造成**永久保护**：某服务的客户端全部迁到其他节点后，本节点续约归零、峰值仍在，该服务及其副本永不清理。因此引入基线回落：

- **节点健康时**（`nodeProtecting == false`）：服务窗口的 `maxCount` 若连续 `maxCountResetMs`（默认 10min，动态）未抬升，则回落到当前窗口续约量。回落通常使 `maxCount < minCount`（或 `current == maxCount`）→ 退出 `PROTECTING`，陈旧租约在后续 TTL 内清理。**单服务误保护的数据陈旧上界 ≈ `maxCountResetMs` + TTL**。
- **节点级保护中**（`nodeProtecting == true`）：视为网络分区/节点级故障，服务窗口**不回落**，保护持续到续约恢复——保留原产品「分区期间不批量误摘」的核心承诺。
- **节点级窗口自身**：沿用原产品语义，先按当前窗口判定保护态，只有当前窗口安全（未触发保护）时才回落 `maxCount`；若在回落检查时恰好骤降，必须先进入保护态，不能在判定前改写峰值（原产品 `safeCheck` 顺序，`LeaseUpdateSafeChecker.java:147-189`）。
- 取舍：长时间节点级分区仍可能保留陈旧数据（原产品语义，通过 API/指标显式可观测）；节点健康但单服务长期低续约时，最多保留 `maxCountResetMs` 后清理。

### 5. 指标（有界基数，就地定义）

节点级（沿用 v0.1 名称，单 series）：

| 指标 | 类型 | 含义 |
|---|---|---|
| `artemis.registry.self.protection` | Gauge（0/1） | 节点级是否保护态 |
| `artemis.registry.self.renewals` | Gauge | 节点级当前窗口续约量 |
| `artemis.registry.self.max` | Gauge | 节点级历史峰值 |

服务级（有界）：

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `artemis.registry.self.service.protection` | Gauge（1） | `namespace`, `service` | **仅 PROTECTING 服务注册**，退出即移除（基数 = 当前保护中服务数） |
| `artemis.registry.self.service.protection.count` | Gauge | — | 当前保护中服务数 |
| `artemis.registry.self.service.protection.transitions` | Counter | `to=protecting|normal` | 状态迁移计数（2 条 series） |

- 服务级 `renewals`/`max`/`protectingForMs` 明细**不常驻指标**，经独立 API 查询——避免稳态 `3 × 服务数` 的 Prometheus 基数爆炸。
- 指标注册/移除只在同步评估器内发生，无并发竞态。
- 告警可直接用 `artemis.registry.self.service.protection > 0` 或 `...count > 0`。

### 6. API：独立保护状态查询

`management.v1` 新增（`management.v1` 不冻结，加法兼容）：

```proto
message GetSelfProtectionStatusRequest {
  string namespace = 1;      // 可选过滤
  string service = 2;        // 可选过滤；指定 namespace + service 时总是返回该服务
  bool include_normal = 3;   // 默认 false：未过滤时仅返回 PROTECTING 服务
}

message ServiceSelfProtectionStatus {
  artemis.common.v1.ServiceKey service = 1;
  int64 window_renewals = 2;
  int64 max_count = 3;
  bool node_fallback = 6;    // 服务级未启用（无窗口/峰值<minCount），清理判定取 node.protecting
  bool protecting = 4;       // 服务级显式状态
  int64 protecting_for_ms = 5;   // 服务级 NORMAL 时为 0
}

message GetSelfProtectionStatusResponseData {
  int64 tracked_services = 1;
  int64 protecting_services = 2;
  repeated ServiceSelfProtectionStatus services = 3;   // 按 (namespace, service) 升序
  bool truncated = 4;                                   // 超过 10000 条
  SelfProtectionStats node = 5;                         // 节点级窗口统计（分区诊断）
}

service RegistryQueryService {
  // ...既有方法
  rpc GetSelfProtectionStatus(GetSelfProtectionStatusRequest) returns (GetSelfProtectionStatusResponse);
}
```

- **默认只返回保护中服务**：稳态响应很小；分区/故障时才是大量条目，仍受 10000 条上限与 `truncated` 保护。`protecting_services` 为服务级 PROTECTING 计数（与默认列表同口径，节点级兜底服务不计入）。
- 指定 `namespace + service` 时该服务状态总是返回（NORMAL 也返回；无本地窗口时返回零计数条目并按节点级兜底），`include_normal` 只影响未指定 `service` 的列表；仅指定 `service` 且无匹配本地窗口时无法合成 `ServiceKey`，返回空列表。
- `protecting` 是服务级显式状态；`node_fallback=true` 表示服务级未启用（无本地窗口或峰值 < `minCount`），实际清理判定为 `protecting || (node_fallback && node.protecting)`。这样既保持服务级状态语义，又不会把节点级兜底中的小服务误报为「未保护」。
- 节点级统计 `node` 复用既有 `SelfProtectionStats`，供区分「节点级故障」与「单服务异常」。
- **`GetLeaseStatus` 保持不变**：其 `protection` 字段继续返回节点级窗口统计（v0.1 语义），不引入无意义聚合。
- **不扩展对外节点状态 API（`replication.v1.NodeStatusService`）**：该 API 是 SDK 三级容灾的成员/就绪观测面；保护态是 registry 排障数据，走状态 API。

**management 状态 API**：新增 `GET /admin/v1/status/protection?namespace={ns}&service={svc}&includeNormal={bool}`，返回上述结构；`/admin/v1/status/leases` 不变。

### 7. 配置项（SCF，键定义在使用类内）

| 键 | 默认 | 型 | 说明 |
|---|---|---|---|
| `artemis.registry.selfProtection.windowSeconds` | 10 | 动态 | 节点级与服务级窗口长度（1..300） |
| `artemis.registry.selfProtection.minCount` | 50 | 动态 | 服务级启用门槛（历史峰值下限）；未达门槛回落节点级兜底 |
| `artemis.registry.selfProtection.factor` | 0.85 | 动态 | 当前窗口量低于 `maxCount × factor` 进入保护 |
| `artemis.registry.selfProtection.maxCountResetMs` | 600000 | 动态 | 峰值无抬升的回落间隔（节点健康时对服务窗口、安全态对节点窗口） |

- 阈值键同时作用于节点级与服务级窗口；不引入按服务覆盖（确有需求再加选择器）。
- 数值越界沿用 `RangeValueConfig` 回落默认。

### 8. 内存、生命周期与并发

- **按需分配**：服务窗口首次心跳时创建；分桶环容量 = 创建时 `windowSeconds`（不再每服务预分配 300 桶）。窗口缩短不重建；动态增长超过容量时重建环形数组。**窗口口径变化后的首个评估轮以新窗口当前续约量重建 `maxCount` 基线**，避免「重建清零/缩短窗口」与旧峰值比较造成误保护（运维动态调参不再触发状态抖动）。
- **回收**：服务从注册表消失后，评估轮末移除窗口与状态（含 gauge），内存与指标基数随活跃服务收敛。回收前比对窗口最近续约时刻：标记缺席后又收到心跳（并发重注册/首次心跳）则按最后续约重新计时，`computeIfPresent` 与 `recordRenewals` 的 `computeIfAbsent` 同键串行，不会丢弃刚落下的续约。
- **写路径**：`recordRenewals` 只做分桶累加与游标推进，无锁；状态、指标、窗口回收只在同步 `evaluate` 中写。
- **读路径**：`isProtected`/`statuses`/`nodeStats` 只读 volatile 状态与只读窗口求和（不推进游标），不加锁、不修改统计；查询值不受清理周期（`evictionIntervalMs`，最长 60s）滞后影响。
- **时间源**：窗口推进、峰值回落、`protectingForMs` 全部用 `MonotonicClock`（D14）；API 不暴露跨进程不可比的单调时间戳。
- **重启/复制**：保护态不落盘、不随 bootstrap/复制传播；重启后峰值重置，节点重新从本地心跳建立窗口。

### 9. 原产品设计对照（源码取证）

原产品机制（`~/Projects/mydotey/artemis`）：`Lease.renew()` 每次成功续约调用 `leaseUpdateSafeChecker().markUpdate()`（`Lease.java:66-77`）；`LeaseUpdateSafeChecker` 全局滑动窗口统计续约量，`maxCount` 低于 50 不启用，当前量低于峰值 85% 置 `_isSafe=false`，峰值 10min 未刷新时回落（`LeaseUpdateSafeChecker.java:147-189`，回落仅在安全态执行）；清理线程逐租约检查 `!isSafe()` 跳过过期清理（`LeaseManager.java:126-141`）；状态 API 暴露全局窗口计数与 `isSafe`（`StatusServiceImpl.java:225-228`）。另原产品按普通/legacy 双租约池**各自独立**统计保护（legacy logic D4），证明「多窗口独立判定」并非新机制。

| 机制 | 原产品 | 本设计 | 取舍 |
|---|---|---|---|
| 判定层 | 全局单窗口（每池一个） | 节点级窗口 + 每服务窗口两层 | 保留节点级分区兜底；新增服务级精度，修复基线 §6 #9「无法区分局部异常与网络故障」 |
| 服务级资格 | — | `maxCount ≥ minCount` 才由服务级权威，否则回落节点级 | 小服务/副本在节点级故障时仍受保护，避免纯 per-service 丢兜底 |
| 保护动作 | 跳过全部过期清理 | 按服务跳过；服务级正常时即使节点级保护也照常清理 | 局部异常只影响局部；显式 deregister 仍不受影响 |
| 峰值回落 | 10min 未刷新且安全态回落 | 节点健康时服务窗口 10min 回落（含保护态）；节点级沿用安全态回落 | 限制「单服务客户端迁移」造成的永久保护与陈旧副本；节点级分区仍不回落 |
| 判定时机 | 独立后台线程每秒评估，布尔状态 | 复用 1s 清理调度周期评估，显式状态机 | 无额外线程；状态、指标、日志有唯一写入口 |
| 可观测 | 状态 API 全局统计 | 节点级指标 + 仅保护中服务的带标签 gauge + 迁移计数 + 独立 API | 保护态一等显式状态；有界基数 |
| 对复制的影响 | 复制路径不读保护态 | 明确不抑制复制 | 原产品无此行为；副本靠 RENEW 保活，抑制复制只会制造副本缺口 |

### 10. 设计决策与同步落点

- **双层判定（节点级兜底 + 服务级权威）+ 节点健康时服务窗口超时回落** → 新增 `docs/decisions/adr-017-self-protection-two-layer.md`，并同步架构 §4.1「自我保护 2.0」、§6 metrics、§7 决策总览表与 `docs/decisions/README.md`（review 通过后立即修订）。
- **显式状态机 + 周期评估（复用 1s 清理调度）** → 同步架构 §4.1「保护态是一等显式状态」表述（状态迁移、`since`、唯一写入口）。
- **有界指标（节点级保留 v0.1 名称；服务级仅保护中带标签 + 聚合计数/迁移计数）** → 同步架构 §6「保护态」指标行、`docs/api/admin-api.md`。
- **新增 `GetSelfProtectionStatus` RPC + `/admin/v1/status/protection`** → 同步 `docs/api/registry-query-api.md`（新方法）、`docs/api/admin-api.md`（新端点）；proto 只增兼容。
- **`GetLeaseStatus` 保持节点级保护统计语义** → 不改既有管理查询契约；产品文档说明两层统计口径。
- **不扩展对外节点状态 API** → 同步架构 §4.1 节点状态节边界（保护态经状态 API，不经容灾面）。
- **不新增按服务阈值配置** → 沿用三个全局键，文档明确作用域；确有需求后置。

## 实现结果（⑦ 补）

- 领域：`SelfProtection` 双层窗口（节点级 + 每 `ServiceKey`）、`NORMAL/PROTECTING` 状态机、单调钟 `protectingSince`、峰值回落、按保护态注册/移除 gauge、评估器单写者回收窗口；写路径无锁，读路径只读窗口求和。
- 应用：心跳按分组 `recordRenewals`；`evictExpired` 先 `evaluate` 再按 `isProtected` 逐服务跳过；`GetSelfProtectionStatus` 查询用例（默认仅保护中、过滤、10000 条上限、节点级统计、`node_fallback`）。
- 接入/管理：`management.v1` 新增 RPC（只增字段）；registry gRPC 委派、management gRPC client 与 `GET /admin/v1/status/protection`。
- code review 修复（2026-10-21）：节点级回落顺序对齐原产品（先判定后回落）；动态窗口变化重建峰值基线；查询改为只读当前窗口求和；窗口回收增加续约活动检查与 `computeIfPresent` 原子删除；`absentSince` 改用 `-1` 哨兵；`minCount/factor/reset` 每轮评估只读一次；无窗口服务按 `namespace+service` 合成零计数条目；新增 `node_fallback` 字段；补齐 client/Admin 端点、多服务心跳与回落边界测试；修正测试文档用例名。

## 文档同步（⑩ 填）

| 文档 | 同步内容 |
|---|---|
| `docs/product/artemis.md`（v1.12） | 新增「v0.2 phase-4 已交付」节：两层判定、显式状态与超时回落、有界指标、状态 API、集群独立判定、工程形态 |
| `docs/api/registry-query-api.md`（v1.3） | §3.5 `GetSelfProtectionStatus`（过滤、`include_normal`、`node_fallback`、10000 条上限；`GetLeaseStatus` 语义不变） |
| `docs/api/admin-api.md`（v1.4） | §5.3 `GET /admin/v1/status/protection`（按服务保护态 + 节点级统计） |
| `docs/arch/artemis-next-architecture.md`（v1.30） | §4.1 自我保护 2.0（双层/显式状态/超时回落/不抑制复制）、§6 保护态指标、§7 D17 |
| `docs/decisions/adr-017-self-protection-two-layer.md`（新增）；`docs/decisions/README.md` | ADR 落盘与索引登记 |
| `docs/milestones/v0.2/plan.md`（v1.3） | phase-4 说明按设计定案修订 |
| `docs/milestones/v0.2/phases/README.md` | 登记本 phase 设计/测试文档 |
| `docs/milestones/dev-state.md` | phase 步骤状态（工作流维护） |

`CLAUDE.md` 无需变更（「当前状态」已将进度指向 dev-state，模块/构建命令未变）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.2 | 2026-10-21 | ⑩ 文档同步：产品/API/架构/ADR/plan 变更清单落盘 |
| 3.1 | 2026-10-21 | 代码 review 修复：节点级回落顺序对齐原产品、动态窗口变化重建峰值基线、查询只读窗口求和、回收续约竞态防护、`node_fallback` 语义与无窗口服务合成条目 |
| 3.0 | 2026-10-21 | review 修订：双层保护（节点级兜底 + 服务级权威）、显式状态机与周期评估、超时回落限制永久保护、有界指标、独立保护状态 API、并发/内存收敛 |
| 2.0 | 2026-10-21 | ③ 设计初稿：按服务窗口/清理/lifecycle、指标标签、状态 API 明细、原产品对照与同步落点 |
| 1.0 | 2026-10-21 | 开工骨架 + 设计定稿 |
