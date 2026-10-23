# Phase 4：discovery 兜底对账与新鲜度

版本: 1.4    更新时间: 2026-10-24

## 目标

交付推拉结合的「拉与对账」件（ADR-019）：hash 对账 API（per-service checksum 表拉取，增量丢失显式检测）与三层兜底轮询契约面，以及新鲜度标记出口（上游断连中）。

## 范围

做什么：

- `proto/discovery/v1`：`DiscoveryReconciliationService.Checksum` 对账契约 + `UpstreamStatus` 新鲜度元信息（查询/对账两响应携带）。
- discovery-core：checksum 随投影条目计算与缓存（canonical 规则跨语言稳定）、上游状态出口（`UpstreamStatuses`）、同步器状态转移上报。
- discovery-server：对账 rpc 出口、新鲜度在查询/对账响应的携带装配。

不做什么：

- 消费方 SDK 的兜底轮询节奏、对账周期与本地视图合并（phase-8——本 phase 只交付契约面与服务端语义，IT 以 SDK 角色驱动验证闭环）。
- 多集群聚合（phase-5——`UpstreamStatuses` 以 map 形态预留，单集群先行）。
- 摘除过滤（phase-6）、发现管理 API 的上游状态查询（phase-7）。
- registry 自我保护态进新鲜度（已定案不进，架构 §4.1/§4.2）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `proto/discovery/v1` | 对账/新鲜度契约定稿 |
| `java/server/artemis-discovery-core` | canonical checksum、投影条目缓存、`UpstreamStatuses`、同步器上报、对账/查询用例扩展 |
| `java/server/artemis-discovery-server` | 对账 rpc 出口、新鲜度携带装配 |

## 验收方式

对应测试文档：[phase-4-fallback-freshness-test.md](phase-4-fallback-freshness-test.md)

## 产品设计（③ 补）

### 契约：discovery.v1 对账与新鲜度（增量，既有 Query/Subscribe 零语义改动）

`proto/discovery/v1/discovery.proto` 新增：

```protobuf
// ---- 对账与新鲜度（SDK → discovery，架构 §2.2 外部端口；v0.3 phase-4，ADR-019 三件套之三）----

// 上游同步状态（新鲜度标记，数据枚举）：投影数据按来源 cluster 维度。SYNCED = 实时；
// 其余 = 投影冻结陈旧、继续服务（陈旧换可用，D-D5）。registry 自我保护态不进新鲜度
// （registry 内部概念，架构 §4.1 定案）。
enum UpstreamSyncState {
  UPSTREAM_SYNC_STATE_UNSPECIFIED = 0;
  UPSTREAM_SYNC_STATE_UNKNOWN = 1;       // 客户端无法识别的值（本地映射目标）
  UPSTREAM_SYNC_STATE_SYNCING = 2;       // 连接与首轮 bootstrap 进行中（含曾同步后的重连轮次）
  UPSTREAM_SYNC_STATE_SYNCED = 3;        // 已完成首轮同步，正在消费变更流
  UPSTREAM_SYNC_STATE_DISCONNECTED = 4;  // 曾同步但当前断连，等待重连（投影冻结）
}

// 上游新鲜度：拉取响应（查询/对账）的状态元信息，消费方感知数据降级（局限 #15 修复）
message UpstreamStatus {
  string cluster = 1;
  UpstreamSyncState state = 2;
}

// per-service 视图校验和（对账单元 = service 实例集快照视图，ADR-007/ADR-019）
message ServiceChecksum {
  artemis.common.v1.ServiceKey key = 1;
  uint32 checksum = 2;                   // canonical 规则见 api 文档（跨语言稳定，D-F2）
}

message ClusterChecksums {
  string cluster = 1;
  repeated ServiceChecksum services = 2;
}

// 对账请求：选择器与 QueryRequest/SubscribeRequest 同一语义（services 空 = 全部服务）
message ChecksumRequest {
  repeated artemis.common.v1.ServiceKey services = 1;
  map<string, string> labels = 2;        // checksum 计算于 labels 过滤后的视图（D-F1/D-F3）
}

message ChecksumResponseData {
  repeated ClusterChecksums clusters = 1;
  repeated UpstreamStatus upstreams = 2; // 新鲜度元信息（语义同查询响应，D-F4）
}

message ChecksumResponse {
  artemis.common.v1.ResponseStatus status = 1;
  ChecksumResponseData data = 2;
}

service DiscoveryReconciliationService {
  // hash 对账（ADR-019 三件套之三）：per-service 视图 checksum 表拉取——SDK 定期与本地
  // 逐 service 比对，不一致的 service 触发 Query 全量拉（增量丢失的显式检测，漂移窗口
  // = 对账周期）。响应只含视图中有实例的条目，缺项 = 该服务在视图中无实例（D-F1）。
  // 未就绪 UNAVAILABLE（readiness 拦截器，语义同 Query/Subscribe）。
  rpc Checksum(ChecksumRequest) returns (ChecksumResponse);
}
```

