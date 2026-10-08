# Artemis 原产品模块功能列表（Legacy Feature Inventory）

状态: 草案  日期: 2026-10-07

> 调研对象：`~/Projects/mydotey/artemis`（version 2.0.2）。本文档为 `docs/legacy/legacy-product-analysis.md`（能力基线，下称「基线」）的下钻层：基线已确立的结论直接引用（格式：基线 §x.y），本文档补充基线未覆盖的细粒度事实——REST/WebSocket 端点全量清单、管理面 API 分组、客户端公开 API、配置项及默认值、行为细节。全部结论以原仓库源码为证据（原仓库 Readme 不作为依据）；证据路径均相对原仓库根并标注「原仓库」。
> API 清单唯一事实源：`artemis-common/src/main/java/org/mydotey/artemis/config/RestPaths.java`、`.../config/WebSocketPaths.java`（原仓库）与 `artemis-management/src/main/java/org/mydotey/artemis/management/config/RestPaths.java`（原仓库）；`CONTEXT_PATH = "/"`（`.../config/ArtemisPaths.java`，原仓库，2026-03 统一为 `/api/` 前缀时引入）。
> 模块视图（职责/依赖链/Spring 依赖）见 arch.md §2，规模数字见基线 §1，本文不重复。

---

---

## 1. artemis-common（基础库）

### 1.1 核心数据模型（基线 §2.1 已确立，此处补字段级证据）

| 实体 | 行为细节 | 证据（原仓库） |
|---|---|---|
| `Instance` | 13 字段可变 POJO；equals/hashCode 委托 InstanceKey（大小写不敏感）；`clone()` 供深拷贝 | `artemis-common/src/main/java/org/mydotey/artemis/Instance.java` |
| `InstanceKey` | regionId.serviceId.instanceId 三元组唯一标识，`InstanceKey.of(instance)` 工厂 | `.../InstanceKey.java` |
| `InstanceChange` | instance + changeType + changeTime；ChangeType 常量 `new/delete/change/reload` | `.../InstanceChange.java` |
| `Service` | serviceId/metadata/instances/logicInstances/routeRules；后两者为发现时注入的派生视图 | `.../Service.java` |
| `ServiceGroup` / `RouteRule` | groupKey/weight/instanceIds/metadata；RouteRule= routeId+strategy+groups。策略常量：`weighted-round-robin`、`close-by-visit` | `.../ServiceGroup.java`、`.../RouteRule.java`（`RouteRule.Strategy`） |
| `Region` / `Zone` / `ServerKey` | region→zones 层级；ServerKey= regionId+serverId（serverId=IP） | `.../Region.java`、`.../Zone.java`、`.../ServerKey.java` |
| `ResponseStatus` / `HasResponseStatus` | 所有响应统一携带 ResponseStatus（status + errorCode + message） | `.../ResponseStatus.java` |

**保留路由规则与权重语义**（`.../util/RouteRules.java`、`.../util/ServiceGroups.java`，原仓库）：

- 保留规则名：`DEFAULT_ROUTE_RULE = "default-route-rule"`、`CANARY_ROUTE_RULE = "canary-route-rule"`；`isDefaultRouteRule()/isCanaryRouteRule()` 大小写不敏感比较。
- 权重：`MIN_WEIGHT_VALUE=0`、`MAX_WEIGHT_VALUE=10000`、**`DEFAULT_WEIGHT_VALUE=5`**；`ServiceGroups.fixWeight()` 对 null 或负值返回默认值、超上限截断为 10000。
  - 注意：基线 §2.1 初版误记「默认 5000」，已修订为 5（源码 `DEFAULT_WEIGHT_VALUE = 5`，见附录勘误）。
- 默认分组：`DEFAULT_GROUP_ID = "default"`，`isDefaultGroupId()` 对空白也返回 true。
- reload 伪装实例：`InstanceChanges.RELOAD_FAKE_INSTANCE_ID="reload"`、`RELOAD_FAKE_IP="0.0.0.0"`、`RELOAD_FAKE_URL="http://serviceId/reload"`；`newReloadInstanceChange(serviceId)` 生成「全量重拉」事件（`.../util/InstanceChanges.java`）。

### 1.2 配置基座（scf 管线）

| 功能 | 行为 | 证据（原仓库） |
|---|---|---|
| ArtemisConfig | 服务端/通用配置管理器：源优先级 = 环境变量 → system properties → `artemis-{env}.properties` → `artemis.properties`，再按本机 IP 做 cascaded；提供 `getListMultimapProperty`（zoneId→urls 用） | `.../config/ArtemisConfig.java`（static 块） |
| DeploymentConfig | 部署身份：`region.id` / `zone.id` / `app.id` / `app.port`（默认 8080，1–65535 校验）/ `app.protocol`（默认 http）/ `app.path`；`deployment.env` 系统属性选择 `application-{env}.properties`；IP 与主机名由 `NetworkInterfaceManager` 自动探测；值在类加载时快照为静态字段（**非热更**） | `.../config/DeploymentConfig.java` |
| RestPaths / WebSocketPaths | 全部 REST/WS 路径常量（见 §4 端点总表）；`CONTEXT_PATH="/"`。注意 `CLUSTER_NODES_RELATIVE_PATH`（`cluster/nodes.json`）常量已定义但**无任何 Controller 引用，是死常量**（全仓库 grep 仅定义处命中） | `.../config/RestPaths.java`、`.../config/WebSocketPaths.java`、`.../config/ArtemisPaths.java` |
| ListMultimapConverter 等 | scf 类型转换器：String→ListMultimap、Map 值转换/纠正 | `.../config/ListMultimapConverter.java`、`MapValueConverter.java`、`MapValueCorrector.java` |

### 1.3 租约（lease）

| 功能 | 行为 | 证据（原仓库） |
|---|---|---|
| `Lease<T>` | creationTime / renewalTime / evictionTime / ttl（Property 动态读）；`renew()` 带.tryLock、过期后拒绝续约并 `markUpdate()` 计数；`evict()` 只置 evictionTime（幂等）；`isExpired() = now > renewalTime + ttl \|\| isEvicted()` | `.../lease/Lease.java` |
| `LeaseManager<T>` | 泛型租约池：`ConcurrentHashMap<T, Lease<T>>`；后台 ScheduledExecutorService 周期 `clean()`：全表扫描 → 逐 lease `tryLock` → 非 evicted 且过期且 safeChecker 通过才摘 → 摘除时若缓存中已是更新的租约（creationTime 更大）则放回（**保护并发重注册的新租约**）→ 回调 `LeaseCleanEventListener.onClean(cleaned)` | `.../lease/LeaseManager.java`（`clean()` L126-164） |
| `LeaseUpdateSafeChecker` | Eureka 式自我保护（基线 §2.3）：滑动窗口 CounterBuffer 统计续约量 `markUpdate()`；每秒 safeCheck：窗口计数 > 历史 maxCount 则更新 maxCount；`maxCount ≥ max-count-threshold` 且 `窗口计数*100/maxCount < percentage-threshold` 时置 `_isSafe=false`；maxCount 超过 reset-interval 未更新则用当前窗口值重置（防长期衰减）。指标：`{id}.event` 的 safe/unsafe 事件 | `.../lease/LeaseUpdateSafeChecker.java`（`safeCheck()` L147-185） |

LeaseManager 配置键（`{managerId}` 前缀，两个实例分别为 `artemis.service.registry.instance` 与 `artemis.service.registry.legacy-instance`）：

| 配置键后缀 | 默认 | 范围 |
|---|---|---|
| `.lease-manager.data.init-capacity` | 50000 | 10k–1M |
| `.lease-manager.clean-task.thread-count` | 2 | 1–10 |
| `.lease-manager.clean-task.init-delay` | 1000ms | 0–10s |
| `.lease-manager.clean-task.run-interval` | 1000ms | 100–5000ms |
| `.lease-manager.lease.ttl` | **20000ms（两个池代码默认相同）** | 10s–7d |
| `.lease-manager.lease-update-safe-checker.enabled` | true | — |
| `.lease-manager.lease-update-safe-checker.time-window` | 10000ms | 10s–5min |
| `.lease-manager.lease-update-safe-checker.percentage-threshold` | 85 | 50–100 |
| `.lease-manager.lease-update-safe-checker.max-count-threshold` | 50 | 0–1M |
| `.lease-manager.lease-update-safe-checker.max-count-reset-interval` | 10min | 1min–24h |

> 代码中 legacy 池 TTL 默认同样是 20s；**90s 来自发布配置** `artemis.properties` 的 `artemis.service.registry.legacy-instance.lease-manager.lease.ttl=90000`（`artemis-package/src/main/resources/artemis.properties`，原仓库）。legacy 判定：`metadata.java_registry` 非空（`RegistryRepository#isLegacyInstance`）。

### 1.4 任务分发（taskdispatcher，复制的基础设施）

