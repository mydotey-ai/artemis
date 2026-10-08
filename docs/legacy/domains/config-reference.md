# 配置项全量字典

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**契约层**制品——每一个被代码读取的配置键及其读取点、默认值、取值范围、所属组件、读取时机，供 1:1 对标复刻。**共 127 个键模式**（展开实例数另计）。
> 证据路径均相对原仓库根；依赖库（scf / caravan / http-rpc-util）以反编译版本为准并标注。

## 0. 配置体系与动态更新机制

**两个独立的 scf `ConfigurationManager`**：

- `ArtemisConfig`（`artemis-common/.../config/ArtemisConfig.java:29-45`）：源顺序 = **环境变量 → 系统属性 → `artemis-<deploymentEnv>.properties` → `artemis.properties`**，再套 `CascadedConfigurationSource`（级联因子 = 本机 IP）。**全部业务配置走它**。
- `DeploymentConfig`（`.../config/DeploymentConfig.java:38-52`）：源顺序 = `application.properties` → `application-<env>.properties` → 系统属性 → 环境变量。

### 动态更新能力：三层控制

**配置源由宿主注入**——产品不内置动态配置源（各组织的配置中心各不相同）。默认三件套（env / sysprops / properties 文件）是**静态源**：`PropertiesFileConfigurationSource` 构造时一次性 `Properties.load` 且**无文件监听**，三类源**从不发变更事件**。

**一个属性初始化后能否被动态更新，取决于三层同时允许**：

| 层 | 机制 | 位置 | 说明 |
|---|---|---|---|
| **1. 源层** | 配置源是否发变更事件 | 宿主注入 | 默认三件套 = 静态源（不发事件）；接入配置中心后可发 |
| **2. 声明层** | `PropertyConfig.isStatic()` | **产品 / 使用方在定义属性时声明** | 默认 `false`（可动态更新）；声明 `true` 表示「静态，不可动态变更」 |
| **3. 使用层** | 代码读取时机 | 产品代码 | **动态读**（运行期反复 `getValue()`）vs **构造快照**（构造 / 类加载只读一次，即便源可变也不更新） |

**声明层的确切语义**（scf 反编译证据）：

- `PropertyConfig.isStatic()` 注释：「whether the property is static (not dynamically changeable)，**default to false**」。
- 强制点 `DefaultConfigurationManager.onSourceChange`：收到源变更事件后，若 `isStatic()` 为 true → **记 warn 日志并忽略该变更**（原文：*"dynamic change for static property will be applied when app restart"*）。
- `CascadedConfigurationSource.makePropertyConfig` 用 `.setStatic(propertyConfig.isStatic())` **传播该标志**（级联不丢失）。
- 便捷方法（`getIntProperty(key, defaultValue)` / `getProperty(key, defaultValue, converter)` 等）**不暴露 setStatic** → 用它们创建的属性恒为 `isStatic = false`。

**原产品现状**：全部属性经便捷方法创建 → **声明层全部为「可动态更新」**（未使用 `isStatic`）。因此表中的**构造快照**项是**使用层**的结果（代码读一次写入字段），而非声明为 static——两者语义不同：

- 构造快照 = 该键**设计上不需要**运行期变更（线程数、schedule 周期、初始化容量等），属合理设计；
- 声明 static = **显式禁止**运行期变更（即便源可变）。

### 本表「读取时机」列的口径

- **动态读**：产品代码运行期反复调用 `getValue()`——**源层（动态源）+ 声明层（isStatic=false）满足时即时生效**；
- **构造快照**：仅构造 / 类加载读一次——**即使源可变也不更新**。

代码中大量 `addChangeListener`（NodeManager / ServiceCluster / SafeChecker）配合动态读，是**为接入动态配置源而预留的完整链路**。

**空值语义**：scf 把空字符串视为「未配置」→ `artemis.properties` 里的 `key=`（空白）等于未设置，回落到代码默认值。

## 1. artemis-common

### 1.1 DeploymentConfig（部署身份）

