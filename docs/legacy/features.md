# Artemis 原产品实现清单（Legacy Feature Inventory）

> 调研对象：`~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**事实层 · 实现清单**——按代码结构（模块 → 包 → 类）组织的全量清单（REST/WS 端点、配置项与默认值、DAO/表、测试），作为规格层（[domains/](domains/)）的取证附录。行为语义与需求规格以域文档为准，本文不重复；**字段级 / 报文级 / 表结构级契约见 [domains/](domains/) 下的四份契约制品**（data-model / api-contract / db-schema / client-sdk-api）；关键事实以 ⚠ 标注并指向域文档。产品级入口 [product-overview.md](product-overview.md)。
> 全部结论以原仓库源码为证据（原仓库 Readme 不作为依据）；证据路径相对原仓库根并注明「原仓库」。
> API 清单唯一事实源：`artemis-common/src/main/java/org/mydotey/artemis/config/RestPaths.java`、`.../config/WebSocketPaths.java`（原仓库）与 `artemis-management/src/main/java/org/mydotey/artemis/management/config/RestPaths.java`（原仓库）；`CONTEXT_PATH = "/"`（`.../config/ArtemisPaths.java`，原仓库，2026-03 统一为 `/api/` 前缀时引入）。
> 模块视图（职责/依赖链/Spring 依赖）见 [arch.md](arch.md) §2，规模数字见基线 §1。

---

## 1. artemis-common（基础库）

### 1.1 核心数据模型

| 实体 | 行为细节 | 证据（原仓库） |
|---|---|---|
| `Instance` | 13 字段可变 POJO；equals/hashCode 委托 InstanceKey（大小写不敏感）；`clone()` 供深拷贝。⚠ `status` 字段（starting/up/down/unhealthy/unknown）为**纯透传**：注册链路不读不写不校验，全仓库 main 零读点；发现响应中的 status 是管理面按摘除判定**覆写**的值（registry-lease FR-RL-14） | `artemis-common/src/main/java/org/mydotey/artemis/Instance.java` |
| `InstanceKey` | regionId.serviceId.instanceId 三元组唯一标识，`InstanceKey.of(instance)` 工厂 | `.../InstanceKey.java` |
| `InstanceChange` | instance + changeType + changeTime；ChangeType 常量 `new/delete/change/reload`。⚠ **CHANGE 类型无任何服务端产生点**（仅注册 NEW / 剔除 DELETE / 管理 RELOAD，discovery FR-DIS-06）；equals 只比较 instance → 事件集合对同实例**折叠去重**（快速翻转丢事件，registry-lease logic §7.2） | `.../InstanceChange.java` |
| `Service` | serviceId/metadata/instances/logicInstances/routeRules；后两者为发现时注入的派生视图 | `.../Service.java` |
| `ServiceGroup` / `RouteRule` | groupKey/weight/instanceIds/metadata；RouteRule = routeId+strategy+groups。策略常量：`weighted-round-robin`、`close-by-visit` | `.../ServiceGroup.java`、`.../RouteRule.java`（`RouteRule.Strategy`） |
| `Region` / `Zone` / `ServerKey` | region→zones 层级；ServerKey = regionId+serverId（serverId=IP） | `.../Region.java`、`.../Zone.java`、`.../ServerKey.java` |
| `ResponseStatus` / `HasResponseStatus` | 所有响应统一携带 ResponseStatus（status + errorCode + message） | `.../ResponseStatus.java` |

**保留路由规则与权重语义**（`.../util/RouteRules.java`、`.../util/ServiceGroups.java`，原仓库）：

- 保留规则名：`DEFAULT_ROUTE_RULE = "default-route-rule"`、`CANARY_ROUTE_RULE = "canary-route-rule"`；`isDefaultRouteRule()/isCanaryRouteRule()` 大小写不敏感比较。⚠ 保留名**无服务端 CRUD 保护**（traffic-governance logic §7.3）。
- 权重：`MIN_WEIGHT_VALUE=0`、`MAX_WEIGHT_VALUE=10000`、`DEFAULT_WEIGHT_VALUE=5`；`ServiceGroups.fixWeight()` 对 null 或负值返回默认值、超上限截断为 10000。
- 默认分组：`DEFAULT_GROUP_ID = "default"`，`isDefaultGroupId()` 对空白也返回 true；无 default 组实体，default-route-rule 由客户端合成（traffic-governance FR-TG-07）。
- reload 伪装实例：`InstanceChanges.RELOAD_FAKE_INSTANCE_ID="reload"`、`RELOAD_FAKE_IP="0.0.0.0"`、`RELOAD_FAKE_URL="http://serviceId/reload"`（`.../util/InstanceChanges.java`）。

### 1.2 配置基座（scf 管线）

| 功能 | 行为 | 证据（原仓库） |
|---|---|---|
| ArtemisConfig | 服务端/通用配置管理器：源优先级 = 环境变量 → system properties → `artemis-{env}.properties` → `artemis.properties`，再按本机 IP cascaded；提供 `getListMultimapProperty`（zoneId→urls 用） | `.../config/ArtemisConfig.java`（static 块） |
| DeploymentConfig | 部署身份：`region.id` / `zone.id` / `app.id` / `app.port`（默认 8080，1–65535 校验）/ `app.protocol`（默认 http）/ `app.path`；`deployment.env` 系统属性选择 `application-{env}.properties`；IP 与主机名由 `NetworkInterfaceManager` 自动探测；值在类加载时快照为静态字段（**非热更**；SDK 不以身份自动填充 Instance 字段，由宿主自填） | `.../config/DeploymentConfig.java` |
| RestPaths / WebSocketPaths | 全部 REST/WS 路径常量（见 §4 端点总表）；`CONTEXT_PATH="/"`。`CLUSTER_NODES_RELATIVE_PATH`（`cluster/nodes.json`）常量已定义但无 Controller 引用（死常量） | `.../config/RestPaths.java`、`WebSocketPaths.java`、`ArtemisPaths.java` |
| ListMultimapConverter 等 | scf 类型转换器 | `.../config/ListMultimapConverter.java`、`MapValueConverter.java`、`MapValueCorrector.java` |

### 1.3 租约（lease）

| 功能 | 行为 | 证据（原仓库） |
|---|---|---|
| `Lease<T>` | creationTime / renewalTime / evictionTime / ttl（Property 动态读）；`renew()` 带 tryLock、过期后拒绝续约（粘滞）并 `markUpdate()` 计数；`evict()` 只置 evictionTime（幂等，首写胜出）；`isExpired() = now > renewalTime + ttl \|\| isEvicted()` | `.../lease/Lease.java` |
| `LeaseManager<T>` | 泛型租约池：`ConcurrentHashMap<T, Lease<T>>`；后台 clean（默认 2 线程 × 1s）：全表扫描 → 逐 lease tryLock → 非 evicted 且过期且 safeChecker 通过才摘 → 摘除时若缓存中已是更新的租约（creationTime 更大）则放回（保护并发重注册）→ 回调 `onClean` | `.../lease/LeaseManager.java` |
| `LeaseUpdateSafeChecker` | Eureka 式自我保护：滑动窗口统计成功续约量；每秒 safeCheck：窗口计数 > 历史 maxCount 则更新；`maxCount ≥ 50` 且 `窗口计数×100/maxCount < 85` 置 unsafe；maxCount 超 10min 未更新则回落当前窗口值。**只挡过期清理，不挡显式 evict** | `.../lease/LeaseUpdateSafeChecker.java` |

LeaseManager 配置键（两个实例分别为 `artemis.service.registry.instance` 与 `...legacy-instance`）：

| 配置键后缀 | 默认 | 范围 |
|---|---|---|
| `.lease-manager.data.init-capacity` | 50000 | 10k–1M |
| `.lease-manager.clean-task.thread-count` | 2 | 1–10 |
| `.lease-manager.clean-task.init-delay` | 1000ms | 0–10s |
| `.lease-manager.clean-task.run-interval` | 1000ms | 100–5000ms |
| `.lease-manager.lease.ttl` | 20000ms（两池代码默认相同） | 10s–7d |
| `.lease-manager.lease-update-safe-checker.enabled` | true | — |
| `.lease-manager.lease-update-safe-checker.time-window` | 10000ms | 10s–5min |
| `.lease-manager.lease-update-safe-checker.percentage-threshold` | 85 | 50–100 |
| `.lease-manager.lease-update-safe-checker.max-count-threshold` | 50 | 0–1M |
| `.lease-manager.lease-update-safe-checker.max-count-reset-interval` | 10min | 1min–24h |

> legacy 池 TTL 90s 来自发布配置 `artemis.properties`（原仓库）；legacy 判定 = `metadata.java_registry` 非空白，**按当次请求**重新判定（池迁移经 data-not-found → 补注册完成，旧池租约靠 creationTime 保护静默消失，registry-lease F6）。

### 1.4 任务分发（taskdispatcher）

| 类 | 职责 | 证据（原仓库） |
|---|---|---|
| `Task` / `AbstractReplicationTask` | taskId / submitTime / expiryTime / batchingEnabled / errorCode | `.../taskdispatcher/Task.java`；`artemis-service/.../replication/AbstractReplicationTask.java` |
| `TaskAcceptor<T,W>` | 接收线程模型：收新任务与重试任务 → `writeCompleteWait`(5ms) → `drainAccept`：新任务按 taskId 放入缓冲（同 taskId 覆盖并**继承旧 submitTime**）；重试任务 `addFirst` 插队队首且 submitTime 置为队首-1；reaccept 时同 taskId 已有新任务则丢弃重试任务（reaccept-drop）；缓冲满（≥ max-buffer-size）丢最老整批 | `.../taskdispatcher/TaskAcceptor.java` |
| `BatchingTaskAcceptor` | 批量工作单元：凑够 250 条或队头等待超 2s 成批；出队时丢弃过期任务 | `.../taskdispatcher/BatchingTaskAcceptor.java` |
| `SingleItemTaskAcceptor` | 单条工作单元（register/unregister 复制默认通道）；缓冲满丢队首、出队滤过期 | `.../taskdispatcher/SingleItemTaskAcceptor.java` |
| `TaskExecutor<T,W>` | N 个 daemon 工作线程 pollWork → process → 失败任务中 RERUNNABLE 集合的 `reaccept`，不可重试的丢弃并 warn。⚠ **批量通道截断缺陷**：同批遇第一个可重试任务即 `reaccept + return`，其余失败任务（含可重试）被静默丢弃（`TaskExecutor.java:110-113`，replication-cluster logic §7.1） | `.../taskdispatcher/TaskExecutor.java` |
| `TrafficShaper` | 按错误码的发送退避：`markFail(errorCode)` 记失败时刻，`transmissionDelay()` 在 fail-delay 内 sleep。⚠ **默认空 map = 无退避延迟**；仅显式配置的条目在值缺省时取 10ms、上限 10s（基线 §8-8 勘误） | `.../taskdispatcher/TrafficShaper.java` |
| `TaskErrorCode` | 任务级错误码（RERUNNABLE = {RateLimited, RerunnableFail}；网络异常归 UNKNOWN 可重试） | `.../taskdispatcher/TaskErrorCode.java` |

taskdispatcher 配置键：

| 配置项 | 默认 | 范围 |
|---|---|---|
| `.task-acceptor.max-buffer-size` | 10000 | 100–100k |
| `.task-acceptor.write-complete-wait` | 5ms | 0–200ms |
| `.task-acceptor.accept-list.init-capacity` | 10000 | 0–100k |
| `.task-acceptor.reaccept-list.init-capacity` | 1000 | 0–100k |
| `.batching.task-acceptor.max-batching-size` | 250 | 10–10k |
| `.batching.task-acceptor.max-batching-delay` | 2000ms | 1s–10s |
| `.task-executor.thread-count` | 20 | 1–100 |
| `.traffic-shaper.fail-delay`（map：错误码→ms） | **空 map（无退避）** | ≤10s |

### 1.5 服务接口与消息模型

- registry：`RegistryService`（register/heartbeat/unregister）+ 请求/响应对；批量语义接口 `HasInstances` / `HasFailedInstances` / `FailedInstance`；`HeartbeatEvent(Listener)` 为预留死代码（全仓库无使用点）。
- discovery：`DiscoveryService`（lookup/getService/getServices/getServicesDelta）+ `DiscoveryConfig`（serviceId + regionId/zoneId + discoveryData map；⚠ `clone()` 不拷贝 regionId/zoneId）+ `DiscoveryFilter` SPI + 请求/响应对。
- cluster：`ClusterService` + `ServiceCluster` + `ServiceNode`（zone + url）+ `ClusterChangeEvent/Listener`。
- `ErrorCodes`（唯一错误码事实源）：`success/partial_fail/bad-request/rate-limited/no-permission/data-not-found/internal-service-error/service-unavailable/unknown`；rerunnable = {rate-limited, unknown}；serviceDown = {internal-service-error, service-unavailable}。
- 证据：`.../registry/*.java`、`.../discovery/*.java`、`.../cluster/*.java`、`.../ErrorCodes.java`、`.../util/ResponseStatusUtil.java`（原仓库）。

### 1.6 通用 util

| 工具 | 职责 | 证据（原仓库） |
|---|---|---|
| `SearchTree<K,V>` | 级联键匹配树：`add(keys, value)` / `first(keys)` 沿 key 列表**逐级精确匹配**（无通配），途中命中非空 value 即返回——group 级摘除按四级 groupKey 对实例五级 key 的精确前缀匹配 | `.../util/SearchTree.java` |
| `ServiceGroupKeys` | groupKey 构造/解析：`of(instance)` = serviceId/regionId/zoneId/(groupId→default)/instanceId 小写拼接 | `.../util/ServiceGroupKeys.java` |
| `ServiceGroups` | 权重/默认分组语义；⚠ `isLocalZone` 全仓库零调用（死代码） | `.../util/ServiceGroups.java` |
| `RouteRules` | 保留规则名、默认规则展开、`generateGroupInstances`（canary 组实例解析：instanceId 精确 ∪ groupKey 前缀，并集去重） | `.../util/RouteRules.java` |
| `DiscoveryConfigs` | 灰度元数据 key（appid/subenv）读写帮助函数。⚠ **全链路零消费，纯协议预留**（基线 §8-1 勘误；traffic-governance FR-TG-09） | `.../util/DiscoveryConfigs.java` |
| `SameRegionChecker` / `SameZoneChecker` | region/zone 一致性校验（大小写不敏感；blank 视为不匹配） | `.../util/SameRegionChecker.java`、`SameZoneChecker.java` |
| checker 包 | `ValueCheckers`、`InstanceChecker`（仅 serviceId/instanceId/url 非空白，无格式/范围校验）、`InstancesChecker`（⚠ main 无调用方）、`ServiceChecker`、`DiscoveryConfigChecker` | `.../checker/*.java`、`.../util/*Checker.java` |
| 其他 | `InstanceChangeComparator`（⚠ 同 changeTime 对不同实例双向返回 -1，违反 Comparator 契约）、`StringUtil.toJson`、`RequestExecutor` | `.../util/` |

### 1.7 metric / trace（开源空壳）

- `MetricLoggerHelper`：全部方法体为空——调用点遍布 server/service，但无输出（`.../metric/MetricLoggerHelper.java`，原仓库）。
- `ArtemisMetricManagers.DEFAULT`：由 `artemis.metric.default.managers-provider` 注入，缺省 NullProvider。
- `ArtemisTraceExecutor` / `ArtemisTraceFactory`：trace 门面，受 `artemis.trace.enabled` 控制。

---

## 2. artemis-service（服务端内核）

### 2.1 注册表内存仓库 RegistryRepository

- 数据结构：`_services: ConcurrentHashMap<serviceId, Service>`、`_leases: ConcurrentHashMap<serviceId, ConcurrentHashMap<instanceId, Lease>>`、`_instanceChangeSet: ConcurrentSkipListSet`（按 changeTime 排序；容量超限丢最老）。⚠ **键语义分裂**：租约池以 Instance（InstanceKey，大小写不敏感）为键覆盖，`_leases` 以原始大小写字符串为键——混大小写注册产生**永不清理的发现残留**（registry-lease logic §7.1）。
- `register`：校验 → 池选择（当次 metadata 判定）→ new Lease 无条件覆盖 → 发 NEW 事件（重复注册也是 NEW，无 diff）。`heartbeat`：取租约 renew，缺失/过期返回 false（上层转 data-not-found）。`unregister`：`lease.evict()`（由清理线程真正摘除）。
- `onLeaseClean`：existing.creationTime > cleaned.creationTime 则放回且不发 DELETE（双池迁移/覆盖保护）；真正摘除发 DELETE；实例清空连 Service 移除。
- 查询族：`getService`（clone 壳装配）、`getServices`、`getInstances`、`getLeases(...)` 四重载。
- 配置：`artemis.service.registry.data.init-capacity`（10000，1k–100k）、`...instance-change.max-buffer-size`（10000，1k–1M）、`...instance-change.poll-wait`（20ms）。
- 证据：`artemis-service/.../registry/RegistryRepository.java`（原仓库）。

### 2.2 注册服务 RegistryServiceImpl

限流（`artemis.service.registry` 默认 100k QPS）→ `RegistryTool` 批量执行 → 成功后复制（register→RegisterTask、heartbeat 成功→HeartbeatTask、unregister→UnregisterTask）。三 API 批量 + failedInstances 部分失败语义。证据：`.../registry/RegistryServiceImpl.java`（原仓库）。

### 2.3 请求准入与执行框架 RegistryTool

校验顺序：checkRequest（空→bad-request）→ checkRegistryStatus（非复制要求 canServiceRegistry，否则 service-unavailable；复制豁免）→ 逐实例 checkSameZone（region 必须相同否则 no-permission，复制不豁免；非复制 zone 须相同除非 allowRegistryFromOtherZone，复制豁免）→ 业务异常 internal-service-error / 心跳 miss data-not-found → 全成 success / 部分 partial_fail。证据：`.../registry/RegistryTool.java`（原仓库）。

### 2.4 注册数据复制（replication）

| 组件 | 职责 | 证据（原仓库） |
|---|---|---|
| `ReplicationManager<T>` | 双通道分发：按 `task.batchingEnabled()` 路由批量/单条 TaskDispatcher | `.../replication/ReplicationManager.java` |
| `RegistryReplicationManager` | 单例装配，managerId=`artemis.service.registry.replication` | `.../registry/replication/RegistryReplicationManager.java` |
| 三种 Task | batching-enabled 默认：heartbeat true、register/unregister false（**可热切换**）；task-ttl 默认 5000ms（自首次提交起算、跨重试不刷新），过期出队即丢 | `.../{RegisterTask,UnregisterTask,HeartbeatTask}.java` |
| `RegistrySingleItemTaskProcessor` / `RegistryBatchingTaskProcessor` | 单条执行 / 按任务类型 × serviceUrl 二维分组批量执行 | 同包 |
| `RegistryReplicationTool` | 扇出执行器：遍历 otherNodes()，**按探测状态表跳过不可服务节点（跳过不生成失败任务）**；对每 peer HTTP；失败按错误码映射 TaskErrorCode；广播失败**退化为按 peer 定向重试**（成功节点不重发） | `.../RegistryReplicationTool.java:127-196` |
| `RegistryReplicationServiceImpl` | 接收端：register 直接入库（无条件覆盖）；**heartbeat 遇缺失实例同步补注册**；getServices 全量返回（无 readiness 门、zone 参数不生效）；四操作共用限流 1M | `.../RegistryReplicationServiceImpl.java` |
| `RegistryReplicationServiceClient` | peer HTTP 客户端（static 共享连接池）：heartbeat socket-timeout 200ms、get-services 2000ms；⚠ register/unregister 未设显式超时 | `.../RegistryReplicationServiceClient.java` |

### 2.5 集群管理（cluster）

| 组件 | 职责 | 证据（原仓库） |
|---|---|---|
| `ServiceCluster` | 静态成员拓扑（zoneId→urls multimap），scf 配置 + `ClusterChangeEvent`（空配置跳过更新；发布形态下变更需重启） | `artemis-common/.../cluster/ServiceCluster.java` |
| `ClusterManager` | 五个 volatile 视图（local/zone 分组）；本节点识别 = URL 含本机 `ip[:port]` 子串；单线程每 5s fixed-delay **串行**探测 peer `/api/status/node.json`（3 次重试，host 不可达即断），状态表整体重建（探测语义 = peer 自声明状态，不可达呈现 UNKNOWN）。⚠ 成员视图仅在新列表**非空**时替换——清空场景旧成员残留；探测调用无显式超时（replication-cluster logic §7.7/7.9） | `.../cluster/ClusterManager.java` |
| `NodeManager` | 本节点状态机：初始 STARTING；daemon 每 1s 执行 initializers 直到 UP。⚠ UP 为终态**无回退路径**；DOWN 仅来自 force-down 且**粘性**（撤销不恢复）；UP 后平面级 force-down 被压制（logic §7.4–7.6） | `.../cluster/NodeManager.java` |
| `NodeInitializer` | 启动门控 SPI：REGISTRY / DISCOVERY 两目标 | `.../cluster/NodeInitializer.java` |
| `RegistryReplicationInitializer` | 冷启动：localZoneOtherNodes 优先、失败试 otherZoneNodes，拉 services.json 全量逐服务复制语义 register 重建。⚠ 成功标准 `instanceCount > 0`——**空集群无法自举到 UP**，须 force-up 引导（logic §7.3） | `.../RegistryReplicationInitializer.java` |
| `ServiceNodeStatus` | status（starting/up/down）+ canServiceRegistry/Discovery + allow*（canService 判定口径：UP 恒可、DOWN 恒不可、STARTING/UNKNOWN 看标志——与 executeInitializers 原始标志口径不一致 ⚠）。⚠ 位于 **artemis-service** 而非 common；equals 漏两个 allow* 而 hashCode 含（契约不一致） | `artemis-service/.../cluster/ServiceNodeStatus.java`、`util/ServiceNodeUtil.java` |
| `ClusterServiceImpl` | up-{registry,discovery}-nodes：按可服务 + zone 匹配（或放开）**过滤**（无排序）；空列表 data-not-found；限流 10k | `.../ClusterServiceImpl.java` |

节点运维配置键：

| 配置键 | 默认 | 说明 |
|---|---|---|
| `...node.status.force-up` / `registry.force-up` / `discovery.force-up` | false | 强制上线（整体/单平面；force-up 跳过全部初始同步 ⚠） |
| `...node.status.force-down.{本机IP}` 及 registry/discovery 变体 | false | 强制下线（down 后判覆盖 up） |
| `...node.init.sync-interval` | 1000ms | 启动同步循环周期 |
| `artemis.service.registry.allow-from-other-zone` / `...discovery...` | false（发布配置 true ⚠ 漂移） | 放开跨 zone 校验 |

### 2.6 发现服务（discovery + 版本化缓存）

| 功能 | 行为 | 证据（原仓库） |
|---|---|---|
| lookup | 批量实时：校验 → readiness → 同 zone → 逐 config 直读注册表 → 过滤器链（逐 filter 独立 try/catch **fail-open**）→ 不存在的服务返回空 Service（⚠ lookup 的空服务过过滤器链、service 端点不过，两端不一致） | `.../discovery/DiscoveryServiceImpl.java` |
| getServices / getServicesDelta | 版本化缓存快照（version=毫秒时间戳，3 份）/ 预计算差集（未命中 data-not-found 逼全量）。**两接口客户端均未使用** | 同上、`.../cache/VersionedCacheManager.java` |
| DiscoveryFilters | 全局过滤器注册表（append-only，顺序=注册顺序：Group → Management） | `.../discovery/DiscoveryFilters.java` |

发现缓存配置：`.versioned-cache.cache-count` 3、`.cache-refresh.init-delay` 60s、`.cache-refresh.interval` 30s（managerId=`artemis.service.discovery`）。

### 2.7 变更推送 NotificationCenter

10 个 daemon worker 阻塞消费变更跳表（poll-wait 20ms）→ NotificationFilter 链（DELETE/RELOAD 恒放行）→ 广播给全部 subscriber（server 侧单播 + 全广播两个 handler；同一 worker 线程串行调用）。⚠ 发送失败关会话即丢该条（at-most-once，无 per-subscriber 队列）；慢消费者占住 worker。证据：`.../discovery/notify/NotificationCenter.java`（原仓库）。

### 2.8 状态服务 StatusServiceImpl

`/api/status` 六端点（node 不限流，其余共用限流 30/10s）：node（自声明状态）/ cluster（探测视图）/ leases、legacy-leases（租约明细 + 自我保护统计，GET appIds 过滤）/ config（全部配置 + 来源）/ deployment（身份快照）。证据：`.../status/StatusServiceImpl.java`（原仓库）。

### 2.9 限流器 ArtemisRateLimiterManager

| 限流键 | 默认 QPS | 范围 |
|---|---|---|
| `artemis.service.registry` | 100000 | 1k–1M |
| `artemis.service.registry.replication` | 1000000 | 1k–10M |
| `artemis.service.cluster` | 10000 | 100–100k |
| `artemis.service.status` | 30 | 1–10k |
| `artemis.service.management.group` | 30 | 1–1000 |

发现查询通道（lookup/service）无限流配置。证据：`.../ratelimiter/ArtemisRateLimiterManager.java` + 各 ServiceImpl 构造器（原仓库）。

### 2.10 util

`HttpClientUtil`（异常分类）、`ServiceNodeUtil`（canService*/isUp/isDown 判定 + `checkCurrentNode`：管理写要求本节点 status=UP）。证据：`.../util/`（原仓库）。