| 类 | 职责 | 证据（原仓库） |
|---|---|---|
| `Task` / `AbstractReplicationTask` | taskId / submitTime / expiryTime / batchingEnabled / errorCode；`AbstractReplicationTask` 持 serviceUrl + submitTime + expiryTime | `.../taskdispatcher/Task.java`；`artemis-service/.../replication/AbstractReplicationTask.java` |
| `TaskAcceptor<T,W>` | 接收线程模型：`MultiWriteBatchReadList` 收新任务与重试任务 → `writeCompleteWait`(5ms) 等待写完成 → `drainAccept`：新任务按 taskId 放入 `_acceptedTasks`(HashMap) + `_processingOrder`(LinkedList)；同 taskId 覆盖并继承旧 submitTime（**去重合并**）；重试任务 `addFirst` 插队队首且 submitTime 置为队首-1（**优先处理**）→ `assignWork` 生成工作单元入 `_workQueue`；缓冲满（pendingTaskCount ≥ max-buffer-size）丢最老整批（buffer-full-dropped 事件） | `.../taskdispatcher/TaskAcceptor.java`（`drainAccept` L211-244、`assignWork`） |
| `BatchingTaskAcceptor` | 批量工作单元：凑够 `max-batching-size` 或首任务等待超 `max-batching-delay` 即成批；`filterExpiredTask` 出队时丢弃过期任务 | `.../taskdispatcher/BatchingTaskAcceptor.java` |
| `SingleItemTaskAcceptor` | 单条工作单元（register/unregister 复制通道） | `.../taskdispatcher/SingleItemTaskAcceptor.java` |
| `TaskExecutor<T,W>` | N 个 daemon 工作线程循环 `pollWork` → `TaskProcessor.process` → 失败任务中 `TaskErrorCode.RERUNNABLE_ERROR_CODES` 的 `reaccept`（并 `TrafficShaper.markFail`），不可重试的丢弃并 warn | `.../taskdispatcher/TaskExecutor.java`（`execute` L100-117） |
| `TrafficShaper` | 按错误码的发送退避：`markFail(errorCode)` 记录失败时刻；`transmissionDelay()` 若距最近失败 < fail-delay 则 sleep 剩余时间；fail-delay 可按错误码配置 map，缺省 10ms、上限 10s | `.../taskdispatcher/TrafficShaper.java` |
| `TaskErrorCode` | 任务级错误码枚举（含 RERUNNABLE 集合与 PermanentFail） | `.../taskdispatcher/TaskErrorCode.java` |

taskdispatcher 配置键（`{dispatcherId}.task-acceptor` / `.batching.task-acceptor` / `.task-executor` / `.traffic-shaper` 后缀）：

| 配置项 | 默认 | 范围 |
|---|---|---|
| `.task-acceptor.max-buffer-size` | 10000 | 100–100k |
| `.task-acceptor.write-complete-wait` | 5ms | 0–200ms |
| `.task-acceptor.accept-list.init-capacity` | 10000 | 0–100k |
| `.task-acceptor.reaccept-list.init-capacity` | 1000 | 0–100k |
| `.batching.task-acceptor.max-batching-size` | 250 | 10–10k |
| `.batching.task-acceptor.max-batching-delay` | 2000ms | 1s–10s |
| `.task-executor.thread-count` | 20 | 1–100 |
| `.traffic-shaper.fail-delay`（map：错误码→ms） | 单值缺省 10ms | ≤10s |

### 1.5 服务接口与消息模型（registry / discovery / cluster 三组）

- registry：`RegistryService`（register/heartbeat/unregister）+ 请求/响应对 `RegisterRequest/Response`、`HeartbeatRequest/Response`、`UnregisterRequest/Response`；批量语义接口 `HasInstances` / `HasFailedInstances` / `FailedInstance`（instance + errorCode + message）；`HeartbeatEvent(Listener)`（定义于 common，服务端注册表未使用）。证据：`.../registry/*.java`（原仓库）。
- discovery：`DiscoveryService`（lookup/getService/getServices/getServicesDelta）+ `DiscoveryConfig`（serviceId + discoveryData map，`DiscoveryConfig.GENERIC` 为通配配置）+ `DiscoveryFilter` SPI（`filter(Service, DiscoveryConfig)`）+ 请求/响应对。证据：`.../discovery/*.java`（原仓库）。
- cluster：`ClusterService`（getUpRegistryNodes/getUpDiscoveryNodes）+ `ServiceCluster`（见 §2.5）+ `ServiceNode`（zone + url）+ `ClusterChangeEvent/Listener`。证据：`.../cluster/*.java`（原仓库）。
- `ErrorCodes`（唯一错误码事实源，基线 §2.8）：`success/partial_fail/bad-request/rate-limited/no-permission/data-not-found/internal-service-error/service-unavailable/unknown`；rerunnable = {rate-limited, unknown}；serviceDown = {internal-service-error, service-unavailable}。`ResponseStatusUtil.isRerunnable()/isServiceDown()` 基于该集合判定。证据：`.../ErrorCodes.java`、`.../util/ResponseStatusUtil.java`（原仓库）。

### 1.6 通用 util

| 工具 | 职责 | 证据（原仓库） |
|---|---|---|
| `SearchTree<K,V>` | 级联键匹配树：`add(keys, value)` / `first(keys)` 沿 key 列表找首个非空值——group 级摘除按 `serviceId/regionId/zoneId/groupId/instanceId` 五级 groupKey 级联判定的数据结构 | `.../util/SearchTree.java` |
| `ServiceGroupKeys` | groupKey 构造/解析：`of(instance)` = serviceId/regionId/zoneId/(groupId→default)/instanceId 小写拼接；`toGroupIdList()` 反解 | `.../util/ServiceGroupKeys.java` |
| `ServiceGroups` | 权重/默认分组语义（见 §1.1）；`isLocalZone(serviceGroup)` 判断分组是否本 zone | `.../util/ServiceGroups.java` |
| `RouteRules` | 保留规则名、默认规则展开、`generateGroupInstances`（canary 组实例解析） | `.../util/RouteRules.java` |
| `DiscoveryConfigs` | 灰度元数据 key：`appid` / `subenv`，读写 `DiscoveryConfig.discoveryData`（基线 §2.5） | `.../util/DiscoveryConfigs.java` |
| `SameRegionChecker` / `SameZoneChecker` | region/zone 一致性校验（大小写不敏感） | `.../util/SameRegionChecker.java`、`SameZoneChecker.java` |
| checker 包 | `ValueCheckers`（notNull/notNullOrWhiteSpace 等）、`InstanceChecker`（serviceId/instanceId/regionId 非空）、`InstancesChecker`、`ServiceChecker`、`DiscoveryConfigChecker` | `.../checker/*.java`、`.../util/*Checker.java` |
| 其他 | `InstanceChangeComparator`（按 changeTime 排序，变更跳表用）、`StringUtil.toJson`、`Loops.executeWithoutTightLoop`、`RequestExecutor` | `.../util/` |

### 1.7 metric / trace（开源空壳，基线 §3.5）

- `MetricLoggerHelper`：全部方法体为空（logResponseEvent/logWebSocketEvent/logRegistryEvent/logSubscribeEvent/logPublishEvent/logWebSocketSessionCount）——调用点遍布 server/service，但无任何输出。证据：`.../metric/MetricLoggerHelper.java`（原仓库）。
- `ArtemisMetricManagers.DEFAULT`：由 `artemis.metric.default.managers-provider` 配置注入 provider，缺省 `NullArtemisMetricManagersProvider`。证据：`.../metric/ArtemisMetricManagers.java`、`NullArtemisMetricManagersProvider.java`。
- `ArtemisTraceExecutor` / `ArtemisTraceFactory`：trace 门面，受 `artemis.trace.enabled` 控制（`artemis.properties` 有该键）。证据：`.../trace/*.java`。

---

## 2. artemis-service（服务端内核）

### 2.1 注册表内存仓库 RegistryRepository

**功能**：region 内全量注册表的纯内存存储与变更事件源（零持久化，基线 §2.2）。

- 数据结构：`_services: ConcurrentHashMap<serviceId, Service>`、`_leases: ConcurrentHashMap<serviceId, ConcurrentHashMap<instanceId, Lease<Instance>>>`、`_instanceChangeSet: ConcurrentSkipListSet<InstanceChange>`（按 changeTime 排序；容量超 `max-buffer-size` 先尝试移除同对象否则 `pollFirst` 挤掉最老并 warn）。
- `register(instance)`：InstanceChecker + SameRegionChecker 校验 → put Service 占位 → 按 legacy 判定选择租约池 `register()` → 二级 map put → 发 `NEW` 变更事件。
- `heartbeat(instance)`：取租约 `renew()`，租约不存在返回 false（由上层转 failedInstance）。
- `unregister(instance)`：`lease.evict()`（只标 evictionTime，由清理线程真正摘除）。
- `onLeaseClean(cleaned)`：摘除时若现有租约比被清理的更新（creationTime 更大）则放回（并发重注册保护）；真正摘除的实例发 `DELETE` 事件；服务的实例清空后连 Service 一起移除。
- `pollInstanceChange()`：NotificationCenter 工作线程的阻塞消费入口（pollFirst + sleep(poll-wait)）。
- 查询族：`getService`（clone Service + 装配 instances）、`getServices`、`getInstances()`（全量 Map<InstanceKey,Instance>，供管理面 diff）、`getLeases(...)` 四个重载（按 serviceIds / 按 LeaseManager 过滤，供状态 API 与 legacy 池拆分）。
- 配置：`artemis.service.registry.data.init-capacity`（默认 10000，1k–100k）、`artemis.service.registry.data.instance-change.max-buffer-size`（默认 10000，1k–1M）、`artemis.service.registry.data.instance-change.poll-wait`（默认 20ms，1–30000ms）。