| 配置键 | 默认 | 取值范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `deployment.env` | 无 | 任意（trim + 小写） | `DeploymentConfig.java:39` | 构造快照（**仅 `System.getProperty`，不走 scf**；环境变量形式不生效） |
| `region.id` / `zone.id` / `app.id` / `app.path` | 无 | 任意 | `:58-65` | **类加载快照** |
| `app.port` | `8080` | 1–65535（越界回落默认） | `:61-62` | 类加载快照 |
| `app.protocol` | `http` | 任意 | `:63-64` | 类加载快照 |

### 1.2 trace / metric

| 配置键 | 默认 | 读取点 | 读取时机 |
|---|---|---|---|
| `artemis.trace.enabled` | `false` | `ArtemisTraceFactory.java:23-24` | 动态读 |
| `artemis.trace.factory-class` | blank → Null | `:26-27,32` | 构造快照（反射实例化） |
| `artemis.metric.default.managers-provider` | blank → NullProvider | `ArtemisMetricManagers.java:23-31` | 类加载快照（静态 DEFAULT） |

### 1.3 LeaseManager（`<mgr>` = `artemis.service.registry.instance` / `...legacy-instance`）

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `<mgr>.lease-manager.data.init-capacity` | 50000 | 10k–1M | `LeaseManager.java:89-91` | 构造快照 |
| `<mgr>.lease-manager.clean-task.thread-count` | 2 | 1–10 | `:92-94` | **构造快照** |
| `<mgr>.lease-manager.clean-task.init-delay` | 1000 | 0–10000 | `:95-97` | 构造快照 |
| `<mgr>.lease-manager.clean-task.run-interval` | 1000 | 100–5000 | `:98-100` | 动态读 |
| `<mgr>.lease-manager.lease.ttl` | 20000 | 10s–7d | `:101-103` | 动态读（`Lease.ttl()` 每次过期判定） |

### 1.4 LeaseUpdateSafeChecker（`<scId>` = `<mgr>.lease-manager.lease-update-safe-checker`）

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `<scId>.enabled` | true | boolean | `LeaseUpdateSafeChecker.java:103` | 动态读 |
| `<scId>.time-window` | 10000 | 夹到 [10000, 300000] 并向下取整到 1000 | `:104-105` | 动态读 |
| `<scId>.percentage-threshold` | 85 | 50–100 | `:106-107` | 动态读 |
| `<scId>.max-count-threshold` | 50 | 0–1M | `:108-109` | 动态读 |
| `<scId>.max-count-reset-interval` | 600000 | 60000–86400000 | `:110-112` | 动态读 |
| `<scId>.dynamic-scheduled-thread.init-delay` | 1000 | 100–60000 | `:121-125` | 构造快照 |
| `<scId>.dynamic-scheduled-thread.run-interval` | 1000 | 100–60000 | 同上 | 动态读 |

### 1.5 ServiceCluster

| 配置键 | 默认 | 取值格式 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `artemis.service.cluster.nodes` | 空 multimap | `zoneId:url1,url2;zone2:url3`（`;` 分 zone / `,` 分 url / `:` 分 k-v） | `ServiceCluster.java:25-26,66` | 动态读 + change listener |

### 1.6 taskdispatcher（`<d>` = `artemis.service.registry.replication.non-batching` / `.batching`）

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `<d>.task-acceptor.max-buffer-size` | 10000 | 100–100000 | `TaskAcceptor.java:94-95` | 动态读 |
| `<d>.task-acceptor.write-complete-wait` | 5 | 0–200 | `:96-97` | 动态读 |
| `<d>.task-acceptor.accept-list.init-capacity` | 10000 | 0–100000 | `:98-100` | 动态读 |
| `<d>.task-acceptor.reaccept-list.init-capacity` | 1000 | 0–100000 | `:101-102` | 动态读 |
| `<d>.task-acceptor.max-batching-size` | 250 | 10–10000 | `BatchingTaskAcceptor.java:29-30` | 动态读（仅批通道） |
| `<d>.task-acceptor.max-batching-delay` | 2000 | 1000–10000 | `:31-32` | 动态读（仅批通道） |
| `<d>.task-executor.thread-count` | 20 | 1–100 | `TaskExecutor.java:58-59` | **构造快照** |
| `<d>.traffic-shaper.fail-delay` | 空 map | `错误码:ms`（值夹到 [0,10000]，null→10） | `TrafficShaper.java:44-45,108-115` | 动态读 |