---

## 3. artemis-management（管理面）

### 3.1 模块组成与初始化

`ManagementInitializer.init()`（`artemis.management.enabled=true` 时由 ArtemisServer 调用）：DataConfig → 三缓存仓库 init → 注册过滤器（Group → Management）与 NotificationFilter → DISCOVERY 门控（= Management/Group 两仓库最近刷新均成功）。刷新周期：ManagementRepository **1s**、Group/ZoneRepository **5s**。证据：`.../ManagementInitializer.java`（原仓库）。

### 3.2 实例 / 服务器摘除运维

- `operate-instance` / `operate-server`：下线 = 写操作记录（complete=false，upsert）；恢复 = 删记录（complete=true）。⚠ 恢复按（业务键 + operation）删**单条**——多条叠加需逐条按原 operation 字符串恢复；operation 值无枚举校验，任意非空字符串即构成摘除（operations-audit logic §7.3/7.4）。写后 `waitForPeerSync()` = sleep 2s；写前 `checkCurrentNode`。
- `isInstanceDown` 四级短路 OR：instance 记录（存在即 down）→ server 记录（regionId + instance.ip，**跨 serviceId**）→ zone（服务粒度）→ group（SearchTree 前缀）。
- `getServices`（管理视角）：每实例标 up/down + 注入 creationTime/renewalTime/ttl 进 metadata；status 按摘除判定覆写。
- `destroyServers`：⚠ **死代码**（无端点无调用方）；且 instance 侧删除条件为 `instance_id = serverId`，与实例键实际形态不符，即使调用也清不到正常记录（operations-audit FR-OA-09）。
- 合成事件：server 级合成 DELETE/NEW **只按 IP 匹配不比 regionId**——跨 region IP 复用会推虚假 DELETE 且被推送过滤器放行（operations-audit logic §7.2）。
- 证据：`.../ManagementServiceImpl.java`、`ManagementRepository.java`（原仓库）。

