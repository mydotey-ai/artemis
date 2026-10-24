# Phase 8：消费方 SDK

版本: 1.7    更新时间: 2026-10-24

## 目标

交付 Java 8 消费方 discover SDK：订阅推送、本地缓存、磁盘快照、兜底轮询与 discovery 专属地址容灾。

## 范围

做什么（据 plan phase-8 说明）：

- 订阅（复用 `discovery.v1 DiscoverySubscriptionService`，SDK 侧视图语义 apply）。
- 本地缓存（订阅快照引用，避免深克隆）。
- 磁盘快照（冷启动回放、server 全挂兜底）。
- 兜底轮询（空服务主动拉、定期全量刷新、推送失败收敛；对账周期与 15min 刷新节奏落 SDK 侧）。
- 地址容灾：与 registry 相同的管线——HTTP 引导地址 → discovery 静态节点目录 → 历史成功连接缓存 + 熔断换址。
- 服务端缺口（③ 登记）：discovery 静态节点目录（`nodes` 配置 + `GET /cluster/nodes`），使客户端可前置寻址（ADR-020）。

不做什么：

- 发现侧 Spring Boot starter 自动装配与 registry + discovery all-in-one 打包（phase-9）。
- discovery 组集群/节点间协议/存活探测（节点目录仅为静态部署声明）。
- 注册侧 SDK 既有能力的重写（心跳、注册四操作、寻址/熔断基础沿用并泛化现有实现）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `java/client/artemis-client` | 复用既有统一 SDK artifact（已自生成 `discovery.v1`，protoc 3.25.x / Java 8）；新增发现侧 public API、缓存、快照、轮询与容灾；`HttpNodeListFetcher`/`NodeListRefresher`/`FailoverAddressProvider` 泛化复用 |
| `java/server/artemis-discovery-server` | 静态节点目录配置 + 外部 HTTP `GET /cluster/nodes`（protobuf、Basic、readiness 门控） |
| `java/client/spring-boot-starter-artemis-client` | phase-9 再接入发现侧自动装配；本 phase 仅随 `RegistryClient` 更名适配 |
| `proto/` | `discovery.v1` 增 `DiscoveryNode`/`DiscoveryNodesResponse`（只增，无新 service） |
| `docs/decisions`、`docs/arch`、roadmap | ADR-020 新建、ADR-002/架构 §2.1/§2.2/roadmap v0.3 同步（已在设计阶段落地） |
| 技术选型/规范 | 若决定新增模块或依赖，须同步 `docs/dev/tech-stack-java.md` §2.1/§2.2 与 reactor（plan phase-8 说明） |

## 验收方式

对应测试文档：[phase-8-consumer-sdk-test.md](phase-8-consumer-sdk-test.md)

## 产品设计（③ 补）

### 1. 落点与范围

本 phase 两条线：

1. **SDK 线**：发现侧消费 API 到既有 `artemis-client` artifact（不新增模块、不新增依赖）：复用其 Java 8 生成线（`common.v1` + `discovery.v1`，protoc 3.25.x）、`ClientBasicAuth` / `ClientProperties` 凭证抽象与 JDK 自带能力。`spring-boot-starter-artemis-client` 本 phase 仅随公开类型更名适配（phase-9 再补发现侧自动装配）。
2. **服务端线**：discovery-service 新增**静态节点目录**（`artemis.discovery.nodes` 配置 + 外部 HTTP `GET /cluster/nodes`），使客户端具备与 registry 相同的前置寻址管线（HTTP 引导地址 → 节点列表 → gRPC 选址/熔断）。**仍不组集群**（无节点间协议、无存活探测；节点列表是部署声明），需新建 ADR-020 并修订 ADR-002/架构 §2.1/§2.2。

**proto 增量为 discovery.v1 节点目录消息**（无新 service；HTTP 返回 protobuf bytes）：

```protobuf
// 静态节点目录（SDK 前置寻址）：部署声明，不做存活探测、无节点 id（地址即标识）。
message DiscoveryNode {
  string host = 1;   // 对外 gRPC 端点
  int32 port = 2;
}
message DiscoveryNodesResponse {
  artemis.common.v1.ResponseStatus status = 1;
  repeated DiscoveryNode nodes = 2;
}
```

**同 phase 公开 API 更名**（review 定案）：注册侧接口 `ArtemisClient` → `RegistryClient`，实现 `DefaultArtemisClient` → `DefaultRegistryClient`，工厂 `ArtemisClients.newClient(...)` → `newRegistryClient(...)`；v0.x 未发布，直接改名不留兼容别名，starter / 测试 / 文档同步更新。

