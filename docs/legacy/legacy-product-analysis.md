# Artemis 原产品能力全景（Legacy Product Analysis）

版本: 1.3    更新时间: 2026-10-09

> 调研对象：`~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）
> 方法：四域并行源码调查（客户端 / 服务端 / 管理面 / 工程全貌），每条能力均追溯到代码；「文档宣称」与「代码事实」严格区分。
> 用途：Artemis 重设计前的产品能力基线。正输入 = 值得继承的设计资产；负输入 = 局限与现代化清单。

---

## 1. 产品概览

**定位**：携程框架部门 SOA 服务注册表（Readme.md 自述，pom 开发者 6 人中 5 人 organization 为 Ctrip, Inc. 可佐证）。支撑过 10 万+ 服务实例的注册发现（规模数字为作者确认的前公司生产实绩，仓库内无压测报告留档；代码中可见为规模设计的痕迹：批量接口、批量复制、限流、lease TTL，均详见下文）。

**时间线**（26 commits，单分支 master，tag 1.5.13 / 1.5.16 / 2.0.1）：

| 时间 | 事件 |
|---|---|
| 2016 | 代码创建期（文件头注释时间），Artemis 1 在携程内网落地 |
| 2017-10 | 开源（`d324170 oss startup`），Tomcat WAR 形态，模块含 artemis-web |
| 2018–2019 | 少量维护后沉寂近两年 |
| 2020-12 | Artemis 2 重写爆发（21 commits）：Spring Boot 2.3 + scf + swagger3，artemis-web 改名 artemis-server，新增 artemis-package fat jar |
| 2021–2025 | 完全沉寂 |
| 2026-03 | 3 笔面向单机可运行的改造：SQLite DAO、API 路径统一 `/api/`、版本 2.0.2 |

**架构形态（一句话）**：AP 型注册中心 —— 对等节点全对全异步复制 + 租约心跳 + Eureka 式自我保护 + 静态集群成员；数据面（注册表，纯内存）与管理面（MySQL/SQLite 持久化元数据）双轨分离。

**模块依赖与规模**：

```text
artemis-common（95 文件，零 Spring 基础库：模型/lease/taskdispatcher/配置）
  ├── artemis-service（53 文件：注册/发现/复制/集群/限流内核）
  │     └── artemis-management（209 文件：管理面服务层+DAO，唯一用 DB 的模块）
  │             └── artemis-server（23 文件：REST Controller + WS Handler 接入层）
  │                    └── artemis-package（1 文件：Spring Boot 启动入口 + 配置 + 部署物）
  ├── artemis-client（27 文件 ≈2.3k 行：纯 Java SDK）
  └── artemis-test（进程内集成测试，仅覆盖 management DAO 层）
```

工程总量：main 代码 408 个 Java 文件 ≈ 29,914 行；test 52 文件。

---

## 2. 功能性产品能力

### 2.1 核心数据模型（artemis-common）

| 实体 | 关键字段 | 说明 |
|---|---|---|
| `Instance` | regionId, zoneId, groupId, serviceId, instanceId, machineName, ip, port, protocol, url, healthCheckUrl, status, metadata | 13 字段可变 POJO；status 取值 starting/up/down/unhealthy/unknown；equals/hashCode 委托 InstanceKey（大小写不敏感） |
| `InstanceKey` | regionId.serviceId.instanceId | 实例唯一标识三元组 |
| `InstanceChange` | instance + changeType(new/delete/change/reload) + changeTime | 推送/增量统一语义；reload 用伪造 `0.0.0.0/reload` 假实例表示「全量重拉」 |
| `Service` | serviceId, metadata, instances, logicInstances, routeRules | logicInstances/routeRules 是发现时由分组路由动态注入的派生视图，非注册数据 |
| `ServiceGroup` / `RouteRule` | groupKey, weight[0–10000 默认 5], instanceIds / routeId, strategy, groups | 内置两条保留规则：`default-route-rule` 与 `canary-route-rule`；策略两种：weighted-round-robin、close-by-visit（就近） |
| `Region` / `Zone` / `ServerKey` | region→zones 层级 / serverId=IP | 三层空间模型 + 管理面机器标识 |

层级模型：**Region（= 集群边界，region 间零同步）→ Zone（机房/单元，写入准入单位）→ ServiceGroup（服务内流量分组）**。

### 2.2 服务注册：「心跳即注册」模型

Artemis 最独特的设计——注册不走显式 HTTP 写路径，本地实例集合是唯一事实源：

1. `RegistryClient.register()` → 先 HTTP `unregister` 清除 server 端旧租约 → 实例并入本地 `AtomicReference<Set<Instance>>`（`artemis-client/.../registry/InstanceRepository.java#register`）。
2. 真正的注册通道是 WebSocket 心跳：`InstanceRegistry` 建连 `/websocket/registry/heartbeat`，每 5s 将**全量本地实例**作为 `HeartbeatRequest` 发给 server，server 以此完成注册 + 续约（`InstanceRegistry#sendHeartbeat`）。
3. 心跳响应携带 `failedInstances`；对 `data-not-found` 的实例客户端自动走 HTTP `/api/registry/register.json` 补注册。
4. 幂等对账语义：server 恢复后 WS 重连 → 心跳全量上报 = 自动重注册，天然具备「断网续注册」能力。

