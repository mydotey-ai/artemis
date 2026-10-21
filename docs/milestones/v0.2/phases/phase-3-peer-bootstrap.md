# Phase 3：peer 全量 bootstrap 与节点状态 API

版本: 3.2    更新时间: 2026-10-21

## 目标

启动数据源插入分支①（存在已就绪 peer → 全量同步成功才 ready）、重启优先从 peer 同步，并落地节点状态 API（成员/就绪态，兼作 SDK 存活节点列表来源）。

## 范围

做什么 / 不做什么。

- **做**：peer 全量 bootstrap（新节点加入拉取 + 分片应答应用）；readiness 分支①（已就绪 peer 存在时同步失败保持 not ready、定期重试、不降级本地快照）；重启优先 peer 同步（peer 不可用回退本地快照）；节点状态 API（成员列表 + 就绪态）；成员配置补对外端点。
- **不做**：自我保护 2.0（phase-4）；SDK 容灾与 TTL 轮换（phase-5，本 phase 只提供其消费的节点列表来源，client 侧契约消费随 phase-5）；优雅停机（phase-6）；peer 间对账（ADR-006）。

## 涉及模块

- `proto/replication/v1/replication.proto`：`ClusterMember` 追加对外端点字段（field number 只增）+ 新增对外 `NodeStatusService`（复用既有节点状态消息；`Bootstrap` 契约 phase-1 已定稿，本 phase 实现）。
- `java/server/artemis-registry-core`：domain（`ClusterMember` 对外端点、`PeerBootstrapSource` 出站端口、`InstanceRegistry.replaceAll`）、application（bootstrap 快照导出/应用、proto 门面与 mapper）、infrastructure（静态成员解析扩展）。
- `java/server/artemis-registry-server`：启动编排 `RegistryBootstrap`（分支①/重试）、出站 bootstrap gRPC 实现（复用 per-peer channel）、`Bootstrap` 入站 handler、对外节点状态 gRPC 服务、未就绪时复制入站拒绝。
- 测试：单测 + 进程内多节点集群语义 IT（复用 phase-1 起的 harness 形态）。

## 验收方式

对应测试文档：[phase-3-peer-bootstrap-test.md](phase-3-peer-bootstrap-test.md)

## 产品设计（③ 补）

### 1. 启动数据源：分支①与重启优先 peer 同步

启动编排由 `RegistryBootstrap`（server，`InitializingBean` + `AutoCloseable`）承担：

```text
afterPropertiesSet:
  self 缺席（单节点）      → 同步回放本地快照（既有分支②/③），ready（保持 v0.1 行为与既有测试）
  self 存在（集群模式）    → 启动单线程命名调度器，异步提交 attempt()（不阻塞 context 刷新/端口绑定）
                             gRPC server 绑定后外部流量由 readiness 拦截，节点状态 API 可观察 STARTING

attempt():
  candidates = 顺序查询 peer 内部 GetNodeStatus（每个 statusTimeoutMs），收集 state == UP 的成员
    （校验应答自报 nodeId 与配置一致，防止配置串位）
  candidates 为空（无已就绪 peer）→ 回放本地快照（缺失/损坏/超期 → 空表，既有语义）→ ready
  candidates 非空 → 依次尝试每个候选：拉取全量 bootstrap 流 → 校验/归一后整体应用 → ready（成功即停止）
                    全部候选失败 → 保持 not ready，按全抖动指数退避排下一轮 attempt()；**不回退本地快照**
```