## 2. artemis-service

### 2.1 RegistryRepository

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `artemis.service.registry.data.init-capacity` | 10000 | 1000–100000 | `RegistryRepository.java:54-56` | 构造快照 |
| `artemis.service.registry.data.instance-change.max-buffer-size` | 10000 | 1000–1000000 | `:58-60` | 动态读 |
| `artemis.service.registry.data.instance-change.poll-wait` | 20 | 1–30000 | `:62-64` | 动态读 |

### 2.2 复制任务

| 配置键 | 默认 | 范围 | 读取点 |
|---|---|---|---|
| `artemis.service.registry.register.replication.batching-enabled` | false | boolean | `RegisterTask.java:14-15` |
| `artemis.service.registry.register.replication.task-ttl` | 5000 | 2000–30000 | `:17-19` |
| `artemis.service.registry.heartbeat.replication.batching-enabled` | **true** | boolean | `HeartbeatTask.java:14-15` |
| `artemis.service.registry.heartbeat.replication.task-ttl` | 5000 | 2000–**10000**（上限与另两者不同） | `:17-19` |
| `artemis.service.registry.unregister.replication.batching-enabled` | false | boolean | `UnregisterTask.java:14-15` |
| `artemis.service.registry.unregister.replication.task-ttl` | 5000 | 2000–30000 | `:17-19` |

### 2.3 服务端内部 HTTP 客户端超时

| 配置键 | 默认 | 范围 | 读取点 |
|---|---|---|---|
| `artemis.service.registry.replication.heartbeat.client.socket-timeout` | 200 | 50–5000 | `RegistryReplicationServiceClient.java:31-33` |
| `artemis.service.registry.replication.get-applications.client.socket-timeout` | 2000 | 100–60000 | `:35-37` |
| `artemis.service.registry.status.get-leases.client.socket-timeout` | 10000 | 100–300000 | `StatusServiceClient.java:26-28` |
| `artemis.service.registry.status.get-cluster-node.client.socket-timeout` | 200 | 100–10000 | `:30-32` |

### 2.4 ClusterManager / NodeManager

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `artemis.service.cluster.nodes.status-update.interval` | 5000 | 100–600000 | `ClusterManager.java:43-45` | 构造快照（schedule 周期） |
| `artemis.service.cluster.nodes.status-update.fail-retry-times` | 3 | 0–10 | `:46-48` | 动态读 |
| `artemis.service.cluster.node.status.force-up` | false | boolean | `NodeManager.java:29-31` | 动态读 + listener |
| `artemis.service.cluster.node.status.registry.force-up` | false | boolean | `:33-34` | 动态读 + listener |
| `artemis.service.cluster.node.status.discovery.force-up` | false | boolean | `:36-37` | 动态读 + listener |
| `artemis.service.cluster.node.status.force-down.<本机IP>` | false | boolean | `:39-41` | 动态读 + listener |
| `artemis.service.cluster.node.status.registry.force-down.<本机IP>` | false | boolean | `:43-46` | 动态读 + listener |
| `artemis.service.cluster.node.status.discovery.force-down.<本机IP>` | false | boolean | `:48-50` | 动态读 + listener |
| `artemis.service.cluster.node.init.sync-interval` | 1000 | 50–600000 | `:52-54` | 动态读 |
| `artemis.service.registry.allow-from-other-zone` | false（**发布配置 = true**） | boolean | `:56-57` | 动态读 + listener |
| `artemis.service.discovery.allow-from-other-zone` | false（发布配置 = true） | boolean | `:59-60` | 动态读 + listener |