既有 `QueryResponseData` 增一字段（wire 兼容只增）：

```protobuf
message QueryResponseData {
  repeated ClusterServiceInstances services = 1;
  repeated UpstreamStatus upstreams = 2; // 新鲜度元信息（v0.3 phase-4）：非 SYNCED = 数据可能陈旧
}
```

### 语义定案

| # | 决策 | 说明 |
|---|---|---|
| D-F1 | **对账单元 = 选择器视图的 service 实例集指纹；缺项即空** | checksum 覆盖与 Query 同一选择器语义的视图（显式 services 命中 + labels 实例过滤）——SDK 本地持有的正是该视图（phase-3 D-S3 视图语义推送的 apply 结果），两侧同视图才可比。响应只含有实例条目；**缺项 = 权威空**：SDK 本地空 + 表缺 = 一致，丢失的 NEW 必致表项出现 → 不一致 → 全量拉（空服务兜底层被对账吸收，D-F6）。absent 与 empty 在协议上等价，无歧义 |
| D-F2 | **canonical 规则：per-instance hash + XOR 结合，无排序依赖（跨语言稳定）** | `serviceChecksum = xor(instanceHash)`；`instanceHash = fnv1a32(utf8(instanceId ␀ host ␀ port ␀ statusName ␀ weight ␀)) ^ xor(fnv1a32(utf8(k ␀ v ␀)) for (k,v) ∈ metadata)`（␀ = U+0000 分隔符，值含 U+0000 不受支持；port/weight 十进制；statusName = 语义名 UNSPECIFIED/UNKNOWN/UP/DOWN）。实例集与 metadata 均以 XOR 结合——**两处排序依赖一并消除**（map 迭代序、跨语言排序语义差异都不进契约），唯一顺序敏感点是 instance 内标量字段的位置序列（固定顺序无歧义）；空实例集 = 0。碰撞天花板：32 位指纹（ADR-019 既定「4 字节级」），instanceId 唯一无重复元素消没，不同内容碰撞 ~2^-32 由 15min 全量刷新兜底（`ponytail:` 注释标注）。规则全文单一来源在 api 文档，实现两侧（服务端/SDK）照抄 |
| D-F3 | **缓存分型：无 labels 读条目缓存零计算；labels 视图响应对算** | 无 labels（常见路径）：checksum 随投影条目维护——`apply`/`replaceAll` 构建新条目时计算，存于条目内（单写者同步线程，无额外锁），响应即读缓存（ADR-019「响应零计算」语义）。labels 非空：对过滤视图响应时计算，成本 O(命中实例)、仅 labels 订阅方、频率 = 对账周期（`ponytail:` 天花板：规模化时升级 per-(service, labels 签名) 缓存）。→ ADR-019 决策 #3 补视图语义一句（v1.2，review 通过时同步） |
| D-F4 | **新鲜度出口 = 拉取面（查询 + 对账响应），订阅流不携带** | 上游断连期间订阅流静默是 phase-3 D-S7 定案（流与上游状态解耦），降级感知归拉取面：SDK 三层兜底轮询本就周期拉 Query/checksum，新鲜度随拉取响应到达。订阅流加 freshness 事件会重新耦合并复杂化事件形状（否决）。plan phase-4 说明「推送快照的状态元信息」为 phase-3 改增量推送前的措辞残留 → review 通过时同步修订 plan（对账响应补入、推送移除） |
| D-F5 | **状态源复用同步器既有状态机，转移即上报** | `UpstreamState` 三值（SYNCING/SYNCED/DISCONNECTED）与转移点原样复用（`ProjectionSynchronizer` 三个赋值点），每次转移上报 `UpstreamStatuses`（domain，CHM<cluster, state> + 弱一致快照读）；readiness 语义不变（首轮 SYNCED 开门、只升不降，D-D5）。重连轮次短暂 SYNCING 属预期——契约语义统一为「非 SYNCED 即可能陈旧」，SDK 无需细分。多集群（phase-5）每同步器独立上报，map 形态天然支持；快照顺序不保证（SDK 按 cluster 键控消费） |
| D-F6 | **三层兜底轮询（资产 #8）契约面收口：对账吸收空服务层，服务端零新增** | ①空服务 60s 主动拉 → 由对账吸收（缺项 = 权威空，D-F1）；②15min 全量刷新 → Query 既有语义，兼作对账失效（checksum 拉取持续失败/碰撞）的最终兜底；③推送失败收敛 → Subscribe 重订阅 + 拉取兜底，契约既有。三层的服务端契约面 = Query + Checksum + Subscribe 三者既有/本 phase 语义，无服务端轮询节奏与配置；SDK 侧节奏参数归 phase-8 |
| D-F7 | **新 rpc 独立 service（`DiscoveryReconciliationService`），无新增配置与指标** | 对齐 phase-3 D-S1 用例分列先例（查询/订阅/对账三线独立演进）；readiness/认证经全局拦截器天然生效。弱一致读：对账拉取与并发 apply 无锁交错，瞬时不一致触发的一次多余全量拉无害（下次对账收敛，at-most-once 哲学一致）。上游状态可观测走新鲜度出口与既有日志，管理面查询归 phase-7，本 phase 无指标新增（架构 §6 无 phase-4 指标要求） |

