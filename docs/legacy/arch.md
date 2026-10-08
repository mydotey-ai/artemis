# Artemis 原产品架构设计（Legacy Architecture）

状态: 草案  日期: 2026-10-07

> 调查对象：`~/Projects/mydotey/artemis`（version 2.0.2）。本文将 `docs/legacy/legacy-product-analysis.md`（下称「基线」）的功能性描述重组为架构师视角的设计文档，并补充基线未展开的结构细节（分层、组件关系、线程模型、数据流），后者均经源码核实。已确立的基线结论以 `docs/legacy/legacy-product-analysis.md` §x.y 直接引用，不重复取证；原产品代码引用为相对原仓库根路径并注明「原仓库」。

---

## 1. 架构总览

**一句话定位**：AP 型服务注册中心——对等节点全对全异步复制 + 租约心跳（「心跳即注册」）+ Eureka 式自我保护 + 静态集群成员；数据面（注册表，纯内存）与管理面（DB 持久化流量治理元数据）双轨分离（基线 §1）。

```text
┌─────────────────────────────────────────────────────────────────────────┐
│  客户端 SDK（artemis-client，纯 Java，宿主进程内）                          │
│  ArtemisClientManager ─┬─ RegistryClientImpl（注册）                      │
│                        │    ├─ InstanceRepository（本地实例集=事实源）     │
│                        │    └─ InstanceRegistry（WS 心跳 5s + 检查线程）   │
│                        └─ DiscoveryClientImpl（发现）                     │
│                             ├─ ServiceRepository（内存缓存，永不失效）     │
│                             └─ ServiceDiscovery（三层兜底轮询 60s）        │
│  公共设施：AddressManager（三级地址容灾）/ ArtemisHttpClient（重试+熔断）    │
│           / WebSocketSessionContext（WS 生命周期）                        │
└──────────────┬──────────────────────────────┬───────────────────────────┘
        HTTP /api/*（REST, JSON）       WS /websocket/*（心跳 + 推送）
┌──────────────▼──────────────────────▼───────────────────────────────────┐
│  接入层（artemis-server，Spring Boot 内嵌 Tomcat）                         │
│  REST：RegistryController / DiscoveryController / ClusterController /    │
│        StatusController / RegistryReplicationController /                │
│        Management{,Group,Log,Zone}Controller / CanaryController /        │
│        WsStatusController（共 11 个，其在 websocket 包）                   │
│  WS  ：HeartbeatWsHandler / ServiceChangeWsHandler /                     │
│        AllServicesChangeWsHandler（3 个端点，WsIPBlackList 拦截）          │
└──────────────┬───────────────────────────────────────────────────────────┘
┌──────────────▼───────────────────────────────────────────────────────────┐
│  服务内核（artemis-service + artemis-common 的 lease/taskdispatcher）      │
│  registry：RegistryRepository（纯内存注册表）+ RegistryServiceImpl        │
│            └─ replication/：RegistryReplicationManager（全对全异步复制）   │
│  discovery：DiscoveryServiceImpl + NotificationCenter（10 worker 推送）    │
│            + cache/VersionedCacheManager（版本化全量缓存 30s/3 份）        │
│  cluster ：ClusterManager（成员表+5s 探测）/ NodeManager（启动门控）        │
│  lease   ：LeaseManager ×2（双租约池，TTL 见 §4.1）+ LeaseUpdateSafeChecker  │
├───────────────────────────────────────────────────────────────────────────┤
│  管理面（artemis-management，唯一用 DB 的模块）                             │
│  ManagementRepository / GroupRepository / ZoneRepository（5s/1s，见 §3）    │
│  → DiscoveryFilters（GroupDiscoveryFilter / ManagementDiscoveryFilter）   │
│  → NotificationCenter.registerFilter(ManagementNotificationFilter)        │
│                          │                                                │
│                    MySQL / SQLite（20 张表：10 业务 + 10 审计日志）         │
└───────────────────────────────────────────────────────────────────────────┘
        ▲ 复制通道：peer 间 HTTP /api/replication/registry/*.json
        │  3 种消息（RegisterTask/UnregisterTask/HeartbeatTask）
        │  taskdispatcher：批量通道 250 条/2s + 单条通道，TTL 5s 过期丢弃
        └── 对等全对全（无 leader，region 内全量复制）
```

分层要点（均有源码依据）：

