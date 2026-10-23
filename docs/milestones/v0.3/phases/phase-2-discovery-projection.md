# Phase 2：discovery 投影内核与查询

版本: 1.5    更新时间: 2026-10-23

## 目标

在 discovery 侧构建 `(cluster, namespace, service)` 投影模型，消费 registry 的 `projection.v1` 全量与变更流，并提供查询 API。

## 范围

做什么：

- discovery-core：投影数据结构（不可变快照 + volatile 发布）、bootstrap/变更流消费应用服务、重连全量覆盖、查询选择器与查询服务。
- discovery-server：连接上游 registry 集群的装配与查询服务出口（形态随 ③ 设计定稿）。

不做什么：

- 订阅推送（phase-3）。
- 三层兜底轮询与新鲜度出口（phase-4）。
- 多集群聚合（phase-5）。
- 摘除过滤（phase-6）。
- 发现管理 API（phase-7）。
- 消费方 SDK（phase-8）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `java/server/artemis-discovery-core` | 投影模型、上游消费、查询应用服务 |
| `java/server/artemis-discovery-server` | 上游连接装配、查询出口 |
| `proto/projection/v1` | 消费既有契约（phase-1 定稿，零改动） |
| `proto/discovery/v1` | 查询/订阅契约（本 phase 是否定稿随 ③ 设计定稿） |

## 验收方式

对应测试文档：[phase-2-discovery-projection-test.md](phase-2-discovery-projection-test.md)

## 产品设计（③ 补）

### 契约：discovery.v1 查询定稿草案

```protobuf
syntax = "proto3";
package artemis.discovery.v1;
import "common/v1/model.proto";
option java_package = "org.mydotey.ai.artemis.proto.discovery.v1";
option java_multiple_files = true;

// SDK → discovery 查询契约（架构 §2.2 外部端口）。v0.3 phase-2 交付 unary 查询；
// 订阅推送 rpc 随 phase-3 以新增 rpc 演进（wire 兼容，field number/rpc 名只增）。
// 选择器模型与订阅共用（v0.3 plan）：services 精确集合（空 = 全部服务）+
// labels 实例 metadata 全键值匹配（实例级过滤——分组以实例 metadata 表达，ADR-013）。

message QueryRequest {
  repeated artemis.common.v1.ServiceKey services = 1;  // 空 = 全部服务
  map<string, string> labels = 2;                      // 实例须含全部键值才入选
}

// 投影视图单元：cluster 是来源标识，由 discovery 在投影边界注入（ADR-014，registry 契约不携带）
message ClusterServiceInstances {
  string cluster = 1;
  artemis.common.v1.ServiceInstances instances = 2;    // service 实例集快照（ADR-007 单元）
}

message QueryResponseData {
  repeated ClusterServiceInstances services = 1;
}

message QueryResponse {
  artemis.common.v1.ResponseStatus status = 1;
  QueryResponseData data = 2;
}

service DiscoveryQueryService {
  // 查询当前投影。未就绪（首轮 bootstrap 未完成）UNAVAILABLE——空数据不作真相返回（资产 #9）。
  rpc Query(QueryRequest) returns (QueryResponse);
}
```

### 语义定案