### 2.5 发现 / 通知 / 版本化缓存

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `artemis.service.discovery.notify.thread-count` | 10 | 1–100 | `NotificationCenter.java:36-37` | **构造快照** |
| `artemis.service.discovery.versioned-cache.cache-count` | 3 | 0–10 | `VersionedCacheManager.java:50-51` | 动态读 |
| `artemis.service.discovery.versioned-cache.cache-refresh.init-delay` | 60000 | 0–300000 | `:52-54` | 构造快照 |
| `artemis.service.discovery.versioned-cache.cache-refresh.interval` | 30000 | 1000–300000 | `:55-57` | 构造快照 |

### 2.6 限流器（5 个 id，每 id 3 键）

**限流键 = 传入 `getRateLimiter(id, ...)` 的 id 自身**，与 managerId `"artemis.service"` 无关。每 id：`<id>.rate-limiter.enabled`（默认 true）/ `.default-rate-limit` / `.rate-limit-map`（`identity:limit` map）。buffer 均 10s 窗口 / 1s 桶。

| 限流 id | default-rate-limit | 范围 | 读取点 |
|---|---|---|---|
| `artemis.service.registry` | 100000 | 1000–1M | `RegistryServiceImpl.java:37-39` |
| `artemis.service.cluster` | 10000 | 100–100000 | `ClusterServiceImpl.java:38-40` |
| `artemis.service.status` | 30 | 1–10000 | `StatusServiceImpl.java:62-64` |
| `artemis.service.registry.replication` | 1000000 | 1000–10M | `RegistryReplicationServiceImpl.java:52-55` |
| `artemis.service.management.group` | 30 | 1–1000 | `management/GroupServiceImpl.java:42-46` |

## 3. artemis-management

### 3.1 缓存刷新器（`dynamic-scheduled-thread.{init-delay,run-interval}`）

| 配置键 | init-delay 默认 | run-interval 默认（范围） | 读取点 |
|---|---|---|---|
| `artemis.management.data.cache-refresher.*`（instance/server 摘除） | 0 | 1000（200–60000） | `ManagementRepository.java:121-125` |
| `artemis.management.group.data.cache-refresher.*` | 0 | 5000（10–60000） | `GroupRepository.java:110-114` |
| `artemis.management.zone.data.cache-refresher.*` | 0 | 5000（10–60000） | `ZoneRepository.java:68-72` |

（`init-delay` 构造快照；`run-interval` 动态读。）

### 3.2 DB 同步等待

| 配置键 | 默认 | 范围 | 读取点 |
|---|---|---|---|
| `artemis.management.db-sync.wait-time` | 2000 | 0–60000 | `ManagementRepository.java:90-91`、`GroupRepository.java:83-84`（同名同默认） |

### 3.3 数据库（`DataConfig`，**非 scf**）

发布文件经 `BasicDataSourceFactory.createDataSource(prop)` 读取：

| 键 | MySQL 默认 | SQLite 默认 |
|---|---|---|
| `driverClassName` | `com.mysql.jdbc.Driver` | `org.sqlite.JDBC` |
| `url` | `jdbc:mysql://localhost:3306/artemis` | `jdbc:sqlite:./artemis.db` |
| `username` / `password` | `admin` / `123456`（明文） | 空 / 空 |
| `initialSize` / `maxActive` / `maxIdle` / `minIdle` / `maxWait` | 10 / 50 / 20 / 5 / 60000 | 5 / 20 / 10 / 2 / 30000 |

**直接读取的系统属性 / 环境变量**（优先级：`artemis.db.*` > `ARTEMIS_DB_*`）：

| 键 | 类型 | 读取点 |
|---|---|---|
| `artemis.db.driver` / `artemis.db.url` | System property | `DataConfig.java:72,77,180,185` |
| `ARTEMIS_DB_DRIVER` / `ARTEMIS_DB_URL` / `ARTEMIS_DB_CONFIG` | 环境变量 | `:83,88,94,119,127,168,173` |

**代码内置兜底**（无文件无 URL 时，与发布文件默认值**不同**）：MySQL driver、`jdbc:mysql://localhost:3306/artemis`、`root` / 空、5/20/10/2。