- **接入层薄、内核重**：Controller/Handler 只做协议转换，一行调服务内核单例。例如 `HeartbeatWsHandler.handleTextMessage` 反序列化后直调 `RegistryServiceImpl.heartbeat`（`artemis-server/src/main/java/org/mydotey/artemis/server/websocket/HeartbeatWsHandler.java`，原仓库）。
- **服务内核与配置零 Spring**：artemis-common 是无 Spring 基础库（基线 §1 模块图）；内核组件全部是显式单例（`getInstance()` + 双检锁），不走 IoC 容器。Spring 只存在于接入层与装配层（`App` 的 `@ComponentScan("org.mydotey.artemis.server")`，`artemis-package/src/main/java/org/mydotey/artemis/server/App.java`，原仓库）。
- **管理面通过两个注入点作用于数据面**：① 发现过滤器链 `DiscoveryFilters`（改写发现结果）；② `NotificationCenter` 的 `ManagementNotificationFilter`（管理变更转推送）。两处均在 `ManagementInitializer.init()` 注册（`artemis-management/src/main/java/org/mydotey/artemis/management/ManagementInitializer.java`，原仓库）。
- **复制是内核的内嵌通道**，不是独立组件：业务线程写本地成功后同线程入队复制任务（§4.2）。

## 2. 模块视图

Maven 依赖链（自各模块 pom.xml 核实，箭头 = depends on）：

```text
artemis-common ◄── artemis-service ◄── artemis-management ◄── artemis-server ◄── artemis-package
      ▲                                                              ▲
      └──────────────────── artemis-client（运行时仅依赖 common）       └── artemis-test（+client）
```

| 模块 | 职责（一句话） | Spring 依赖（pom 核实） | 关键包 |
|---|---|---|---|
| artemis-common | 零 Spring 基础库：数据模型、租约、任务分发、配置 | 无 | `Instance*`/`Service*`/`RouteRule` 等模型、`lease/`（Lease、LeaseManager、LeaseUpdateSafeChecker）、`taskdispatcher/`（TaskAcceptor、TaskExecutor、TrafficShaper）、`config/`（ArtemisConfig、RestPaths、WebSocketPaths）、`ErrorCodes` |
| artemis-service | 注册/发现/复制/集群/限流内核（全部显式单例） | 无 | `registry/`（RegistryRepository、RegistryServiceImpl、RegistryTool）、`registry/replication/`、`replication/`（ReplicationManager 抽象）、`discovery/`（DiscoveryServiceImpl、notify/NotificationCenter）、`discovery/DiscoveryFilters`（SPI 容器）、`cluster/`（ClusterManager、NodeManager、RegistryReplicationInitializer）、`cache/`（VersionedCacheManager）、`ratelimiter/` |
| artemis-management | 管理面服务层 + DAO（唯一用 DB 的模块）：分组/路由/摘除/审计 | spring-jdbc/context（JdbcTemplate + 事务，无容器装配） | `ManagementRepository`、`GroupRepository`、`ZoneRepository`、`canary/CanaryServiceImpl`、`group/dao/`（BusinessDao 等）、`ManagementInitializer`、`GroupDiscoveryFilter`/`ManagementDiscoveryFilter` |
| artemis-server | 接入层：11 个 Controller（10 个 `rest/controller/` + `websocket/WsStatusController`）+ 3 个 WS Handler + Jackson 装配 | Boot Web/WebSocket/Messaging | `rest/controller/*`、`websocket/*`、`ArtemisServer`（启动编排：先 ManagementInitializer 后 ClusterManager） |
| artemis-package | Spring Boot 启动入口（fat jar main + WAR configure 双形态）+ 配置三件套 + 部署物 | Boot 启动（starter-tomcat + springfox） | `App`、`src/main/resources/{application,artemis,data-source}.properties`、`deployment/artemis-management.sql` |
| artemis-client | 纯 Java SDK，无自动装配，配置源/指标由宿主注入（基线 §2.8） | 仅 spring-websocket/messaging（WS 客户端） | `ArtemisClientManager`、`common/`（AddressRepository、AddressManager、ArtemisHttpClient）、`registry/`（InstanceRegistry、InstanceRepository）、`discovery/`（ServiceDiscovery、ServiceRepository）、`websocket/WebSocketSessionContext` |
| artemis-test | 进程内集成测试基础设施，仅覆盖 management DAO 层（基线 §3.7） | spring-boot-starter-test | `ArtemisTest.java` |

补充两点（pom 核实）：

- artemis-client 对 artemis-package 的依赖是 `test` scope——运行时 SDK 只拖 artemis-common，客户端极轻。
- 依赖严格单向无环；「registry/discovery/management separation」在 Maven 模块层成立，但部署仍是单一 Spring Boot 应用，management 仅运行时开关（基线 §4）。

## 3. 数据面与管理面双轨

| 维度 | 数据面（注册表） | 管理面（流量治理元数据） |
|---|---|---|
| 存储 | 纯内存 `ConcurrentHashMap`，零持久化 | MySQL/SQLite，20 张表（10 业务 + 10 审计） |
| 事实源 | 客户端本地实例集（心跳全量对账，基线 §2.2） | 共享 DB（各节点直读） |
| 写路径 | WS/HTTP 心跳 → `RegistryRepository.register/heartbeat` | Controller → `GroupServiceImpl`/`ManagementServiceImpl` → DAO → DB |
| 读路径 | `getService(s)` 直读内存 + 过滤器链 | DAO 全量读 → 内存缓存 → 5s 定时重刷 |
| 节点间同步 | 复制通道（§4.2） | 无节点间同步——各节点独立 5s 轮询同一 DB |
| 生效语义 | 覆盖写 + 最终一致 | 写后 `waitForPeerSync()` 即 `Thread.sleep(2s)`（`artemis.management.db-sync.wait-time` 默认 2000ms，`artemis-management/src/main/java/org/mydotey/artemis/management/GroupRepository.java`，原仓库），写成功 ≠ 全网生效（基线 §3.3） |