服务端注册表（纯内存，零持久化，`artemis-service/.../registry/RegistryRepository.java`）：

- `_services: ConcurrentHashMap<serviceId, Service>` + `_leases: ConcurrentHashMap<serviceId, ConcurrentHashMap<instanceId, Lease<Instance>>>` + `_instanceChangeSet: ConcurrentSkipListSet<InstanceChange>`（按 changeTime 排序，容量 10k 满则挤掉最老）。
- **双租约池**：普通实例 TTL 20s；legacy 实例（`metadata.java_registry` 非空）TTL 90s（发布配置值，代码默认两池同为 20s）——兼容老客户端的过渡设计。

HTTP 注册 API 均为批量（`instances[]` 入参 + `failedInstances[]` 部分失败语义）：register.json / heartbeat.json / unregister.json（`RegistryController.java`）。

### 2.3 心跳与实例剔除

- 客户端：心跳间隔 5s（可配 500ms–5min）；检查线程每 1s 跑，心跳响应超时 ≥ instance-ttl（默认 20s）即标记连接不可用并重建。
- 服务端：`LeaseManager` 清理线程（2 线程，每 1s，可配）全表扫描，`now > renewalTime + ttl` 判过期，逐 lease tryLock 摘除 → 发 DELETE 变更事件。
- **自我保护（Eureka 式）**：`LeaseUpdateSafeChecker` 滑动窗口（默认 10s）统计续约量，低于历史 maxCount 的 85%（maxCount ≥ 50 才启用）即进入保护态，跳过所有「仅过期未显式 evict」的清理——大面积心跳丢失（网络故障）时不批量摘实例；显式 unregister 不受保护。统计暴露于 `/api/status/leases.json`。
- **无服务端主动健康探测**：`healthCheckUrl` 字段服务端全程零读取，纯透传；实例 status 由客户端自报。

### 2.4 服务发现：推送为主 + 多层兜底

**服务端**（`DiscoveryController.java` + `discovery/notify/NotificationCenter.java`）：

- `lookup.json`：批量实时查询（一次查多服务），直读内存 + 过滤器链（`DiscoveryFilter` SPI，分组路由/管理摘除均为过滤器实现）。
- `services.json`：全量服务列表，读**版本化缓存**——后台每 30s 生成全量快照（version=毫秒时间戳），保留最近 3 份（`VersionedCacheManager`）。
- `services-delta.json`：按 version 增量拉取，预计算版本间差集；version 过旧返回 DATA_NOT_FOUND 逼客户端全量。**注意：此接口客户端实际未使用**（客户端增量完全靠 WS 推送）。
- WS 推送：客户端发 `DiscoveryConfig` 订阅单服务（`/websocket/discovery/instance-change`），`NotificationCenter` 10 worker 线程从变更跳表逐条推送，亚秒级；另有全服务广播通道 all-instance-change。

**客户端**（`discovery/ServiceDiscovery.java` + `ServiceRepository.java`）：

- 首次按需同步 lookup → 内存缓存（`ConcurrentHashMap<serviceId, ServiceContext>`）→ WS 订阅推送增量（new/delete/change 原地更新 ⚠ 勘误 §8-3：CHANGE 类型无服务端产生点；reload 触发全量重拉）。
- 三层兜底轮询（60s 周期）：① 上次 reload 失败的服务；② 实例列表为空的服务；③ 距上次全量刷新超 TTL（默认 15min）的全部服务——批量 lookup 拉取。
- 缓存**永不失效**：server 全挂时 `getService` 继续返回内存最后一份快照（接受陈旧换可用）。
- 变更回调：`ServiceChangeListener`，单线程 executor 异步通知，事件携带克隆后的全量 Service。

