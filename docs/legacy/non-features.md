# Artemis 原产品非功能性需求列表（Reverse-Engineered NFR）

状态: 草案  日期: 2026-10-07

> 调查对象：`~/Projects/mydotey/artemis`（version 2.0.2）。
> 方法说明：**原产品没有显式的需求文档，本列表是从代码反推（reverse-engineered）的非功能性需求**——即「代码结构与配置默认值体现出原设计者曾经要求系统满足的性质」。数字类证据（配置键、默认值、允许范围）均于本次调查在源码中逐一核实；原仓库 Readme.md 不作为证据。
> 与基线的关系：`docs/legacy/legacy-product-analysis.md`（下称「基线」）§3 已确立的结论直接引用；本文档的价值在于按非功能维度**重组**为结构化需求列表，并补充基线未展开的配置键与代码机制证据。
> 满足程度判定均指**开源形态下**的现状：已满足 = 机制完整可用；部分满足 = 机制在但有明确上限或缺口；未满足 = 无实现。
> 路径约定：`原仓库` 前缀表示相对 `~/Projects/mydotey/artemis` 根路径；其余为相对本仓库根路径。

---

## 1. 容量与可扩展性

#### NFR-1 注册表容量目标：单 region 10 万级服务实例

- **陈述**：内存注册表须容纳 10 万级服务实例，容量参数按该目标设计。
- **证据**：注册表初始容量配置 `artemis.service.registry.data.init-capacity` 默认 10,000、允许范围 1,000–100,000——**容量上限 10 万直接写在配置范围里**（原仓库 `artemis-service/src/main/java/org/mydotey/artemis/registry/RegistryRepository.java` L54-56）；租约池初始容量默认 50,000、范围 10,000–1,000,000（原仓库 `artemis-common/src/main/java/org/mydotey/artemis/lease/LeaseManager.java` L90-91）。10 万+ 实例的实绩来自口头历史（基线 §1），代码可见为规模设计的痕迹。
- **满足程度**：部分满足。读路径可支撑，但写路径受全对全复制写放大约束，写容量不随节点数扩展（基线 §3.1「上限约束」）。实际 10 万实绩待验证。

#### NFR-2 全链路批量化以摊薄单位开销

- **陈述**：注册/心跳/注销/发现/复制各通道均须批量执行，把单实例操作摊薄为批量消息。
- **证据**：注册/心跳/注销 API 均为 `instances[]` 批量入参（原仓库 `artemis-server/src/main/java/org/mydotey/artemis/server/rest/controller/RegistryController.java`）；复制批量通道 `max-batching-size` 默认 **250** 条/批（范围 10–10,000）、`max-batching-delay` 默认 **2,000ms** 最大批延迟（范围 1,000–10,000ms）（原仓库 `artemis-common/src/main/java/org/mydotey/artemis/taskdispatcher/BatchingTaskAcceptor.java` L29-32）；客户端心跳单条 WS 消息携带全量本地实例（基线 §2.2）；发现 lookup 批量查询（基线 §2.4）。
- **满足程度**：已满足（基线 §5 资产 11）。

#### NFR-3 读容量水平扩展

- **陈述**：发现读流量须能通过加节点线性扩展。
- **证据**：每节点全量持有 region 数据、任一节点可服务发现请求，加节点即扩读（基线 §3.1）；对照写路径全对全复制 N-1 写放大（基线 §6.4）。
- **满足程度**：已满足（读）；写容量受 NFR-1 上限约束。

#### NFR-4 分级限流保护节点不被打挂