- **「已就绪 peer」判定 = 内部 `GetNodeStatus.self.state == UP`**：自报语义，不做主动探活；`STARTING` 不算。无任何 UP peer（空集群/首个节点/全集群同时冷启动/peer 均不可达）走本地快照分支，不等待。
- **重试**：单线程命名 `ScheduledExecutorService`（guidelines §4.2）；**全抖动指数退避**（`retryInitialMs × 2^attempt`，上限 `retryMaxMs`，抖动范围 `[0, delay]`），成功即停止。原产品为固定 1s 轮询（无退避/抖动，基线 §6 #2）——这里修正其在大规模同时重启时的同步风暴；上限 10s 保证故障恢复后的就绪时延可控。**每轮尝试全部 UP 候选**，避免「首个 UP peer 持久故障则永远不同步」。
- **同步成功判定 = bootstrap 流正常结束**；零分片（空注册表）也是成功，不设数据非空门槛（修复原产品 `instanceCount > 0` 导致的空集群死锁，见 §7）。`UP` 只代表该 peer 已完成自身启动流程，**不保证数据完整/最新**——无对账设计（ADR-006）下首个成功的完整流即数据源，缺失由变更再扇出/客户端 TTL 轮换自愈。
- **应用时机**：流式分片先收集为完整列表，全部收齐后一次 `replaceAll` 应用——不存在「部分应用后失败」的中间态，重试天然整轮重来。bootstrap 期间节点未 ready，外部 SDK 流量被 readiness 拦截、复制入站被临时拒绝（§4），因此应用时无并发写者。
- **启动期快照保护**：not ready 期间快照调度器不落盘（装配层以 `ReadinessGate` 包裹 `writeSnapshot`）——否则分支①重试期间会把「空注册表」写成快照，覆盖本可用于后续回退的本地快照。
- 逃逸路径沿用架构 §4.1——调整静态成员配置移除故障 peer 后重启（按「无已就绪 peer」走本地快照/空表）。

### 2. 全量 bootstrap 契约实现（入站 + 出站）

**入站（server-stream）**：`Bootstrap(BootstrapRequest) → stream BootstrapResponse`

- `requester_node_id` 仅用于日志；内部端口认证是信任边界，不做成员表校验（滚动变更期新旧成员并存，与 Replicate 同纪律）。
- **源节点未 ready 时返回 `UNAVAILABLE`**：未就绪节点不能作为数据源（客户端候选已按 UP 过滤，此为服务端边界守卫，防误用空/半量数据）。
- 从注册表读当前各 service 的共享快照（弱一致读），转为 proto 后按**序列化字节预算**分片（`shardBytes`，默认 1 MiB）；单个 service 超过预算时独占一片。空注册表 = 零分片、流正常结束。
- 每片 `status = ACK_SUCCESS`；处理异常以 gRPC `INTERNAL` 收尾，客户端整轮重来。不携带跨进程不可比的单调时间（D14）。

**出站（启动编排）**：domain 端口

```java
public interface PeerBootstrapSource {
    NodeStatus status(ClusterMember peer, long timeoutMs);    // 阻塞至多 timeoutMs；失败抛异常
    List<ServiceInstances> bootstrap(ClusterMember peer);     // 阻塞至流结束；失败抛异常
}
```

server 实现 `GrpcPeerBootstrapSource`：与 `GrpcPeerReplicator` 共用 `PeerChannels`（per-peer 惰性 channel、动态认证头、外部/内部端点区分），每次调用现取 deadline 与凭证。

**应用（application）**：

```java
// RegistryService 扩展：与快照回放同一校验/归一门；返回应用实例数
int applyBootstrap(List<ServiceInstances> services);

// InstanceRegistry 扩展：整体替换（仅启动期使用，零事件，续约时刻 = now）
void replaceAll(List<ServiceInstances> services);  // 先 upsert 全部，再移除快照中缺席的 service key
```

### 3. 节点状态 API（对外）

**契约**：`replication.v1` 新增对外服务（内部 `GetNodeStatus` 消息复用）：

```proto
// 对外节点状态 API（SDK 三级容灾的节点列表来源）：self = 自报就绪态，
// members.state = 本节点后台探测的观测态；探测有周期窗口，客户端仍以失败/熔断兜底。
service NodeStatusService {
  rpc GetNodeStatus(GetNodeStatusRequest) returns (GetNodeStatusResponse);
}

message ClusterMember {
  string node_id = 1;
  string peer_host = 2;
  int32 peer_port = 3;
  string external_host = 4;   // phase-3：对外（SDK/管理面）端点
  int32 external_port = 5;
  NodeState state = 6;        // phase-3：本节点视角的观测态（self=就绪门，peer=后台探测）
}
```