**就近访问**：客户端在 lookup / up-nodes 请求中上报自身 regionId/zoneId；服务端默认仅接受同 zone 请求（`RegistryTool#checkSameZone`，可配放开）；节点返回同 zone 优先的节点列表（⚠ 勘误 §8-2：实为过滤，无排序）。实际选址由宿主 RPC 框架按 RouteRule strategy（close-by-visit）执行——**客户端不内置负载均衡器**。

### 2.5 流量治理能力（管理面特色，携程差异化价值）

这是 Artemis 区别于通用注册中心的核心产品能力，全部实现在 artemis-management + 发现过滤器链：

| 能力 | 机制 | 证据 |
|---|---|---|
| **服务分组** | ServiceGroup：按 groupKey 组织实例，带 weight / metadata / tag；实例经 `groupId` 挂组或手工绑定 | `GroupRepository.java`、`group/` 包 |
| **命名路由规则** | RouteRule（name + strategy + status active/inactive）组织多分组加权路由；`create-route-rule` 一步建规则+组+绑定 | `RouteRule.java`、`BusinessDao#createServiceRouteRules` |
| **两段式灰度发布** | 权重双列：编辑写 `unreleased_weight`，调 `release-route-rule-groups.json` 才拷贝到 `weight` 生效——分批灰度语义 | `RouteRuleGroupDao.java` L122-125 |
| **一键 Canary** | 传 serviceId+appId+IP 列表，自动生成 canary 专属 RouteRule + Group + 实例绑定 | `canary/CanaryServiceImpl#updateCanaryIPs` |
| **逻辑实例（静态实例）** | 维护不经过注册中心的实例（完整 ip/port/protocol/url/metadata），进发现结果 `logicInstances`——托管非 Java/第三方系统 | `GroupRepository#getServiceInstances`、`GroupDiscoveryFilter` |
| **灰度元数据路由** | `DiscoveryConfig.discoveryData` 约定 key（appid/subenv）随 lookup 上送参与服务端筛选（⚠ 勘误 §8-1：全链路零消费，纯协议预留） | `util/DiscoveryConfigs.java` |

分组路由的生效路径：写管理 DB → 各节点 `DynamicScheduledThread` 定时（Group/Zone 默认 5s、Management 默认 1s）重刷内存缓存 → diff 变化 → 推送 `InstanceChange` 通知订阅方。发现时 `GroupDiscoveryFilter` 将路由规则展开注入 `logicInstances` 与 `routeRules`。

### 2.6 运维管控能力

**四级摘除级联**：instance → server（物理机，serverId=IP）→ zone（机房级）→ group（组级），`ManagementRepository#isInstanceDown` 四级级联判定 + `SearchTree` 按 groupKey 级联匹配。摘除不删注册数据，只加一条「下线操作」记录（可叠加多条、各带原因、可审计），恢复即删记录——**操作记录即状态**。

**操作面**（57 个 API，5 组前缀，REST 由 artemis-server 的 5 个 Controller 暴露，逻辑在 artemis-management）：

- `/api/management/`：实例/server 上下线（operate-instance/server、instance-down/server-down 判定、operations 查询）、服务查询
- `/api/management/group/`：32 个——group、group-tag、group-operation（组级摘除）、route-rule、route-rule-group、group-instance、service-instance 七族的 CRUD + operate + create/release
- `/api/management/zone/`：5 个 zone 级摘除
- `/api/management/canary/`：1 个
- `/api/management/log/`：9 类审计日志查询（⚠ 勘误 §8-10：实为单快照（删前/写后），instance/server 日志无实体快照无 reason；过滤字段仅业务键/operation/operatorId/complete，token/reason/时间段不可过滤）

**审计**：所有变更双写 `*_log` 表（20 张表 = 10 业务 + 10 日志，`artemis-package/deployment/artemis-management.sql` 336 行）。

**节点运维**：`/api/status/` 7 个端点（node/cluster/leases/legacy-leases/config/deployment/ws-connection）；本机 IP 粒度 force-up/down 开关 6 个（`NodeManager`）。

**限制**：无控制台 UI（携程内部 console 未开源，本仓库 0 前端文件）；无分页无模糊搜索（getAll* 全量返回）；管理写操作要求当前节点 UP（`ServiceNodeUtil.checkCurrentNode`）；**操作上下文 `OperationContext` 的 token 只存不验**。

### 2.7 集群成员与数据同步（分布式核心）

**成员发现：静态配置，无自动发现协议。**

- 集群拓扑来自配置 `artemis.service.cluster.nodes`（zoneId→urls multimap），SCF 配置 + `ClusterChangeListener`（⚠ 勘误 §8-13：发布形态下配置变更需重启，非热更）。
- 节点存活：单线程定时任务每 5s 逐个调 peer `/api/status/node.json`（3 次重试）写 volatile 状态表。
- 本节点识别：「URL 包含本机 ip:port」子串匹配（脆弱）。