| # | 决策 | 说明 |
|---|---|---|
| D-D1 | **discovery.v1 本 phase 只定稿查询 rpc** | 订阅/推送契约形状（server-stream、推送单元语义）归 phase-3 以新增 rpc 落地，避免提前固化未设计面；查询先行定稿支撑 phase-8 SDK 拉取/兜底轮询路径与本 phase IT 驱动 |
| D-D2 | **投影存储：per-cluster `ConcurrentHashMap<ServiceKey, ServiceInstances>`，值不可变 + per-service 替换（registry `InstanceRegistry` 同构）** | 原子粒度 = service 实例集（ADR-007 既定，无表级原子需求）：变更批次只重建命中的 service 条目（新不可变 `ServiceInstances` 替换），bootstrap 覆盖 = 逐 service put + 删除快照外 key（覆盖过程的中间态与逐条变更中间态同级，最终一致投影可接受）。**条目存在性 = 服务存在性（≥1 实例）：应用 DELETE 后实例集为空即删条目**——registry 侧空 holder 回收、bootstrap 快照不含空服务，两路径（变更流清空 / 覆盖删除快照外 key）语义收敛一致；空条目不得滞留（phase-2 无周期 bootstrap，残留将漂移至下次重连）。写侧 per-cluster 单线程（D-D3 同步线程）保同 service 变更序；读路径无锁（CHM 读 + 不可变值），查询持引用过滤零克隆（局限 #13 深克隆侧一并规避）。变更高频下无整表复制开销与 GC 压力。cluster 维度进结构是 ADR-014 既定模型，phase-2 单源、phase-5 多源只加配置与 per-source 同步器，聚合无特殊化。~~初稿整表 copy-on-write~~（review 修正：表级原子无决策依据，变更高频时整表复制为持续开销） |
| D-D3 | **消费安全序：同步线程合一驱动，覆盖先于一切事件应用** | 单同步线程循环：Watch 建立确认（首条空批，phase-1 契约）→ Bootstrap（blocking）→ **立即整表覆盖** → 进入 drain 循环消费事件队列。gRPC 回调线程只 `offer`（有界队列，满 = 视为慢消费：关流走重连整轮重来，at-most-once 自愈）；流断开以哨兵事件入队唤醒。无丢失论证：覆盖前积压事件中产生于快照读取前者已被快照吸收（幂等覆盖）、产生于后者者必在流中随后到达（服务端按产生序推送），两者都在覆盖后按到达序应用；「产生于快照读取后、到达于覆盖前」的窗口事件不被覆盖吞掉——覆盖先于 drain，队列事件全部在覆盖后应用 |
| D-D4 | **重连：整轮重来 + 全抖动指数退避** | 断流/失败/队列满 → 关流、清队列、状态 DISCONNECTED → 退避（全抖动指数，1s→10s 默认，复用 registry peer 同策略）→ 重走 Watch+Bootstrap（全量覆盖幂等，phase-1 契约自愈序）。每轮依次尝试全部配置 endpoints（任一成功即用） |
| D-D5 | **readiness：首轮 SYNCED 才 ready；断连不降 ready** | 冷启动上游不可达 → 持续重试、不 ready、查询 UNAVAILABLE（空数据不作真相，资产 #9）；SYNCED 后上游断连 → 继续 ready、投影继续服务（陈旧换可用，原产品缓存永不失效语义），新鲜度出口（「上游断连中」标记）归 phase-4，本 phase 状态内部维护（D-D6） |
| D-D6 | **上游状态内部维护** | `UpstreamState`（SYNCING/SYNCED/DISCONNECTED + lastBootstrapAt/lastEventAt）随同步器维护，本 phase 出日志；新鲜度查询出口归 phase-4、管理面出口归 phase-7，不提前 |
| D-D7 | **选择器语义：services 精确集合 + labels 实例级过滤** | `services` 空 = 全部服务；非空 = namespace+service 精确匹配（重复 key 归一）。`labels` = 实例 metadata 全键值匹配（实例级子集过滤，分组语义 ADR-013）。空条目规则（随 D-D2 空即删修正）：**空条目仅产生于 labels 过滤**——服务条目存在（≥1 实例）但实例全不匹配 labels 时，显式指定 services 命中返回空实例条目（有效答案）；服务本身零实例时条目不存在，与未命中同形不返回。未指定 services 的全量查询不返回过滤后为空的条目（防响应膨胀）。校验：指定 services 时字段空串/空白 → INVALID_ARGUMENT（错误码注册表现值），前置校验在应用服务一次完成 |
| D-D8 | **上游配置单集群（phase-5 演进）** | `artemis.discovery.upstream.clusterId`（投影 cluster 维度值，默认 `default`）+ `endpoints`（逗号分隔 host:port，**必填 fail-fast**——上游 1..N 是 discovery 存在前提）+ 内部认证凭证（registry 内部 peer 端口的 BasicAuth 客户端侧，键型同 registry internal auth）。多集群（1..N）与独立上游状态归 phase-5，配置形状届时演进 |
| D-D9 | **discovery 侧自建 readiness/认证三件，不上移 common** | `ReadinessGate`/`ReadinessInterceptor`/readiness HealthIndicator 与 `BasicAuthServerInterceptor` 同构复制于 discovery-server（common 禁 grpc 源码 import——出站 stub 生成除外；不动 v0.2 已交付 registry 模块）；第三处出现（v0.4 management）时再评估上移专设模块 |
| D-D10 | **查询出口认证同 registry 外部端口模式** | discovery 外部 gRPC 端口挂 BasicAuth（默认关闭，启用需显式凭证）+ readiness 拦截器（未就绪 UNAVAILABLE）；与 registry 外部端口行为对齐，SDK 侧接入（phase-8）零新语义 |

对照基线：资产 #9（启动门控）、#13 修复（快照引用零克隆）；原产品 discovery 与 registry 同节点无投影分发（legacy §2.4），投影消费链为新建，承接 phase-1 契约既定语义（建立确认、全量覆盖幂等、at-most-once 自愈）。