- 返回 `self`（node_id、STARTING/UP、started_at_ms）+ 全部静态成员（含 peer/external 端点与**观测态**）。`ClusterMember` 追加 `NodeState state = 6`：self 取就绪门，peer 取后台探测结果，探测失败/超时/未探测 = `UNKNOWN`。
- 绑定在**外部 gRPC 端口**，**保留外部认证、豁免 readiness 门控**（`ReadinessInterceptor` 按完整方法名放行 `artemis.replication.v1.NodeStatusService/GetNodeStatus`）——与原产品「`/api/status/*`、`/api/cluster/*` 排障面刻意不门控」一致，否则 STARTING 态永远不可观测；节点本身未就绪不应提供服务流量，但节点列表查询是排障/引导信息，不改变这一语义。
- 集群引导期间该 API 由已绑定端口的任一节点应答；SDK（phase-5）优先选择 `UP` 成员，探测周期窗口内的误判由连接失败 + 熔断兜底。
- 单节点模式：`self.node_id` 为空、成员列表为空；SDK 使用配置引导地址，不依赖本 API。

**后台 peer 状态探测**（`ClusterNodeStatusMonitor`，core infrastructure）：

```text
start(): 单线程命名调度器，首轮立即执行，之后按 probeIntervalMs（动态）固定延迟
probeRound():
  peers = members - self
  tasks = peers.map(p -> probe(p))                    // 提交到 virtual-thread-per-task executor
  futures = invokeAll(tasks, probeTimeoutMs)           // 并发探测 + 整轮超时（超时任务取消）
  next = {}                                            // 每轮整体重建，不保留旧值
  for (peer, future):
    ok + 自报 nodeId == 配置 nodeId → NodeState（STARTING/UP/DOWN）
    超时 / 异常 / 不可达 / nodeId 不匹配 → UNKNOWN（记录 WARN/指标）
  states = Map.copyOf(next)                            // volatile 发布，读侧无锁
```

- **并发 + 超时**：每个 peer 一个虚拟线程任务；`invokeAll` 以 `probeTimeoutMs` 为整轮上限并取消未完成任务；`PeerBootstrapSource.status(peer, timeoutMs)` 同时把 deadline 下传到 gRPC 调用（双保险，防端口实现阻塞）。
- **状态语义**：探测 = 拉取对端**自报**就绪状态（非业务健康检查）；`UNKNOWN` = 本轮不可达/超时/应答异常/nodeId 不匹配，不等于 DOWN；`DOWN` 只来自对端自报（phase-6 优雅停机）。self 成员的状态由就绪门实时计算，不依赖探测。
- **每轮整体重建**：瞬时失败只影响一轮，不保留陈旧 UP；探测开销 ∝ peer 数/周期，≤10 节点可忽略。
- 探测结果只服务节点状态 API / 观测，**不参与复制扇出门控**——复制仍按 per-peer 队列独立重试（phase-2 语义），避免探测状态误判导致事件丢失。
- 单节点模式（无成员身份）不启动探测调度（`stateOf` 恒 UNKNOWN，API 成员列表为空），保持零开销。
- 关闭时 `shutdownNow` 调度线程与探测执行器；探测异常隔离在单轮内，调度继续。

接口：

```java
public record ClusterMemberStatus(ClusterMember member, NodeState state) {}

// domain 端口：探测结果视图（UNKNOWN = 无记录）
public interface PeerStatusView {
    NodeState stateOf(String nodeId);
}

// ReplicationService：替换原 members()，同时服务内部/外部节点状态响应
List<ClusterMemberStatus> clusterNodes();
```

**成员配置补对外端点**（phase-1 留待本 phase 定稿）：

```text
<node-id>@<peer-host>:<peer-port>@<external-host>:<external-port>
例：node-a@10.0.0.1:9093@10.0.0.1:9090,node-b@10.0.0.2:9093@10.0.0.2:9090
```

- 集群模式（members 非空）要求恰好三段；host 仍支持括号 IPv6。单节点（members 为空）不要求。
- 为什么显式配置：对外主机可能因多网卡/NAT/K8s 与 peer 主机不同、端口也未必与 peer 一致；由 server 生命周期/集群统一端口推导会在这些形态下宣布错误地址。显式配置让每个节点都持有全量可宣布端点，API 永不返回不可用地址。
- 运维契约：external 端点必须与实际外部 gRPC 监听一致（测试以预分配固定端口启动校验）。