### 3.3 zone 摘除运维

ZoneKey = serviceId+regionId+zoneId（按服务摘 zone，无全 zone 形态）；操作语义同「记录即状态」。⚠ **推送盲区**：diff 只在 serviceId 集合对称差层面——同服务已有记录时再摘/恢复另一 zone **不触发任何推送**，仅靠缓存刷新后的过滤静默生效；zone 写路径无 waitForPeerSync（operations-audit logic §7.1/§7.9）。证据：`.../ZoneRepository.java`、`ZoneServiceImpl.java`（原仓库）。另：`getAllZoneOperations(regionId)` 忽略参数恒返全量 ⚠。

### 3.4 流量治理（group 域）

数据模型：`Group`（四级 key + status）、`RouteRuleInfo`、`RouteRuleGroup`（**weight + unreleasedWeight 双列**）、`GroupInstance`、`ServiceInstance`（逻辑实例）、`GroupOperations`、`GroupTags`。

| 能力 | 行为 | 证据（原仓库） |
|---|---|---|
| CRUD 族 | 七族对象各自 insert/update/delete/get/get-all，经 BusinessDao（写 + log 双写）；route_rule/group 软删（可复活），route_rule_group/group_instance 硬删 ⚠ | `GroupRepository.java`、`GroupServiceImpl.java` |
| 两段式权重发布 | 编辑只写 `unreleased_weight`；`release` 拷贝到 `weight` 生效；`publish` 反向（无 REST 暴露 ⚠）。⚠ **SQLite/Generic 分支 upsert 写 unreleased 同时置 weight=NULL——两段式被破坏**（仅 MySQL 分支语义完整） | `group/dao/RouteRuleGroupDao.java` |
| create-route-rule | 一步建规则+组+绑定（组合事务；weight 无范围校验，靠 fixWeight 兜底） | `group/dao/BusinessDao.java` |
| 逻辑实例 | service_instance 表全量注入 `logicInstances`；12 字段指纹 diff → reload；不参与租约；metadata JSON 解析失败静默空 map | `GroupRepository.java:540-567` |
| 分组路由展开 | 仅 ACTIVE 规则 × ACTIVE 组参与（装载期过滤）；权重取 released 列；routeId 实为规则名 ⚠ | `GroupRepository.java:399-467` |
| 组级摘除 | group-operation → SearchTree 四级 key 对实例五级 key 精确前缀匹配 | `GroupRepository.java:372-382` |
| GroupServiceImpl 限流 | 30 QPS；写操作 checkCurrentNode + waitForPeerSync | `GroupServiceImpl.java` |