- **陈述**：各 API 通道须独立限流，超限返回 RATE_LIMITED 错误码而非排队挂死。
- **证据**（原仓库源码，caravan RateLimiter）：
  | 通道 | 配置 id | 默认 QPS | 允许范围 |
  |---|---|---|---|
  | registry（register/heartbeat/unregister） | `artemis.service.registry` | **100,000** | 1,000–1,000,000 |
  | replication（复制入站） | `artemis.service.registry.replication` | **1,000,000** | 1,000–10,000,000 |
  | cluster（up-*-nodes 端点） | `artemis.service.cluster` | **10,000** | 100–100,000 |
  | status 端点 | `artemis.service.status` | **30** | 1–10,000 |
  | 管理面 GroupService | `artemis.service.management.group` | **30** | 1–1,000 |

  分别见原仓库 `artemis-service/.../registry/RegistryServiceImpl.java` L37-38、`registry/replication/RegistryReplicationServiceImpl.java` L52-54、`cluster/ClusterServiceImpl.java`、`status/StatusServiceImpl.java` L62-63、`artemis-management/.../GroupServiceImpl.java` L43-45。按操作覆盖的 shipped 示例：`artemis.service.status.rate-limiter.rate-limit-map=get-leases:10`（原仓库 `artemis-package/src/main/resources/artemis.properties`）。
- **满足程度**：已满足。

#### NFR-5 O(全量) 组装移出请求路径

- **陈述**：全量服务列表接口不得在请求线程上做全量数据组装。
- **证据**：版本化缓存 managerId `artemis.service.discovery`：`versioned-cache.cache-count` 默认 **3** 份、`cache-refresh.init-delay` 默认 60s、`cache-refresh.interval` 默认 **30s**（原仓库 `artemis-service/src/main/java/org/mydotey/artemis/cache/VersionedCacheManager.java` L50-56，实例化见 `discovery/DiscoveryServiceImpl.java` L51）；services.json 读快照、services-delta 按 version 取预计算差集（基线 §2.4）。
- **满足程度**：已满足。注意 version 用本地毫秒时间戳、delta 窗口仅 3×30s≈90s 且客户端实际未使用 delta（基线 §6.3/§6.7）。

---

## 2. 性能

#### NFR-6 实例变更亚秒级推送

- **陈述**：实例变更须在亚秒级推送到订阅客户端。
- **证据**：变更写入 `ConcurrentSkipListSet`（poll-wait 默认 **20ms**，原仓库 `RegistryRepository.java` L62-64），`NotificationCenter` 推送线程数 `artemis.service.discovery.notify.thread-count` 默认 **10**（范围 1–100，原仓库 `artemis-service/.../discovery/notify/NotificationCenter.java` L37）；WS 单服务订阅 + 全服务广播双通道（基线 §2.4）。
- **满足程度**：已满足（正常路径亚秒级；漏推场景靠 NFR-24 兜底）。

#### NFR-7 心跳通道低延迟、快速失败

- **陈述**：心跳（注册续约主通道）处理须低延迟，复制超时须快速失败不拖垮业务线程。
- **证据**：复制心跳 socket timeout `artemis.service.registry.replication.heartbeat.client.socket-timeout` 默认 **200ms**（范围 50–5,000ms）、get-services 全量拉取默认 2,000ms（原仓库 `artemis-service/.../registry/replication/RegistryReplicationServiceClient.java` L31-38）；复制全程异步、入队即返回（基线 §2.7）；批量通道默认 250 条/批（NFR-2）。
- **满足程度**：已满足。

#### NFR-8 传输压缩

- **陈述**：客户端与服务端、节点与节点之间的 HTTP 传输须启用 gzip。
- **证据**：客户端全部请求 `HttpRequestFactory.gzipRequest(...)`（原仓库 `artemis-client/.../common/ArtemisHttpClient.java` L63、`registry/RegistryServiceClient.java`、`common/AddressRepository.java` L102）；复制通道同样 gzip（`RegistryReplicationServiceClient.java`）。
- **满足程度**：已满足。WS 文本帧不走 gzip（JSON 文本推送，基线 §6.1）。

#### NFR-9 读路径细粒度并发