### 2. 公开 API（`org.mydotey.ai.artemis.client`）

```java
/** 消费方发现客户端：共享连接/地址容灾/线程，按订阅承载各自选择器与本地视图。 */
public interface DiscoveryClient extends AutoCloseable {
    /** 启动共享资源（地址/Grpc/线程）；重复调用抛 IllegalStateException。 */
    void start();

    /**
     * 建立订阅（选择器是 API 参数，可运行期增删多个订阅；每个订阅一条 discovery.v1 流）。
     * 选择器空集语义与服务端一致（services 空 = 全部、labels 空 = 不过滤、clusters 空 = union）。
     */
    Subscription subscribe(DiscoverySelector selector, DiscoveryListener listener);

    /** 幂等：关闭全部订阅 → 释放共享资源。 */
    @Override
    void close();
}

/** 单个选择器订阅：本地视图 + 订阅推送 + 兜底拉取 + 磁盘快照。 */
public interface Subscription extends AutoCloseable {
    DiscoverySelector selector();

    /** 本地视图（来源 cluster 的 union，不可变列表引用；无数据返回空列表，不返回 null）。 */
    List<ServiceInstance> getInstances(ServiceId service);

    /** 连接态 / 快照回放 / 最近一次拉取的上游新鲜度。 */
    DiscoveryStatus status();

    /** 幂等：cancel 本订阅流 → 停本订阅调度 → 落快照。 */
    @Override
    void close();
}

public interface DiscoveryListener {
    /** 仅信号：消费方按 service 读 getInstances 取最新不可变视图（零拷贝，不做深克隆）。 */
    void onServiceChanged(ServiceId service);
}

/** 选择器值对象（不可变，防御性拷贝；builder 空集 = 全部，对齐服务端 selector 语义）。 */
public final class DiscoverySelector {
    public static Builder newBuilder();
    public Set<ServiceId> services();
    public Map<String, String> labels();
    public Set<String> clusters();

    public static final class Builder {
        public Builder addService(ServiceId service);
        public Builder putLabel(String key, String value);
        public Builder addCluster(String cluster);
        // services(...) / labels(...) / clusters(...) 批量入口
        public DiscoverySelector build();
    }
}

public enum UpstreamState { UNKNOWN, SYNCING, SYNCED, DISCONNECTED }

public final class DiscoveryStatus {   // 不可变
    public boolean isConnected();           // 本订阅流已建立
    public boolean isSnapshotReplay();      // 本订阅启动后尚未成功同步，数据来自磁盘快照
    public long snapshotTimestampMs();      // 快照写入时间；0 = 无
    public Map<String, UpstreamState> upstreamStates(); // cluster → 最近一次拉取的新鲜度
}
```

工厂方法并入 `ArtemisClients`（与注册侧同构；注册侧为改名后的 `newRegistryClient`）：

```java
public static RegistryClient newRegistryClient();
public static RegistryClient newRegistryClient(StringProperties properties);
public static RegistryClient newRegistryClient(StringProperties properties, MeterRegistry meterRegistry);
public static DiscoveryClient newDiscoveryClient();
public static DiscoveryClient newDiscoveryClient(StringProperties properties);
public static DiscoveryClient newDiscoveryClient(StringProperties properties, MeterRegistry meterRegistry);
```

`InstanceStatus` 增加 `UNSPECIFIED`、`UNKNOWN` 两个常量（additive API）：发现侧按 wire 原值保留状态名（**不做 UNSPECIFIED→UP 归一**），否则本地 checksum 与服务端 canonical 规则（`statusName = UNSPECIFIED/UNKNOWN/UP/DOWN`）永久不一致。注册侧现有 UP/DOWN 用法不变。

**语义**：`start()` 前/`DiscoveryClient.close()` 后 `subscribe` 抛 `IllegalStateException`；`Subscription.close()` 后调用其 `getInstances`/`status` 抛 `IllegalStateException`（编程错误，与注册侧一致）。`getInstances` 对无实例服务返回不可变空列表。同一选择器重复 `subscribe` 得到独立订阅（各自流/缓存/快照，互不影响）。