**管理面缓存的刷新与联动**（源码核实）：`GroupRepository`/`ManagementRepository`/`ZoneRepository` 各持一条 caravan `DynamicScheduledThread`（`artemis.management.group.data.cache-refresher` 等；Group/Zone 代码默认 5s、Management 代码默认 1s——原仓库 `GroupRepository.java` L110-114、`ManagementRepository.java` L121-124，构造参数顺序经上游 mydotey/caravan-util 源码核实），每轮 `refreshCache()` 全量重拉 DB → 与旧缓存 diff → 变化部分向 `RegistryRepository._instanceChangeSet` 注入 `InstanceChange`（分组配置变化注入 reload 伪实例触发订阅方全量重拉，`GroupRepository` L393、`ManagementRepository` L345-370，原仓库）——管理面因此复用了数据面的推送管线。`ManagementInitializer.initialized()` 以 `isLastRefreshSuccess()` 作为 DISCOVERY 启动门控条件（§4.7）。

## 4. 核心机制设计

### 4.1 「心跳即注册」与租约模型

- **机制**：注册不走显式写路径。客户端本地 `AtomicReference<Set<Instance>>` 是唯一事实源，每 5s 将全量实例作为 `HeartbeatRequest` 经 WS 发给 server；server 对每条实例先 `heartbeat()`（续约），失败则就地 `register()` 补注册（`artemis-service/src/main/java/org/mydotey/artemis/registry/replication/RegistryReplicationServiceImpl.java` L90-93 为复制入口的同款语义；客户端入口 `RegistryServiceImpl.heartbeat` 则返回 failedInstances 交客户端 HTTP 补注册，`artemis-service/src/main/java/org/mydotey/artemis/registry/RegistryServiceImpl.java`，原仓库）。客户端侧 `InstanceRegistry.checkHeartbeat` 每 1s 检查：距上次心跳 ≥ interval 即发送，≥ instance-ttl 即 `markdown()` 重建连接（`artemis-client/src/main/java/org/mydotey/artemis/client/registry/InstanceRegistry.java` L158-168，原仓库）。
- **关键类**：`RegistryRepository`（注册表）、`LeaseManager`/`Lease`（租约）、客户端 `InstanceRepository`/`InstanceRegistry`。
- **结构细节**：注册表三层结构（`_services`/`_leases`/`_instanceChangeSet` 跳表、容量 10k 挤老）见基线 §2.2，不重复；此处补基线未展开的两点：推送 worker 通过 `pollInstanceChange()` 阻塞式消费跳表（空则 sleep 20ms 自旋）；`register()` 无条件 `_services.put` 覆盖（新 Service 壳），实例级数据在 `_leases`。
- **双租约池**：`RegistryRepository` 持两个 `LeaseManager`——普通实例（`artemis.service.registry.instance`，TTL 20s）与 legacy 实例（`metadata.java_registry` 非空，`...legacy-instance`，TTL 90s；**两池代码默认同为 20s，90s 为发布配置值**，基线 §2.2）。`LeaseManager` 内部各有独立 `_leaseCache: ConcurrentHashMap<T, Lease<T>>`，即 `LeaseManager._leaseCache` 与 `_leases` 是**同一租约的两份索引**（clean 事件经 `LeaseCleanEventListener` 回调同步摘除 `_leases` 一侧）。
- **设计取舍**：全量幂等对账换掉显式注册状态机——断网/重启/server 恢复后自动收敛，代价是心跳消息体积随实例数线性增长（单客户端多实例场景一条 WS 文本消息携带全部实例）。

### 4.2 对等全对全异步复制