**复制协议：对等全对全异步广播，无 leader，region 内全量复制。**

- 消息仅 3 种：RegisterTask / UnregisterTask / HeartbeatTask，payload = Instance 列表。
- 触发：本节点每次成功的客户端写操作，业务线程 `replicate(task)` 入队即返回（写延迟不受 peer 影响）。
- 双通道：心跳走批量通道（默认 250 条/批、2s 最大批延迟，`BatchingTaskAcceptor`）；register/unregister 走单条通道。
- 防重：taskId = taskClass + InstanceKey + serviceUrl，drainAccept 时 HashMap 去重（心跳天然幂等合并）。
- 防丢：网络失败/UNKNOWN/RATE_LIMITED → 重试队列且插队队首（⚠ 勘误 §8-9：批量通道存在重试截断缺陷，同批仅首个可重试任务被重试）；心跳复制 socket timeout 仅 200ms 快速失败。
- **过期即丢**：任务 TTL 默认 5s，出队时丢弃——刻意设计，一致性靠下一轮心跳收敛。
- 背压：`TrafficShaper` 按错误码退避（⚠ 勘误 §8-8：默认空 map，无退避）；缓冲区满（10k）丢最老整批。
- 冲突处理：register 直接 put 覆盖；清理与并发重注册用 creationTime 新旧比较保护新租约；复制心跳遇缺失实例自动补注册。无脑裂检测。

**冷启动与启动门控（readiness 协议）**：节点启动时 daemon 线程每 1s 循环——REGISTRY 目标要求从任一 UP peer 全量拉取 `services.json` 并重建租约；DISCOVERY 目标要求管理 DB 缓存刷新成功。两者通过才置 `canServiceRegistry/canServiceDiscovery=true`，对应 API 才放行——**数据不全的节点不接流量**。

**CAP/多机房**：region 内 AP 最终一致，跨 region 零同步（多 region = 多独立集群）；zone 是写入准入单位而非数据局部性单位（复制仍是 region 全量）。

### 2.8 客户端 SDK 能力

- 接入：纯 Java API（`ArtemisClientManager` 静态按 managerId 单例），**无 Spring Boot starter、无自动装配**；配置源与指标实现由宿主注入（SCF StringProperties + caravan metric，默认 Null）。
- 部署身份：region.id / zone.id / app.id / app.port / app.protocol / app.path（application.properties），IP/主机名自动探测。
- **三级地址容灾**：引导地址 `.service.domain.url` → 周期（5min）拉 `/api/cluster/up-*-nodes.json` 存活节点列表 → 随机选址 + `markUnavailable` 熔单点 + 1h TTL 强制轮换；列表拉不到降级回引导地址（⚠ 勘误 §8-5：拉取失败实为保留旧列表，仅列表为空才降级）。
- WS 生命周期：健康检查线程 1s 周期（ping/pong 1s 超时）、会话 5min 强制重建（防漂移到坏节点）、重连限流 5 次/20s（防重连风暴）、重连成功自动重订阅。
- 统一 HTTP 执行器：固定 5 次重试 × 100ms 间隔 + 错误码语义决策（`ErrorCodes` 是唯一事实源：rate-limited/unknown 可重试；internal-service-error/service-unavailable → 摘节点换节点）+ gzip。
- 主要配置项（前缀 `artemis.client.{managerId}`；⚠ 勘误 §8-13：动态更新为**源/声明/读取时机三层**，默认静态源形态下不生效）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `.service.domain.url` | 必配 | 集群引导地址 |
| `.registry/.discovery.http-client.retry-times/interval` | 5 / 100ms | HTTP 重试 |
| `.instance-registry.heartbeat-interval` | 5s | 心跳间隔 |
| `.instance-registry.instance-ttl` | 20s | 心跳超时判连接不可用 |
| `.service-discovery.ttl` | 15min | 全量刷新周期 |
| `.websocket-session.ttl / connect-timeout / ping-timeout` | 5min / 5s / 1s | WS 会话参数 |
| `.websocket-session.reconnect-times` | 5 次/20s | 重连限流 |
| `.address.context-ttl` | 1h | 节点上下文强制轮换 |

- 线程模型：每 manager 固定 7+ 线程（⚠ 勘误 §8-7：回调池线程 non-daemon 且无 shutdown；2 地址刷新 + 2 WS 健康检查 + 1 心跳检查 + 1 兜底轮询 + 1 回调池）。

---

## 3. 非功能性产品能力

### 3.1 可扩展性（支撑 10 万+ 实例的手段与上限）