### 3. 配置（SCF，`artemis.client.discovery.*`；凭证沿用 `artemis.client.auth.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `clientId` | `default` | **client 身份**（静态；注册/发现两侧共享）：用于指标 `client` 标签与快照目录隔离。同 JVM 多 client 必须配置不同值 |
| `discovery.addresses` | **必配** | discovery 前置寻址 **HTTP(S) 引导地址**列表（逗号分隔，与 `registry.addresses` 同形；拒绝 query/fragment）。构造期校验失败即 `IllegalArgumentException`；每次刷新重读 |
| `discovery.address.refreshIntervalMs` / `retryBaseMs` / `retryMaxMs` | 300000 / 2000 / 60000 | 节点目录稳态刷新周期 / 拉取失败指数退避起点、上限（含抖动，成功归零） |
| `discovery.address.circuitThreshold` / `circuitOpenMs` | 3 / 30000 | 同址连续失败熔断阈值 / 窗口（期内选址跳过，全熔断仍可回退全量） |
| `discovery.reconnect.baseDelayMs` / `maxDelayMs` | 1000 / 30000 | 订阅断流重连指数退避（±20% 抖动） |
| `discovery.reconcile.intervalMs` | 60000 | checksum 对账周期（吸收原「空服务 60s 主动拉」层） |
| `discovery.refresh.intervalMs` | 900000 | 全量 Query 刷新周期（15min 最终兜底） |
| `discovery.snapshot.enabled` / `dir` | true / `artemis-client-discovery-snapshots` | 磁盘快照开关 / 目录（相对路径基于工作目录）；每订阅文件 = `<dir>/<clientId>/selector-<fingerprint>.snapshot` |
| `discovery.listener.queueCapacity` | 1000 | 监听回调队列容量（满时丢最旧 + 计数，修复 legacy 局限 #13） |

所有数值项经 SCF `RangeValueConfig` 校验。**选择器不走配置**：`services`/`labels`/`clusters` 由 `DiscoverySelector` API 参数传入（运行期可增删订阅）；HTTP 引导地址列表按启动期语义使用（每次刷新重读，要求同一列表语义）。

### 3.1 服务端配置（discovery-service，静态）

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.discovery.nodes` | 空 | 静态节点目录 `<host>:<port>` 逗号分隔（host 支持 `[ipv6]`）；单节点 = 只配一条。**未配置 = 目录为空**（端点返回空列表，SDK 无地址可用——要用前置寻址必须配置）；配置了但非法（端点语法错/重复）启动失败（fail-fast） |

- **无节点 id、无 self 概念**：地址即标识；每个 discovery 节点配置同一份部署声明，`/cluster/nodes` 原样返回（registry 侧需要 id/self 是复制与探测归因所需，discovery 无节点间协议、无从归因）。
- 节点目录是**部署声明**：无节点间协议、无探测、无状态字段；客户端按所有条目为候选，失败由熔断换址兜底。
- 端点 `GET /cluster/nodes`（discovery 外部 HTTP 端口，默认 8090）返回 `DiscoveryNodesResponse` protobuf bytes；复用外部 Basic 认证（`discoveryAdminAuthFilter` 的 urlPatterns 追加 `/cluster/nodes`，凭证 `artemis.discovery.auth.*`）；**readiness 门控**（未就绪 503，同 registry `/cluster/nodes`），未就绪节点不作寻址应答者。

### 4. 本地视图模型（不可变快照 + 零拷贝读）

**每个 Subscription 一个独立缓存**（选择器不同，视图不同）；client 共享 applier/调度/连接。

```text
LocalDiscoveryCache（per Subscription）
  volatile ConcurrentHashMap<ServiceId, ServiceView> views   // 全量刷新时代整体换引用
  ServiceView (immutable)
    Map<String, List<ServiceInstance>> byCluster             // cluster → 实例集（server 视图语义已过滤）
    List<ServiceInstance> union                              // union 列表（不可变，getInstances 直接返回引用）