```text
业务线程（Tomcat WS/HTTP worker）
  │ RegistryServiceImpl.register/heartbeat/unregister
  ├─① 本地写 RegistryRepository（同步，成功才继续）
  └─② RegistryReplicationManager.replicate(task)   ──入队即返回，写延迟与 peer 解耦
        │  batchingEnabled? ──是──► batching 通道（HeartbeatTask 默认）
        │                    └─否──► non-batching 通道（Register/UnregisterTask 默认）
        ▼
   TaskAcceptor（每通道 1 线程：5ms 写等待 → drainAccept 去重合批 → 入 workQueue）
        │  taskId = taskClass+InstanceKey+serviceUrl，HashMap 去重（重复心跳合并、保留原 submitTime）
        │  批通道：满 250 条或最老任务等满 2s 成批；缓冲 ≥10k 丢最老整批
        ▼
   TaskExecutor（每通道默认 20 线程）→ RegistryBatchingTaskProcessor / SingleItemTaskProcessor
        │  出队先滤过期（任务 TTL 5s，过期即丢——一致性靠下一轮心跳收敛）
        │  TrafficShaper：按错误码记录失败时间，fail-delay（默认 10ms，可配 per-code）内 worker sleep 退避
        ▼
   RegistryReplicationTool.replicate：遍历 ClusterManager.otherNodes()，
   跳过 canServiceRegistry=false 的节点 → HTTP POST /api/replication/registry/{register|unregister|heartbeat}.json
        │  失败（网络异常/UNKNOWN/RATE_LIMITED）→ 生成失败任务 reaccept：
        │  插队 processingOrder 队首（submitTime 重置为比最老还早），非可重试错误码直接丢弃
        ▼
   peer 端 RegistryReplicationServiceImpl（isReplication=true：跳过 zone 准入与 readiness 门控，
   心跳遇缺失实例自动补注册）→ 各自写本地注册表
```

- **关键类**：`ReplicationManager`/`RegistryReplicationManager`（双通道装配）、`artemis-common/.../taskdispatcher/`（TaskAcceptor、BatchingTaskAcceptor、TaskExecutor、TrafficShaper）、`RegistryReplicationTool`（扇出与失败任务生成）、`RegistryReplicationServiceImpl`（peer 端处理）。
- **基线未展开的结构细节**（源码核实）：
  - 每个 `TaskDispatcher` = 1 acceptor 线程 + 默认 20 个 executor 线程（`<id>.task-executor.thread-count` 默认 20，范围 1-100；`artemis-common/src/main/java/org/mydotey/artemis/taskdispatcher/TaskExecutor.java`，原仓库）——复制子系统默认 ~42 线程（双通道）。
  - 重试插队的实现是 `processingOrder.addFirst` + submitTime 重置为「最老任务 submitTime - 1」，保证失败任务下轮最先成批。
  - `reaccept` 时若同 taskId 任务已在缓冲中则丢弃（reaccept-drop），防止重复放大。
  - 扇出对象是 `otherNodes()`（本 zone 其他节点 + 其他 zone 全部节点）——zone 不是数据局部性单位（基线 §2.7）。
- **设计取舍**：3 种消息、无 leader、无确认闭环、TTL 过期即丢——把「至少一次」降级为「尽力而为 + 心跳自愈」，换来写路径完全异步与无协调点。代价是写放大 N-1 倍（基线 §6.4）。
- 附：样例配置 `artemis.properties` 中 `...replicaton...`（拼写错误）与 `lease-manager.thread-pool-size` 键在代码中无读取方（grep 零命中），属死配置（基线 §6.23 已录拼写问题）。

### 4.3 集群成员与节点状态

- **机制**：静态配置成员 + 周期探测。`artemis.service.cluster.nodes` 配置（multimap：`zone1:http://ip:port`，SCF 热更 + `ClusterChangeListener`）经 `ServiceCluster` 解析；`ClusterManager.init()` 构建 localZone/otherZone/allNodes 视图（volatile List 整体换新）。
- **探测**：单线程 `ScheduledExecutorService`（线程名 `serivce-cluster`，拼写即源码如此）每 5s `scheduleWithFixedDelay`，**串行**逐个调 peer `/api/status/node.json`（3 次重试、host 不可达即断），整体重建 `_nodeStatusMap`（普通 `HashMap` 整体换新、非 volatile——读线程可能短暂读到旧表，属良性竞态设计）（`artemis-service/src/main/java/org/mydotey/artemis/cluster/ClusterManager.java`，原仓库）。
- **本节点识别**：URL 含本机 `ip[:port]` 子串匹配（`isLocalNode`，脆弱，基线 §6.10）。
- **人工干预**：`NodeManager` 持 6 个 force-up/down 配置键（全局/registry/discovery 三级 × up/down，down 键按本机 IP 后缀），SCF 变更即时生效。
- **设计取舍**：零成员协议、零共识——扩缩容 = 改配置；节点多时串行探测 5s 周期可能跑不完（基线 §3.1）。

### 4.4 自我保护（Eureka 式）

- **机制**：每个 `LeaseManager` 内嵌一个 `LeaseUpdateSafeChecker`：caravan `DynamicScheduledThread`（默认 1s）检查滑动窗口（默认 10s，1s 桶）内 `Lease.renew()` 打点数；窗口计数低于历史 maxCount 的 85%（maxCount ≥ 50 才启用）置 `_isSafe=false`。`LeaseManager.clean()` 在摘除「仅过期未显式 evict」的租约前检查 `isSafe()`，不安全则整轮跳过（`artemis-common/src/main/java/org/mydotey/artemis/lease/LeaseManager.java` L126-164、`LeaseUpdateSafeChecker.java`，原仓库）。显式 unregister 走 `lease.evict()`，不受保护。
- **细节**：maxCount 随窗口计数抬升即时更新；超过 10min（`max-count-reset-interval`）未刷新则回落到当前窗口值——防止历史高峰永久抬高阈值。每个租约池各有一套独立统计（双租约池 = 两套窗口）。
- **设计取舍**：语义上保护「大面积心跳丢失不批量摘实例」；粒度是全局单阈值，无法区分局部异常与网络故障（基线 §6.9）。