- **陈述**：注册表读路径须近无锁，写冲突须收敛到细粒度锁。
- **证据**：两级 ConcurrentHashMap 直读；租约清理逐 lease tryLock 摘除；缓存整体 volatile 换新（基线 §3.4）；配置热更 Property 均带 ChangeListener（原仓库 `artemis-service/.../cluster/NodeManager.java` L86-94）。
- **满足程度**：部分满足。服务端成立；客户端每次 `getService` 深克隆 + 重建路由 Map、大小写不敏感 equals/hashCode 拼串开销是隐性热点（基线 §3.4 代价点）。

---

## 3. 可用性与容灾

#### NFR-10 集群无单点

- **陈述**：任一节点均可读写，无 leader、无单点角色。
- **证据**：对等全对全复制拓扑，集群成员来自静态配置 `artemis.service.cluster.nodes`（zoneId→urls multimap）；节点状态探测 `artemis.service.cluster.nodes.status-update.interval` 默认 **5s**、`fail-retry-times` 默认 **3**（原仓库 `artemis-service/.../cluster/ClusterManager.java` L44-47）；管理面 DB 是共享依赖但可整体关闭（NFR-35）。
- **满足程度**：已满足（数据面）。管理面 DB 是管理功能单点（基线 §3.2）。

#### NFR-11 客户端节点故障自动切换

- **陈述**：客户端无须人工干预即可在节点故障时切换到其他节点。
- **证据**：三级地址容灾管线——引导地址 → 周期拉存活节点列表 → 随机选址 + `markUnavailable` 熔单点 + TTL 强制轮换 `address.context-ttl` 默认 **1h**（原仓库 `artemis-client/.../common/AddressContext.java` L36）；HTTP 统一执行器默认 **5 次重试 × 100ms** 间隔（`.http-client.retry-times` / `.retry-interval`，原仓库 `ArtemisHttpClient.java` L45-47）+ 错误码驱动摘节点（基线 §2.8）。
- **满足程度**：已满足（基线 §5 资产 2、3）。

#### NFR-12 故障节点恢复自动追平数据

- **陈述**：节点重启/恢复后须自动从 peer 全量拉取注册表，不依赖人工补数。
- **证据**：启动门控 daemon 线程循环间隔 `artemis.service.cluster.node.init.sync-interval` 默认 **1s**（范围 50ms–600s，原仓库 `NodeManager.java` L52-54），REGISTRY 目标从任一 UP peer 拉 services.json 重建租约（基线 §2.7 冷启动）。
- **满足程度**：已满足。依赖至少一个存活 peer——全集群同时重启无解（NFR-16）。

#### NFR-13 启动门控（readiness）

- **陈述**：数据未同步完成的节点不得对外提供注册/发现服务。
- **证据**：REGISTRY/DISCOVERY 两类 NodeInitializer 全部通过才置 `canServiceRegistry/canServiceDiscovery=true`（原仓库 `NodeManager.java#executeInitializers` L166-190）；人工逃生门：6 个 force-up/down 配置键（IP 粒度，`artemis.service.cluster.node.status.{registry.,discovery.,}force-{up,down}.<ip>`）。
- **满足程度**：已满足（基线 §5 资产 9）。

#### NFR-14 注册断线自愈（幂等对账）

- **陈述**：客户端断网/重启/server 恢复后，注册状态须自动对账，无须显式重注册状态机。
- **证据**：「心跳即注册」——心跳每 5s 全量上报本地实例集（`.instance-registry.heartbeat-interval` 默认 5,000ms、范围 500ms–5min；`.instance-registry.instance-ttl` 默认 20,000ms、范围 5s–24h，原仓库 `artemis-client/.../registry/InstanceRegistry.java` L57-60）；心跳响应 failedInstances 触发 HTTP 补注册（基线 §2.2）。
- **满足程度**：已满足（基线 §5 资产 1）。

#### NFR-15 服务端大面积心跳丢失不误摘（自我保护）