**做到的**：
- 内存两级 ConcurrentHashMap，读路径近无锁；初始容量可配（默认 1 万、上限 10 万——容量目标直接写在配置范围里）。
- 全量批量化：注册/心跳/注销 API 批量、心跳复制批量（250/批）、客户端心跳单消息携带全量实例（N 实例摊薄为 1 条消息）、批量 lookup。
- 读容量水平扩展：每节点全量持有 region 数据，加节点即扩读。
- 版本化全量缓存：把 O(全量) 的 services.json 组装从请求路径挪到后台 30s 定时。
- 分级限流：registry 100k QPS / replication 1M / status 30 / 管理 GroupService 30 QPS（caravan RateLimiter，超限返回 RATE_LIMITED 而非挂死）。

**上限约束**：
- **全对全复制写放大**：每写复制 N-1 次。10 万实例 × TTL 20s ≈ 5k 心跳/s 原生流量 ×(N-1) peer——region 节点数与实例数乘积是容量天花板，写容量不随节点数扩展。
- `LeaseManager#clean` 每 1s 全表扫描（10 万级可接受，百万级需分片/时间轮）。
- 节点状态探测串行 HTTP，节点多时 5s 周期可能跑不完。

### 3.2 可用性与容灾

**服务端**：对等无 leader，任一节点可读写；节点故障客户端自动换节点；故障节点恢复靠冷启动全量拉取追平；启动门控保证空数据节点不接流量；管理面读写可人工 force-up/down 干预。

**客户端**：内存缓存永不失效（server 全挂仍可发现）；注册幂等对账（断网/重启后自动重注册）；三级地址容灾；错误码驱动的节点熔断。

**弱点**：注册数据零持久化——全集群同时重启无解，依赖客户端风暴式重注册；客户端无本地快照文件——server 全挂 + 客户端重启 = 发现完全不可用（对比 Eureka/Nacos 均有磁盘 snapshot）；冷启动依赖至少一个存活 peer。

### 3.3 一致性模型

- **最终一致（AP）**，无 quorum、无事务、无版本向量、无 Raft。
- 收敛路径：覆盖写 + 复制重试 + 任务 TTL 过期丢弃 + 客户端心跳自愈 + 重启全量拉取 + 客户端 15min 全量兜底。
- 客户端可见的收敛上限：正常推送亚秒级；漏推场景最久 15min（全量刷新 TTL）才纠正；无数据版本/世代概念，无法量化「落后多少」。
- 管理面：共享 DB + 各节点 5s 定时刷缓存，写后 `waitForPeerSync()` 直接 `Thread.sleep(2s)`——写成功 ≠ 全网生效，存在秒级窗口。
- 无脑裂检测：分区时两侧各自接受写，恢复后靠覆盖 + TTL 收敛。

### 3.4 性能设计

批量（API/复制/lookup）、异步（复制全程异步、推送 10 worker、缓存后台刷新）、gzip 全链路、细粒度锁（Lease 级 tryLock、volatile 整体换新）。

代价点：客户端每次 `getService` 壳克隆 + 全量重建路由 Map（⚠ 勘误 §8-4：Instance 元素引用共享，非深克隆；O(实例数)，大服务高频读有 CPU/GC 压力）；变更回调单线程无界队列；大小写不敏感 equals/hashCode 每次比较都拼串 + toLowerCase（隐性热点）；WS 文本缓冲默认 8KB（上限 32KB）与大规模叙事不匹配。

### 3.5 可观测性

- **开源版为空壳**：`MetricLoggerHelper` 全部方法体为空；metric/trace 默认 NullProvider（内网接 caravan 体系，开源时掏空）。需配置 `artemis.metric.default.managers-provider` 指向自实现才生效。
- 实际可观测性主力是状态 API：config.json（全部配置+来源，排障利器）、leases.json（租约明细+自我保护统计）、cluster.json、deployment.json、ws-connection 数。
- 审计：管理操作全量双写 log 表。
- 无 metrics 大盘、无 trace、无告警。

### 3.6 安全

**几乎为零**：全部 API 无认证鉴权（ErrorCodes 定义了 no-permission 但无实现）；明文 http/ws 无 TLS 配置点；管理写接口（摘除实例、改路由权重）同样裸奔；唯一屏障是 WS IP 黑名单（`WsIPBlackList.java`）与 region/zone 软隔离；DB 密码明文（data-source.properties: admin/123456）；管理面 OperationContext.token 只存不验。

### 3.7 部署与工程实践