### 4.5 推送通知

- **机制**：变更统一进 `RegistryRepository._instanceChangeSet` 跳表（register→NEW、清理→DELETE、管理配置变化→RELOAD 伪实例 `0.0.0.0/reload`）；`NotificationCenter` 启动 10 个 daemon worker，各自 `pollInstanceChange()` 取走一条变更 → 经 `NotificationFilter` 链过滤（DELETE/RELOAD 恒通过）→ **同步遍历所有 subscriber** 逐个推送（`artemis-service/src/main/java/org/mydotey/artemis/discovery/notify/NotificationCenter.java`，原仓库）。
- **关键类**：`NotificationCenter`（单例）、`InstanceChangeSubscriber`（订阅者接口）、`ServiceChangeWsHandler`/`AllServicesChangeWsHandler`（既是 WS 端点又是订阅者，在 `WebSocketEndpointConfig` 注册）、`ManagementNotificationFilter`。
- **基线未展开的结构细节**（源码核实）：
  - 推送是 **worker 线程内同步 `session.sendMessage`（per-session synchronized）**——一个慢 WS 客户端会占住一个 notification worker，10 个慢客户端即耗尽推送并行度（`ServiceChangeWsHandler.accept`，原仓库）。无 per-subscriber 发送队列。
  - 订阅模型：客户端 WS 发 `DiscoveryConfig`（单 serviceId）→ handler 记入 `serviceChangeSessions: Map<serviceId, Set<sessionId>>`；推送按 serviceId 找 session 集合。另有 all-instance-change 全服务广播通道。
  - 服务端 WS 会话治理：`ArtemisWsHandler` 基类为**每个 handler** 配一条 `DynamicScheduledThread`（默认 60s）扫 `DelayQueue`，到 TTL（默认 6min，范围 5min-5h）强制关闭会话——服务端也有防漂移的会话轮换，与客户端 5min 强制重建对称（`artemis-server/src/main/java/org/mydotey/artemis/server/websocket/ArtemisWsHandler.java`，原仓库）。
- **设计取舍**：跳表 + 多 worker 消费实现变更的天然合批与并行，亚秒级推送；代价是同步发送的队头阻塞风险与 WS 文本 8KB/32KB 缓冲上限（基线 §6.1）。

### 4.6 版本化缓存与增量

- **机制**：`VersionedCacheManager`（单线程 `ScheduledExecutorService`，初始延迟 60s、周期 30s）后台生成全量快照，version=毫秒时间戳，`ConcurrentSkipListMap<version, VersionedCache>` 保留最近 3 份；每轮为新版本**预计算**所有旧版本的 delta（`artemis-service/src/main/java/org/mydotey/artemis/cache/VersionedCacheManager.java`，原仓库）。`services.json` 读当前版本缓存（60s 首刷前降级为请求路径现算）；`services-delta.json` 按 version 取预计算差集，version 过旧返回 DATA_NOT_FOUND 逼全量。
- **设计取舍**：把 O(全量) 组装挪出请求路径（基线 §3.1）；但 delta 窗口仅 3 版 × 30s ≈ 90s、version 无单调性、且客户端实际未使用该接口（基线 §6.3/§6.7）——一套设计完整却未被产品自己消费的机制。

### 4.7 启动门控（readiness 协议）

- **机制**：`NodeManager.init()` 置状态 STARTING 后起 daemon 线程每 1s 循环执行 `NodeInitializer` 列表，直到 UP 退出（`artemis-service/src/main/java/org/mydotey/artemis/cluster/NodeManager.java`，原仓库）。两个目标：
  - **REGISTRY**：`RegistryReplicationInitializer` —— 依次尝试 localZoneOtherNodes → otherZoneNodes 中任一 UP peer，拉 `/api/replication/registry/services.json` 全量，逐服务 `register` 重建租约；注意空列表（peer 有 0 实例）视为失败继续等，即**拒绝以空数据宣告就绪**（`replicationInstanceCount > 0` 判断）。
  - **DISCOVERY**：`ManagementInitializer`（管理面启用时）—— `GroupRepository`/`ManagementRepository` 最近一轮 DB 缓存刷新成功。
  - 两目标均过才 `setStatus(UP)`；`canServiceRegistry/canServiceDiscovery` 分别门控注册/发现 API（`RegistryTool.checkRegistryStatus`、`DiscoveryServiceImpl.checkDiscoveryStatus`），复制入口（isReplication）不受门控——peer 恢复期仍可接收复制写。