证据：`artemis-service/src/main/java/org/mydotey/artemis/registry/RegistryRepository.java`（原仓库）。

### 2.2 注册服务 RegistryServiceImpl（限流 + 复制触发）

**功能**：register/heartbeat/unregister 三个数据面写操作的编排：限流 → RegistryTool 批量执行 → 成功后同步触发复制。

- 限流：`ArtemisRateLimiterManager.getRateLimiter("artemis.service.registry", 默认 100000 QPS（1000–1M），10s 窗口/1s 桶)`；超限直接返回 `RATE_LIMITED`（按操作名细分计数）。
- register：逐实例 `_repository.register` + `RegistryReplicationManager.INSTANCE.replicate(new RegisterTask(instance))`。
- heartbeat：`renew()` 失败的实例以 errorCode=`data-not-found` 计入 failedInstances（客户端据此补注册）；成功则复制 HeartbeatTask。
- unregister：`repository.unregister` + 复制 UnregisterTask。
- 三个 API 均为批量：响应携带 `failedInstances[]` 部分失败语义（基线 §2.2）。

证据：`artemis-service/.../registry/RegistryServiceImpl.java`（原仓库）。

### 2.3 请求准入与执行框架 RegistryTool

**功能**：registry/replication 两类写请求的统一校验、逐实例执行、错误码归类。

校验顺序（`execute`）：

1. `checkRequest`：request/instances 非空 → 否则 `bad-request`。
2. `checkRegistryStatus(isReplication)`：非复制请求要求本节点 `canServiceRegistry`（启动门控，基线 §2.7）→ 否则 `service-unavailable`。复制请求跳过。
3. 逐实例 `checkSameZone(regionId, zoneId, isReplication)`：region 必须相同（否则 `no-permission`）；非复制请求 zone 必须相同，除非节点开 `allowRegistryFromOtherZone` → 否则 `no-permission`。
4. 业务执行异常 → `internal-service-error`；业务返回错误消息（如 heartbeat 未注册）→ `data-not-found`。
5. 全部成功 = success；部分失败 = partial_fail + failedInstances。

`replicationExecute` = isReplication=true 的变体（跳过 2、放 zone 校验）。证据：`artemis-service/.../registry/RegistryTool.java`（原仓库）。

### 2.4 注册数据复制（replication）

**功能**：对等全对全异步复制（基线 §2.7 已确立协议语义，此处补实现结构）。

| 组件 | 职责 | 证据（原仓库） |
|---|---|---|
| `ReplicationManager<T>` | 双通道分发框架：`replicate(task)` 按 `task.batchingEnabled()` 路由到 batching/single-item 两个 TaskDispatcher | `artemis-service/.../replication/ReplicationManager.java` |
| `RegistryReplicationManager` | 单例，managerId=`artemis.service.registry.replication`，装配两个 processor | `.../registry/replication/RegistryReplicationManager.java` |
| `RegisterTask` / `UnregisterTask` / `HeartbeatTask` | 三种消息（payload=Instance）；batching-enabled 默认：heartbeat **true**、register/unregister **false**；task-ttl 默认均 5000ms（2s–30s），过期出队即丢 | `.../registry/replication/{RegisterTask,UnregisterTask,HeartbeatTask}.java` |
| `RegistrySingleItemTaskProcessor` | 单条：校验任务类型 → `RegistryReplicationTool.replicate(clazz, serviceUrl, [instance], expiryMap)` | `.../registry/replication/RegistrySingleItemTaskProcessor.java` |
| `RegistryBatchingTaskProcessor` | 批量：按任务类型 × serviceUrl 二维分组 → 分组批量复制 → 汇总失败任务 | `.../registry/replication/RegistryBatchingTaskProcessor.java` |
| `RegistryReplicationTool` | 复制执行器：serviceUrl 为空时广播到 `ClusterManager.otherNodes()` 中所有 `canServiceRegistry` 节点；对每个 peer 调 `RegistryReplicationServiceClient`；响应 fail/异常 → 全批 failedInstances（异常归 `unknown`），partial → 逐实例；失败任务按响应 errorCode 映射 TaskErrorCode 并记 metric（`...failed-instance.error-code` 按 service_url 维度） | `.../registry/replication/RegistryReplicationTool.java`（`replicate` L127-196） |
| `RegistryReplicationServiceImpl` | 复制接收端（供 RegistryReplicationController）：register 直接入库；**heartbeat 遇未注册实例自动补注册**；getServices 供冷启动全量拉取；限流 `artemis.service.registry.replication` 默认 1,000,000（1k–10M） | `.../registry/replication/RegistryReplicationServiceImpl.java` |
| `RegistryReplicationServiceClient` | peer HTTP 客户端；socket-timeout：heartbeat 200ms（快速失败）、get-applications 2000ms | `.../registry/replication/RegistryReplicationServiceClient.java` |

### 2.5 集群管理（cluster）

| 组件 | 职责 | 证据（原仓库） |
|---|---|---|
| `ServiceCluster` | 静态成员拓扑：配置 `artemis.service.cluster.nodes`（ListMultimap：zoneId→urls，如 `zone1:http://ip:port`），scf 热更触发 `updateClusterNodes` + 广播 `ClusterChangeEvent`（新拓扑为空时跳过更新） | `artemis-common/.../cluster/ServiceCluster.java` |
| `ClusterManager` | 单例（`ClusterManager.INSTANCE.init()` 由 ArtemisServer 调）：维护 localNode/localZoneNodes/localZoneOtherNodes/otherZoneNodes/otherNodes/allNodes 五个 volatile 视图；**本节点识别 = URL 含 `ip[:port]` 子串**；单线程定时（`status-update.interval` 默认 5s，100ms–10min）逐 peer 调 `/api/status/node.json`（重试 `fail-retry-times` 默认 3 次，远端不可达立即 break），状态写入 volatile map；节点状态变化打 info 日志 | `artemis-service/.../cluster/ClusterManager.java`（`isLocalNode` L194-196、`syncNodeStatus` L216-245） |
| `NodeManager` | 本节点状态机：初始 STARTING；6 个 force 开关（见配置表）+ 2 个 allow-from-other-zone 全部热更（ChangeListener → updateNodeStatus）；daemon 线程每 `init.sync-interval`（默认 1s）循环执行 initializers 直到 UP | `artemis-service/.../cluster/NodeManager.java` |
| `NodeInitializer` | 启动门控 SPI：`target()`（REGISTRY/DISCOVERY）+ `initialized()`；REGISTRY 目标内置 `RegistryReplicationInitializer`，DISCOVERY 目标由 management 注入 `ManagementInitializer` | `.../cluster/NodeInitializer.java`、`artemis-management/.../ManagementInitializer.java` |
| `RegistryReplicationInitializer` | 冷启动：优先 localZoneOtherNodes、失败再 otherZoneNodes，找第一个 UP peer 调 `/api/replication/registry/services.json` 全量拉取，逐 service 走**复制通道 register** 重建本地租约（replicationInstanceCount>0 才算成功） | `.../cluster/RegistryReplicationInitializer.java` |
| `ServiceNodeStatus` | 节点状态模型：status（starting/up/down）+ canServiceRegistry/canServiceDiscovery + allowRegistryFromOtherZone/allowDiscoveryFromOtherZone | `.../cluster/ServiceNodeStatus.java` |
| `ClusterServiceImpl` | `/api/cluster` 两端点实现：限流（`artemis.service.cluster` 默认 10000，100–100k）；up-registry-nodes = allNodes 中 canServiceRegistry 且（允许跨 zone 或同 zone 请求方）；up-discovery-nodes 同理；空列表返回 `data-not-found` | `.../cluster/ClusterServiceImpl.java` |

NodeManager 语义细节（`executeInitializers` L166-190）：DISCOVERY initializer 全部成功 → canServiceDiscovery=true；REGISTRY initializer 全部成功 → canServiceRegistry=true；两者皆 true 且非 force-down → status=UP。force-up 优先级最高（直接置 UP + 双 true）。

节点运维配置键（`NodeManager`，原仓库）：