## 4. artemis-server

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `artemis.management.enabled` | `"true"` | 字符串（trim，大小写无关） | `ArtemisServer.java:18-19` | **System property**，构造快照 |
| `artemis.service.websocket.session.ttl` | 360000 | 300000–18000000 | `ArtemisWsHandler.java:42-43` | 动态读 |
| `artemis.service.websocket.session.health-checker.dynamic-scheduled-thread.init-delay` | 20 | 0–200 | `:38-44` | 构造快照 |
| `artemis.service.websocket.session.health-checker.dynamic-scheduled-thread.run-interval` | 60000 | 10000–3600000 | 同上 | 动态读 |
| `artemis.service.inet-socket-address.get-host.enabled` | true | boolean | `InetSocketAddressHelper.java:14-15` | 动态读 |
| `artemis.service.<ipBlackListId>.ws-ip.black-list.enabled` | true | boolean | `WsIPBlackList.java:25-28` | 动态读 |
| `artemis.service.<ipBlackListId>.ws-ip.black-list` | 空 List | List&lt;String&gt; | `:29-30` | 动态读 |

`<ipBlackListId>` 三个取值：`service-heartbeat` / `service-discovery` / `service-discoveries`（共 6 个具体键）。

## 5. artemis-client（`<c>` = `artemis.client.<managerId>`）

| 配置键 | 默认 | 范围 | 读取点 | 读取时机 |
|---|---|---|---|---|
| `<c>.service.domain.url` | `""` | 任意 URL | `AddressRepository.java:53` | 动态读 |
| `<c>.address.context-ttl` | 3600000 | 60000–86400000 | `AddressContext.java:36-37` | 动态读 |
| `<c>.address-repository.dynamic-scheduled-thread.init-delay` | 20 | 0–200 | `AddressRepository.java:54-59` | 构造快照 |
| `<c>.address-repository.dynamic-scheduled-thread.run-interval` | 300000 | 60000–1800000 | 同上 | 动态读 |
| `<c>.instance-registry.instance-ttl` | 20000 | 5000–86400000 | `InstanceRegistry.java:57-58` | 动态读 |
| `<c>.instance-registry.heartbeat-interval` | 5000 | 500–300000 | `:59-60` | 动态读 |
| `<c>.instance-registry.heartbeat-checker.dynamic-scheduled-thread.init-delay` | 1 | 1–3600000 | `:88-92` | 构造快照 |
| `<c>.instance-registry.heartbeat-checker.dynamic-scheduled-thread.run-interval` | 1000 | 500–90000 | 同上 | 动态读 |
| `<c>.service-discovery.ttl` | 900000 | 60000–86400000 | `ServiceDiscovery.java:46-47` | 动态读 |
| `<c>.service-discovery.dynamic-scheduled-thread.init-delay` | 0 | 0–200 | `:67-71` | 构造快照 |
| `<c>.service-discovery.dynamic-scheduled-thread.run-interval` | 60000 | 60000–86400000 | 同上 | 动态读 |
| `<c>.websocket-session.ttl` | 300000 | 300000–1800000 | `WebSocketSessionContext.java:54-55` | 动态读 |
| `<c>.websocket-session.connect-timeout` | 5000 | 1000–30000 | `:56-57` | 动态读 |
| `<c>.websocket-session.ping-timeout` | 1000 | 50–10000 | `:58-59` | 动态读 |
| `<c>.websocket-session.text-message.buffer-size` | 8 | 8–32（KB） | `:60-62` | **构造快照**（写 JVM 全局容器） |
| `<c>.websocket-session.health-check.dynamic-scheduled-thread.init-delay` | 20 | 0–200 | `:110-113` | 构造快照 |
| `<c>.websocket-session.health-check.dynamic-scheduled-thread.run-interval` | 1000 | 100–600000 | 同上 | 动态读 |
| `<c>.websocket-session.reconnect-times.rate-limiter.enabled` | true | boolean | `:105-108` | 动态读 |
| `<c>.websocket-session.reconnect-times.rate-limiter.default-rate-limit` | 5 | 3–60 | 同上 | 动态读 |
| `<c>.websocket-session.reconnect-times.rate-limiter.rate-limit-map` | 空 map | `identity:limit` | 同上 | 动态读 |
| `<c>.registry.http-client.retry-times` | 5 | 1–10 | `ArtemisHttpClient.java:45-46` | 动态读 |
| `<c>.registry.http-client.retry-interval` | 100 | 0–1000 | `:47-48` | 动态读 |
| `<c>.discovery.http-client.retry-times` | 5 | 1–10 | 同上 | 动态读 |
| `<c>.discovery.http-client.retry-interval` | 100 | 0–1000 | 同上 | 动态读 |