- **旁路**：force-up 配置可无视门控直接置位（样例配置 `force-up=true` 即单机演示模式的开关）。
- **设计取舍**：数据不全的节点不接流量（基线 §5.9）；代价是冷启动强依赖至少一个存活 peer（基线 §3.2）。

### 4.8 发现过滤器链 SPI

- **机制**：`DiscoveryFilters.INSTANCE` 是过滤器容器（registerFilter 注册，发现时逐个 apply）；接口 `DiscoveryFilter` 定义于 artemis-common，实现在 artemis-management——管理面经此把 DB 元数据注入发现结果而不污染数据面（`GroupDiscoveryFilter` 展开路由规则注入 `logicInstances`/`routeRules`，`ManagementDiscoveryFilter` 执行四级摘除判定）（`artemis-management/src/main/java/org/mydotey/artemis/management/ManagementInitializer.java` L40-45，原仓库）。
- **设计取舍**：扩展点干净（基线 §5.7），分组路由、管理摘除、通知过滤（`NotificationFilter`）同构；过滤器在请求线程内同步执行，DB 元数据已在内存缓存中，无额外 IO。

## 5. 关键数据流

### 5.1 注册（客户端视角）

1. 宿主调 `RegistryClient.register(instances)` → `InstanceRepository.register`：先 HTTP `unregister` 清 server 旧租约 → 实例并入本地 `AtomicReference<Set<Instance>>`（基线 §2.2）。
2. `InstanceRegistry` 心跳检查线程（1s 周期）发现距上次心跳 ≥ 5s → WS 发全量 `HeartbeatRequest`（一条消息含全部本地实例）。
3. server `HeartbeatWsHandler`（Tomcat WS 线程）→ `RegistryServiceImpl.heartbeat` → 限流检查（100k QPS）→ `RegistryTool.execute`（readiness + zone 准入）→ 逐实例 `_repository.heartbeat()` 续约；不存在的实例返回 failedInstances(data-not-found)。
4. 同一业务线程内 `_repository` 写成功后 `RegistryReplicationManager.replicate(new HeartbeatTask(instance))` 入批通道即返回。
5. 客户端收到 `HeartbeatResponse.failedInstances` → 对 data-not-found/UNKNOWN 的实例 HTTP `/api/registry/register.json` 补注册（`InstanceRegistry.registerToServicesRegistry`，原仓库）。
6. server 注册路径：`register()` → 双索引写入 + `addInstanceChange(NEW)` → NotificationCenter worker 推送给该 serviceId 的订阅会话。

### 5.2 心跳（含复制扇出）

1. 客户端 WS 每 5s 全量上报（同 5.1 步骤 2-3）。
2. server 续约成功：`Lease.renew()`（tryLock → 未过期则刷新 renewalTime + safe-checker 打点）。
3. 复制通道（异步，与 2 无时序依赖）：acceptor 线程 5ms 收集 → 同 taskId 心跳合并去重 → 满 250 条或 2s 成批 → executor 线程逐 peer HTTP 扇出（跳过不可写节点）。
4. peer 端 `RegistryReplicationServiceImpl.heartbeat`（isReplication 免门控）：续约失败就地补注册——复制通道对「实例尚在途中」的注册/心跳乱序天然容错。
5. 失败的 peer 调用生成失败任务 reaccept 插队；超过 TTL 5s 的任务出队即丢，等客户端下一轮心跳收敛。

### 5.3 发现（首次 + 推送 + 兜底）

1. **首次**：消费方 `getService(serviceId)` → 本地缓存 miss → HTTP `/api/discovery/lookup.json`（可批量多服务）→ server `DiscoveryServiceImpl` 直读内存 → `DiscoveryFilters` 链（分组路由注入 + 摘除过滤）→ 返回完整 Service → 客户端写 `ServiceContext` 缓存。
2. **订阅**：客户端 WS 向 `/websocket/discovery/instance-change` 发 `DiscoveryConfig`；此后变更经 NotificationCenter 亚秒级推送，new/delete/change 原地更新缓存；reload 伪实例触发全量重拉（基线 §2.4）。
3. **兜底**：`ServiceDiscovery.poller`（60s）三层扫描——reload 失败的服务 / 实例列表为空的服务 / 距上次全量超 TTL（15min）的服务——批量 lookup 纠偏。
4. server 全挂时缓存永不失效，`getService` 返回最后一份快照（基线 §2.4）。

### 5.4 管理配置生效（以灰度权重调整为例）