| 配置键 | 默认 | 说明 |
|---|---|---|
| `artemis.service.cluster.node.status.force-up` | false | 整节点强制 UP（status+registry+discovery） |
| `artemis.service.cluster.node.status.registry.force-up` | false | 仅注册面放行 |
| `artemis.service.cluster.node.status.discovery.force-up` | false | 仅发现面放行 |
| `artemis.service.cluster.node.status.force-down.{本机IP}` | false | 整节点强制 DOWN |
| `artemis.service.cluster.node.status.registry.force-down.{本机IP}` | false | 仅注册面关闭 |
| `artemis.service.cluster.node.status.discovery.force-down.{本机IP}` | false | 仅发现面关闭 |
| `artemis.service.cluster.node.init.sync-interval` | 1000ms | 启动同步循环周期 |
| `artemis.service.registry.allow-from-other-zone` | false | 放开注册 zone 校验 |
| `artemis.service.discovery.allow-from-other-zone` | false | 放开发现 zone 校验 |

（发布配置 `artemis.properties` 中 force-up=true、两个 allow-from-other-zone=true，用于单机演示。）

### 2.6 发现服务（discovery + 版本化缓存）

| 功能 | 行为 | 证据（原仓库） |
|---|---|---|
| lookup | 批量实时查询：校验 discoveryConfigs 非空 → 节点 canServiceDiscovery → 同 zone 校验（`no-permission`）→ 逐 config 直读 RegistryRepository → **过滤器链**（DiscoveryFilters 注册的 DiscoveryFilter 依次执行，单 filter 异常仅记日志）→ 不存在的服务返回空 Service 对象（非 null） | `artemis-service/.../discovery/DiscoveryServiceImpl.java#lookupImpl` |
| getService | 单服务实时查询，同上校验；服务不存在返回空 Service | 同上 `getServiceImpl` |
| getServices | 全量服务列表：读 VersionedCacheManager 版本化缓存（version 随响应返回）；**不走过滤器以外的实时组装** | 同上 `getServicesImpl` |
| getServicesDelta | 按 version 增量：`getDelta(version)` 命中缓存版本则返回预计算差集（Map<Service, List<InstanceChange>>），未命中返回 `data-not-found` 逼全量。**客户端未使用该接口**（基线 §2.4） | 同上 `getServicesDelta` |
| 版本化缓存 | 后台单线程定时刷新：`dataGenerator` 生成全量（对每个服务跑 GENERIC DiscoveryConfig 过滤器链，实例全被过滤掉的服务剔除）；version=毫秒时间戳；保留最近 cache-count 份；为每个旧版本预计算 delta | `artemis-service/.../cache/VersionedCacheManager.java`、`ServicesDeltaGenerator.java` |
| DiscoveryFilters | 全局过滤器注册表（volatile 不可变 List 整体替换）；management 启动时注册 GroupDiscoveryFilter + ManagementDiscoveryFilter | `.../discovery/DiscoveryFilters.java`、`artemis-management/.../ManagementInitializer.java` |

发现缓存配置（managerId=`artemis.service.discovery`）：`.versioned-cache.cache-count` 默认 3（0–10）、`.versioned-cache.cache-refresh.init-delay` 默认 60s（0–5min）、`.versioned-cache.cache-refresh.interval` 默认 30s（1s–5min）。

### 2.7 变更推送 NotificationCenter

- 构造时启动 `artemis.service.discovery.notify.thread-count`（默认 10，1–100）个 daemon 工作线程，循环 `pollInstanceChange()` 阻塞消费注册表变更跳表。
- 每条变更先过 `NotificationFilter` 链（DELETE/RELOAD 恒通知，`_alwaysNotifyChangeTypes`；其余被 filter 置 null 则丢弃——management 注册了 `ManagementNotificationFilter` 摘除已下线实例的推送），再广播给全部 `InstanceChangeSubscriber`（server 侧为 ServiceChangeWsHandler + AllServicesChangeWsHandler）。
- 订阅者/过滤器均为按 id 的 ConcurrentHashMap，重复 id 注册记 error 并覆盖。

证据：`artemis-service/.../discovery/notify/NotificationCenter.java`（原仓库）。

### 2.8 状态服务 StatusServiceImpl

`/api/status` 六端点实现（限流 `artemis.service.status` 默认 30（1–10k））：

| 端点语义 | 内容 |
|---|---|
| node | 本节点 ServiceNodeStatus（status/canServiceRegistry/canServiceDiscovery/allow*） |
| cluster | 全节点状态视图（来自 ClusterManager 状态表） |
| leases / legacy-leases | 租约明细 + 自我保护统计：`GetLeasesStatusResponse{leaseUpdateMaxCount, leaseUpdateMaxCountLastUpdateTime, leaseUpdateCountLastTimeWindow, isSafe, isSafeCheckEnabled, leaseCount, leasesStatus: Map<Service, List<LeaseStatus>>}`；LeaseStatus = instance 串 + creationTime/renewalTime/evictionTime（yyyy-MM-dd HH:mm:ss.SSS）+ ttl；支持 serviceIds 过滤（GET 参数 appIds） |
| config | 全部配置项及来源（排障利器，基线 §3.5） |
| deployment | DeploymentConfig 快照（regionId/zoneId/appId/ip/port/protocol/path） |

证据：`artemis-service/.../status/StatusServiceImpl.java`、`GetLeasesStatusResponse.java`、`LeaseStatus.java`（原仓库）。客户端侧：`StatusServiceClient`（节点探测用，get-cluster-node socket-timeout 200ms、get-leases 10s）。

### 2.9 限流器 ArtemisRateLimiterManager

caravan RateLimiterManager 封装（managerId=`artemis.service`），全服务端分级限流默认值汇总：

| 限流键 | 默认 QPS | 范围 | 使用方 |
|---|---|---|---|
| `artemis.service.registry` | 100000 | 1k–1M | register/heartbeat/unregister |
| `artemis.service.registry.replication` | 1000000 | 1k–10M | 复制接收端四操作 |
| `artemis.service.cluster` | 10000 | 100–100k | up-registry/discovery-nodes |
| `artemis.service.status` | 30 | 1–10k | 六个状态端点 |
| `artemis.service.management.group` | 30 | 1–1000 | group 全部 32 端点 |

证据：`artemis-service/.../ratelimiter/ArtemisRateLimiterManager.java` + 各 ServiceImpl 构造器（原仓库）。（基线 §3.1 的「registry 100k / replication 1M / status 30 / GroupService 30」与源码一致，cluster 10k 为本文档补充。）

### 2.10 util

`HttpClientUtil`（`isRemoteHostUnavailable`/`isSocketTimeout` 异常分类）、`Requests`、`ServiceNodeUtil`（`canServiceRegistry/canServiceDiscovery/isUp/isDown` 判定 + `newUnknownNodeStatus` + `checkCurrentNode`：管理写操作要求当前节点 UP，否则返回 service-unavailable）。证据：`artemis-service/.../util/`（原仓库）。

---

## 3. artemis-management（管理面）

### 3.1 模块组成与初始化

`ManagementInitializer.init()`（由 `ArtemisServer.init()` 在 `artemis.management.enabled=true` 时调用）依次：`DataConfig.init()`（DB 数据源）→ GroupRepository / ZoneRepository / ManagementRepository 三个缓存仓库 init → 注册 GroupDiscoveryFilter 到 ManagementRepository（供管理查询过滤）→ `NodeManager.registerInitializer(this)`（DISCOVERY 门控）→ `DiscoveryFilters` 注册 Group+Management 两个发现过滤器 → NotificationCenter 注册 ManagementNotificationFilter。门控条件 = Management 与 Group 两个仓库最近一次缓存刷新均成功。证据：`artemis-management/.../ManagementInitializer.java`（原仓库）。

三个缓存仓库的刷新周期（DynamicScheduledThread run-interval 默认值，均为 daemon 线程、可配）：

| 仓库 | 数据 | 默认周期 | 证据（原仓库） |
|---|---|---|---|
| ManagementRepository | instance/server 摘除操作 | **1000ms**（200ms–60s） | `ManagementRepository.java#init` |
| GroupRepository | 分组/路由/逻辑实例 | **5000ms**（10ms–60s） | `GroupRepository.java#init` |
| ZoneRepository | zone 摘除操作 | **5000ms**（10ms–60s） | `ZoneRepository.java#init` |

（基线 §2.5 的「默认 5s 重刷」对 group/zone 成立，instance/server 摘除缓存实际为 1s。）

### 3.2 实例 / 服务器摘除运维（四级级联的前两级）