- **陈述**：网络故障导致的大面积心跳丢失不得触发批量摘除实例。
- **证据**：`LeaseUpdateSafeChecker` 配置（原仓库 `artemis-common/.../lease/LeaseUpdateSafeChecker.java#initConfig`）：`enabled` 默认 true、`time-window` 默认 **10s**（10s–5min）、`percentage-threshold` 默认 **85**（50–100）、`max-count-threshold` 默认 **50**（即续约量历史 maxCount ≥ 50 才启用保护）、`max-count-reset-interval` 默认 10min；保护态跳过「仅过期未显式 evict」的清理（基线 §2.3）。
- **满足程度**：部分满足。机制完整，但全局单阈值无法区分局部异常与网络故障（基线 §6.9）。

#### NFR-16 注册数据持久化与全集群恢复

- **陈述**：全集群同时重启后注册表须可恢复。
- **证据**：注册表纯内存零持久化（基线 §3.2 弱点）；恢复完全依赖客户端风暴式重注册。唯一持久化是管理面 DB（MySQL/SQLite，仅流量治理元数据与审计，原仓库 `artemis-package/src/main/resources/data-source*.properties`）。
- **满足程度**：未满足（基线 §6.5）。

#### NFR-17 客户端本地快照

- **陈述**：客户端重启后须能从本地磁盘快照恢复发现数据（冷启动兜底）。
- **证据**：客户端仅内存缓存、无磁盘 snapshot 文件（基线 §3.2）；对照：缓存永不失效策略使 server 全挂时**已运行**客户端仍可发现。
- **满足程度**：未满足（基线 §6.6）。

---

## 4. 一致性

#### NFR-18 AP 最终一致（region 内）

- **陈述**：region 内节点间数据最终一致，收敛时间有上界；不追求强一致。
- **证据**：无 quorum/事务/Raft；收敛路径 = 覆盖写 + 复制重试 + 客户端心跳自愈 + 重启全量拉取 + 客户端全量兜底轮询 `.service-discovery.ttl` 默认 **15min**（原仓库 `artemis-client/.../discovery/ServiceDiscovery.java` L46）——漏推场景最久 15min 纠正（基线 §3.3）。
- **满足程度**：已满足（按 AP 语义）。无数据版本/世代概念，无法量化落后程度（基线 §6.3）。

#### NFR-19 复制尽力送达 + 有界丢弃

- **陈述**：复制任务失败须重试且优先；超龄任务须丢弃而非无限堆积；须有背压。
- **证据**：任务 TTL `artemis.service.registry.{register,unregister,heartbeat}.replication.task-ttl` 默认 **5,000ms**（原仓库 `registry/replication/{RegisterTask,UnregisterTask,HeartbeatTask}.java` L17-19）——过期即丢、靠下一轮心跳收敛（刻意设计，基线 §2.7）；失败任务重试并插队队首；缓冲区 `max-buffer-size` 默认 **10,000**（范围 100–100,000，原仓库 `taskdispatcher/TaskAcceptor.java` L94）满则丢最老整批；`TrafficShaper` 按错误码退避。
- **满足程度**：已满足（best-effort 语义内）。无确认闭环、无水位比对（基线 §6.4）。

#### NFR-20 复制冲突不回退新数据

- **陈述**：并发注册/清理/复制乱序不得导致新租约被旧数据覆盖。
- **证据**：register 直接 put 覆盖；清理与并发重注册用 creationTime 新旧比较保护新租约；复制心跳遇缺失实例自动补注册（基线 §2.7 冲突处理）。
- **满足程度**：部分满足。单键语义成立；无脑裂检测，分区两侧各自接受写、恢复后靠覆盖收敛（基线 §3.3）。

#### NFR-21 管理面变更秒级全网生效