### discovery-core 设计

**domain**（自建同构 record——guidelines §2 domain 不依赖 proto，registry-core 的 domain 不跨模块复用，跨模块共享只走 common 同源 proto）：

- `ServiceKey`/`Instance`/`InstanceStatus`/`ServiceInstances`：与 registry domain 同构的小 record（消费侧只读，无租约概念）。
- `ChangeType`（NEW/UPDATE/DELETE 三值消费语义）/`InstanceChange`/`ServiceChange`：projection.v1 事件 domain 形态（UNKNOWN 哨兵映射在 application mapper）。
- `DiscoverySelector`（services 集合 + labels map）、`QueryRequest`/`QueryResponse`/`ClusterServiceInstances`（查询领域模型，与契约同名同构）。
- `ProjectionSource`（出站端口，server 供 gRPC 实现）：`bootstrap(requesterId)`（blocking，流正常结束返回全部）/ `watch(subscriberId, ProjectionListener)` 返回 `ProjectionWatch`（AutoCloseable 流句柄）。
- `ProjectionListener`：`onEstablished()`（订阅建立确认）/ `onChanges(List<ServiceChange>)`。
- `ProjectionRegistry`：投影存储（D-D2）。写接口（同步线程独占）：`replaceAll(services)`（put 全部 + 删除快照外 key）、`apply(batch)`（重建命中 service 条目，DELETE 后空集即删条目）；读接口：`query(selector)` 返回 `List<ClusterServiceInstances>`（CHM 弱一致迭代 + 不可变值持引用过滤）；`clusterIds()`。
- `UpstreamState`（enum + 状态记录）。

**application**：

- `DiscoveryQueryService`：查询用例——选择器归一/校验（信任边界一次）→ 投影查询 → `QueryResponse`。
- `DiscoveryProtoFacade`/`DiscoveryProtoMapper`：discovery.v1 ↔ domain 同名类转换（MapStruct 语义性转换手写补齐：枚举 UNKNOWN 哨兵、空批确认信号）。
- `DiscoveryReadinessGate`：readiness 门控（synchronizer 置位，拦截器/HealthIndicator 消费）。

**infrastructure**：

- `ProjectionSynchronizer`（D-D3/D-D4）：单同步线程循环 + 有界事件队列（gRPC 回调 offer、满触发重连）+ 哨兵唤醒；持有 `ProjectionSource`/`ProjectionRegistry`/`ReadinessGate`/`Clock`，AutoCloseable（stop + join）。配置类内定义（键见 D-D8 + `artemis.discovery.projection.queueCapacity` 默认 8192、`artemis.discovery.upstream.establishTimeoutMs` 默认 10s、`bootstrapTimeoutMs` 默认 60s、`maxInboundMessageBytes` 默认 16MiB、退避 min/max）。

### discovery-server 设计

- `upstream/UpstreamConfig` + `UpstreamAuthConfig`（SCF，静态，D-D8；endpoints 未配置 fail-fast）。
- `upstream/UpstreamChannels`：per-endpoint channel 池（惰性、复用、close 释放；模式同 `PeerChannels`，接收上限 16MiB）。
- `upstream/GrpcProjectionSource`：`ProjectionServiceGrpc` 实现 `ProjectionSource`——bootstrap blocking 迭代分片（模式同 `GrpcPeerBootstrapSource`）；watch async stub + `StreamObserver` 适配（onNext→mapper→listener；空批→onEstablished；onError/onCompleted→触发重连信号）。
- `grpc/DiscoveryQueryGrpcService`：`DiscoveryQueryService` 出口（一行委派 proto 门面）。
- `grpc/ReadinessInterceptor`、`grpc/BasicAuthServerInterceptor`、`health/DiscoveryReadinessHealthIndicator`：D-D9 自建三件。
- `DiscoveryConfiguration`：装配（`StringProperties`/上游组件/`ProjectionSynchronizer` 生命周期/查询服务与拦截器注册）。
- `application.properties`：查询外部 gRPC 端口（默认 9095）、HTTP 辅助/actuator 管理端口（9096，默认仅绑本机）、上游配置键位。

## 实现结果（⑦ 补）

**交付**：