- `operate-instance` / `operate-server`：写「下线操作」记录（instance/server 表 + 同步双写 *_log 表）；`operationComplete=true` 即删除记录（恢复）。写后 `waitForPeerSync()` = `Thread.sleep(artemis.management.db-sync.wait-time)`（默认 2000ms，0–60s）——写成功 ≠ 全网生效（基线 §3.3）。写前 `ServiceNodeUtil.checkCurrentNode` 要求本节点 UP。
- `isInstanceDown(instance)` 四级级联判定（基线 §2.6）：instance 操作记录 → server 操作记录（按 instance.ip 匹配 ServerKey）→ zone 摘除（ZoneRepository.isZoneDown）→ group 摘除（SearchTree 按 groupKey 级联 first 匹配）。
- 查询：instance-operations / server-operations（单个 key）、all-instance-operations / all-server-operations（可按 regionId 过滤，GET/POST 双形态）。
- `getServices`（管理视角）：全量服务 + 每实例标记 up/down + **metadata 注入 creationTime/renewalTime/ttl**（来自租约）——管理查询独有的租约可视化。`getService` 额外返回该服务的分组（`groupRepository.getServiceInstanceGroups`）。
- `destroyServers`：按 ServerKey 批量物理删除 server + instance 记录（清理废弃机器）。

证据：`artemis-management/.../ManagementServiceImpl.java`、`ManagementRepository.java`（原仓库）。

### 3.3 zone 摘除运维

ZoneRepository 维护 `Map<ZoneKey, ZoneOperations>`（ZoneKey= serviceId+regionId+zoneId，即 zone 摘除可按服务粒度）；刷新 diff 后对变化服务发 reload InstanceChange。五个端点：operate-zone-operations（增/删操作记录，complete 语义同上）、get-all-zone-operations、get-zone-operations、get-zone-operations-list、is-zone-down。证据：`artemis-management/.../ZoneRepository.java`、`ZoneServiceImpl.java`、`zone/ZoneKey.java`（原仓库）。

### 3.4 流量治理（group 域，基线 §2.5 的 API 化）