```

- 所有视图写操作收敛到 client 的**单线程 applier**（有界队列，任务携带 Subscription），读路径无锁、零深克隆（修复 legacy 局限 #13 的 `getService` 深克隆）。`ServiceInstance` 不可变。
- 应用变更：NEW/UPDATE = 按 `instanceId` 全量 upsert；DELETE = 按 `instanceId` 移除；UNKNOWN/UNSPECIFIED 忽略（DEBUG）。同 service 的变更按流内产生序逐条应用（gRPC 单流有序）。
- union = 各 cluster 列表顺序拼接；**并列语义**与服务端多集群 union 一致（同 instanceId 可跨 cluster 并存）。不保证跨 cluster 顺序。
- union 为空即删 `ServiceId` 条目（条目存在性 = 有实例，与服务端一致）。变更后 union 与旧值相等则**不通知**（幂等推送/基线重放不产生通知风暴）。

### 5. 同步流程（推拉结合落地，ADR-019）

**client 启动**：解析地址/配置 → 建共享 applier / listener dispatcher / scheduler / snapshot writer / gRPC 传输；不建流。

**subscribe(selector, listener)**：创建 Subscription → 读该选择器的磁盘快照（指纹匹配才采用；`isSnapshotReplay=true` 若快照有数据）→ 立即开流并调度该订阅的对账/全量刷新任务。每订阅独立视图、刷新缓冲与快照，互不影响。

**订阅（推，per Subscription）**：
1. 成功建立（首条空事件 = 确认）：`connected=true`、`markSucceeded(address)`、重置重连退避；随后触发一次 **bootstrap 全量 Query**（与选择器同参），并进入「刷新缓冲」模式。
2. 刷新缓冲：applier 置 `refreshing=true`；期间订阅事件只入有界缓冲（不应用），本地继续服务旧视图（陈旧换可用，不制造空窗）。
3. Query 成功：applier 以响应**整体替换**本地视图（`services` 里的 cluster+service 为权威；本地有而响应无 = 服务已消失），再按序**重放缓冲事件**（NEW/UPDATE/DELETE 均幂等，对快照时点前后的事件都安全），最后 `refreshing=false`；逐 service diff 通知变化。
4. 刷新失败：重放缓冲事件到旧视图（不丢实时变更），按 `reconnect.baseDelayMs` 退避重试 bootstrap Query；期间订阅流保持。
5. 断流/错误：`connected=false`、`markFailed(address)`、取消缓冲（若在刷新中走步骤 4 的失败路径）、换址重连（指数退避 + 抖动）。
6. 缓冲有界（8192）：溢出丢最旧 + 计数；成功刷新后被 Query 快照收敛，失败漂移由对账兜底。

> 为什么需要 bootstrap Query 而不是仅靠订阅基线：服务端基线只推「当前命中的 service」，**断连期间消失的服务不会出现在基线里**；全量替换才能删除陈旧条目。缓冲-替换-重放保证替换期间到达的增量不丢。

**对账（拉，60s）**：`ChecksumRequest`（同选择器）→ 更新上游新鲜度 → 按 canonical 规则（[discovery-reconciliation-api.md §3](../../../api/discovery-reconciliation-api.md)，SDK 侧独立实现、不抄服务端代码、不依赖 protobuf 字节）逐 `(cluster, service)` 比对本地 checksum 与响应表：缺项按 0。任一不一致 → 触发一次全量 Query 刷新（同一缓冲机制；不逐 service 局部拉，selector 通常规模小，简单且正确）。checksum 失败保留视图，下一周期重试。

**全量刷新（15min）**：与 bootstrap 相同的 Query 刷新路径，作为 checksum 失效/碰撞的最终兜底。

**上游状态**：Query/Checksum 响应的 `upstreams` 按 cluster 按键覆盖进 `upstreamStates`（未知枚举 → `UNKNOWN`）；订阅流不携带新鲜度（D-S7/D-F4）。非 `SYNCED` 即可能陈旧。

### 6. 地址容灾（discovery 专属形态，ADR-002）

**与 registry 完全相同的三级寻址管线**（复用实现，不是另起一套）：

```text
HTTP 引导地址（discovery.addresses）
  → GET <url>/cluster/nodes（DiscoveryNodesResponse protobuf）
  → 节点列表（gRPC 端点，全部视为 UP 候选）
  → FailoverAddressProvider 选址（历史最近成功优先 + 随机 + 熔断）
  → GrpcDiscoveryClient 建 channel（失败上报 → 熔断 → NodeListRefresher.refreshNow）