1. 运维调 `/api/management/group/...`（写 `unreleased_weight`）→ `GroupServiceImpl` → DAO 写 DB + 审计 log 表。
2. 调 `release-route-rule-groups.json` → `unreleased_weight` 拷贝到 `weight`（两段式，基线 §2.5）→ `groupRepository.waitForPeerSync()`（sleep 2s）→ API 返回。
3. 全集群各节点 `GroupRepository.cacheRefresher`（5s 周期）下一轮 `refreshCache()` 重拉 DB → diff 出变化的 serviceId → 向 `_instanceChangeSet` 注入 reload 伪实例。
4. NotificationCenter worker 推送 reload → 订阅客户端全量重拉 lookup → 新路由规则经 `GroupDiscoveryFilter` 进入发现结果。
5. 端到端生效延迟 ≈ 0-5s（轮询）+ 推送 + 客户端重拉；API 返回仅代表本节点已写 DB（基线 §3.3 的秒级窗口即此）。

### 5.5 节点冷启动

1. `App.main`/`configure` → `ArtemisServer.init()`：先 `ManagementInitializer.init()`（DataConfig + 三 Repository 装配 + 过滤器/通知器注册），后 `ClusterManager.init()`（解析成员配置 → 起探测线程 → `NodeManager.init()` 置 STARTING、起 1s 门控循环）。
2. DISCOVERY 目标：等管理面首轮缓存刷新成功（DB 可达）。
3. REGISTRY 目标：`RegistryReplicationInitializer` 逐个 UP peer 拉 `services.json` 全量，逐服务 register 重建租约（走复制入口语义）；成功前注册 API 一律 SERVICE_UNAVAILABLE，客户端被客户端侧地址容灾换节点。
4. 双目标达成 → UP，开始接流量与发送复制；旧 peer 上滞留的本节点状态由探测线程 5s 内刷新。
5. 单机/演示模式：`force-up=true` 跳过 REGISTRY 同步直接就绪（样例配置即此形态）。

## 6. 线程模型与并发设计

### 6.1 服务端后台线程清单（默认配置，源码逐一核实）

| 线程（组） | 数量 | 周期/触发 | 归属 |
|---|---|---|---|
| lease-manager clean | 2 × 2 池 = 4 | 1s（init-delay 1s） | 两个 LeaseManager 各自 `ScheduledExecutorService`（`clean-task.thread-count` 默认 2） |
| lease-update-safe-checker | 2 | 1s | 每个 LeaseManager 一条 caravan DynamicScheduledThread |
| serivce-cluster 探测 | 1 | 5s fixed-delay | ClusterManager 单线程 scheduler |
| NodeManager init 循环 | 1 | 1s（UP 即退出） | daemon Thread |
| notification-worker | 10 | 阻塞消费跳表（空则 20ms 自旋） | NotificationCenter |
| versioned-cache 刷新 | 1 | 60s 首刷 / 30s | VersionedCacheManager 单线程 scheduler |
| 复制 acceptor | 2（批/非批各 1） | 5ms 写等待循环 | 两个 TaskAcceptor |
| 复制 executor | 2 × 20 = 40 | 阻塞取 workQueue | 两个 TaskExecutor（`thread-count` 默认 20） |
| WS session health-checker | 3（每 handler 1） | 默认 60s | ArtemisWsHandler 基类 DelayQueue 扫描 |
| 管理面 cache-refresher | 3 | Group/Zone 5s、Management 1s | Group/Management/Zone Repository 各一条 DynamicScheduledThread |
| Tomcat worker 池 | Spring Boot 默认 | 请求驱动 | 接入层 |

合计常驻后台线程 ≈ 67（默认配置，上表行和，未计 Tomcat worker 池），其中复制子系统占 42——线程预算大头在复制，与全对全扇出的负载形态匹配。

### 6.2 客户端线程清单（每 manager，基线 §2.8 的 7+ 已核实构成）

| 线程 | 归属 | 周期 |
|---|---|---|
| address-repository ×2 | registry/discovery 各一条 AddressRepository | 节点列表刷新（默认 5min 拉取 + context-ttl 1h 轮换） |
| websocket-session.health-check ×2 | 两套 WebSocketSessionContext | 1s（ping/pong 超时检测、会话 5min 强制重建、重连限流 5 次/20s） |
| instance-registry.heartbeat-checker ×1 | InstanceRegistry | 1s（≥5s 发心跳、≥20s 判不可用重建） |
| service-discovery poller ×1 | ServiceDiscovery | 60s 三层兜底 |
| serviceChangeCallback ×1 | ServiceRepository 单线程 ExecutorService | 变更回调异步通知 |

### 6.3 锁策略与并发手法

- **Lease 级 tryLock**：`Lease` 内嵌 `ReentrantLock`，`renew()` 与 clean 竞争用 tryLock 非阻塞互斥——续约失败（正被清理）即返回 false，不阻塞心跳线程（`artemis-common/src/main/java/org/mydotey/artemis/lease/Lease.java`，原仓库）。
- **volatile 整体换新**：ClusterManager 节点视图列表、NodeManager 状态、管理面各缓存 Map/Multimap、`_nodeStatusMap`、`_currentVersion` 均为「后台线程构建新对象 → volatile 引用置换」的 copy-on-swap 模式，读路径无锁。
- **注册表读路径近无锁**：两级 `ConcurrentHashMap` 直读；`getService` 组装时对 Service 壳 clone 后填 instances（`getApplicationInternal` 的 clone），读者拿快照。
- **写入无事务**：`register()` 的三步（services put / lease 建 / leases 索引 put）非原子，靠幂等与覆盖写收敛；清理与并发重注册的冲突用 creationTime 新旧比较保护新租约（`RegistryRepository.onLeaseClean`、`LeaseManager.clean`，原仓库）。
- **已知弱点**：NotificationCenter 同步发送（§4.5）、`_nodeStatusMap` 非 volatile 的 benign race、客户端回调单线程无界队列（基线 §6.13）。