- **陈述**：管理面写操作（摘除/路由权重）须在秒级对全部节点的发现结果生效。
- **证据**：写后 `waitForPeerSync()` 直接 sleep `artemis.management.db-sync.wait-time` 默认 **2,000ms**（范围 0–60s，原仓库 `artemis-management/.../ManagementRepository.java` L90-91、L320-323）；各节点 `DynamicScheduledThread` 定时重刷缓存，shipped 配置 `artemis.management.data.cache-refresher.dynamic-scheduled-thread.run-interval=1000`（原仓库 `artemis.properties` L38，仅覆盖 Management 键且与代码默认相同）。三个 Repository 轮询周期不同（构造参数顺序已经上游 mydotey/caravan-util 源码核实定案）：`GroupRepository`（`artemis.management.group.data.cache-refresher`）与 `ZoneRepository` 代码默认 **5s**（范围 10ms–60s，原仓库 `GroupRepository.java` L110-114、`ZoneRepository.java` L68-71），`ManagementRepository` 代码默认 **1s**（范围 200ms–60s，原仓库 `ManagementRepository.java` L121-124）——即分组/路由（Group）生效轮询周期 5s、摘除判定（Management）1s；基线 §2.5 原记统一「默认 5s」，已同步修订为该口径。
- **满足程度**：部分满足。写成功 ≠ 全网生效，存在秒级窗口且无生效确认（基线 §6.8）。

#### NFR-22 跨 zone 写入准入与 region 隔离

- **陈述**：注册/发现请求默认仅接受同 zone 客户端；region 间数据零同步（多 region = 多独立集群）。
- **证据**：`artemis.service.registry.allow-from-other-zone` / `artemis.service.discovery.allow-from-other-zone` 代码默认 **false**（原仓库 `NodeManager.java` L56-60；shipped 配置放开为 true）；region 间无任何复制代码路径（基线 §2.7）。
- **满足程度**：已满足（作为软隔离手段；非安全边界，见 NFR-27）。

---

## 5. 安全

#### NFR-23 API 认证与操作鉴权

- **陈述**：REST/WS API 须有认证；管理写操作须鉴权与权限控制。
- **证据**：全部 API 无认证鉴权实现；`ErrorCodes` 定义了 no-permission 错误码但无调用路径；`OperationContext.token` 只存不验（基线 §3.6、§6.18）。
- **满足程度**：未满足（开源形态不可上生产，基线 §6.19）。

#### NFR-24 传输加密

- **陈述**：客户端-服务端、节点间通信须支持 TLS。
- **证据**：明文 http/ws，无 TLS 配置点（基线 §3.6）；集群成员配置仅 http url（`artemis.service.cluster.nodes=zone1:http://...`，原仓库 `artemis.properties`）。
- **满足程度**：未满足。

#### NFR-25 凭据保护

- **陈述**：DB 等凭据不得明文存放。
- **证据**：原仓库 `artemis-package/src/main/resources/data-source.properties` 明文 `username=admin` / `password=123456`（MySQL）；SQLite 配置无密码。
- **满足程度**：未满足。

#### NFR-26 恶意客户端防护（WS 层）

- **陈述**：WebSocket 接入点须可按来源 IP 拒绝连接。
- **证据**：`WsIPBlackList` HandshakeInterceptor 挂载于全部 3 个 WS handler；配置 `artemis.service.<id>.ws-ip.black-list.enabled` 默认 true、`ws-ip.black-list` 默认空列表（原仓库 `artemis-server/.../websocket/WsIPBlackList.java`、`WebSocketEndpointConfig.java`）。
- **满足程度**：部分满足。静态 IP 黑名单粒度粗，且为唯一访问控制屏障（基线 §3.6）。

---

## 6. 可观测性

#### NFR-27 运行时状态可查询（状态 API 即排障面）

- **陈述**：节点配置、租约、集群拓扑、连接数等运行时状态须可通过 API 查询，无需登录机器。
- **证据**：`/api/status/` 7 个端点（node/cluster/leases/legacy-leases/config/deployment/ws-connection）；config.json 返回全部配置值+来源，leases.json 含自我保护统计（maxCount/countLastTimeWindow）（基线 §2.6、§3.5）；限流后 status 端点仍有独立限流额度（NFR-4）。
- **满足程度**：已满足（基线 §5 资产 12）。