### 4. 未就绪节点的复制入站语义（phase-2 细化）

分支①重试期间节点长期 not ready 而内部端口已可服务。为避免「bootstrap 全量替换覆盖并发复制事件」，`Replicate` handler 在 `ReadinessGate.isReady() == false` 时返回 gRPC `UNAVAILABLE`：

- 发送侧 `GrpcPeerReplicator` 将其视为暂时失败 → per-peer worker 保序退避重发同批（契约的「串行投递、重试保序」不变）；
- 节点 ready 后积压批次按序应用；bootstrap 已包含快照时刻状态，重复投递幂等；
- `Bootstrap` 同样在未就绪时返回 `UNAVAILABLE`（源节点守卫，§2）；`GetNodeStatus` **不**受限（启动期必须可被查询，供其他节点做 UP 判定）。

### 5. 配置项（SCF，键定义在使用类内）

| 键 | 默认 | 型 | 说明 |
|---|---|---|---|
| `artemis.registry.cluster.probeIntervalMs` | 5000 | 动态 | 后台 peer 状态探测周期（首轮立即） |
| `artemis.registry.cluster.probeTimeoutMs` | 1000 | 动态 | 单轮探测上限 / 单 peer 探测 deadline |
| `artemis.registry.bootstrap.retryInitialMs` | 1000 | 动态 | 分支①同步失败的退避基数（全抖动指数退避） |
| `artemis.registry.bootstrap.retryMaxMs` | 10000 | 动态 | 退避上限（控制故障恢复后的就绪时延） |
| `artemis.registry.bootstrap.statusTimeoutMs` | 1000 | 动态 | 查询单个 peer 节点状态的 deadline |
| `artemis.registry.bootstrap.timeoutMs` | 60000 | 动态 | 单轮全量 bootstrap 流的 deadline |
| `artemis.registry.bootstrap.shardBytes` | 1048576 | 动态 | 入站分片序列化字节预算 |
| `artemis.registry.bootstrap.maxInboundMessageBytes` | 16777216 | 静态 | 出站 bootstrap channel 单消息接收上限 |

数值项全部走 `RangeValueConfig` 值域过滤（非法回落默认）。成员条目仍为静态配置。

### 6. 指标（复制 v0.2 组）

| 指标 | 类型 | 位置 |
|---|---|---|
| `artemis.registry.bootstrap.attempts` | Counter | `RegistryBootstrap`（每轮同步尝试） |
| `artemis.registry.bootstrap.failures` | Counter | `RegistryBootstrap`（单轮失败） |
| `artemis.registry.bootstrap.synced.instances` | Counter | `RegistryBootstrap`（成功应用实例数） |
| `artemis.registry.cluster.probe.rounds` | Counter | `ClusterNodeStatusMonitor`（探测轮次） |
| `artemis.registry.cluster.probe.failures`（tag `peer`） | Counter | `ClusterNodeStatusMonitor`（超时/异常/nodeId 不匹配） |
| `artemis.registry.cluster.peers.up` | Gauge | `ClusterNodeStatusMonitor`（当前 UP peer 数） |

日志：分支选择与同步成功 INFO；失败 WARN（自愈中但需关注）；不进入热路径。

### 7. 原产品设计对照（源码取证）

原产品对应机制（源码取证：`NodeManager.java:81-190`、`RegistryReplicationInitializer.java:43-113`、`ClusterManager.java:84-245`、`ClusterServiceImpl.java:47-110`、`AddressRepository.java:52-126`；文档见 `docs/legacy/arch/runtime.md` §3.2–3.4/§4.1、`docs/legacy/domains/replication-cluster-logic.md` §3/§5 F3/§7、`docs/legacy/domains/replication-cluster-spec.md` FR-RC-09/FR-RC-14）：