- `proto/discovery/v1/discovery.proto`：`DiscoveryQueryService.Query`（unary，选择器 services 精确集合 + labels 实例级过滤）；`ClusterServiceInstances`（cluster 投影边界注入，ADR-014）。
- discovery-core：domain 12 类（同构 record + `ProjectionRegistry` per-service 条目存储 + `ProjectionSource/Session/Watch/Listener` 会话型端口 + `ProjectionSyncListener` 回调）；application 5 类（`DiscoveryQueryService` 归一校验、`DiscoveryProtoMapper/Facade`、`DiscoveryReadinessGate`、`ErrorCodes`）；infrastructure `ProjectionSynchronizer`（单同步线程 + per-round 有界队列、覆盖先于 drain、全抖动退避重连）。
- discovery-server：`GrpcProjectionSource`（端点 round-robin 会话、channel 池 + keepalive 半开检测、认证头动态现取）、`DiscoveryQueryGrpcService` 出口、readiness/认证拦截器与 HealthIndicator（D-D9 同构复制）、SCF sources 装配、`application.properties` + `logback-spring.xml`；pom（discovery-core grpc stub 生成、server gRPC/观测/IT 依赖）。

**code-review 修复（⑥/⑦）**：

| finding | 处理 |
|---|---|
| DELETE 未知 instanceId 误删整服务条目（陈旧 DELETE 吞兄弟实例） | `without` 返回 null 改 no-op（语义对齐 registry InstanceRegistry）+ 新单测 |
| watch 被动长流无 keepalive，NAT 半开连接永久失感 | channel 加 `keepAliveTime(310s)`（≥ 服务端默认 permitKeepAlive 5min，不触 GOAWAY） |
| 旧轮 watch close 竞态窗口内迟到回调污染新轮队列/断流标志 | 队列 + 标志 + 哨兵改 per-round（`RoundEvents`），旧轮对象整体废弃 |
| 未同步成功即报 DISCONNECTED（虚报「有陈旧投影」） | 断连通知与状态迁移以 everSynced 门控（SYNCING 期失败保持 SYNCING） |
| discovery-server 缺 logback-spring.xml（JSON 日志依赖落空） | 复制 registry 同款 9 行配置 |
| 未用 test 依赖（spring-boot-grpc-test、registry-core test-jar） | 删除；测试文档设施描述对齐 |
| IT registry context 替换 config.location 丢 grace-period=1s | registryArgs 显式补 `--spring.grpc.server.shutdown.grace-period=1s` |
| query 显式集合 LinkedHashSet 无效包装 | 直接迭代（去重已在 DiscoverySelector 构造完成） |
| UpstreamConfig 端口解析裸 NFE（无键/值上下文） | 包装为带 KEY_ENDPOINTS 上下文的 IllegalStateException |
| `SpringEnvironmentConfigurationSource`/认证拦截器第三份重复 | **defer v0.4 批次统一上移**（需新建共享 server-support 模块并重构 registry/management 已交付装配，本 phase 不扩散范围；注释与本文档记录） |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md`（v1.17） | 新增 v0.3 phase-2 交付节（投影模型/上游消费安全序/readiness/查询 API/配置/工程形态） |
| `docs/api/discovery-query-api.md` | 新建：discovery.v1 查询契约（选择器语义、空条目规则、readiness/认证、上游配置表） |
| `docs/api/README.md` | 登记 discovery-query-api.md |
| `CLAUDE.md` | 无需变更（进度单一来源为 dev-state.md，构建命令与模块结构未变） |
| `docs/milestones/v0.3/phases/phase-2-discovery-projection-test.md`（v1.5） | 用例结果/回归/验证记录（随 ⑤⑧⑨ 逐步更新） |
| 架构文档 / ADR | 无变更（投影模型、cluster 注入、快照单元分别已有 §4.2 与 ADR-014/007 记载；本 phase 为契约细化，无架构级决策） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.5 | 2026-10-23 | ⑨ 验证通过（自动化 + 人工）；⑩ 文档同步清单 |
| 1.4 | 2026-10-23 | ④–⑧ 完成：实现结果与 code-review 修复记录（含 defer 决策） |
| 1.3 | 2026-10-23 | review 修正：D-D2 补「空即删条目」（与 registry 空 holder 回收对齐，两路径语义收敛）；D-D7 空条目规则改为仅产生于 labels 过滤 |
| 1.2 | 2026-10-23 | review 修正 D-D2：整表 copy-on-write → per-service 条目替换（CHM + 不可变值，registry InstanceRegistry 同构；表级原子无决策依据且高频变更有整表复制开销） |
| 1.1 | 2026-10-23 | 产品设计定稿（discovery.v1 查询契约 + D-D1..D-D10 + 模块设计） |
| 1.0 | 2026-10-23 | 开工骨架 |