#### NFR-28 指标与追踪埋点

- **陈述**：核心路径（API/复制/推送/清理）须有 metric 与 trace 埋点，可接外部监控体系。
- **证据**：埋点调用遍布代码（`ArtemisMetricManagers`/`ArtemisTraceExecutor.INSTANCE.execute(...)` 包裹所有核心操作，如原仓库 `RegistryReplicationServiceClient.java`、`NodeManager.java#initAsync`）；但开源版 `MetricLoggerHelper` 方法体全空、metric/trace 默认 NullProvider，须配置 `artemis.metric.default.managers-provider` 与 `artemis.trace.factory-class` 指向自实现才生效（shipped 均为空串，原仓库 `artemis.properties`；基线 §3.5）。
- **满足程度**：部分满足（埋点点位完整，实现被掏空；无大盘、无告警，基线 §6.20）。

#### NFR-29 管理操作全量审计

- **陈述**：所有管理面变更操作须留痕可查（操作者、原因、前后数据、时间）。
- **证据**：变更双写 `*_log` 表（20 张表 = 10 业务 + 10 日志，原仓库 `artemis-package/deployment/artemis-management.sql`）；9 类审计日志查询 API（基线 §2.6）。
- **满足程度**：已满足（查询无分页，10 万级下不可用，基线 §6.16）。

---

## 7. 兼容性

#### NFR-30 老客户端长心跳兼容

- **陈述**：须兼容不改造的存量老客户端（长心跳间隔）。
- **证据**：双租约池——legacy 实例（`metadata.java_registry` 非空）独立 LeaseManager，shipped 配置 `artemis.service.registry.legacy-instance.lease-manager.lease.ttl=90000`（90s）vs 普通实例 20s（原仓库 `artemis.properties`；代码默认上限见 `LeaseManager.java` L101-103：lease.ttl 默认 20,000ms、范围 10s–7 天）。
- **满足程度**：已满足（作为过渡包袱存在，基线 §3.8）。

#### NFR-31 SDK 运行环境兼容性

- **陈述**：客户端 SDK 须在宿主（携程内网 Java 8 应用）环境运行，配置源与指标实现由宿主注入。
- **证据**：仅 Java 8；强绑定 org.mydotey.* 私有依赖链（scf/rpc-util/caravan/codec，均不在 Maven Central）；metric/trace provider 设计为可注入接口（基线 §2.8、§3.8）。
- **满足程度**：部分满足——内网环境已满足，开源环境下外部落地必须连号移植私有库（基线 §6.14）。

#### NFR-32 API 自文档

- **陈述**：REST API 须有在线文档便于接入方使用。
- **证据**：springfox swagger（`springfox-boot-starter`，原仓库 `artemis-package/pom.xml`）。
- **满足程度**：部分满足。文档在，但 springfox 3.0.0 是已死项目且与 Boot 2.3 后版本不兼容（基线 §3.7、§6.22）。

---

## 8. 部署与可运维性

#### NFR-33 单一部署物、极少外部依赖

- **陈述**：注册/发现核心须打包为单一部署物运行，不依赖 MQ/缓存/其他中间件。
- **证据**：Spring Boot fat jar（内嵌 Tomcat）或 WAR；唯一外部依赖是管理面 DB（MySQL，2026-03 起可切 SQLite 单文件，原仓库 `SQLITE_SETUP.md`、`data-source-sqlite.properties`）；连接池初始 10/max 50（`data-source.properties`）。
- **满足程度**：已满足。

#### NFR-34 管理面可整体关闭

- **陈述**：不需要流量治理/运维管控功能时，须可关闭管理面连同 DB 依赖运行。
- **证据**：启动开关 System property `artemis.management.enabled` 默认 `"true"`（原仓库 `artemis-server/src/main/java/org/mydotey/artemis/server/ArtemisServer.java` L18）；关闭时注册/发现核心无 DB 访问路径（基线 §3.7）。
- **满足程度**：已满足（逻辑开关，非独立部署物，基线 §4）。