| 机制 | 原产品设计 | 本设计 | 取舍理由 |
|---|---|---|---|
| 启动同步执行方式 | daemon 单线程 1s 固定间隔（动态读），异步不阻塞；UP 即退出，一次性同步 | 集群模式异步；单节点同步 | 继承异步（启动可观测、端口先绑定）；单节点保留 v0.1 同步语义；UP 后同样不做周期对账（ADR-006） |
| 就绪目标 | REGISTRY（peer 全量 + 数据非空）+ DISCOVERY（管理面双仓库首刷）双目标；REGISTRY 成功可顺带置 DISCOVERY | 只门控 registry 自身数据源 | registry 零外部依赖、management 独立进程（ADR-016）；双目标在原产品中还存在平面 force-down 被覆盖的问题 |
| 同步源选择 | 本 zone 其他节点→其他 zone；准入**严格 UP**；配置顺序取第一个成功者，peer 之间不合并 | 收集全部 UP 成员，每轮依次尝试全部候选 | v0.2 无 zone 维度（架构无 zone 模型）；修正「首个 UP peer 持久故障/数据不完整则永不同步或带残缺数据」 |
| 失败重试 | 固定 1s 轮询（无退避/抖动，基线 §6 #2）；失败按 service 部分应用、不回滚，下轮重置续约并重发 NEW | 全抖动指数退避 1s→10s；整轮收集后一次应用 | 修正同时重启的同步风暴与重放副作用；上限保证恢复时延 |
| 数据门槛 | `replicationInstanceCount > 0`（按 service 计数）才算成功 | 零分片（空注册表）也算成功 | 修复空集群死锁（logic §7.3）：新集群/空注册表不等待不存在的 peer |
| force 开关族 | 整体 + 双平面 × up/down 6 键；整体 force-up 跳过全部同步，发布样例默认开启 | 不继承任何 force 开关 | 危险旁路 + DOWN 粘性 + 口径分裂（logic §7.4–7.6）；逃生路径改为调整成员配置后重启 |
| 全量数据面 | 单次 POST `services.json` 整包（2s socket timeout，无分片/版本/游标） | `replication.v1 Bootstrap` server-stream 分片 | phase-1 已定契约；10 万实例需要分片与显式超时 |
| 重建方式 | 走复制 register 逐实例建租约，`creation=renewal=now`，每实例发 NEW，事件流可能截断（logic §7.10） | `replaceAll` 零事件、续约时刻 = now | 保持 D4「回放零事件」；避免重建事件风暴与推送截断 |
| 本地快照回退 | 无服务端快照（全仓库无文件 IO） | 无 UP peer 时回放本地有效快照，否则空表 ready | 继承 v0.1 持久化；peer 不可用不阻塞启动 |
| 节点状态视图 | 5s fixed-delay + **串行**探测、3 次异常重试、**无读超时**（超时属性错用），非 success 不重试；结果供扇出与 up-nodes | 后台单线程调度 + **每轮虚拟线程并发探测全部 peer**，per-probe/整轮 timeout；失败/超时=UNKNOWN；结果只供 API/观测 | 继承后台探测与状态视图；修正原产品串行超周期、无超时、状态陈旧的问题 |
| 扇出门控 | 出批时读探测状态表，`canServiceRegistry` 不满足即跳过且不生成失败任务 | 不看探测状态，per-peer 有界队列独立退避重试 | 探测有周期窗口/误判；phase-2 已定投递确认 + 重试保序，跳过会造成静默缺口 |
| up-nodes 端点 | 按 `canServiceRegistry` + zone/allow 位过滤（**非严格 UP**，带平面 force-up 的 STARTING 也会下发），空列表 data-not-found，限流 | 返回全部成员的观测态（含 UNKNOWN）；SDK 优先 UP、连接失败/熔断兜底 | 保留原产品「列表 + 状态」能力；服务端不做过滤，避免探测窗口把可用节点误剔除；探测周期窗口由客户端熔断兜底 |
| 复制入站 readiness | 复制入口显式豁免 readiness（自举依赖），探测/状态端点也不门控 | 仅 `GetNodeStatus` 不受限；`Replicate`/`Bootstrap` 未就绪返回 UNAVAILABLE | 避免 bootstrap 全量替换覆盖并发复制事件、禁止未就绪节点充当数据源；UNAVAILABLE 让发送侧保序重试，不丢事件（§4） |
| 排障面 readiness | `/api/status/*`、`/api/cluster/*` 不门控 | 节点状态 API 豁免 readiness，仅保留认证 | STARTING 可观测；写流量仍严格门控 |
| 自身识别 / 成员配置 | `cluster.nodes`（zone→urls）URL 列表 + URL 子串匹配本机；空列表保护导致残留 | `cluster.self` + 三段式成员条目（含对外端点） | phase-1 已定（legacy §6 #9/#10）；显式配置支持多网卡/NAT |

