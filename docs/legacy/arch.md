# Artemis 原产品架构视图（Legacy Architecture）

版本: 1.1    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**事实层 · 架构视图**——结构维度的完整描述：分层与依赖、模块划分、双轨分离、线程模型、部署形态、架构权衡。行为语义与业务逻辑（机制细节、决策规则、含失败路径的流程）已由规格层业务域文档承接，本文以索引指向、不再重复展开。产品级入口 [product-overview.md](product-overview.md)。
> 证据引用为相对原仓库根路径并注明「原仓库」。

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
│  REST：11 个 Controller ／ WS：3 个 Handler（WsIPBlackList 拦截）          │
└──────────────┬───────────────────────────────────────────────────────────┘
┌──────────────▼───────────────────────────────────────────────────────────┐
│  服务内核（artemis-service + artemis-common 的 lease/taskdispatcher）      │
│  registry / discovery / cluster / lease / 限流                            │
├──────────────────────────────────────────────────────────────────────────┤
│  管理面（artemis-management，唯一用 DB 的模块）                             │
│  三缓存仓库 → DiscoveryFilters / NotificationCenter 注入                   │
│                    MySQL / SQLite（20 张表：10 业务 + 10 审计）             │
└──────────────────────────────────────────────────────────────────────────┘
        ▲ 复制通道：peer 间 HTTP /api/replication/registry/*.json
          3 种消息，批量通道 250 条/2s + 单条通道，TTL 5s 过期丢弃
          对等全对全（无 leader，region 内全量复制）
```

分层要点（源码依据）：

- **接入层薄、内核重**：Controller/Handler 只做协议转换，一行调服务内核单例（如 `HeartbeatWsHandler.handleTextMessage` 反序列化后直调 `RegistryServiceImpl.heartbeat`，`artemis-server/.../websocket/HeartbeatWsHandler.java`，原仓库）。
- **服务内核与配置零 Spring**：artemis-common 是无 Spring 基础库；内核组件全部显式单例，不走 IoC 容器。Spring 只存在于接入层与装配层（`App` 的 `@ComponentScan("org.mydotey.artemis.server")`，`artemis-package/.../App.java`，原仓库）。
- **管理面通过三个注入点作用于数据面**：发现过滤器链 `DiscoveryFilters`、`NotificationCenter` 的 NotificationFilter、变更事件通道（合成事件复用数据面推送管线）；均在 `ManagementInitializer.init()` 注册（`artemis-management/.../ManagementInitializer.java`，原仓库）。
- **复制是内核的内嵌通道**：业务线程写本地成功后同线程入队复制任务（§4 索引：replication-cluster L1）。

## 2. 模块视图

Maven 依赖链（自各模块 pom.xml 核实，箭头 = depends on）：

```text
artemis-common ◄── artemis-service ◄── artemis-management ◄── artemis-server ◄── artemis-package
      ▲                                                              ▲
      └──────────────────── artemis-client（运行时仅依赖 common）       └── artemis-test（+client）
```

| 模块 | 职责（一句话） | Spring 依赖（pom 核实） | 关键包 |
|---|---|---|---|
| artemis-common | 零 Spring 基础库：数据模型、租约、任务分发、配置 | 无 | 模型类、`lease/`、`taskdispatcher/`、`config/`、`ErrorCodes` |
| artemis-service | 注册/发现/复制/集群/限流内核（全部显式单例） | 无 | `registry/`（+ replication/）、`discovery/`（+ notify/）、`cluster/`、`cache/`、`ratelimiter/` |
| artemis-management | 管理面服务层 + DAO（唯一用 DB）：分组/路由/摘除/审计 | spring-jdbc/context（无容器装配） | 三 Repository、`canary/`、`group/dao/`、两个 DiscoveryFilter |
| artemis-server | 接入层：11 Controller + 3 WS Handler + Jackson 装配 | Boot Web/WebSocket/Messaging | `rest/controller/*`、`websocket/*`、`ArtemisServer` |
| artemis-package | 启动入口（fat jar + WAR 双形态）+ 配置三件套 + 部署物 | Boot 启动（starter-tomcat + springfox） | `App`、resources、deployment |
| artemis-client | 纯 Java SDK，无自动装配，配置源/指标宿主注入 | 仅 spring-websocket/messaging（WS 客户端） | `common/`、`registry/`、`discovery/`、`websocket/` |
| artemis-test | 进程内集成测试基础设施，仅覆盖 management DAO | spring-boot-starter-test | `ArtemisTest.java` |

补充：artemis-client 对 artemis-package 的依赖是 test scope（运行时 SDK 只拖 artemis-common）；依赖严格单向无环；「registry/discovery/management separation」在 Maven 模块层成立，部署仍是单一 Spring Boot 应用（基线 §4）。

## 3. 数据面与管理面双轨

| 维度 | 数据面（注册表） | 管理面（流量治理元数据） |
|---|---|---|
| 存储 | 纯内存 `ConcurrentHashMap`，零持久化 | MySQL/SQLite，20 张表 |
| 事实源 | 客户端本地实例集（心跳全量对账） | 共享 DB（各节点直读） |
| 写路径 | WS/HTTP 心跳 → RegistryRepository | Controller → ServiceImpl → DAO → DB |
| 读路径 | getService(s) 直读内存 + 过滤器链 | DAO 全量读 → 内存缓存 → 定时重刷 |
| 节点间同步 | 复制通道（§4 索引） | 无节点间同步——各节点独立轮询同一 DB |
| 生效语义 | 覆盖写 + 最终一致 | 写后 `waitForPeerSync()` = `Thread.sleep(2s)`（`artemis.management.db-sync.wait-time` 默认 2000ms），写成功 ≠ 全网生效 |

缓存刷新周期（代码默认）：Group/Zone 仓库 5s、Management 仓库 1s（原仓库 `GroupRepository.java` L110-114、`ManagementRepository.java` L121-124）。刷新 diff 出变化服务后向 `_instanceChangeSet` 注入 reload 伪实例——管理面复用数据面的推送管线；`ManagementInitializer.initialized()` 以 `isLastRefreshSuccess()` 作为 DISCOVERY 门控条件。

## 4. 核心机制与数据流（索引）

机制叙事与数据流已由规格层业务域文档承接（更完整：含失败路径、决策表、行级证据），本文只保留索引：

| 机制 / 数据流 | 要点（一句话） | 详见 |
|---|---|---|
| 心跳即注册与租约模型 | 本地实例集唯一事实源；心跳全量上报 = 注册 + 续约；双租约池 | [registry-lease-logic](domains/registry-lease-logic.md) L1–L7、F1–F3 |
| 过期清理与自我保护 | clean 摘除决策；safe-checker 窗口阈值；显式 evict 绕过保护 | 同上 L6–L8、D2/D4、F3/F4 |
| 对等全对全异步复制 | 双通道去重合并；TTL 尽力送达；扇出按状态门控；失败定向重试 | [replication-cluster-logic](domains/replication-cluster-logic.md) L1–L4、F1–F2 |
| 集群成员与节点状态 | 静态拓扑（配置驱动）；5s 串行自声明探测；force 优先级矩阵 | 同上 L5–L6、D3–D5、F4 |
| 启动门控与冷启动 | 双目标 readiness；peer 全量拉取重建；空集群死锁缺陷 | 同上 L6–L7、F3 |
| 推送通知 | 跳表消费 → 过滤 → 会话同步发送；at-most-once | [discovery-logic](domains/discovery-logic.md) L3–L4、F2/F5 |
| 版本化缓存与增量 | 30s × 3 份快照；delta 设计完整但客户端未消费 | 同上 L2、[discovery-spec](domains/discovery-spec.md) FR-DIS-10 |
| 发现过滤器链 SPI | Group → Management；逐 filter 容错（fail-open） | 同上 L1/D4、[traffic-governance-logic](domains/traffic-governance-logic.md) L2 |
| 注册 / 心跳端到端数据流 | 首次注册 ≈6s 可见；稳态心跳 + 复制扇出 | registry-logic F1–F2、replication-logic F1 |
| 发现端到端数据流 | 首次 lookup + 订阅；增量落地；三层兜底 | discovery-logic F1/F4/F5 |
| 管理配置生效数据流 | release → sleep 2s → 5s 刷新 → reload 推送 → 重拉（5–7s 窗口） | traffic-governance-logic F1、[operations-audit-logic](domains/operations-audit-logic.md) F1 |
| 节点冷启动数据流 | 门控循环 → peer 拉取 → 三路收敛 → UP | replication-cluster-logic F3 |

## 5. 线程模型与并发设计

### 5.1 服务端后台线程清单（默认配置，源码逐一核实）

| 线程（组） | 数量 | 周期/触发 | 归属 |
|---|---|---|---|
| lease-manager clean | 2 × 2 池 = 4 | 1s | 两个 LeaseManager |
| lease-update-safe-checker | 2 | 1s | 每 LeaseManager 一条 |
| serivce-cluster 探测 | 1 | 5s fixed-delay（串行） | ClusterManager |
| NodeManager init 循环 | 1 | 1s（UP 即退出） | daemon Thread |
| notification-worker | 10 | 阻塞消费跳表（空则 20ms 自旋） | NotificationCenter |
| versioned-cache 刷新 | 1 | 60s 首刷 / 30s | VersionedCacheManager |
| 复制 acceptor | 2（批/非批各 1） | 5ms 写等待循环 | 两个 TaskAcceptor |
| 复制 executor | 2 × 20 = 40 | 阻塞取 workQueue | 两个 TaskExecutor |
| WS session health-checker | 3（每 handler 1） | 默认 60s | ArtemisWsHandler 基类 |
| 管理面 cache-refresher | 3 | Group/Zone 5s、Management 1s | 三 Repository |
| Tomcat worker 池 | Spring Boot 默认 | 请求驱动 | 接入层 |

合计常驻后台线程 ≈ 67（未计 Tomcat worker），其中复制子系统占 42——线程预算大头在复制，与全对全扇出的负载形态匹配。

### 5.2 客户端线程清单（每 manager）

| 线程 | 归属 | 周期 |
|---|---|---|
| address-repository ×2 | registry/discovery 各一条（⚠ 同名） | 5min 列表刷新 |
| websocket-session.health-check ×2 | 两套 SessionContext | 1s |
| instance-registry.heartbeat-checker ×1 | InstanceRegistry | 1s |
| service-discovery poller ×1 | ServiceDiscovery | 60s 兜底 |
| serviceChangeCallback ×1 | 单线程 ExecutorService | 事件驱动 |

⚠ 回调 executor 线程为 **non-daemon** 且无 shutdown——阻止 JVM 退出（基线 §8-7 勘误；[client-sdk-logic](domains/client-sdk-logic.md) §7.1）。

### 5.3 锁策略与并发手法

- **Lease 级 tryLock**：renew 与 clean 竞争非阻塞互斥（`artemis-common/.../lease/Lease.java`，原仓库）。
- **volatile 整体换新**（copy-on-swap）：节点视图、管理面缓存、状态表、`_currentVersion` 等——读路径无锁。
- **注册表读路径近无锁**：两级 CHM 直读；getService 组装时 clone Service 壳（⚠ Instance 元素引用共享，discovery FR-DIS-12）。
- **写入无事务**：register 三步非原子，靠幂等覆盖 + creationTime 新旧保护收敛（registry-lease L4/L7）。
- **已知弱点**：NotificationCenter 同步发送（慢消费者占 worker）、`_nodeStatusMap` 非 volatile 的 benign race、客户端回调单线程无界队列。

## 6. 部署视图

- **形态**：Spring Boot fat jar 或 WAR 到外部 Tomcat（双入口，`artemis-package/.../App.java`，原仓库）。无 Dockerfile/K8s manifest（基线 §3.7）。
- **配置三件套**（classpath）：`application.properties`（部署身份）、`artemis.properties`（集群成员、租约、复制、限流、门控旁路）、`data-source.properties`（管理面 DB；SQLite 单文件变体）。
- **外部依赖**：仅管理面 DB；`artemis.management.enabled=false` 时零外部依赖。
- **region/zone 拓扑**：region = 集群边界（跨 region 零同步）；zone = 写入准入单位（非数据局部性）；复制与数据 region 全量（基线 §2.7、product-overview §3.2）。
- **客户端接入**：引导地址 + 5min 存活节点列表 + 随机选址/熔断/轮换。生产是否前置 LB 不可从代码证实（待验证）。
- **样例配置即单机演示形态**：单节点、force-up=true 跳过门控——开箱可跑，但自我保护/复制/门控全部旁路。

## 7. 架构权衡评估

以架构师视角对原设计的得与失做总评；负项编号引用基线 §6，行级证据见各域 logic 文档。

**做对的事（结构性优势）**：

1. **AP 语义贯彻到底且自洽**：无 quorum/无 leader/覆盖写 + 五重收敛路径（复制重试、TTL 丢弃、心跳自愈、重启全量拉取、客户端 15min 兜底）——每个「不可靠」组件都有上游对账兜底。
2. **读写路径分离彻底**：读路径纯内存 + 后台缓存 + 推送，请求线程上没有锁等待、没有 DB、没有 peer 调用（管理面 API 除外）。
3. **双轨分离让数据面保持零依赖**：注册/发现内核不碰 DB；管理面经注入点作用于数据面——管理面挂了注册发现照常，模块依赖链单向无环的架构红利。
4. **批量贯穿 + 异步扇出是 10 万实例的立身之本**（基线 §5.11）。
5. **readiness 门控 + 客户端三级容灾 + 缓存永不失效**三层配合，节点故障对客户端近乎无感。

**结构性代价（重设计必须直面）**：

1. **写放大天花板**：全对全复制使写容量与节点数负相关（基线 §6.4）——规模化路线在原架构内无解。
2. **零持久化 + 客户端无快照的双重脆弱**（基线 §6.5/§6.6）。
3. **管理面一致性靠时序赌注**：sleep(2s) + 轮询，无生效确认（基线 §6.8）。
4. **推送管道的单点脆弱**：10 worker 同步发送，慢消费者耗尽并行度；叠加 WS 8KB 缓冲上限（基线 §6.1）。
5. **静态成员 + 子串识别本机 + 串行探测**（基线 §6.10）。
6. **协议与工程债**：WS+JSON、同步 HttpClient4、无鉴权无 TLS、依赖链 EOL（基线 §6.1/§6.19/§6.22）。

**给重设计的直接映射**：资产 1/2/3/8/9/11（基线 §5）可在新协议栈（gRPC/HTTP2、持久化快照、动态成员）下原语义继承；写放大、管理面生效确认、推送解耦（per-subscriber 队列）、多租户容量分片是原架构内无法修补、必须在新设计中重立的四根柱子。逐单元的取舍依据见 [product-overview.md](product-overview.md) 与各域 logic §8 对照表。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-08 | 按规格层重整：机制叙事改为索引 |
| 1.0 | 2026-10-07 | 初版 |