- 形态：Spring Boot fat jar（内嵌 Tomcat）或 WAR 到外部 Tomcat；配置三件套 application/artemis/data-source.properties；管理面可关（`artemis.management.enabled=false` 时零外部依赖）。
- 外部依赖：仅管理面 DB（MySQL，2026-03 起可切 SQLite 单文件）——注册/发现核心无 MQ、无缓存、无其他服务依赖。
- **缺失**：无 Dockerfile、无 K8s manifest、无 CI/CD、无覆盖率/静态分析；测试仅覆盖 management DAO 层（57 @Test，SQLite 内存库进程内起服），核心的复制/推送/租约/故障转移零测试。
- 技术栈定格 2020-12：Java 8、Spring Boot 2.3.6（EOL）、javax 命名空间、springfox 3.0.0（已死项目）、HttpClient 4.x、Jackson 2.12、MySQL Connector 5.1.49、JUnit 4.13 + Mockito 1.10。

### 3.8 兼容性

仅 Java 8 SDK；绑定 org.mydotey.* 自研库栈（scf 配置 / rpc-util HTTP / caravan 线程限流指标 / codec / lang-extension）——勘误 §8-14：这些库**均已发布 Maven Central** 且版本覆盖原产品所用，可解析获取，非私有不可得；无多语言客户端；双租约池（20s/90s）是内部老客户端兼容包袱。

---

## 4. 宣称 vs 事实对照（以 Readme 为输入的决策需先对齐）

| Readme 宣称 | 代码事实 | 判定 |
|---|---|---|
| Artemis 2 "use gRpc instead of websocket" | 全仓库 grep `grpc` 零命中；心跳/推送长连全在 WebSocket（9 个 server WS handler） | **未兑现** |
| Artemis 2 "registry/discovery/management separation" | Maven 模块/包级分离成立；部署仍是单一 Spring Boot 应用，management 仅运行时开关 | 部分兑现（逻辑分离非部署分离） |
| 实例变更实时推送 | WS 推送亚秒级，代码完整 | 可证实 |
| 分组路由、拉入拉出管理 | 完整实现且是产品差异化能力 | 可证实 |
| 部署章节指向 `artemis-web/deployment/...` | 模块 2020-12 已改名 artemis-server，路径全部过期 | 文档漂移 |
| "use scf instead of caravan config" | 配置已全面基于 scf | 可证实 |
| wiki 链接的产品介绍 | 仓库内无对应内容 | 不可验证 |

---

## 5. 值得继承的设计资产（重设计正输入）

1. **「心跳即注册」全量幂等对账模型**——本地实例集为唯一事实源，心跳=全量状态同步，断网/重启/server 恢复后自动对账，免显式重注册状态机。经过 10 万级验证的核心资产。
2. **三级地址容灾管线**——引导地址 → 存活节点列表 → 随机选址 + 节点熔断 + TTL 强制轮换，无中心 LB 实现客户端侧负载均衡。
3. **错误码驱动的统一容错语义**——`ErrorCodes` 静态划分 rerunnable / serviceDown，作为客户端重试/摘节点/服务端分类的单一事实源。
4. **四级摘除级联 + 「操作记录即状态」**——instance→server→zone→group 级联判定；下线=一条可叠加、带原因、可审计的记录而非删数据。
5. **两段式权重发布**——weight/unreleased_weight + 显式 release，天然支持分批灰度。
6. **逻辑实例（静态实例）**——把非注册体系的服务（第三方/异构系统）纳入统一发现视图。
7. **发现过滤器链 SPI**——分组路由、管理摘除、通知过滤都是 `DiscoveryFilter`/NotificationFilter 实现，扩展点干净。
8. **三层兜底轮询**（推送丢失自愈：失败/空服务 60s + 全量 15min）与 WS 会话 TTL 强制轮换、重连限流。
9. **启动门控（readiness）**——全量同步未完成不接流量，防止空数据节点服务发现。
10. **自我保护机制**——续约量滑动窗口阈值保护，网络抖动不误摘（语义值得继承，粒度需改进）。
11. **批量贯穿一切**——API、心跳、复制、lookup 全批量，是 10 万实例的立身之本。
12. **状态 API 即排障面**——config.json（配置+来源）、leases.json（含保护统计）这类运维端点设计。

## 6. 局限与现代化清单（重设计负输入，按域分组)

**传输与协议**
1. WebSocket + JSON 文本推送，无 gRPC/HTTP2 流；WS 缓冲 8KB 上限 32KB，大服务消息可能截断。
2. 同步阻塞 HttpClient 4 + 固定间隔重试（无指数退避、无抖动）；错误模型是无类型 RuntimeException + 字符串状态码。
3. delta 机制设计而未用：services-delta 接口 + 版本化缓存在，客户端却只靠 WS 单条推送；无数据版本/世代暴露。