### 3.5 一键 Canary

`update-canary-ips`：get-or-create 规则（canary-route-rule，可复活软删）→ get-or-create 组（name=appId、zoneId="canary"）→ get-or-create 绑定（仅写 unreleased=5，**永不需要 release**）→ IP 集**全量覆盖**绑定（空 = 清空）。⚠ 匹配约定 `GroupInstance.instanceId` = **纯 IP**（宿主 instanceId 为 ip:port 形态时静默不生效）；四步无整体事务，幂等重调收敛；同名 canary 规则只展开第一条。证据：`.../canary/CanaryServiceImpl.java`、`CanaryServices.java`（原仓库）。

### 3.6 审计日志（9 类查询）

全部 `*_log` 表双写。⚠ 快照语义（勘误，原「操作前后数据快照」不成立）：**单快照**——delete 记删前值、insert/update 记写后值；instance/server 操作日志**无实体快照且无 reason**（表结构无）；zone 日志 reason 模型有但 insert SQL 无该列（不落库）。查询过滤字段实际集 = 业务键 + operation + operatorId + complete（group-logs 另有 name/appId）；**token / reason / 时间段均不可过滤**。`OperationContext` 随写传递，token 只存不验（instance/server 路径连非空都不要求）。证据：`.../ManagementLogServiceImpl.java`、各 LogDao（原仓库）。