```

内部复用改造（对注册侧语义零变化）：

- `HttpNodeListFetcher` 泛化：地址 `Property` 与响应解析器可注入——注册侧用 `GetNodeStatusResponse` 解析器，发现侧用 `DiscoveryNodesResponse` 解析器（节点全部 `PRIORITY_UP`，无探测态）。HTTP 传输/超时/大小上限/认证/多 url 依次尝试逻辑共用。
- `NodeListRefresher` 泛化：配置键前缀参数化（`artemis.client.registry.address.*` / `artemis.client.discovery.address.*`），稳态刷新/失败退避/熔断触发立即刷新逻辑共用。
- `FailoverAddressProvider` 泛化：熔断键前缀参数化；新增 `preferLastSuccess` 开关（注册侧默认关闭保持原行为，发现侧开启——历史成功连接缓存优先；未熔断且在列表中才优先，否则高优先级组内随机）。
- **熔断换址**：同址连续失败达阈值 → 窗口内跳过，打开事件计指标/日志；全熔断时回退全量随机（可用性优先，降权不清零）。成功即清零并更新 lastSuccessful。
- `select(exclude)`：重连排除刚断开的地址（唯一候选时保留原址重试）。
- 熔断计数**仅由订阅流驱动**（确认/事件 = 成功，断流/开流失败 = 失败）；unary 对账/查询不参与计失败（与注册侧 GrpcRegistryClient 同一归因纪律，避免并发竞态误熔断）。
- **共享 channel 失败去重**：流级失败仅当失败地址仍是当前 channel 地址时计一次熔断并重建 channel；同一 channel 上其它流收到的连带错误直接忽略（`currentAddress` 已变/null）。**慢消费者断流（`RESOURCE_EXHAUSTED`）**只重连该订阅，不熔断、不换址——channel 健康。

`GrpcDiscoveryClient`：`ManagedChannel` 懒建（grpc-netty-shaded、keepalive 30s/10s、Basic 认证 interceptor 与注册侧同源凭证）；`resetChannel()` 换址时异步 shutdown 旧 channel。订阅用 `ClientResponseObserver` 捕获 request stream 以便 close 时 cancel；Query/Checksum 用异步 stub + 10s deadline，回调 enqueue 到 applier，不阻塞调度线程。channel/节点列表/熔断为 client 级共享，多订阅只多出各自的流。

### 7. 磁盘快照（冷启动回放 + server 全挂兜底）

- **粒度**：每 Subscription 一个文件 `<snapshot.dir>/<clientId>/selector-<fingerprint>.snapshot`；选择器指纹 = services/labels/clusters 的 canonical 串 FNV-1a-64。`clientId` 来自 `artemis.client.clientId`（静态），多 client 天然隔离目录。
- **内容**：选择器视图的 cluster 归属实例集（复用 `discovery.v1 QueryResponseData` proto bytes），格式 = magic（`ARTEMIS-DISCOVERY-SNAPSHOT`）+ 版本 + 写入时间戳 + 选择器指纹 + 长度 + payload。**不落上游状态**（回放时陈旧，避免误用）。
- **校验**：magic/版本/长度上限（64MB）/选择器指纹任一不符 → 忽略并 WARN/INFO，不影响订阅启动；**不设过期**（陈旧换可用，架构 §4.1），`status.isSnapshotReplay` 暴露数据来源。
- **写（随变更异步）**：视图每次实际变化（union 变化/全量替换差异）即置 dirty 并向 client 共享的**单线程 snapshot writer** 投递写任务；writer 合并写请求（写进行中再有变更只置脏，写完补写最新），首次变更延迟 200ms 合并突发；`Subscription.close()` 做一次同步终写。临时文件 + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`（不支持原子移动时降级 REPLACE_EXISTING）。写失败 WARN + 指标，不影响数据面。
- **读**：`subscribe()` 时同步读取本订阅快照（本地小文件），失败降级空视图。
- 同 `clientId` + 同选择器的重复订阅共享快照文件（内容语义等价，原子替换保证可读）；不同 `clientId` 或不同选择器天然分文件。

### 8. 并发、线程与生命周期

| 组件 | 用法 |
|---|---|
| applier（client 共享） | 单线程 `BoundedScheduledExecutor`（容量 8192）；所有订阅的视图写、刷新替换/重放、通知判定 |
| listener dispatcher（client 共享） | 单 daemon 线程 + `ArrayBlockingQueue(queueCapacity)`；满丢最旧 + `listener.dropped` 计数；监听异常 catch + WARN |
| scheduler（client 共享） | 单线程 `BoundedScheduledExecutor`：每订阅重连退避、对账/全量刷新自续；队列有界（订阅数 × 少量任务） |
| snapshot writer（client 共享） | 单 daemon 线程，合并写请求、只写最新视图；不阻塞 applier/调度 |
| gRPC 回调 | shaded netty 线程，仅做 enqueue（不阻塞、不碰视图） |

- 线程数不随订阅数线性增长（除每订阅一条 gRPC 流外，执行设施 client 级共享——修复 legacy 局限 #12 的线程放大问题）。全部线程 daemon + 命名（`artemis-client-discovery-*`）；禁止无界队列/`Thread.sleep` 轮询；配置动态项在每次使用时 `getValue()`。
- `Subscription.close()`：置关闭 → cancel 本订阅流 → 取消本订阅的周期任务 → applier 有界等待在途任务 → 同步终写快照 → 从 client 注销。
- `DiscoveryClient.close()`：置 CLOSED → 关全部订阅（同上）→ 停 scheduler → 停 applier（有界等待）→ 停 listener → 停 snapshot writer（等待终写）→ 关 channel → 移除 gauge；幂等。监听器 close 后不再回调。

### 9. 可观测（Micrometer 库模式）