对照基线：ADR-019（三件套之三落地；决策 #3 视图语义补充见 D-F3）；架构 §4.2「三层兜底轮询 + hash 对账」「新鲜度标记：查询响应携带」既有记载逐条兑现（查询响应 → 查询/对账两响应，D-F4）；资产 #8 三层兜底（服务端契约面收口，D-F6）；局限 #3 修复落地（丢失显式检测，ADR-019 立项根因）；局限 #15 修复（新鲜度元信息）。

### discovery-core 设计

**domain**：

- `ProjectionChecksum`（纯函数）：`int of(ServiceInstances)` / `int of(Instance)`——canonical 规则实现（D-F2），单测直测。
- `ProjectionRegistry`：`ClusterProjection` 表值改私有 record `Entry(ServiceInstances instances, int checksum)`——`replaceAll`/`apply` 构建新条目时经 `ProjectionChecksum` 计算（单写者同步线程）；既有 `query`/`service`/`services` 解包 `.instances()` 对外签名零变化。新增 `checksums(DiscoverySelector)`：labels 空 → 读条目缓存（显式集合 per-key get，全量弱一致迭代）；labels 非空 → 过滤视图现算（复用既有 `filter`，D-F3）。
- `UpstreamStatus`（record：`cluster` + `UpstreamState`）+ `UpstreamStatuses`：`update(cluster, state)` / `snapshot() → List<UpstreamStatus>`（CHM 弱一致，D-F5）。
- `QueryResponse` 增字段 `List<UpstreamStatus> upstreams`。
- 对账响应模型：`ServiceChecksum(ServiceKey key, int checksum)` + `ClusterChecksums(String cluster, List<ServiceChecksum> services)`（请求不另立 domain 模型，用例入参即归一后的 `DiscoverySelector`）。

**application**：

- `DiscoveryReconciliationService`：selector 归一/校验（复用 `DiscoverySelector.requireExplicitServicesValid`，失败 IllegalArgumentException 由门面转 INVALID_ARGUMENT）→ `registry.checksums(selector)` + `statuses.snapshot()` → `ChecksumResponse`。
- `DiscoveryQueryService`：注入 `UpstreamStatuses`，`query()` 组装 freshness 进 `QueryResponse`。
- `DiscoveryProtoFacade`/`DiscoveryProtoMapper` 扩展：`ChecksumRequest` → selector、`ChecksumResponse` → proto、`UpstreamState` ↔ proto enum（UNKNOWN 哨兵收敛）、`QueryResponse` upstreams → proto。

**infrastructure**：

- `ProjectionSynchronizer`：构造注入 `UpstreamStatuses`，三个状态赋值点（round 起 SYNCING / SYNCED / everSynced 后 DISCONNECTED）同步上报（D-F5）；既有 `state` 字段与控制流不变。

### discovery-server 设计

- `grpc/DiscoveryReconciliationGrpcService`：`@GrpcService` unary 出口，一行委派 proto 门面（同查询出口形态）。
- `DiscoveryConfiguration`：`UpstreamStatuses` bean；注入 `DiscoveryQueryService`/`DiscoveryReconciliationService`/`ProjectionSynchronizer`；新出口 bean。

## 实现结果（⑦ 补）

**交付**：