## 7. 部署视图

- **形态**：Spring Boot fat jar（`App.main`）或 WAR 到外部 Tomcat（`SpringBootServletInitializer.configure` 双入口，`artemis-package/src/main/java/org/mydotey/artemis/server/App.java`，原仓库）。无 Dockerfile/K8s manifest（基线 §3.7）。
- **配置三件套**（classpath）：`application.properties`（部署身份 region.id/zone.id/app.* 等）、`artemis.properties`（服务端行为：集群成员、租约、复制、限流、门控旁路）、`data-source.properties`（管理面 DB；另有 `data-source-sqlite.properties` 单文件形态）。
- **外部依赖**：仅管理面 DB（MySQL，2026-03 起可切 SQLite）；`artemis.management.enabled=false` 时管理面整体关闭、零外部依赖（基线 §3.7）。
- **region/zone 拓扑**：region = 集群边界（跨 region 零同步）；zone = 写入准入单位（默认仅同 zone 可写，`allow-from-other-zone` 放开；样例配置默认放开）+ 就近发现优先级；复制与数据仍是 region 全量（基线 §2.7）。
- **客户端接入**：`.service.domain.url` 引导地址 + 5min 存活节点列表 + 随机选址/熔断/轮换（基线 §2.8）。生产是否在引导地址前置 LB 不可从代码证实（待验证）。
- **样例配置即单机演示形态**：`cluster.nodes` 仅一条 127.0.0.1、`force-up=true` 跳过门控、SQLite 可选——开箱单机可跑，但该形态下自我保护/复制/门控全部旁路。

## 8. 架构权衡评估

以架构师视角对原设计的得与失做总评；负项编号引用基线 §6（不重复论证）。

**做对的事（结构性优势）**：

1. **AP 语义贯彻到底且自洽**：无 quorum/无 leader/覆盖写 + 五重收敛路径（复制重试、TTL 丢弃、心跳自愈、重启全量拉取、客户端 15min 兜底，基线 §3.3）——每个「不可靠」组件都有上游对账兜底，一致性不依赖任何单点正确。
2. **读写路径分离彻底**：写路径只有心跳/注册（客户端→单节点→异步扇出），读路径纯内存 + 后台缓存 + 推送，请求线程上没有锁等待、没有 DB、没有 peer 调用（管理面 API 除外）。
3. **双轨分离让数据面保持零依赖**：注册/发现内核不碰 DB；管理面经过滤器/通知器两个注入点作用于数据面——管理面挂了注册发现照常，这是模块依赖链单向无环的架构红利。
4. **批量贯穿 + 异步扇出是 10 万实例的立身之本**（基线 §5.11）：API 批、心跳批、复制批、lookup 批。
5. **readiness 门控 + 客户端三级容灾 + 缓存永不失效**三层配合，节点故障对客户端近乎无感（基线 §3.2）。

**结构性代价（重设计必须直面）**：

1. **写放大天花板**：全对全复制使写容量与节点数负相关，region 内节点数 × 实例心跳频率是硬上限（基线 §6.4）——规模化路线（分片/分层）在原架构内无解。
2. **零持久化 + 客户端无快照的双重脆弱**：全集群重启依赖客户端风暴重注册；server 全挂 + 客户端重启 = 发现不可用（基线 §6.5/§6.6）。
3. **管理面一致性靠时序赌注**：sleep(2s) + 5s 轮询，无生效确认与版本化（基线 §6.8）。
4. **推送管道的单点脆弱**：10 worker 同步发送，慢消费者可耗尽推送并行度（§4.5 源码核实）；叠加 WS 8KB 缓冲上限（基线 §6.1）。
5. **静态成员 + 子串识别本机 + 串行探测**：运维模型停在「改配置扩容」时代（基线 §6.10）。
6. **协议与工程债**：WS+JSON、同步 HttpClient4、无鉴权无 TLS、依赖链 EOL（基线 §6.1/§6.19/§6.22）。

**给重设计的直接映射**：资产 1/2/3/8/9/11（基线 §5）可在新协议栈（gRPC/HTTP2、持久化快照、动态成员）下原语义继承；写放大、管理面生效确认、推送解耦（per-subscriber 队列）、多租户容量分片是原架构内无法修补、必须在新设计中重立的四根柱子。