| 指标 | 类型 | 标签 |
|---|---|---|
| `artemis.client.discovery.connected` | Gauge 0/1 | `client`、`subscription` |
| `artemis.client.discovery.services` | Gauge | `client`、`subscription` |
| `artemis.client.discovery.subscribe.reconnect` | Counter | `client`、`subscription` |
| `artemis.client.discovery.push` | Counter | `client`、`subscription` |
| `artemis.client.discovery.refresh` | Counter | `client`、`subscription`、`trigger`（bootstrap/periodic/checksum）、`outcome` |
| `artemis.client.discovery.reconcile` | Counter | `client`、`subscription`、`outcome`（success/failure/mismatch） |
| `artemis.client.discovery.snapshot` | Counter | `client`、`subscription`、`outcome`（load-hit/load-miss/load-invalid/save-success/save-failure） |
| `artemis.client.discovery.listener.dropped` | Counter | `client`、`subscription` |
| `artemis.client.address.circuit.opened` | Counter | `client`、`address`（发现侧另加 `channel=discovery`；registry 既有标签不变） |

`subscription` 标签 = 选择器指纹（FNV-1a-64，16 位 16 进制）+ 本地序号——同选择器重复订阅也可区分，
避免 Gauge id 冲突与 close 误删他人 meter；快照文件仍只按选择器指纹命名（同内容共享）。
`channel` 维度仅发现侧新增（registry 指标保持原标签集，避免既有看板/告警失配）。

日志：启停/连接/换址 INFO，断流/快照损坏/刷新失败 WARN，逐事件 DEBUG（占位符，不拼串）。SDK 仅 SLF4J API。

### 10. 语义定案汇总及其原始文档同步

| # | 定案 | 原始文档/同步落点 |
|---|---|---|
| D-C1 | 发现侧 API 并入既有 `artemis-client` artifact，不新增模块/依赖 | tech-stack §2.1/§2.2 已含 discovery.v1 生成，无改动；公开 API 于 ⑩ 落 `docs/api/sdk-java-discovery.md` |
| D-C2 | **选择器是 `subscribe(DiscoverySelector, listener)` 的 API 参数**，运行期可建多个独立订阅（各一条流/视图/快照）；SCF 只承载地址与节奏 | 选择器语义单一来源 [discovery-query-api.md](../../../api/discovery-query-api.md) §2，不复制；⑩ API 文档登记 |
| D-C3 | 订阅 + bootstrap 全量替换 + 事件缓冲重放；对账 60s / 全量 15min | 架构 §4.2「三层兜底轮询 + hash 对账」既有记载，无改动；节奏参数归 SDK，⑩ 产品/API 文档登记 |
| D-C4 | **寻址机制与 registry 相同**：HTTP 引导地址 → `GET /cluster/nodes` → 节点列表 → `FailoverAddressProvider`（最近成功优先 + 随机 + 熔断换址）；复用 `HttpNodeListFetcher`/`NodeListRefresher`/`FailoverAddressProvider` 泛化实现 | 架构 §2.1/§2.2、ADR-002 修订落点见 D-C11；实现级复用登记于 ⑩ API 文档 |
| D-C11 | **discovery 静态节点目录**：`artemis.discovery.nodes` 配置（`host:port` 列表，**无节点 id/self**——地址即标识）+ 外部 HTTP `GET /cluster/nodes`（protobuf、Basic、readiness 503）；仍是部署声明、不组集群、无探测 | 新建 `docs/decisions/adr-020-discovery-static-node-directory.md`；修订 ADR-002、架构 §2.1/§2.2、roadmap v0.3；⑩ 新建 `docs/api/discovery-nodes-api.md` |
| D-C12 | **`artemis.client.clientId` 静态配置**（默认 `default`）：注册/发现共享，作为指标 `client` 标签与快照目录 `<dir>/<clientId>/...` 的隔离键 | 公开 API/配置变更，⑩ 同步 `docs/api/sdk-java.md` 与 `docs/api/sdk-java-discovery.md` |
| D-C5 | **磁盘快照 per Subscription、随变更异步落盘**（合并写 + 终写）复用 discovery proto bytes + 选择器指纹，不过期、仅作兜底 | 架构 §4.1「消费方磁盘快照」既有记载；格式为 SDK 内部实现，⑩ API 文档登记（不新增契约） |
| D-C6 | 本地视图 = per-subscription、per-cluster 不可变 ServiceView + union 引用；零深克隆、单线程写 | guidelines §4.1 快照发布纪律；legacy 局限 #13 修复 |
| D-C7 | `InstanceStatus` additive 增 `UNSPECIFIED`/`UNKNOWN`，statusName 按 wire 原值参与 checksum | canonical 规则单一来源 [discovery-reconciliation-api.md](../../../api/discovery-reconciliation-api.md) §3；⑩ API 文档 + 兼容说明 |
| D-C8 | 监听回调有界队列、满丢最旧 + 计数 | guidelines §9 / legacy 局限 #13；⑩ API 文档登记 |
| D-C9 | 测试引入 `*IT`（进程内 fake discovery + 真实 SDK/协议），SDK 线绑定 failsafe + fork JDK 8 | guidelines §7 既有 *IT 形态；仅测试基础设施调整，⑩ 测试文档记录，dev-test.sh 无需改（all=verify 自动纳入） |
| D-C10 | **注册侧公开接口改名** `ArtemisClient` → `RegistryClient`、`newClient` → `newRegistryClient`（v0.x 不留别名）；starter 自动配置类名保持 `ArtemisClientAutoConfiguration`（2026-10-24 用户确认，bean 返回类型已为 `RegistryClient`） | 公开 API 变更，⑩ 同步 `docs/api/sdk-java.md`、产品文档中的类型名 |