### 8. 设计决策与同步落点

- **分支①判定/异步执行/退避重试/不回退本地快照/未就绪不落盘 + 未就绪拒绝复制入站** → 同步 `docs/arch/artemis-next-architecture.md` §4.1「readiness 门控」与「变更事件扇出」节（review 通过后立即修订）。
- **成员配置追加对外端点（三段式）** → 同步架构 §4.1「集群成员」节与产品文档（phase-1 交付节的「端点不进成员配置」表述随之修正）。
- **对外节点状态 API 落 `replication.v1` 的独立 `NodeStatusService`（复用节点状态消息、外部端口、豁免 readiness 保留认证）** → 同步架构 §2.2 端口表/§4.1；`docs/dev/tech-stack-java.md` §2.2 生成矩阵无需变（client 随 phase-5 引入 replication.v1 生成，本 phase 不改 client）。
- **后台 peer 状态探测（并发 + 超时，状态只服务 API/观测）** → 同步架构 §4.1「节点状态」与 §6「复制 v0.2」指标节（review 通过后立即修订）；探测不参与扇出门控的边界一并写入；phase-1 契约注释「不做 peer 主动探测」随 ④ 实现更新为「self 自报 + peer 后台探测」，wire 语义只增兼容。
- **bootstrap 全量应用 `replaceAll` 零事件** → 沿用 D4「回放零事件」不变量，无新 ADR；架构 §4.1 写入。
- **不新增 ADR**：均属 ADR-004（全对全 + 增量 + bootstrap 自愈）、ADR-006（无对账）语义内细化；proto 变更按 proto-contract §2 只增兼容。

## 实现结果（⑦ 补）

- **契约**：`ClusterMember` 追加 `external_host/external_port/state`（field 4–6）；新增对外 `NodeStatusService.GetNodeStatus`（复用节点状态消息，外部端口）。
- **domain**：`ClusterMember` 携带 peer + external 端点；新增 `ClusterMemberStatus`、`PeerStatusView`、`PeerBootstrapSource`；`InstanceRegistry.replaceAll`（启动期清空后按 restore 重建、零事件）。
- **infrastructure**：`StaticClusterMembers` 三段式解析（集群模式缺对外端点 fail-fast）；`ClusterNodeStatusMonitor`——单线程调度（首轮立即、动态周期）+ 虚拟线程并发探测全部 peer，`invokeAll` 整轮超时 + gRPC deadline 双保险，每轮整体重建并经 volatile Map 发布，失败/超时/nodeId 不匹配只计一次 `probe.failures`，状态迁移日志与 `peers.up` gauge；`AbstractIntervalScheduler` 支持首轮立即执行。
- **application**：`ReplicationService.clusterNodes()`（self 就绪门 + peer 探测态）与 `bootstrapSnapshot()`；`RegistryService.applyBootstrap` 与快照回放共用 `validatedServices` 校验/归一/丢弃口径；`ReplicationProtoFacade` 以**惰性流**导出 bootstrap proto（逐 service 映射，不物化整表）。
- **server**：`PeerChannels`（复制与 bootstrap 共用的 per-peer channel 池、认证头现取、bootstrap 接收上限）；`GrpcPeerBootstrapSource`；`RegistryBootstrap` 集群模式异步（首轮立即、全抖动指数退避、每轮尝试全部 UP 候选、确认过数据源后不降级快照、失败日志带堆栈）；快照调度器按 readiness 门控；`ReplicationGrpcService` 增加 `Bootstrap`（按序列化字节预算分片、requester 日志、未就绪拒绝）与 `Replicate` 未就绪拒绝（UNAVAILABLE）；`NodeStatusGrpcService` 对外服务；`ReadinessInterceptor` 按方法名豁免节点状态 API。
- **配置/指标**：`cluster.probeIntervalMs/probeTimeoutMs`、`bootstrap.retryInitialMs/retryMaxMs/statusTimeoutMs/timeoutMs/shardBytes/maxInboundMessageBytes`；`cluster.probe.rounds/failures`、`cluster.peers.up`、`bootstrap.attempts/failures/synced.instances`。
- **code-review 修复（9 条）**：`ClusterMembershipIT` 补 ready 等待；探测超时失败计数去重（`ProbeResult`，只计一次）；bootstrap 改为惰性流避免整表 proto 复制；回放/bootstrap 校验口径抽公共 helper；补多分片与空注册表 bootstrap 用例；成员配置描述同步三段式；bootstrap 失败日志带异常堆栈；bootstrap 记录 requester；清理测试死代码/多余端口预留。
- 测试结果：`dev-test.sh all` 全绿（⑤ 首跑 + ⑧ 回归）。