数据模型（`group/` 包，原仓库）：`Group`（GroupModel：serviceId/groupKey/status(active|inactive)/...）、`RouteRuleInfo`/`ServiceRouteRule`（RouteRuleModel：serviceId/name/status/strategy）、`RouteRuleGroup`（RouteRuleGroupModel：routeRuleId/groupId/**weight + unreleasedWeight 双列**）、`GroupInstance`（分组-实例绑定）、`ServiceInstance`（逻辑实例，完整 ip/port/protocol/url/metadata JSON）、`GroupOperations`（组级摘除）、`GroupTags`（组标签）。

关键行为：

| 能力 | 行为 | 证据（原仓库） |
|---|---|---|
| CRUD 族 | route-rules / route-rule-groups / groups / group-tags / group-instances / service-instances 各自 insert/update/delete/get/get-all，全部经 BusinessDao（写 + log 双写），读走缓存或 DAO select | `GroupRepository.java` L167-318、`GroupServiceImpl.java` |
| 两段式权重发布 | 编辑写 `unreleased_weight`（`update ... on duplicate key update unreleased_weight=?`）；`release-route-rule-groups` 执行 `update service_route_rule_group set weight = unreleased_weight where route_rule_id=? and group_id=?` 才生效；`publishRouteRuleGroups` 反向（写 weight、清 unreleased） | `group/dao/RouteRuleGroupDao.java`（release L122-125、insertOrUpdate L250-268） |
| create-route-rule | 一步建规则：`BusinessDao.createServiceRouteRules`（规则+组+绑定组合事务） | `group/dao/BusinessDao.java` L50、`GroupRepository#createRouteRules` |
| 逻辑实例 | service_instance 表全量进发现结果 `logicInstances`（GroupDiscoveryFilter 注入）；刷新时 diff 实例指纹串（region/zone/group/service/instance/machine/ip/port/protocol/url/healthCheckUrl/metadata JSON）变化即对服务发 reload | `GroupRepository#refreshServiceInstancesCache` L469-494、`generateServiceInstanceIds` |
| 分组路由展开 | refreshCache 重建 routeRules multimap：仅 status=active 的规则、仅 status=active 的组参与；权重经 `ServiceGroups.fixWeight`；diff 采用 routeId/strategy/groupKey/weight/instanceId 指纹集合比较，变化服务发 reload 推送 | `GroupRepository#refreshServiceRouteRulesCache` L399-467 |
| 组级摘除 | group-operation 记录 → SearchTree 按 5 级 groupKey 级联 `first()` 匹配，命中即 down | `GroupRepository#isInstanceDown` L126-128、L372-382 |
| canary | 见 §3.5 | |
| GroupServiceImpl 限流 | `artemis.service.management.group` 30 QPS（1–1000）；全部写操作 checkCurrentNode + waitForPeerSync | `GroupServiceImpl.java` L43-46、L731 |

### 3.5 一键 Canary

`update-canary-ips`：入参 serviceId/appId/canaryIps[]；自动 `generateRouteRule`（name=canary-route-rule、status=active、strategy=weighted-round-robin）→ `generateGroup`（canary 专属组）→ `generateRouteRuleGroup`（默认权重）→ `updateGroupInstances`（绑 IP 对应实例）→ waitForPeerSync。证据：`artemis-management/.../canary/CanaryServiceImpl.java`、`canary/CanaryServices.java`（原仓库）。

### 3.6 审计日志（9 类查询）

全部 `*_log` 表双写于每次变更（ManagementLogServiceImpl 只读）。9 个查询端点及过滤字段：

| 端点 | 过滤字段（请求） |
|---|---|
| instance-operation-logs | instanceKey（regionId/serviceId/instanceId）+ operation + operatorId + token + complete + 时间段 |
| server-operation-logs | serverKey（regionId/serverId）+ 同上公共字段 |
| group-operation-logs | groupId + operation + 公共字段 |
| group-logs | serviceId + groupKey + 公共字段 |
| route-rule-logs | serviceId + name + 公共字段 |
| route-rule-group-logs | routeRuleId + groupId + 公共字段 |
| zone-operation-logs | operation + 公共字段 |
| group-instance-logs | groupId + instanceId + 公共字段 |
| service-instance-logs | serviceId + instanceId + 公共字段 |

公共字段 = operatorId / token / operation / reason / complete 标志 / 时间戳（操作前后数据快照存于 log 行）。`OperationContext{operatorId, token, operation, reason, extensions}` 随写操作传递，**token 只存不验**（基线 §2.6）。证据：`artemis-management/.../ManagementLogServiceImpl.java`、`log/*Request.java`、`common/OperationContext.java`（原仓库）。

### 3.7 与数据面的三个集成点

| 集成点 | 类 | 行为 |
|---|---|---|
| 发现过滤器 1 | `GroupDiscoveryFilter` | 注入 `logicInstances` 与 `routeRules`；canary 规则额外解析组实例（`RouteRules.generateGroupInstances`，按 groupKey 前缀 / instanceId 绑定匹配注册实例与逻辑实例） |
| 发现过滤器 2 | `ManagementDiscoveryFilter` | 从 instances 与 logicInstances 中移除四级判定为 down 的实例（info 日志记录移除） |
| 推送过滤器 | `ManagementNotificationFilter` | 已摘除实例的 NEW/CHANGE 推送直接置 null 丢弃（DELETE/RELOAD 恒放行） |

证据：`artemis-management/.../GroupDiscoveryFilter.java`、`ManagementDiscoveryFilter.java`、`ManagementNotificationFilter.java`（原仓库）。

### 3.8 DAO / DB 层

- `DataConfig`：dbcp2 `BasicDataSource` + Spring `JdbcTemplate`/`DataSourceTransactionManager`；默认 MySQL（`data-source.properties`，driver `com.mysql.jdbc.Driver`）；SQLite 分支（2026-03）：优先级 system property `artemis.db.driver/url` → 环境变量 `ARTEMIS_DB_DRIVER/URL` → `ARTEMIS_DB_CONFIG` 指向的 properties → 默认 MySQL；`isMySQL()` 判定含 `System.out.println` 调试残留（基线 §6.23）。证据：`dao/DataConfig.java`（原仓库）。
- DAO 清单：instance/server 域 4 个（InstanceDao/InstanceLogDao/ServerDao/ServerLogDao）；group 域 16 个（GroupDao、GroupTagDao(+Log)、GroupInstanceDao(+Log)、GroupLogDao、GroupOperationDao(+Log)、RouteRuleDao(+Log)、RouteRuleGroupDao(+Log)、ServiceInstanceDao(+Log)、BusinessDao 聚合门面）；zone 域 2 个。全部单例 + JdbcTemplate 直写 SQL。
- 表结构：`artemis-package/deployment/artemis-management.sql`（原仓库）20 张表 = 10 业务 + 10 日志：instance、instance_log、server、server_log、service_group、service_group_instance(+_log)、service_group_log、service_group_operation(+_log)、service_group_tag(+_log)、service_instance(+_log)、service_route_rule(+_log)、service_route_rule_group(+_log)、service_zone(+_log)。

---

## 4. artemis-server（REST / WebSocket 接入层）

11 个 Controller（10 个在 `rest/controller/` + 1 个 `websocket/WsStatusController`）+ websocket 包 9 个类。所有 REST 端点均为 JSON（`consumes/produces=application/json`），多数查询端点同时提供 POST（body）与 GET（query params）两种形态；每个端点调用后过 `MetricLoggerHelper.logResponseEvent`（空实现，见 §1.7）。

### 4.1 数据面端点总表（5 组 20 个路径）

**（1）`/api/registry/`** — `RegistryController`（`artemis-server/.../rest/controller/RegistryController.java`，原仓库）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/registry/register.json` | 批量注册（instances[] → failedInstances[]） |
| POST | `/api/registry/heartbeat.json` | 批量心跳续约 |
| POST | `/api/registry/unregister.json` | 批量注销 |

**（2）`/api/replication/registry/`** — `RegistryReplicationController`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/replication/registry/register.json` | 复制通道注册（peer → 本节点） |
| POST | `/api/replication/registry/heartbeat.json` | 复制通道心跳（缺实例自动补注册） |
| POST | `/api/replication/registry/unregister.json` | 复制通道注销 |
| POST/GET | `/api/replication/registry/services.json` | 全量服务拉取（冷启动用；GET 需 regionId 参数，zoneId 可选） |

**（3）`/api/cluster/`** — `ClusterController`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST/GET | `/api/cluster/up-registry-nodes.json` | 可注册节点列表（GET 参数 regionId/zoneId 可选） |
| POST/GET | `/api/cluster/up-discovery-nodes.json` | 可发现节点列表 |

（`cluster/nodes.json` 常量为死代码，无 Controller 映射，见 §1.2。）

**（4）`/api/status/`** — `StatusController` + `WsStatusController`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST/GET | `/api/status/node.json` | 本节点状态（peer 探测端点） |
| POST/GET | `/api/status/cluster.json` | 集群全节点状态 |
| POST/GET | `/api/status/leases.json` | 租约明细 + 自我保护统计（GET 参数 appIds 过滤） |
| POST/GET | `/api/status/legacy-leases.json` | legacy 池租约明细 |
| POST/GET | `/api/status/config.json` | 全部配置项 + 来源 |
| POST/GET | `/api/status/deployment.json` | 部署身份快照 |
| GET | `/api/status/websocket/connection.json` | WS 连接数：`{"registry":N,"discovery":M}` |

**（5）`/api/discovery/`** — `DiscoveryController`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/discovery/lookup.json` | 批量实时查询（discoveryConfigs[]） |
| GET/POST | `/api/discovery/service.json` | 单服务查询（GET 必参 serviceId，regionId/zoneId 可选） |
| GET/POST | `/api/discovery/services.json` | 全量服务（版本化缓存，返回 version） |
| POST | `/api/discovery/services-delta.json` | 按 version 增量（客户端未使用） |

### 4.2 管理面端点总表（5 组 57 个路径）

常量源：`artemis-management/.../config/RestPaths.java`（原仓库）；Controller 在 `artemis-server/.../rest/controller/`（原仓库）。除注明 GET 外均为 POST。

**（1）`/api/management/`（10 个）** — `ManagementController`

| 路径 | 用途 |
|---|---|
| `operate-instance.json` | 实例下线/恢复（操作记录增删） |
| `operate-server.json` | 服务器下线/恢复 |
| `instance-operations.json` | 查单实例下线操作集 |
| `server-operations.json` | 查单服务器下线操作集 |
| `all-instance-operations.json`（POST+GET） | 全部实例下线操作（GET 参数 regionId） |
| `all-server-operations.json`（POST+GET） | 全部服务器下线操作 |
| `instance-down.json` | 四级级联判定单实例是否下线 |
| `server-down.json` | 判定服务器是否下线 |
| `services.json`（POST+GET） | 管理视角全量服务（实例 up/down + 租约时间注入） |
| `service.json` | 管理视角单服务（含分组） |

**（2）`/api/management/group/`（32 个）** — `ManagementGroupController`，按对象分 7 族：

| 族 | 端点 |
|---|---|
| route-rule（6） | insert-route-rules / update-route-rules / delete-route-rules / get-route-rules / get-all-route-rules / create-route-rule |
| route-rule-group（6） | insert-route-rule-groups / update-route-rule-groups / release-route-rule-groups / delete-route-rule-groups / get-all-route-rule-groups / get-route-rule-groups |
| group（5） | insert-groups / update-groups / delete-groups / get-groups / get-all-groups |
| group-tag（5） | insert-group-tags / update-group-tags / delete-group-tags / get-group-tags / get-all-group-tags |
| group-operation（4） | operate-group-operations / operate-group-operation / get-all-group-operations / get-group-operations |
| group-instance（3） | insert-group-instances / delete-group-instances / get-group-instances |
| service-instance（逻辑实例，3） | insert-service-instances / delete-service-instances / get-service-instances |

**（3）`/api/management/log/`（9 个）** — `ManagementLogController`：instance-operation-logs / server-operation-logs / group-operation-logs / group-logs / route-rule-logs / route-rule-group-logs / zone-operation-logs / group-instance-logs / service-instance-logs（过滤字段见 §3.6）。

**（4）`/api/management/zone/`（5 个）** — `ManagementZoneController`：operate-zone-operations / get-all-zone-operations / get-zone-operations / get-zone-operations-list / is-zone-down。

**（5）`/api/management/canary/`（1 个）** — `CanaryController`：update-canary-ips。

> 路径计数：10 + 32 + 9 + 5 + 1 = **57 个路径**（基线 §2.6 原记约数「~60 个 / group 33 个」，已修订；精确计数以 RestPaths.java 逐常量清点为准）。

### 4.3 WebSocket（websocket 包 9 个类，注册 3 个端点）

注册于 `WebSocketEndpointConfig#registerWebSocketHandlers`（`artemis-server/.../websocket/WebSocketEndpointConfig.java`，原仓库），全部 `setAllowedOrigins("*")` + 挂 `WsIPBlackList` 拦截器；后两个 handler 同时注册为 NotificationCenter 订阅者。

| 端点（destination） | Handler | 协议行为 |
|---|---|---|
| `/websocket/registry/heartbeat` | `HeartbeatWsHandler` | 客户端每 5s 发全量实例 `HeartbeatRequest`（JSON 文本），handler 解码→`RegistryServiceImpl.heartbeat`→回 `HeartbeatResponse`（解析异常回固定 success 消息）。「心跳即注册」通道（基线 §2.2） |
| `/websocket/discovery/instance-change` | `ServiceChangeWsHandler` | 客户端发 `DiscoveryConfig`（JSON 文本）= 订阅单服务；`serviceChangeSessions: Map<serviceId, Set<sessionId>>` 维护订阅关系；`accept(instanceChange)` 对该服务全部 open 会话 `synchronized(session)` 逐个推送 InstanceChange，发送失败关会话 |
| `/websocket/discovery/all-instance-change` | `AllServicesChangeWsHandler` | 无订阅语义，全部会话广播每条 InstanceChange（monitor 类消费方） |

websocket 包其余 6 个类：

| 类 | 职责 | 证据（原仓库） |
|---|---|---|
| `ArtemisWsHandler` | 抽象基类：sessions 并发表 + `DelayQueue<DelayItem>` 会话 TTL 管理——连接建立即入队 `artemis.service.websocket.session.ttl`（默认 **6min**，范围 5min–5h）；健康检查线程（默认 60s 周期，10s–1h）关过期会话并记连接数；transport error / closed 均移除会话 | `.../websocket/ArtemisWsHandler.java` |
| `WsIPBlackList` | HandshakeInterceptor：`artemis.service.{id}.ws-ip.black-list.enabled`（默认 true）+ `.ws-ip.black-list`（默认空）大小写不敏感匹配远端 IP，命中拒绝握手；黑名单 id 为 `service-heartbeat` / `service-discovery` / `service-discoveries` | `.../websocket/WsIPBlackList.java` |
| `WsStatusController` | `/api/status/websocket/connection.json` 实现（见 §4.1） | `.../websocket/WsStatusController.java` |
| `DelayItem` / `InetSocketAddressHelper` | DelayQueue 元素 / 远端 IP 提取 | `.../websocket/DelayItem.java`、`InetSocketAddressHelper.java` |
| `WebSocketEndpointConfig` | `@Configuration @EnableWebSocket` 注册上述 3 端点 | `.../websocket/WebSocketEndpointConfig.java` |

（基线 §4 的「9 个 server WS handler」即指 websocket 包 9 个类；实际注册的 WS destination 为 3 个。）

### 4.4 REST 基础设施与启动

| 组件 | 职责 | 证据（原仓库） |
|---|---|---|
| `FilterConfig` | 三个全局 Filter：跨域（caravan CrossDomainFilter）、响应压缩（ziplet CompressingFilter）、HiddenHttpMethodFilter；均 UTF-8 forceEncoding | `artemis-server/.../rest/FilterConfig.java` |
| `CustomObjectMapper` + `JsonSerializationHack` | 替换 Spring MVC Jackson converter：忽略未知属性/null 原始值失败、属性大小写不敏感、按字母序输出 | `.../rest/CustomObjectMapper.java`、`JsonSerializationHack.java` |
| `ArtemisServer` | 聚合启动：`artemis.management.enabled`（system property，默认 true）决定是否 init Management；随后 `ClusterManager.INSTANCE.init()` | `artemis-server/.../ArtemisServer.java` |
| swagger | artemis-package 依赖 `springfox-boot-starter`（ springfox 3.0.0，基线 §3.7）；无自定义 UI | `artemis-package/pom.xml` |

---

## 5. artemis-client（客户端 SDK）

### 5.1 公开 API（宿主接入面）

| 类型 | API | 说明 | 证据（原仓库） |
|---|---|---|---|
| 入口 | `ArtemisClientManager.getManager(managerId, managerConfig)` | 静态按 managerId 单例（ConcurrentHashMap.computeIfAbsent）；`getManagerId()/getManagerConfig()` | `artemis-client/.../ArtemisClientManager.java` |
| 入口 | `getRegistryClient()` / `getDiscoveryClient()` | 懒加载双工客户端（同一 manager 下各一个实例） | 同上 |
| 配置 | `ArtemisClientManagerConfig` | 必传 `StringProperties`（scf 配置源，宿主注入）；可选 EventMetricManager / AuditMetricManager（缺省 Null）、RegistryClientConfig（RegistryFilter 列表）、DiscoveryClientConfig（当前为空类） | `.../ArtemisClientManagerConfig.java`、`RegistryClientConfig.java`、`DiscoveryClientConfig.java` |
| 注册 | `RegistryClient.register(Instance...)` / `unregister(Instance...)` | 语义见 §5.2；**无 close()/生命周期 API**（基线 §6.11） | `.../RegistryClient.java`、`RegistryClientImpl.java` |
| 发现 | `DiscoveryClient.getService(DiscoveryConfig)` / `registerServiceChangeListener(DiscoveryConfig, ServiceChangeListener)` | 本地缓存读取 + 按需同步 + 订阅推送（§5.3）；`ServiceChangeEvent` 携带 changeType 与克隆后的全量 Service | `.../DiscoveryClient.java`、`ServiceChangeListener.java`、`ServiceChangeEvent.java` |
| 扩展点 | `RegistryFilter` | 注册前实例过滤 SPI（`getRegistryFilterId()` + `filter(List<Instance>)`），心跳与补注册均过过滤器链，带耗时 metric | `.../RegistryFilter.java`、`registry/InstanceRepository#filterInstances` |

### 5.2 注册链路（「心跳即注册」，基线 §2.2 的客户端侧实现）

| 组件 | 行为 | 证据（原仓库） |
|---|---|---|
| `RegistryClientImpl` | register/unregister → `InstanceRepository.register/unregister` | `.../registry/RegistryClientImpl.java` |
| `InstanceRepository` | 本地实例集 `AtomicReference<Set<Instance>>`（整体换新，synchronized updateInstances）；`register()` **先 HTTP unregister 清 server 旧租约**再并入本地集；`unregister()` HTTP unregister + 本地移除；`getHeartbeatMessage()` = 过滤后全量实例序列化为 `HeartbeatRequest` JSON 文本（空集返回 null 不发）；`registerToRemote()` 对补注册实例过过滤器链后 HTTP register | `.../registry/InstanceRepository.java` |
| `InstanceRegistry` | WS 心跳引擎：构造即建 `WebSocketSessionContext`（destination=heartbeat）并启动检查线程（默认 1s 周期）；`checkHeartbeat()`：距上次心跳 ≥ instance-ttl → `markdown()` 强制换节点；≥ heartbeat-interval → `sendHeartbeat()`（发送全量消息并记 lastHeartbeatTime）；`acceptHeartbeat()`（WS onMessage）：解析 HeartbeatResponse；serviceDown → markdown；对 failedInstances 中 `data-not-found`/`unknown` 的实例调 `registerToRemote` HTTP 补注册；全程 4 个 metric（heartbeat.event + prepare/send/accept-latency） | `.../registry/InstanceRegistry.java`（`checkHeartbeat` L158-168、`registerToServicesRegistry` L170-187） |
| `ArtemisRegistryHttpClient` | HTTP 通道：register/unregister 打 `/api/registry/*.json`，走 ArtemisHttpClient 统一容错（§5.5） | `.../registry/ArtemisRegistryHttpClient.java` |

### 5.3 发现链路（推送为主 + 三层兜底，基线 §2.4 客户端侧）

| 组件 | 行为 | 证据（原仓库） |
|---|---|---|
| `DiscoveryClientImpl` | 门面：getService / registerServiceChangeListener → `ServiceRepository` | `.../discovery/DiscoveryClientImpl.java` |
| `ServiceRepository` | 缓存 `ConcurrentHashMap<serviceId(lowercase), ServiceContext>` + discoveryConfigs 表；`getService()`：未缓存则 `registerService()`（首次同步 HTTP lookup + WS 订阅）→ 返回 `serviceContext.newService()`（克隆快照）；**缓存永不失效**；`update(InstanceChange)`：按 new/delete/change 原地更新 ServiceContext，updated 才回调；`update(Service)`（全量刷新结果）以 RELOAD 事件回调；回调经单线程 `serviceChangeCallback` executor 异步执行；变更 metric 以伪实例（instanceId=reload）携带 region/zone/service 维度 | `.../discovery/ServiceRepository.java` |
| `ServiceDiscovery` | WS 订阅 + 兜底轮询引擎：WS destination=instance-change；连接建立后对全部已缓存服务重发 DiscoveryConfig（**重连自动重订阅**）；onMessage 收 InstanceChange：reload → `reload(config)` 全量重拉，否则 `serviceRepository.update(instanceChange)`；轮询线程（默认 60s 周期）`getReloadDiscoveryConfigs()`：TTL 过期（默认 15min）→ 全部服务；否则 = 上次 reload 失败集（reloadFailedDiscoveryConfigs，成功后清除）+ 实例列表为空的服务；`reload()` 批量 lookup（`/api/discovery/lookup.json`）刷新缓存，失败则全部标入失败集并抛出 | `.../discovery/ServiceDiscovery.java`（`getReloadDiscoveryConfigs` L103-119、`reload` L125-159） |
| `ArtemisDiscoveryHttpClient` | getService（单服务）/ getServices（批量 lookup）打 `/api/discovery/*.json` | `.../discovery/ArtemisDiscoveryHttpClient.java` |
| `ServiceContext` | 单服务上下文：Service 快照 + listeners + `isAvailable()`（实例非空）+ `newService()` 克隆 | `.../discovery/ServiceContext.java` |

### 5.4 三级地址容灾（基线 §2.8 / §5.2 资产）

| 组件 | 行为 | 证据（原仓库） |
|---|---|---|
| `AddressRepository` | 第一级：引导地址 `{clientId}.service.domain.url`（必配，空白则 error 且不刷新）；第二级：daemon 轮询线程（默认 5min，1–30min）打 `/api/cluster/up-{registry\|discovery}-nodes.json`（POST GetServiceNodesRequest，携带 DeploymentConfig region/zone），成功则更新可用 URL 列表（去重、trim 尾 `/`）；`get()`：列表非空随机选一，**空则降级回引导地址** | `.../common/AddressRepository.java` |
| `AddressContext` | 第三级：单节点上下文 = httpUrl + wsEndpoint（http(s):// 前缀替换为 ws:// + destination 后缀）+ `_available` 标志 + TTL（`{clientId}.address.context-ttl` 默认 1h，1min–24h）；`markUnavailable()` 熔单点；过期/不可用即换 | `.../common/AddressContext.java` |
| `AddressManager` | registry/discovery 两个工厂（各自 AddressRepository + WS destination）；`getContext()`：不可用或过期 → `newAddressContext()`（重新随机选址） | `.../common/AddressManager.java` |

### 5.5 统一 HTTP 执行器 ArtemisHttpClient

- `request(path, request, clazz)`：固定循环 `retry-times`（默认 5，1–10）次 × 间隔 `retry-interval`（默认 100ms，0–1000ms）：
  - 响应非 serviceDown 且非 rerunnable → 直接返回；
  - serviceDown（internal-service-error/service-unavailable）→ `context.markUnavailable()` 熔节点后重试；
  - rerunnable（rate-limited/unknown）→ 重试；
  - 异常 → markUnavailable + 重试；末次异常直接抛出；重试耗尽抛 RuntimeException。
- 请求构造：`HttpRequestFactory.createRequest`（POST + Jackson）+ `gzipRequest`（全链路 gzip，基线 §3.4）。
- httpClientId = `artemis.client.{managerId}.registry` / `.discovery`，即重试配置键为 `artemis.client.{managerId}.registry.http-client.retry-times` 等。
- 证据：`.../common/ArtemisHttpClient.java`（原仓库）。

### 5.6 WebSocketSessionContext（客户端 WS 生命周期）

- 配置：ttl（默认 5min，5–30min）/ connect-timeout（5s，1–30s）/ ping-timeout（1s，50ms–10s）/ text-message.buffer-size（默认 8KB，8–32）/ reconnect-times 限流（默认 5 次/20s 窗口，3–60）。
- 健康检查线程（默认 1s 周期，100ms–10min）：`checkHealth()` = 地址可用 && 会话未过期 && isAvailable()（**发 Ping 等 Pong**，超时即死）→ 不满足则 `connect()`。
- `connect()`：先过重连限流器（超限仅 error 日志）；handshake 异步 + `future.get(connect-timeout)`；成功替换旧会话（关旧）、刷新 _lastUpdatedTime、回调 `afterConnectionEstablished`（子类重订阅）；失败 markUnavailable。`markdown()` = 熔当前地址 + 立即 checkHealth。
- 会话 TTL 强制重建（防漂移到坏节点，基线 §5.8）。
- 证据：`.../websocket/WebSocketSessionContext.java`（原仓库）。

### 5.7 客户端配置项总表（前缀 `artemis.client.{managerId}`，全部 scf 热更）

| 配置键（前缀省略） | 默认 | 范围 | 作用 | 证据（原仓库） |
|---|---|---|---|---|
| `.service.domain.url` | ""（必配） | — | 集群引导地址 | `common/AddressRepository.java` |
| `.registry.http-client.retry-times / retry-interval` | 5 / 100ms | 1–10 / 0–1000ms | 注册 HTTP 重试 | `common/ArtemisHttpClient.java`（httpClientId=`...registry`） |
| `.discovery.http-client.retry-times / retry-interval` | 5 / 100ms | 同上 | 发现 HTTP 重试 | 同上 |
| `.instance-registry.heartbeat-interval` | 5000ms | 500ms–5min | 心跳发送间隔 | `registry/InstanceRegistry.java` |
| `.instance-registry.instance-ttl` | 20000ms | 5s–24h | 心跳超时判连接不可用（markdown） | 同上 |
| `.instance-registry.heartbeat-checker`（DynamicScheduledThreadConfig） | 间隔 1000ms | 500ms–90s | 心跳检查线程周期 | 同上 |
| `.service-discovery.ttl` | 15min | 1min–24h | 全量刷新周期 | `discovery/ServiceDiscovery.java` |
| `.service-discovery`（poller 线程） | 间隔 60s | 60s–24h | 兜底轮询周期 | 同上 |
| `.address.context-ttl` | 1h | 1min–24h | 节点上下文强制轮换 | `common/AddressContext.java` |
| `.address-repository`（刷新线程） | 间隔 5min | 1–30min | 存活节点列表刷新 | `common/AddressRepository.java` |
| `.websocket-session.ttl` | 5min | 5–30min | 会话强制重建 | `websocket/WebSocketSessionContext.java` |
| `.websocket-session.connect-timeout` | 5s | 1–30s | 握手超时 | 同上 |
| `.websocket-session.ping-timeout` | 1000ms | 50ms–10s | Ping/Pong 存活判定 | 同上 |
| `.websocket-session.text-message.buffer-size` | 8（KB） | 8–32 | WS 文本缓冲（大服务消息截断风险，基线 §6.1） | 同上 |
| `.websocket-session.reconnect-times` | 5 次/20s | 3–60 | 重连限流 | 同上 |
| `.websocket-session.health-check`（线程） | 间隔 1000ms | 100ms–10min | 健康检查周期 | 同上 |

线程模型（每 manager）：2× AddressRepository 刷新（registry+discovery）+ 2× WS 健康检查 + 1× 心跳检查 + 1× 兜底轮询 + 1× 回调 executor（单线程）= 7+ daemon 线程（基线 §2.8）。

### 5.8 死代码与已知缺陷

- `registry/RegistryServiceClient`：实现了 RegistryService 的 HTTP 客户端，无任何调用方（基线 §6.23 死代码）。
- `common/Conditions`、`RegisterType`：参数校验/枚举辅助。
- 客户端自带单测（AddressManager/AddressRepository/WebSocketSessionContext/DiscoveryClient 等 14 个测试类，`artemis-client/src/test/`，原仓库）——与基线「核心零测试」的表述相比，client 的地址/WS 基础设施有少量单测，但复制/推送/租约等服务端核心仍零测试。

---

## 6. artemis-package（启动与部署）

| 功能 | 内容 | 证据（原仓库） |
|---|---|---|
| 启动入口 | `App extends SpringBootServletInitializer`：`@SpringBootApplication(exclude=DataSourceAutoConfiguration)` + `@ComponentScan("org.mydotey.artemis.server")`；main 与 WAR configure 均先 `ArtemisServer.INSTANCE.init()`——**双形态部署（fat jar / 外部 Tomcat WAR）** | `artemis-package/src/main/java/org/mydotey/artemis/server/App.java` |
| 配置三件套 | `application.properties`（部署身份：region.id=lab/zone.id=zone1/app.id=10001/app.port=8080/app.protocol=http）；`artemis.properties`（服务端运行配置，含集群成员、force-up、双租约池 TTL 演示值、限流 map、replicaton 拼写错误的线程数键——该拼写错误键实际无效，见基线 §6.23）；`data-source.properties`（MySQL，admin/123456 明文）/ `data-source-sqlite.properties`（`jdbc:sqlite:./artemis.db`） | `artemis-package/src/main/resources/*` |
| 部署物 | `deployment/artemis-management.sql`（336 行，20 表 DDL，见 §3.8）；`deployment/server.xml`（外部 Tomcat 配置）；maven `spring-boot-maven-plugin` repackage，finalName=`artemis-${project.version}` | `artemis-package/deployment/*`、`artemis-package/pom.xml` |
| 依赖 | spring-boot-starter-tomcat（provided 语义按 pom）、springfox-boot-starter（swagger UI）；其余能力全部来自上游四个模块 | `artemis-package/pom.xml` |

管理面开关：`-Dartemis.management.enabled=false` 时 `ArtemisServer.init()` 跳过 ManagementInitializer——零 DB 依赖运行（基线 §3.7）；此时 DISCOVERY 门控 initializer 缺失，节点靠 force-up 或仅 registry 数据也可 UP（`NodeManager#executeInitializers` 中 DISCOVERY 目标无 initializer 时视为成功）。

---

## 7. artemis-test（集成测试）

- 组成：`src/test/java` 下 24 个 DAO 测试类 + `GroupRepositoryTest` / `ZoneRepositoryTest` + 测试基础设施（`ArtemisTest`、`TestDatabaseInitializer`、`util/*Models` 常量工厂）；`src/main/java` 另有一个 `App.java`。
- 范围：仅 management DAO 层（MySQL 语法 SQL 在 SQLite 内存库上执行，进程内起服），约 57 个 @Test（基线 §3.7）；覆盖 instance/server/group/route-rule/group-instance/service-instance/zone-operation 全部 DAO 的 CRUD 与 log 双写断言。
- 未覆盖：artemis-service 全部（注册表/复制/推送/租约/故障转移）、artemis-server Controller、artemis-client 与 server 的端到端链路。
- 证据：`artemis-test/src/test/java/org/mydotey/artemis/**`（原仓库）。

---

## 附录：待验证清单与基线勘误

1. **基线 §2.1 勘误（已修订）**：ServiceGroup weight 默认值基线初版误记「5000」，源码为 `ServiceGroups.DEFAULT_WEIGHT_VALUE = 5`（`artemis-common/.../util/ServiceGroups.java`，原仓库；`CanaryServiceImpl` L53 生成 canary 组即引用该常量）。
2. **基线 §2.6 约数勘误（已修订）**：管理面 API 精确计数 57 路径（根组 10、group 32；基线原记 ~60/33），以 `artemis-management/.../config/RestPaths.java`（原仓库）逐常量清点为准。
3. **基线 §2.5 补充**：管理面三仓库刷新周期并非统一 5s——ManagementRepository（instance/server 摘除）默认 1s，Group/ZoneRepository 5s（各类 `init()` 的 DynamicScheduledThreadConfig 默认值）。
4. 已验证补充：`HeartbeatEvent/HeartbeatEventListener`（`artemis-common/.../registry/`，原仓库）全仓库无任何使用点，为预留死代码。
5. 已验证补充：`artemis.properties` 中 `artemis.service.registry.replicaton.*`（拼写错误）与 `*.lease-manager.thread-pool-size` 键在代码中无对应读取点（LeaseManager 实际键为 `clean-task.thread-count`），发布配置样例存在多处无效键；不影响运行，重设计时不予继承。
6. 已验证补充：`SingleItemTaskAcceptor.assignWork` 逐任务出队为单条工作单元（每 task 一个 work），缓冲满同样丢队首工作单元（buffer-full-dropped），出队时过滤过期任务（`artemis-common/.../taskdispatcher/SingleItemTaskAcceptor.java`，原仓库）。