### 11. 不做的事

- 不让 discovery 组集群/做节点间协议/存活探测：节点目录是静态部署声明，客户端失败由熔断换址兜底。
- 不加发现侧 starter 自动装配（phase-9）。
- 不支持修改**已建订阅**的选择器（需要变更 = close 后重新 `subscribe`）；不做客户端负载均衡、TLS。
- 不为每个 service 单独开订阅流（一个选择器可承载一批 service，空 = 全部；服务端一流多目标既有能力）。

## 实现结果（⑦ 补）

**交付**：`artemis-client` 新增发现侧公开 API（`DiscoveryClient`/`Subscription`/`DiscoverySelector`/`DiscoveryListener`/`DiscoveryStatus`/`UpstreamState`）与 internal 同步引擎（订阅推送 + bootstrap 全量替换 + 事件缓冲重放 + 60s checksum 对账 + 15min 全量刷新 + 异步磁盘快照 + 地址容灾）；`ArtemisClient` → `RegistryClient` 更名；discovery-server 新增静态节点目录（`artemis.discovery.nodes` + `GET /cluster/nodes`）；discovery.v1 增节点目录消息；ADR-020/ADR-002/架构/roadmap/plan 同步。

**⑥ code-review（max）15 条 findings，⑦ 全部修复**：

| # | finding | 修复 |
|---|---|---|
| 1 | 任一订阅流失败都重置共享 channel 并对同一地址重复 markFailed | `GrpcDiscoveryClient.onStreamFailure` 按 channel 代次去重（仅首个失败计熔断/重建）；慢消费者 `RESOURCE_EXHAUSTED` 只重连该订阅，不熔断/不换址 |
| 2 | finishRefresh 映射异常逃逸导致 `refreshing` 永久 true | 响应映射/应用包 try/catch，异常走 `failRefreshInternal` 收敛（重放缓冲、复位状态、重试） |
| 3 | 刷新完成/失败任务入队失败被静默丢弃 | `enqueueCompletion` + scheduler 退避重试（≤20 次），不丢完成信号 |
| 4 | Subscription.close 不取消已排期任务，反复建删可耗尽 scheduler | 保存 reconcile/refresh/reconnect/retry future 并在 close 取消；构造期入队失败清理 Gauge 后抛错 |
| 5 | 同选择器重复订阅 Gauge id 冲突/close 误删 | `subscription` 标签 = 指纹 + 本地序号（快照文件仍按指纹共享） |
| 6 | replaceAll 只看 union，cluster 归属变化不写回 | 同时比较 `union` 与 `byCluster`；新增归属迁移单测 |
| 7 | selector 指纹 32 位、集合序敏感、key/value 可歧义 | 改为 FNV-1a-64 + 排序 + 长度前缀 canonical 编码；新增序无关/无歧义单测 |
| 8 | clientId 放行 `.`/`..`（路径穿越） | 显式拒绝 `.`/`..`；快照目录保持 `<dir>/<clientId>/` |
| 9 | status() close 后不抛异常 | `status()` 补 `requireOpen()` |
| 10 | 节点目录未校验端口范围/IPv6 括号 | `UpstreamConfig.endpoint` 统一校验端口 1..65535、host 非空、IPv6 必须括号成对 |
| 11 | AuthInterceptor 声称共用但注册侧仍保留重复实现 | `GrpcRegistryClient` 改用共用 `AuthInterceptor`；半配置 WARN 收敛到 interceptor |
| 12 | close 终写快照早于在途 applier 任务 | close 经 applier 屏障（有界 2s）等待在途任务后再终写 |
| 13 | close 与 connect 竞态新流未取消 | 开流后补 `closed` 检查并 cancel |
| 14 | 未知变更类型也构造完整实例（前向兼容可能抛错） | `InstanceChange.unknown` 不构造实例；新增 ProtoMapper 单测 |
| 15 | 设计文档指标名/指纹位宽与实现不符 | 指标改为共享 `artemis.client.address.circuit.opened`（发现侧 `channel=discovery`）、指纹 64 位说明同步 |