## 文档同步（⑩ 填）

- 产品文档 `docs/product/artemis.md` v1.11：新增 v0.2 phase-3 交付节（peer bootstrap、not ready 语义、三段式成员配置、节点状态 API 与后台探测、配置/指标）；修正 phase-1 交付节「对外端点不进入成员配置」表述。
- 架构 `docs/arch/artemis-next-architecture.md` v1.29：§2.2 外部端口补对外节点状态 API（认证保留、readiness 按方法豁免）与内部 Replicate/Bootstrap 未就绪语义；§4.1 分支①异步/退避/全候选/不降级快照、not ready 守卫与快照落盘守卫、三段式成员配置与后台并发探测（不参与扇出门控）；§6 指标补 bootstrap/探测。
- 版本规划 `docs/milestones/v0.2/plan.md` v1.2：phase-3 说明同步定案（异步 bootstrap/退避/全候选、三段式成员、节点状态 API 与探测）。
- API 文档：新增 `docs/api/node-status-api.md` v1.0（契约、认证/readiness、探测语义、示例），`docs/api/README.md` 索引登记。
- 契约：`proto/replication/v1/replication.proto` 追加 `ClusterMember` 对外端点/观测态字段（4–6）与对外 `NodeStatusService`；按 proto-contract §2 只增兼容。
- 索引：`docs/milestones/v0.2/phases/README.md` 已登记本 phase 两份文档；`docs/api/README.md` 已登记新 API 文档；其余索引无变更。
- 开发规范/技术选型：client 本 phase 不生成 replication.v1（phase-5 消费节点状态 API 时更新生成矩阵）；构建命令与模块结构未变。
- `CLAUDE.md`：构建命令、模块结构、当前状态描述均未变，无需修改。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.2 | 2026-10-21 | ⑨ 人工验证通过；⑩ 文档同步清单回填（产品/架构/plan/API/契约/索引） |
| 3.1 | 2026-10-21 | ④⑤⑥⑦⑧ 回填：实现结果、code-review 9 条修复、全量 verify 全绿 |
| 3.0 | 2026-10-21 | review 定案：节点状态改为后台探测——单线程调度 + 每轮虚拟线程并发探测全部 peer、整轮/单 peer 超时、失败=UNKNOWN；节点状态 API 返回成员观测态；探测不参与扇出门控；新增探测配置与指标 |
| 2.2 | 2026-10-21 | 原产品源码取证补全 §7 对照表（双就绪目标、peer 不合并、部分应用/重置续约、up-nodes 非严格 UP、复制 readiness 豁免、探测缺陷精确位置） |
| 2.1 | 2026-10-21 | review 输入：对照原产品启动门控/探测/up-nodes 设计（新增 §7 对照表）；分支①改异步、退避加抖动、每轮尝试全部 UP 候选、not ready 不落盘；节点状态 API 豁免 readiness 但保留认证 |
| 2.0 | 2026-10-21 | ③ 设计定稿：分支①判定与重试、bootstrap 入站/出站、节点状态 API 与成员配置对外端点、未就绪复制拒绝、配置与指标 |
| 1.0 | 2026-10-21 | 开工骨架 |