**一致性与容量**
4. 全对全复制写放大 N 倍，无分片/分层（对比 Nacos Distro、Consul Raft）；best-effort 复制无确认闭环、无水位比对。
5. 注册数据零持久化，全集群重启依赖客户端风暴重注册。
6. 客户端无磁盘快照，重启冷启动。
7. delta 窗口仅 3 版本×30s≈90s；version 用本地毫秒时间戳无单调性。
8. 管理面同步靠 sleep(2s) + 5s 轮询，无生效确认。
9. 自我保护全局单阈值，无法区分局部异常与网络故障。
10. 静态集群成员 + URL 子串匹配识别本机；扩缩容改配置。

**客户端工程**
11. 无生命周期 API（无 close()、无 shutdown hook、无法优雅下线）；register() 语义隐晦（实际靠心跳通道生效，WS 起不来则不可发现且消费方不可见）。
12. 每 manager 7+ 线程线性放大；discovery/registry 两套连接设施完全重复。
13. 回调单线程无界队列（一个慢消费者拖垮全部通知）；getService 深克隆 + 重建路由。
14. 无 Spring Boot starter / 多语言 SDK / 自动配置；org.mydotey.* 依赖栈已发布 Maven Central（勘误 §8-14），但属自研小众库，生态与文档面窄。
15. 缓存数据无新鲜度元信息（消费方不知数据是否陈旧/降级）。

**管理面产品**
16. 无 UI、无分页、无模糊搜索——10 万实例下服务列表/日志查询不可用。
17. 表驱动 API（57 个细粒度端点 = DB 表直透，灰度发布需前端串 5 个 API）。
18. 无鉴权/RBAC/审批流；token 不验；实例 metadata 不可改；无订阅关系/调用方视图；无变更 diff 预览与回滚。

**安全**
19. 全链路无认证、无 TLS、DB 明文密码——开源形态不可上生产。

**可观测性与工程**
20. metric/trace 空壳；无大盘、无告警。
21. 核心路径（复制/推送/租约/故障转移）零测试；无 CI、无容器化、无 K8s。
22. 依赖栈整体 EOL（Java 8 / Boot 2.3 / javax / springfox / HttpClient 4 / MySQL 驱动 5.1 / JUnit4）。
23. 代码时代痕迹：可变 POJO + 混杂命名（_regionId/regionId）、字符串常量代枚举、Map<Service,...> 对象键、死代码（RegistryServiceClient、CLUSTER_NODES 死常量）、打包配置键拼写错误（replicaton）未被发现、GetServiceRequest 构造函数字段互换 bug、SQLite 分支 System.out.println。

---

## 7. 附录：证据索引

关键文件（相对原仓库根）：

- 数据模型：`artemis-common/src/main/java/org/mydotey/artemis/{Instance,InstanceKey,InstanceChange,Service,ServiceGroup,RouteRule,Region,Zone}.java`、`ErrorCodes.java`
- 客户端：`artemis-client/src/main/java/org/mydotey/artemis/client/`（`ArtemisClientManager`、`common/AddressRepository|AddressManager|ArtemisHttpClient`、`registry/InstanceRegistry|InstanceRepository`、`discovery/ServiceDiscovery|ServiceRepository`、`websocket/WebSocketSessionContext`）
- 服务端核心：`artemis-service/src/main/java/org/mydotey/artemis/`（`registry/RegistryRepository|RegistryServiceImpl|RegistryTool`、`registry/replication/*`、`cluster/{ClusterManager,NodeManager,RegistryReplicationInitializer}`、`discovery/notify/NotificationCenter`、`cache/VersionedCacheManager`）
- 租约：`artemis-common/src/main/java/org/mydotey/artemis/lease/{Lease,LeaseManager,LeaseUpdateSafeChecker}.java`
- 任务分发：`artemis-common/src/main/java/org/mydotey/artemis/taskdispatcher/`
- 接入层：`artemis-server/src/main/java/org/mydotey/artemis/server/`（Controller 11 个 = `rest/controller/` 10 个 + `websocket/WsStatusController.java`；另 `websocket/` 包 9 类含 `*WsHandler`）
- 管理面：`artemis-management/src/main/java/org/mydotey/artemis/management/`（`config/RestPaths.java`、`ManagementServiceImpl|ManagementRepository`、`GroupServiceImpl|GroupRepository`、`canary/CanaryServiceImpl`、`group/dao/BusinessDao|RouteRuleGroupDao`、`common/OperationContext`）
- 配置与部署：`artemis-common/.../config/{ArtemisConfig,DeploymentConfig,RestPaths,WebSocketPaths}.java`、`artemis-package/src/main/resources/*`、`artemis-package/deployment/artemis-management.sql`、根目录 `SQLITE_SETUP.md`
- 测试：`artemis-test/src/test/java/.../test/ArtemisTest.java`（进程内起服基础设施）