另修复既有 `NodeListRefresherTest` 首轮寻址未完成时 `select` 抛 `NoAddressException` 的 flaky 断言（测试稳健性，非产品行为变更）。

**验证**：`java/scripts/dev-test.sh all` BUILD SUCCESS（client 63 单测 + 7 IT、server 9 单测 + 2 节点目录 IT、starter 9 单测；spotless/checkstyle/enforcer 全绿）。

## 文档同步（⑩ 填）

- **产品文档**：`docs/product/artemis.md` 新增 v0.3 phase-8 交付节（发现侧 SDK、同步/快照/容灾、静态节点目录、`RegistryClient` 更名与 `clientId`、工程形态），更新头部版本/交付状态/更新历史（1.23）。
- **API 文档**：
  - 新建 `docs/api/sdk-java-discovery.md`：发现侧 SDK 公开 API、配置、同步语义、磁盘快照、地址容灾、指标与已知限制。
  - 新建 `docs/api/discovery-nodes-api.md`：`GET /cluster/nodes` 端点、`DiscoveryNodesResponse` 契约、服务端配置、认证/readiness 语义。
  - 更新 `docs/api/sdk-java.md`：`ArtemisClient` → `RegistryClient`、`newClient` → `newRegistryClient`、新增 `artemis.client.clientId`、`InstanceStatus` 新增常量、移除「发现侧随 v0.3 交付」旧限制并链接新文档（1.4）。
  - 更新 `docs/api/README.md`：登记两份新文档，注册侧 SDK 描述改为「注册侧」。
- **架构/决策/路线图/plan（设计阶段已同步，本步核对）**：`ADR-020`（新建）、`ADR-002`（v1.1）、`docs/decisions/README.md`、架构 §2.1/§2.2/§7（1.39）、roadmap（1.13）、`docs/milestones/v0.3/plan.md`（1.5）。
- **索引核对**：`docs/api/README.md`、`docs/decisions/README.md`、`docs/milestones/v0.3/phases/README.md` 均已登记；`docs/milestones/dev-state.md` 由工作流维护。
- **无需变更**：`docs/dev/tech-stack-java.md`（无新增组件/模块，client 生成矩阵已含 discovery.v1）、`docs/dev/coding-guidelines-java.md`（*IT/failsafe 形态既有）、`CLAUDE.md`（进度以 dev-state 为准，构建命令未变）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.7 | 2026-10-24 | ⑩ 文档同步：产品文档 phase-8 交付节；新建发现侧 SDK 与节点目录 API 文档；注册侧 SDK 文档更名/clientId 同步；索引核对 |
| 1.6 | 2026-10-24 | ⑦ 实现结果补记：交付清单 + ⑥ 15 条 findings 修复对照 + 全量验证结果 |
| 1.5 | 2026-10-24 | ⑥ code-review 修复：共享 channel 失败去重/慢消费者不熔断、刷新完成入队重试与映射异常收敛、订阅任务取消/关闭屏障、Gauge 唯一标签、selector 指纹 64 位无歧义、replaceAll 归属比对、clientId 防穿越、状态/端口校验与指标文档同步 |
| 1.4 | 2026-10-24 | review 三次修订：节点目录去 id/self（`host:port` 地址即标识），消息与配置简化；其余确认定案（discovery.v1 新消息、clientId 默认 default） |
| 1.3 | 2026-10-24 | review 二次修订：discovery 新增静态节点目录（server 配置 + `GET /cluster/nodes` + discovery.v1 消息，ADR-020）；客户端走与 registry 完全相同的 HTTP→节点列表→熔断管线；新增 `artemis.client.clientId` 并用于快照目录隔离。定案 D-C1–D-C12 |
| 1.2 | 2026-10-24 | review 修订：注册侧接口更名 `RegistryClient`；选择器改为 `subscribe` API 参数（多订阅）；寻址与 `ArtemisClient` 同构；快照 per subscription 随变更异步落盘 |
| 1.1 | 2026-10-24 | ③ 设计定稿：公开 API/配置/视图模型/同步流程/地址容灾/磁盘快照/并发与可观测；定案 D-C1–D-C9 |
| 1.0 | 2026-10-24 | 开工骨架 |