- `proto/discovery/v1`：`DiscoveryReconciliationService.Checksum`（unary 对账）+ `UpstreamSyncState`/`UpstreamStatus`（新鲜度）+ `ServiceChecksum`/`ClusterChecksums`/`ChecksumRequest`/`ChecksumResponse(Data)`；既有 `QueryResponseData` 增 `upstreams` 字段（wire 兼容只增）。
- discovery-core：domain `ProjectionChecksum`（canonical：per-instance FNV-1a-32 + XOR 结合，实例集与 metadata 双抗序）、`ProjectionRegistry` 表值改 `Entry(instances, checksum)`（写时计算缓存）+ `checksums(selector)`（无 labels 读缓存零计算 / labels 视图现算）、`UpstreamStatus`/`UpstreamStatuses`（CHM 弱一致快照）、对账响应模型三 record、`QueryResponse` 增 `upstreams`；application `DiscoveryReconciliationService` + `DiscoveryQueryService` 新鲜度装饰 + facade/mapper 扩展；infrastructure `ProjectionSynchronizer.setState` 三转移点成对上报。
- discovery-server：`DiscoveryReconciliationGrpcService`（unary 出口一行委派）+ 装配（`UpstreamStatuses` bean 注入查询/对账/同步器）。
- 测试：单测 8 用例（ProjectionChecksumTest 3 + UpstreamStatusesTest 1 + ProjectionRegistryTest 增 2 + ProjectionSynchronizerTest 增 1 + DiscoveryProtoMapperTest 增 1）+ IT 5 用例（DiscoveryReconciliationIT，SDK 角色独立实现 canonical 规则驱动端到端闭环）。

**code-review 修复（⑥ skill max 5 findings + 内联 1，逐条验证后 4 修 2 归 ⑩）**：

| finding | 处理 |
|---|---|
| S1 query/checksum 先读投影后读快照 → 重连覆盖窗口可出现「陈旧数据 + SYNCED」（checksum 侧致 SDK 对账静默跳过全量拉） | **快照先于读表**：两用例先 `upstreamStatuses.snapshot()` 后读 registry——与同步线程「先覆盖后 setState(SYNCED)」发布序交错只余保守向（陈旧标签 + 新数据） |
| S2 状态上报测试 await DISCONNECTED 快照值为瞬态脉冲（backoff 1-10ms 被 SYNCING 覆盖，10ms 采样间歇错过） | 断言改持久 `events` 锚定转移；快照值语义由 UpstreamStatusesTest 直测、上报对称性由 setState 单点保证（skill 建议的 `state()` 字段方案经验证同样瞬态，不采纳） |
| F1（内联）labels checksum 用例 `containsExactly` + `get(0)` 依赖 CHM 迭代序 | `containsExactlyInAnyOrder` + 按 key 定位断言 |
| S5 两份 phase 文档头版本 1.1 与更新历史 1.2 不一致 | 文档头对齐 1.2 |
| S3 discovery-query-api.md 未记 `upstreams` 字段 | 归 ⑩（api 文档同步计划项） |
| S4 `docs/api/discovery-reconciliation-api.md`（canonical 单一来源）缺失 | 归 ⑩（新建，含 Rust 移植要点：mod-2³² wrapping 乘法、uint32 位模式比较） |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（v1.19） | 新增 v0.3 phase-4 交付节（hash 对账/canonical 规则/缓存分型/新鲜度/三层兜底收口/工程形态） |
| `docs/api/discovery-reconciliation-api.md` | 新建：Checksum 契约 + canonical 规则单一来源（§3，含 golden vector 与 Rust 移植要点——code-review S4 归⑩项）+ 缺项规则 + 新鲜度语义 |
| `docs/api/discovery-query-api.md`（v1.1） | `QueryResponseData.upstreams` 新鲜度字段（code-review S3 归⑩项），细节引用 reconciliation 文档不复制 |
| `docs/api/README.md` | 登记 discovery-reconciliation-api.md |
| `docs/decisions/adr-019-hybrid-push-pull-subscription.md` | ③ 已同步（v1.2，决策 #3 视图语义） |
| v0.3 plan | ③ 已同步（v1.4，phase-4 说明措辞修订）——本 phase 无增量 |
| `CLAUDE.md` | 无需变更（构建命令与模块结构未变；进度单一来源为 dev-state.md） |
| 本 phase 测试文档（v1.2） | 用例结果/验证记录/回归记录（随 ⑤⑧⑨ 更新） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-24 | ⑨ 验证通过（自动化 + 人工）；⑩ 文档同步清单（产品文档 1.19、reconciliation api 新建、query api 1.1） |
| 1.3 | 2026-10-24 | ④–⑧ 完成：实现结果与 code-review 修复记录（4 修 2 归 ⑩） |
| 1.2 | 2026-10-24 | review 定案（一轮通过）：ADR-019 v1.2（决策 #3 视图语义）、plan 1.4（phase-4 说明措辞）同步 |
| 1.1 | 2026-10-24 | 产品设计定稿草案：对账/新鲜度契约 + D-F1..D-F7 + 模块设计 |
| 1.0 | 2026-10-24 | 开工骨架 |