#### NFR-35 配置全部热更

- **陈述**：运行参数（限流、TTL、集群成员、zone 准入等）须支持不重启生效。
- **证据**：全配置走 SCF Property + ChangeListener 模式：节点状态 6 个 force 键、zone 准入 2 键均注册监听即时更新（原仓库 `NodeManager.java` L86-94）；限流/TTL/批参数均为 Property 动态读（NFR-4/15/19 各证据）；集群成员变更触发 `ClusterChangeListener`（基线 §2.7）。
- **满足程度**：已满足（配置源含 env var/system properties/properties 文件级联，原仓库 `artemis-common/.../config/ArtemisConfig.java` L29-45）。

#### NFR-36 人工运维干预开关

- **陈述**：故障/演练场景下须可人工强制节点上线/下线（含分职能粒度）。
- **证据**：本机 IP 粒度 6 键——`artemis.service.cluster.node.status.{status,registry,discovery}.force-{up,down}.<hostIP>`（原仓库 `NodeManager.java` L29-50）；force-up 可越过启动门控（逃生门，与 NFR-13 配对）。
- **满足程度**：已满足。

#### NFR-37 集群扩缩容

- **陈述**：加/减节点须低成本完成。
- **证据**：静态集群成员表，扩缩容 = 修改 `artemis.service.cluster.nodes` 配置并热更；无自动成员发现协议；本节点识别靠「URL 包含本机 ip:port」子串匹配（基线 §2.7、§6.10）。
- **满足程度**：部分满足（小集群可接受；无自动发现、识别机制脆弱）。

#### NFR-38 容器化与交付流水线

- **陈述**：须提供容器镜像与 CI/CD 支持。
- **证据**：原仓库无 Dockerfile、无 K8s manifest、无 CI 配置（基线 §3.7、§6.21）。
- **满足程度**：未满足。

---

## 9. 可维护性与工程质量

#### NFR-39 核心路径测试覆盖

- **陈述**：复制、推送、租约、故障转移等分布式核心路径须有自动化测试。
- **证据**：测试仅 artemis-test 进程内集成测试覆盖 management DAO 层（57 个 @Test，SQLite 内存库，基线 §3.7）；taskdispatcher/lease 等公共库无单测。
- **满足程度**：未满足（基线 §6.21）。

#### NFR-40 依赖栈可维护性

- **陈述**：依赖须处于可维护（非 EOL）状态。
- **证据**：Java 8、Spring Boot 2.3.6（EOL）、javax 命名空间、springfox 3.0.0（死项目）、HttpClient 4.x、Jackson 2.12、MySQL Connector 5.1.49、JUnit 4.13 + Mockito 1.10（基线 §3.7）；技术栈定格 2020-12（基线 §1）。
- **满足程度**：未满足（基线 §6.22）。

#### NFR-41 配置键与代码一致性

- **陈述**：发布包自带配置样例须与代码读取的键一致、有效。
- **证据**：两处漂移（本次调查发现）：① shipped `artemis.service.registry.instance.lease-manager.thread-pool-size=3` 无代码读取（代码读 `.lease-manager.clean-task.thread-count`，默认 2——原仓库 `LeaseManager.java` L92-95），疑似死键；② 复制线程配置键拼写 `replicaton`（缺字母 i，原仓库 `artemis.properties` L25-26）与代码一致地拼错，属「错而有效」。另有打包配置键拼写错误的既有记录（基线 §6.23）。
- **满足程度**：未满足（配置面缺少与代码的对照校验机制）。

---

## 附：待验证清单

| 编号 | 事项 | 原因 |
|---|---|---|
| NFR-1 | 10 万+ 实例实绩 | 口头历史，仓库内无压测报告/数据（基线 §1 已标注） |
| NFR-41 | shipped `thread-pool-size` 是否曾为有效键（历史版本） | 需对比 1.5.x tag；本列表基于 2.0.2 HEAD 判定为死键 |