---

## 8. 勘误与增补（2026-10-08 规格层补证）

规格层文档集（`domains/`、`product-overview.md`、`nfr-spec.md`）成稿过程中对原仓库做了六域行为级补证，以下基线原表述与代码事实不符，**以本节与域文档为准**；逐条证据见 [product-overview.md](product-overview.md) §5：

| # | 基线位置 | 原表述 | 代码事实 |
|---|---|---|---|
| 1 | §2.5 | discoveryData（appid/subenv）随 lookup 上送参与服务端筛选 | 纯协议预留，全链路零消费 |
| 2 | §2.4 | up-nodes 返回同 zone 优先的节点列表 | 过滤，无排序 |
| 3 | §2.4 | new/delete/change 原地更新 | CHANGE 类型无服务端产生点 |
| 4 | §3.4 | 每次 getService 深克隆 | List 壳复制 + Instance 引用共享 + RouteRules 重建 |
| 5 | §2.8 | 节点列表拉不到降级回引导地址 | 失败保留旧列表，仅列表为空才降级 |
| 6 | §2.8 | 客户端配置均可热更 | 缺源层前提（静态配置源不发事件）；另 WS buffer-size 为构造快照。详见 §8-13 |
| 7 | §2.8 | 每 manager 7+ daemon 线程 | 回调 executor 线程 non-daemon（无 shutdown，阻止 JVM 退出） |
| 8 | §2.7 | TrafficShaper 按错误码退避（默认 10ms） | 默认空 map = 无退避 |
| 9 | §2.7 | 失败任务重试插队队首 | 批量通道存在重试截断缺陷（一批仅首个可重试任务被重试） |
| 10 | §2.6 | 审计 log 行含操作前后数据快照；可按 token/reason/时间段过滤 | 单快照（删前/写后）；instance/server 日志无快照无 reason；token/reason/时间段不可过滤 |
| 11 | features §3.2（基线未述及） | destroyServers 按 ServerKey 批量物理删除 | 死代码（无端点无调用方），instance 侧删除条件错位 |
| 12 | §2.5 | 管理面统一 5s 重刷 | 两级：instance/server 摘除缓存 1s、group/zone 5s |
| 13 | §2.8、§3.7 | 配置「全热更」（改造点仅部署身份） | 缺**源层前提**：产品属性声明层全部可动态更新（scf `PropertyConfig.isStatic` 默认 false，原产品未使用该标志），但**配置源由宿主注入**——默认三件套为静态源故不生效；使用层另有构造快照键。详见 config-reference §0 |
| 14 | §3.8、§6.14 | org.mydotey.* 私有依赖链「均不在 Maven Central，外部落地必须连号移植」 | **误判**：scf / rpc / caravan / codec / lang-extension / circular-buffer 均已发布 Maven Central 且版本覆盖原产品所用（2026-10 实测 repo1.maven.org：scf-core 1.6.3→latest 1.6.4、caravan-util 2.0.1→2.0.3、jackson-codec-util 1.1.0、lang-extension 1.2.0→1.2.1、circular-buffer 1.0.0、http-rpc-util 已发布），外部可解析获取；易用性缺口在 starter/多语言/生命周期 API，非依赖可得性 |

增补（§6 局限清单之外的**新发现缺陷**，约 20 条，含大小写键语义分裂导致永不清理的发现残留、冷启动空集群死锁、UP 无回退 / DOWN 粘性、zone 摘除推送盲区、合成事件 region 不匹配、SQLite 分支破坏两段式发布、摘除过滤不作用于路由视图成员、WebSocketContainer 全局单例跨 manager 污染等）：汇总索引见 [product-overview.md](product-overview.md) §6，行级证据见各域 `domains/*-logic.md` §7。这些缺陷是重设计负输入的组成部分，与 §6 合并使用。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-09 | §1 规模数字定性更新：10 万+ 实例为作者确认的前公司生产实绩（原「口头历史」） |
| 1.2 | 2026-10-09 | §8 勘误 #14：org.mydotey.* 依赖已发布 Maven Central（修正 §3.8/§6.14「私有不可得」误判） |
| 1.1 | 2026-10-08 | §8 勘误与增补 |
| 1.0 | 2026-10-07 | 初版 |