### 3.7 与数据面的三个集成点

| 集成点 | 行为 |
|---|---|
| `GroupDiscoveryFilter` | 注入 logicInstances（全量）与 routeRules；canary 规则展开组成员（instanceId 精确 ∪ groupKey 前缀）。⚠ canary 展开直接 setInstances **写共享缓存对象**（并发竞争）；摘除过滤不清路由组内已展开成员（down 实例经路由视图可见） |
| `ManagementDiscoveryFilter` | 从 instances 与 logicInstances 移除四级判定 down 的实例 |
| `ManagementNotificationFilter` | down 实例的 NEW/CHANGE 推送置 null 丢弃（DELETE/RELOAD 恒放行） |

证据：`.../GroupDiscoveryFilter.java`、`ManagementDiscoveryFilter.java`、`ManagementNotificationFilter.java`（原仓库）。

### 3.8 DAO / DB 层

- `DataConfig`：dbcp2 + JdbcTemplate；默认 MySQL，SQLite 分支（env/system property 优先级）；`isMySQL()` 判定含 `System.out.println` 调试残留。
- DAO 清单：instance/server 域 4 个、group 域 **15 个**（含 BusinessDao 聚合门面）、zone 域 2 个 = **21 个 DAO 类**（全单例 + JdbcTemplate 直写 SQL）。
- 表结构：20 张表 = 10 业务 + 10 日志——**DDL 级完整描述（字段/类型/键/索引、软删与时间戳语义、DDL↔DAO 不一致、MySQL↔SQLite 差异）见 [domains/db-schema.md](domains/db-schema.md)**。
- ⚠ SQLite 建表漂移：`SQLITE_SETUP.md` 宣称「首次启动自动创建表结构」，但生产代码（`DataConfig` 仅按 driver 选 DAO，`ManagementInitializer` 仅 init 缓存）**无任何建表逻辑**；唯一建表代码在测试（`TestDatabaseInitializer` 执行测试用 schema.sql）。