### 5.1 依赖库持有的键（http-rpc-util，仓库代码不直接读）

每个 `DynamicPoolingHttpClientProvider(clientId, manager)` 只读**一个**键：`<clientId>.default-request-config`（值类型 `DynamicPoolingHttpClientProviderConfig`）。clientId 出现 5 处：`<c>.address.http-client`、`<c>.registry.http-client`、`<c>.discovery.http-client`、`artemis.service.registry.replication`、`artemis.service.registry.status`，以及**死类** `artemis.client.registry-service`（`RegistryServiceClient.java:35-36`，无调用方）。

默认 POJO 字段（反编译 http-rpc-util 1.2.2）：`connectTimeout=1000` / `connectionRequestTimeout=1000` / `socketTimeout=10000` / `maxConnectionsPerRoute=10` / `maxTotalConections=100`（原文拼写）/ `connectionTtl=300000` / `connectionIdleTime=10000` / `cleanCheckInterval=5000` / `inactivityTimeBeforeValidate=5000` / `destroyDelayTime=60000` / `ioThreadCount=1` / `retryTimes=1` / `retryIOExceptions=[NoHttpResponseException]`。

⚠ **该键是事实死键**：Provider 只 `setValueType(...)`、未 `addValueConverter`——scf 的 `AbstractConfigurationSource.convert` 在无 converter 时只接受类型可赋的值，而 String 源永远返回 null → **恒用默认 POJO，无法经属性文件配置**（除非宿主注入返回该 POJO 的源）。

⚠ 注意：`<c>.{registry,discovery}.http-client.retry-times/interval` 是**另一套独立重试参数**（`ArtemisHttpClient` 自己的循环），与 Provider 内部的 `retryTimes` 并存。

## 6. 配置键总数统计

| 模块 | 键模式数 |
|---|---|
| artemis-common | 31（DeploymentConfig 7 + trace/metric 3 + LeaseManager 5 + SafeChecker 7 + ServiceCluster 1 + taskdispatcher 8） |
| artemis-service | 40（RegistryRepository 3 + 复制任务 6 + socket-timeout 4 + Cluster/NodeManager 11 + notify/cache 4 + 限流器 4×3 = 12） |
| artemis-management | 24（缓存刷新 6 + db-sync 1 + management.group 限流 3 + DBCP 9 + DB 属性/环境变量 5） |
| artemis-server | 11 |
| artemis-client | 21 |
| 依赖库 | 1 |
| **合计** | **127** |

**展开为具体键**（动态段）：`<mgr>` 租约相关 12×2 = 24；`<scId>` 随上 7×2 = 14；`<d>` 8×2 = 16；限流器 5×3 = 15；WS-IP 黑名单 3×2 = 6；`<本机IP>` force-down 3。

## 7. 死键与缺失键

### 7.1 死键（发布配置存在、代码不读取）

`artemis-package/src/main/resources/artemis.properties`（`artemis-test` 副本逐字相同）：

| 死键 | 为何死 |
|---|---|
| `artemis.service.registry.instance.lease-manager.thread-pool-size`（:13） | 代码读 `<mgr>.lease-manager.clean-task.thread-count` |
| `artemis.service.registry.legacy-instance.lease-manager.thread-pool-size`（:19） | 同上 |
| `artemis.service.registry.replicaton.batching.task-executor.thread-count`（:25） | 拼写 `replicaton`；代码实际前缀 `...registry.replication.batching...` |
| `artemis.service.registry.replicaton.non-batching.task-executor.thread-count`（:26） | 同上 |
| `artemis.service.registry.replicaton.batching.task-acceptor.max-batching-delay`（:27） | 同上 |