---

## 4. artemis-server（REST / WebSocket 接入层）

11 个 Controller（10 个 `rest/controller/` + `websocket/WsStatusController`）+ websocket 包 9 个类。REST 全 JSON，多数查询端点 GET/POST 双形态。

> **逐端点请求/响应字段契约、GET 参数名与 required、errorCode 集、死端点标记见 [domains/api-contract.md](domains/api-contract.md)**；WS 三通道报文格式见 [domains/client-sdk-api.md](domains/client-sdk-api.md) §3。下表仅列路径与用途。

### 4.1 数据面端点总表（5 组 20 个路径）

**（1）`/api/registry/`** — `RegistryController`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/registry/register.json` | 批量注册（instances[] → failedInstances[]） |
| POST | `/api/registry/heartbeat.json` | 批量心跳续约。⚠ 客户端无调用方（WS 为唯一心跳通道；与 WS 入口同一服务管线，registry-lease FR-RL-05） |
| POST | `/api/registry/unregister.json` | 批量注销 |

**（2）`/api/replication/registry/`** — `RegistryReplicationController`：register / heartbeat / unregister（复制通道，heartbeat 缺实例自动补注册）+ services.json（GET 需 regionId，zoneId 可选但**实际不生效**）。

**（3）`/api/cluster/`** — `ClusterController`：up-registry-nodes.json / up-discovery-nodes.json（过滤非排序；空列表 data-not-found）。

**（4）`/api/status/`** — `StatusController` + `WsStatusController`：node / cluster / leases / legacy-leases / config / deployment / websocket/connection.json（`{"registry":N,"discovery":M}`）。

**（5）`/api/discovery/`** — `DiscoveryController`：lookup.json（批量实时）、service.json（单服务）、services.json（版本化缓存）、services-delta.json（客户端未使用）。

### 4.2 管理面端点总表（5 组 57 个路径）

常量源：`artemis-management/.../config/RestPaths.java`（原仓库）。

**（1）`/api/management/`（10 个）** — `ManagementController`：operate-instance / operate-server / instance-operations / server-operations / all-instance-operations(POST+GET) / all-server-operations(POST+GET) / instance-down / server-down / services(POST+GET) / service。

**（2）`/api/management/group/`（32 个）** — `ManagementGroupController`，七族：

| 族 | 端点 |
|---|---|
| route-rule（6） | insert / update / delete / get / get-all / create-route-rule |
| route-rule-group（6） | insert / update / **release** / delete / get-all / get |
| group（5） | insert / update / delete / get / get-all |
| group-tag（5） | insert / update / delete / get / get-all |
| group-operation（4） | operate-group-operations / operate-group-operation / get-all / get |
| group-instance（3） | insert / delete / get |
| service-instance（3） | insert / delete / get |

**（3）`/api/management/log/`（9 个）** — `ManagementLogController`（过滤字段见 §3.6）。

**（4）`/api/management/zone/`（5 个）** — `ManagementZoneController`：operate-zone-operations / get-all-zone-operations / get-zone-operations / get-zone-operations-list / is-zone-down。

**（5）`/api/management/canary/`（1 个）** — `CanaryController`：update-canary-ips。

### 4.3 WebSocket（3 个端点）

注册于 `WebSocketEndpointConfig`，全部 `setAllowedOrigins("*")` + `WsIPBlackList` 拦截器：

| 端点 | Handler | 协议行为 |
|---|---|---|
| `/websocket/registry/heartbeat` | `HeartbeatWsHandler` | 客户端每 5s 发全量实例；⚠ 解析异常回**固定 success**（错误吞掉）；服务端会话 TTL 6min 强制关闭 |
| `/websocket/discovery/instance-change` | `ServiceChangeWsHandler` | 一条消息 = 一个 DiscoveryConfig = 订阅单服务；可多服务；幂等；**无订阅确认**；推送 `synchronized(session)` 同步发送，失败关会话即丢（at-most-once）；订阅项随会话死惰性清理 |
| `/websocket/discovery/all-instance-change` | `AllServicesChangeWsHandler` | 无订阅语义全量广播（SDK 不消费，供外部系统）；与会话表/单播 handler 同构 |

其余类：`ArtemisWsHandler`（基类：DelayQueue 会话 TTL 治理，60s 检查）、`WsIPBlackList`（唯一访问控制屏障）、`WsStatusController`、`DelayItem` / `InetSocketAddressHelper`、`WebSocketEndpointConfig`。证据：`artemis-server/.../websocket/`（原仓库）。

### 4.4 REST 基础设施与启动

`FilterConfig`（跨域 / ziplet 压缩 / HiddenHttpMethod）、`CustomObjectMapper` + `JsonSerializationHack`（大小写不敏感反序列化）、`ArtemisServer`（启动编排）、springfox swagger。证据：`artemis-server/.../rest/`、`artemis-package/pom.xml`（原仓库）。

---

## 5. artemis-client（客户端 SDK）

### 5.1 公开 API（宿主接入面）

| 类型 | API | 说明 |
|---|---|---|
| 入口 | `ArtemisClientManager.getManager(managerId, config)` | 静态单例（computeIfAbsent）；⚠ 同 managerId 二次调用**静默忽略新 config**；clientId = `artemis.client.{managerId}` 即配置前缀根 |
| 注册 | `RegistryClient.register / unregister` | 无 close / 无状态查询 / 无 unregisterListener（client-sdk FR-CS-02） |
| 发现 | `DiscoveryClient.getService / registerServiceChangeListener` | 事件携带克隆快照 |
| 扩展点 | `RegistryFilter` | 注册前实例过滤（心跳 + 补注册两处调用，操作副本不回写本地集） |

### 5.2 注册链路（客户端侧）

`InstanceRepository`：本地实例集 `AtomicReference<Set<Instance>>`；`register()` 先 HTTP unregister（失败不阻塞，异常全吞）再并入本地集，**不发 HTTP register**——真正注册靠心跳 miss → data-not-found/unknown → `registerToRemote` HTTP 补注册；空集心跳返回 null 不发送（但刷新 lastHeartbeatTime）。⚠ 本地集 HashSet 合并：同 InstanceKey 新数据 no-op 不替换。`InstanceRegistry`：检查线程 1s（≥interval 发送 / ≥ttl markdown 重建）；心跳响应 serviceDown → markdown。证据：`.../registry/`（原仓库）。

### 5.3 发现链路（客户端侧）

`ServiceRepository`：缓存 `Map<serviceId(小写), ServiceContext>`，永不失效；增量 update（DELETE 真删才回调 / NEW 恒回调无 diff）；⚠ 增量落地按推送内**原始大小写**查小写键——混大小写服务的增量静默丢弃（discovery FR-DIS-13）；首次 getService 同步 lookup 失败静默缓存空 Service。`ServiceDiscovery`：WS 订阅 + 60s 三层兜底（reload 失败集 + 空服务 + TTL 15min 全量）；批量失败整批记失败集后重抛。证据：`.../discovery/`（原仓库）。

### 5.4 三级地址容灾

`AddressRepository`：引导地址（动态 Property）→ 5min 拉 up-nodes（请求只带构造时固化的 region/zone；成功且非空才覆盖列表，**失败/空保留旧列表**）→ 随机选址；列表空才降级回引导地址。`AddressContext`：TTL 1h 强制轮换；`markUnavailable` 仅作废当前上下文，⚠ **不将地址移出候选列表**（无冷却/计数，坏地址可被反复选中）。证据：`.../common/AddressRepository.java`、`AddressContext.java`、`AddressManager.java`（原仓库）。

### 5.5 统一 HTTP 执行器 ArtemisHttpClient

固定循环 retry-times（5）× retry-interval（100ms）：非 serviceDown 非 rerunnable 直接返回；serviceDown / 异常 → markUnavailable 换址重试；rerunnable 重试；末次异常抛原始 / 耗尽抛 RuntimeException。每轮重建请求 + gzip。证据：`.../common/ArtemisHttpClient.java`（原仓库）。

### 5.6 WebSocketSessionContext

健康检查 1s（地址可用 && 会话未过期 5min && ping/pong 1s）；connect 限流 5 次/20s → handshake 5s → 成功替换旧会话 + 重订阅回调。⚠ `WebSocketContainer` 为 **JVM 全局单例**，`setDefaultMaxTextMessageBufferSize` 多 manager 互相覆盖（后构造者赢）。证据：`.../websocket/WebSocketSessionContext.java`（原仓库）。

### 5.7 客户端配置项总表（前缀 `artemis.client.{managerId}`）

| 配置键（前缀省略） | 默认 | 范围 | 作用 |
|---|---|---|---|
| `.service.domain.url` | ""（必配） | — | 集群引导地址 |
| `.registry` / `.discovery` `.http-client.retry-times / retry-interval` | 5 / 100ms | 1–10 / 0–1000ms | HTTP 重试 |
| `.instance-registry.heartbeat-interval / instance-ttl / heartbeat-checker` | 5s / 20s / 1s | — | 心跳三参数 |
| `.service-discovery.ttl` / 兜底轮询周期 | 15min / 60s | — | 全量刷新 / 兜底 |
| `.address.context-ttl` | 1h | 1min–24h | 节点轮换 |
| `.address-repository` 刷新周期 | 5min | 1–30min | 节点列表 |
| `.websocket-session.ttl / connect-timeout / ping-timeout` | 5min / 5s / 1s | — | 会话参数 |
| `.websocket-session.text-message.buffer-size` | 8KB | 8–32 | ⚠ 构造快照（读取时机口径见 config-reference §0） |
| `.websocket-session.reconnect-times` | 5 次/20s | 3–60 | 重连限流 |
| `.websocket-session.health-check` | 1000ms | 100ms–10min | 健康检查周期 |

线程模型（每 manager）：2 地址刷新 + 2 WS 健康检查 + 1 心跳检查 + 1 兜底轮询 + 1 回调 executor = 7+ 线程。⚠ 回调 executor 线程 **non-daemon** 且无 shutdown——阻止 JVM 退出（基线 §8-7 勘误；client-sdk logic §7.1）；两个地址刷新线程同名（dump 无法区分）。

### 5.8 死代码与已知缺陷

`RegistryServiceClient`（无调用方）、`InstancesChecker`、`ServiceGroups.isLocalZone`、`HeartbeatEvent(Listener)`、`CLUSTER_NODES` 死常量。缺陷明细（大小写残留 / 回调无界 / 全局容器等）见 [client-sdk-logic.md](domains/client-sdk-logic.md) §7 与 [discovery-logic.md](domains/discovery-logic.md) §7。客户端自带 14 个测试类（地址 / WS 基础设施有少量单测）。

---

## 6. artemis-package（启动与部署）

启动入口 `App`（fat jar main + WAR configure 双形态，先 `ArtemisServer.init()`）；配置三件套 application / artemis / data-source(.sqlite).properties；部署物 `deployment/artemis-management.sql` + `server.xml`。`-Dartemis.management.enabled=false` 跳过管理面（零 DB 依赖；此时 DISCOVERY 门控缺失视为成功）。⚠ `artemis.properties` 存在无效键（`replicaton` 拼写、`thread-pool-size`），重设计不继承。证据：`artemis-package/`（原仓库）。

## 7. artemis-test（集成测试）

24 个 DAO 测试类 + Group/Zone Repository 测试 + 进程内起服基础设施；约 57 个 @Test，仅覆盖 management DAO 层（SQLite 内存库）；service / server / client 端到端零测试。证据：`artemis-test/src/test/`（原仓库）。