⚠ 推论：发布配置里 `replicaton` 尝试设置的线程数是死键，**实际生效线程数 = 代码默认 20**；`max-batching-delay=`（空白）→ 默认 2000 生效。

### 7.2 代码读取但发布配置未提供

除已提供的约 20 个键外，其余 **100+ 个键全靠代码默认值**工作。典型：全部 `*.rate-limiter.*`（除 status 的 `rate-limit-map`）、`cluster.nodes.status-update.*`、`registry.data.*`、全部 socket-timeout、`notify.thread-count`、`*.dynamic-scheduled-thread.*`（除 management.data）、全部 WS 键、全部客户端键、全部 DB 环境键。

## 8. 对既有文档的勘误

| # | 位置 | 原表述 | 代码事实 |
|---|---|---|---|
| 1 | [product-overview](../product-overview.md) §3.6 | 「除部署身份（类加载快照）与 WS buffer-size（构造快照）外**全热更**」 | 表述缺**源层前提**：产品声明层全部为可动态更新（未用 `isStatic`），但**源层依赖宿主注入**——默认三件套为静态源（无文件监听、不发事件），故默认形态下不生效；使用层另有大量构造快照键 |
| 2 | [nfr-spec](../nfr-spec.md) NFR-35 | 「运行参数全部热更」 | **产品能力具备**（scf `PropertyConfig.isStatic` 声明 + 动态读链路）；效果取决于宿主注入的配置源——非能力缺失，**属部署形态差别** |
| 3 | [nfr-spec](../nfr-spec.md) NFR-37 | 「改配置热更即生效」 | 同上——`cluster.nodes` 为动态读键，接入动态源即生效；默认静态源形态下需重启 |
| 4 | [features.md](../features.md) §5.7 | `buffer-size` 为「唯一例外」 | 构造快照键远不止此；应改为「读取时机」列（§0 口径）——构造快照是**使用层**设计选择，非声明级 static |
| 5 | [client-sdk-spec](client-sdk-spec.md) FR-CS-03 | 「默认全部热更；两个例外」 | 同上（缺源层前提） |
| 6 | [features.md](../features.md) §2.9 | 限流表列 4 个 | 实际 5 个（含 `artemis.service.management.group`，本制品 §2.6 补全） |
| 7 | 全部文档 | 未记 `<clientId>.default-request-config`（依赖库键 + 事实死键 + Provider 默认参数） | §5.1 |
| 8 | 全部文档 | 未记**动态更新三层模型**（源 / 声明 `isStatic` / 读取时机）与 `PropertyConfig.isStatic` 的强制点 | §0 |

> 注：上述「热更」表述的修正口径 = **代码层面的读取时机（动态读 / 构造快照）+ 发布形态下的实际行为（均静态）**，两者须同时说明，否则读者要么误以为可热更、要么误以为设计者没留扩展点。

## 9. 待验证

| 事项 | 说明 |
|---|---|
| http-rpc-util 实际解析版本 | pom 引 `rpc-util-bom` 1.3.1，本机仅 1.2.2（构造签名一致，判断为 1.2.2）；若为 1.3.x，`default-request-config` 键名可能不同 |
| `ObjectExtension.isNullOrEmpty` 对空白串的精确判定 | 本机 lang-extension 仅 1.1.1，无该方法；影响「空字符串是否等同未配置」的边界 |

## 10. 复刻完备性自检（本制品）

- 键模式 127 个，按模块分节（common 31 / service 40 / management 24 / server 11 / client 21 / 依赖库 1）✓
- 每键：完整键名（含动态段展开）/ 默认值 / 范围 / 读取点行号 / 组件 / 读取时机 ✓
- 动态前缀（`<mgr>` / `<scId>` / `<d>` / `<c>` / `<ipBlackListId>` / `<本机IP>`）的展开实例数已列 ✓
- 死键（5）与缺失键（100+）区分 ✓
- 系统属性 / 环境变量直读键全列 ✓
- 动态更新三层模型（源 / 声明 / 读取时机）+ `isStatic` 强制点与标志传播 ✓
- 对既有文档的 8 条勘误 ✓

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
