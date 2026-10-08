# REST API 契约（逐端点）

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**契约层**制品——全部 77 个 REST 端点的请求/响应契约（字段级），供 1:1 对标复刻。实体字段定义见 [data-model.md](data-model.md)；SDK / WS / 复制协议见 [client-sdk-api.md](client-sdk-api.md)。
> 证据路径均相对原仓库根。本制品**自足**（含全部请求/响应字段）。

## 0. 全局约定

### 0.1 路径拼接

- **数据面**：`artemis-common/.../config/RestPaths.java` **extends `ArtemisPaths`**，`CONTEXT_PATH = "/"`（`ArtemisPaths.java:5`，注释「与 Rust 版本保持一致」）→ `REGISTRY_PATH = "/api/registry/"` 等；Controller 类级 `@RequestMapping` + 方法级 `@RequestMapping` 拼接。
- **管理面**：`artemis-management/.../config/RestPaths.java` 是**独立接口，不继承 `ArtemisPaths`**，路径为绝对字面量（`/api/management/`、`/api/management/group/` 等）。
- 结论：**对外路径统一以 `/api/...` 开头**（无 host 前缀；`application.properties` 未设 `server.port`/`context-path` → 默认 8080 + `/`）。

### 0.2 JSON 序列化

键名 = JavaBean getter 派生（camelCase）——`_instances` → `instances`，`isIsSafe()` → `isSafe`。`CustomObjectMapper`：未知属性忽略、primitive 可缺省、**入参大小写不敏感**、**出参键字母序**。**全仓库无 Jackson 注解**。详见 [data-model.md](data-model.md) §1。

### 0.3 通用响应包络

所有响应体 `implements HasResponseStatus`，顶层含 `responseStatus`：

| 字段 | 类型 | 说明 |
|---|---|---|
| status | String | `success` / `fail` / `partial_fail` / `unknown` |
| errorCode | String | 见 §0.4 |
| message | String | 人类可读 |

**业务错误一律 HTTP 200 + body 内 responseStatus**（无全局 `@RestControllerAdvice`）。⚠ 例外：`GET /api/status/websocket/connection.json` 返回**裸 Map**，无包络。

### 0.4 errorCode 全集

`success` / `partial_fail` / `bad-request` / `rate-limited` / `no-permission` / `data-not-found` / `internal-service-error` / `service-unavailable` / `unknown`。⚠ `no-permission` 无鉴权实现，仅用于 region/zone 软隔离校验。

### 0.5 通用子结构（引用 [data-model.md](data-model.md)）

`Instance`（13 字段）/ `Service`（serviceId, metadata, instances, logicInstances, routeRules）/ `RouteRule`（routeId, strategy, groups）/ `ServiceGroup`（groupKey, weight, instanceIds, instances, metadata）/ `FailedInstance`（instance, errorCode, errorMessage）/ `ServiceNode`（zone, url）/ `Zone`（regionId, zoneId, metadata）/ `ServiceNodeStatus`（node, status, canServiceDiscovery, canServiceRegistry, allowRegistryFromOtherZone, allowDiscoveryFromOtherZone）/ `InstanceKey`（regionId, serviceId, instanceId）/ `ServerKey`（regionId, serverId）/ `InstanceChange`（instance, changeType, changeTime）/ `DiscoveryConfig`（serviceId, regionId, zoneId, discoveryData）。

**`OperationContext`**（management `common/OperationContext.java:7-11`）——**所有 group / zone / canary 写请求的公共父类**：`operatorId` / `token` / `operation` / `reason` / `extensions`（全可空；token 仅落库不校验）。

## 1. 数据面（20 端点）

### 1.A RegistryController — `/api/registry/`

| # | 端点 | 请求体 | 响应体 | errorCode |
|---|---|---|---|---|
| 1.A.1 | `POST register.json` | `RegisterRequest.instances`（非空） | `RegisterResponse`: `failedInstances` / `responseStatus` | success / partial_fail / bad-request / rate-limited / service-unavailable / internal-service-error；元素级 no-permission（跨 zone）/ data-not-found / internal-service-error |
| 1.A.2 | `POST heartbeat.json` ⚠ **死端点**（§3） | `HeartbeatRequest.instances` | `HeartbeatResponse` | 同 1.A.1（元素级失败 = data-not-found） |
| 1.A.3 | `POST unregister.json` | `UnregisterRequest.instances` | `UnregisterResponse` | 同 1.A.1 |

⚠ 无 GET 变体。证据：`RegistryController.java:28,35,42`、`RegistryServiceImpl.java:44-90`、`RegistryTool.java:56-113`。

### 1.B RegistryReplicationController — `/api/replication/registry/`

> 接收端 isReplication=true：**跳过本机 up 与同 zone 校验**，仅校验同 region。

| # | 端点 | 请求体 | 响应体 | 备注 |
|---|---|---|---|---|
| 1.B.1 | `POST register.json` | RegisterRequest | RegisterResponse | 元素级 no-permission = 跨 region |
| 1.B.2 | `POST heartbeat.json` | HeartbeatRequest | HeartbeatResponse | **缺租约直接补注册**（不返回 data-not-found） |
| 1.B.3 | `POST unregister.json` | UnregisterRequest | UnregisterResponse | |
| 1.B.4 | `services.json`（POST + **GET**） | `GetServicesRequest`: `regionId` / `zoneId` | `GetServicesResponse`: `services` / `responseStatus` | GET 参数 `regionId`(**必填**) / `zoneId`(可选) |

errorCode：success / partial_fail / bad-request / rate-limited / service-unavailable / no-permission / internal-service-error。证据：`RegistryReplicationController.java:31-64`、`RegistryReplicationServiceImpl.java:63-145`。

### 1.C ClusterController — `/api/cluster/`

| # | 端点 | GET 参数 | 请求体 | 响应体 |
|---|---|---|---|---|
| 1.C.1 | `up-registry-nodes.json`（POST + GET） | `regionId`(可选) / `zoneId`(可选) | GetServiceNodesRequest | `GetServiceNodesResponse`: `nodes` / `responseStatus` |
| 1.C.2 | `up-discovery-nodes.json`（POST + GET） | 同上 | 同上 | 同上 |

errorCode：success / **data-not-found**（无可用节点）/ rate-limited / internal-service-error。⚠ `/api/cluster/nodes.json` 为**死路径常量**（无映射，§3）。证据：`ClusterController.java:24-47`、`ClusterServiceImpl.java:49,71,93-95`。

### 1.D StatusController — `/api/status/`

| # | 端点 | GET 参数 | 响应体 |
|---|---|---|---|
| 1.D.1 | `node.json`（POST + GET） | 无 | `GetClusterNodeStatusResponse`: `nodeStatus`(ServiceNodeStatus) / `responseStatus`。**无限流** |
| 1.D.2 | `cluster.json`（POST + GET） | 无 | `GetClusterStatusResponse`: `nodesStatus`(List&lt;ServiceNodeStatus&gt;) / `nodeCount`(int) / `responseStatus` |
| 1.D.3 | `leases.json`（POST + GET） | **`appIds`**（List，可选） | `GetLeasesStatusResponse`（见下） |
| 1.D.4 | `legacy-leases.json`（POST + GET） | **`appIds`**（可选） | 同 1.D.3（走同一 impl 私有方法，语义仅历史兼容） |
| 1.D.5 | `config.json`（POST + GET） | 无 | `GetConfigStatusResponse`: `sources`(Map&lt;String,Integer&gt;) / `properties`(Map&lt;String,String&gt;) / `responseStatus` |
| 1.D.6 | `deployment.json`（POST + GET） | 无 | `GetDeploymentStatusResponse`: regionId / zoneId / appId / machineName / ip / port / protocol / path / sources / properties / responseStatus |

**GetLeasesStatusResponse**：`leaseUpdateMaxCount`(long) / `leaseUpdateMaxCountLastUpdateTime`(long) / `leaseUpdateCountLastTimeWindow`(long) / `isSafe`(boolean，getter `isIsSafe` → 键 `isSafe`) / `isSafeCheckEnabled`(boolean) / `leaseCount`(int) / `leases`(Map&lt;Service, List&lt;LeaseStatus&gt;&gt;) / `responseStatus`。
**LeaseStatus**：`instance`(String) / `creationTime` / `renewalTime` / **`evitionTime`**（原文拼写错误）(String，格式 `yyyy-MM-dd HH:mm:ss.SSS`) / `ttl`(long)。

⚠ **参数名不一致陷阱**：1.D.3/1.D.4 的 **GET 参数名为 `appIds`，而 POST body 字段名为 `serviceIds`**（Controller 传 `new GetLeasesStatusRequest(appIds)`）。证据：`StatusController.java:63-98`、`StatusServiceImpl.java:73-123,199`、`GetLeasesStatusResponse.java:15-22`、`LeaseStatus.java:8-12`。

### 1.E DiscoveryController — `/api/discovery/`

| # | 端点 | GET 参数 | 请求体 | 响应体 |
|---|---|---|---|---|
| 1.E.1 | `lookup.json`（**仅 POST**） | — | `LookupRequest`: `regionId` / `zoneId` / `discoveryConfigs` | `LookupResponse`: `services` / `responseStatus` |
| 1.E.2 | `service.json`（POST + GET） | `regionId`(可选) / `zoneId`(可选) / **`serviceId`(必填)** | `GetServiceRequest`: `discoveryConfig` / `regionId` / `zoneId` | `GetServiceResponse`: `service` / `responseStatus` |
| 1.E.3 | `services.json`（POST + GET） | `regionId`(可选) / `zoneId`(可选) | `GetServicesRequest` | `GetServicesResponse`: `services` / **`version`(long)** / `responseStatus` |
| 1.E.4 | `services-delta.json`（**仅 POST**）⚠ **死端点** | — | `GetServicesDeltaRequest`: `regionId` / `zoneId` / `version` | `GetServicesDeltaResponse`: **`delta`(Map&lt;Service, List&lt;InstanceChange&gt;&gt;)** / `version` / `responseStatus` |

errorCode：1.E.1/1.E.2 = success / bad-request / service-unavailable / no-permission / internal-service-error；1.E.3 = success / service-unavailable / internal-service-error（**无 no-permission**）；1.E.4 另加 **data-not-found**（version 过旧）。证据：`DiscoveryController.java:30-69`、`DiscoveryServiceImpl.java:97-162`。

### 1.F WsStatusController

| # | 端点 | 响应体 |
|---|---|---|
| 1.F.1 | `GET /api/status/websocket/connection.json` | **裸 `Map<String,String>`**，固定键 `"registry"` / `"discovery"`（= 两个 WS handler 连接数）；**无 responseStatus 包络** |

证据：`WsStatusController.java:27-31`、`HeartbeatWsHandler.java:57-59`、`ServiceChangeWsHandler.java:153-155`。

## 2. 管理面（57 端点）

> 全部 **POST-only**（除下表注明的 GET 变体）；写方法统一 `checkCurrentNode`（→ service-unavailable）+ `check`（→ bad-request）；异常 → internal-service-error。

### 2.A ManagementController — `/api/management/`（10 路径 / 13 路由）

| # | 端点 | GET 参数 | 请求体 | 响应体 |
|---|---|---|---|---|
| 2.A.1 | `POST operate-instance.json` | — | `OperateInstanceRequest`: `instanceKey` / `operation` / `operationComplete`(boolean) / `operatorId` / `token` | 仅 `responseStatus` |
| 2.A.2 | `POST operate-server.json` | — | `OperateServerRequest`: `serverKey` / 同上 4 字段 | 仅 `responseStatus` |
| 2.A.3 | `POST instance-operations.json` | — | `GetInstanceOperationsRequest.instanceKey` | `operations`(InstanceOperations) / `responseStatus` |
| 2.A.4 | `POST server-operations.json` | — | `GetServerOperationsRequest.serverKey` | `operations`(ServerOperations) / `responseStatus` |
| 2.A.5 | `all-instance-operations.json`（POST + GET） | `regionId`(可选) | `GetAllInstanceOperationsRequest.regionId` | `allInstanceOperations`(List&lt;InstanceOperations&gt;) / `responseStatus` |
| 2.A.6 | `all-server-operations.json`（POST + GET） | `regionId`(可选) | `GetAllServerOperationsRequest.regionId` | `allServerOperations` / `responseStatus` |
| 2.A.7 | `POST instance-down.json` | — | `IsInstanceDownRequest.instance`(Instance) | `down`(boolean) / `responseStatus` |
| 2.A.8 | `POST server-down.json` | — | `IsServerDownRequest.serverKey` | `down`(boolean) / `responseStatus` |
| 2.A.9 | `services.json`（POST + GET） | `regionId`(可选) / `zoneId`(可选) | `GetServicesRequest` | `services` / `version` / `responseStatus` |
| 2.A.10 | `POST service.json`（**无 GET**） | — | `GetServiceRequest`: `serviceId` / `zoneId` / `regionId` | `service`(Service) / **`groups`(List&lt;ServiceGroup&gt;)** / `responseStatus` |

`InstanceOperations`：`instanceKey` / `operations`(List&lt;String&gt;)。`ServerOperations`：`serverKey` / `operations`。
⚠ `management/GetServiceRequest` 构造器**把 regionId/zoneId 写反**（`this.zoneId = regionId; this.regionId = zoneId;`，`:18-22`）——不影响 POST（走 setter），GET 绑定受影响待验证。证据：`ManagementController.java:41-132`、`ManagementServiceImpl.java:108-142`。

### 2.B ManagementGroupController — `/api/management/group/`（32 端点）

**errorCode 规律**：写 = success / bad-request / service-unavailable / internal-service-error；读 = success / bad-request / internal-service-error；**仅 3 个读方法有限流**（`get-route-rules` / `get-route-rule-groups` / `get-groups` → rate-limited）。

**（1）route-rule 族（6）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.1.1 | `POST insert-route-rules.json` | `routeRules`(List&lt;ServiceRouteRule&gt;) + OperationContext | 仅 responseStatus |
| 2.B.1.2 | `POST update-route-rules.json` | 同上 | 仅 responseStatus |
| 2.B.1.3 | `POST delete-route-rules.json` | `routeRuleIds`(List&lt;Long&gt;) + OC | 仅 responseStatus |
| 2.B.1.4 | `POST get-route-rules.json` | `routeRuleId` / `serviceId` / `name` / `status`（全可选；**非 OC**） | `routeRules` / responseStatus |
| 2.B.1.5 | `POST get-all-route-rules.json` | `regionId` | `routeRules` / responseStatus |
| 2.B.1.6 | `POST create-route-rule.json` | `routeRule`(ServiceRouteRule) / `groups`(List&lt;GroupWeight&gt;) + OC | 仅 responseStatus |

`ServiceRouteRule`：`routeRuleId`(Long) / `serviceId` / `name` / `description` / `status` / `strategy`。`GroupWeight`（extends Group）：Group 全字段 + `weight`(Integer)。

**（2）route-rule-group 族（6）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.2.1 | `POST insert-route-rule-groups.json` | `routeRuleGroups`(List&lt;RouteRuleGroup&gt;) + OC | 仅 responseStatus |
| 2.B.2.2 | `POST update-route-rule-groups.json` | 同上 | 仅 responseStatus |
| 2.B.2.3 | `POST release-route-rule-groups.json` | 同上 | 仅 responseStatus |
| 2.B.2.4 | `POST delete-route-rule-groups.json` | `routeRuleGroupIds`(List&lt;Long&gt;) + OC | 仅 responseStatus |
| 2.B.2.5 | `POST get-all-route-rule-groups.json` | `regionId` | `routeRuleGroups` / responseStatus |
| 2.B.2.6 | `POST get-route-rule-groups.json` | `routeRuleGroupId` / `routeRuleId` / `groupId`(Long) | `routeRuleGroups` / responseStatus |

`RouteRuleGroup`：`routeRuleGroupId` / `routeRuleId` / `groupId`(Long) / `weight`(Integer) / `unreleasedWeight`(Integer)。

**（3）group 族（5）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.3.1 | `POST insert-groups.json` | `groups`(List&lt;Group&gt;) + OC | 仅 responseStatus |
| 2.B.3.2 | `POST update-groups.json` | 同上 | 仅 responseStatus |
| 2.B.3.3 | `POST delete-groups.json` | `groupIds`(List&lt;Long&gt;) + OC | 仅 responseStatus |
| 2.B.3.4 | `POST get-all-groups.json` | `regionId` | `groups` / responseStatus |
| 2.B.3.5 | `POST get-groups.json` | `groupId` / `serviceId` / `regionId` / `zoneId` / `name` / `appId` / `status`（全可选） | `groups` / responseStatus |

`Group`：`groupId`(Long) / `serviceId` / `regionId` / `zoneId` / `name` / `appId` / `description` / `status` / `metadata`(Map) / **`groupKey`（计算属性，getter 派生，出现在响应中）**。

**（4）group-tag 族（5）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.4.1 | `POST insert-group-tags.json` | `groupTagsList`(List&lt;GroupTags&gt;) + OC | 仅 responseStatus |
| 2.B.4.2 | `POST update-group-tags.json` | 同上 | 仅 responseStatus |
| 2.B.4.3 | `POST delete-group-tags.json` | `groupId`(Long) / `tag` / `value` + OC | 仅 responseStatus |
| 2.B.4.4 | `POST get-all-group-tags.json` | `regionId` | `allGroupTags`(List&lt;GroupTags&gt;) / responseStatus |
| 2.B.4.5 | `POST get-group-tags.json` | `tagId`(Long) / `groupId`(Long) / `tagKey` | `groupTags`(GroupTags) / responseStatus |

`GroupTags`：`groupId`(Long) / `tags`(Map&lt;String,String&gt;)。

**（5）group-operation 族（4）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.5.1 | `POST operate-group-operations.json` | `groupOperationsList`(List&lt;GroupOperations&gt;) / `operationComplete`(boolean) + OC | 仅 responseStatus |
| 2.B.5.2 | `POST operate-group-operation.json` | Group 全字段 + `operation` / `operationComplete` + OC | 仅 responseStatus |
| 2.B.5.3 | `POST get-all-group-operations.json` | `regionId` | `allGroupOperations` / responseStatus |
| 2.B.5.4 | `POST get-group-operations.json` | `groupId`(Long) | `groupOperations`(GroupOperations) / responseStatus |

`GroupOperations`：`groupId`(Long) / `operations`(List&lt;String&gt;)。

**（6）group-instance 族（3）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.6.1 | `POST insert-group-instances.json` | `groupInstances`(List&lt;GroupInstance&gt;) + OC | 仅 responseStatus |
| 2.B.6.2 | `POST delete-group-instances.json` | **`DeleteGroupsInstancesRequest`**（类名单复数错位）`.groupInstanceIds`(List&lt;Long&gt;) + OC | 仅 responseStatus |
| 2.B.6.3 | `POST get-group-instances.json` | `groupId`(Long) / `instanceId` | `groupInstances` / responseStatus |

`GroupInstance`：`id`(Long) / `groupId`(Long) / `instanceId`(String)——⚠ **public 字段，无 getter/setter**。

**（7）service-instance 族（3）**

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.B.7.1 | `POST insert-service-instances.json` | `serviceInstances`(List&lt;ServiceInstance&gt;) + OC | ⚠ **`OperationResponse`**（与其他 insert 的专属 Response 类不同） |
| 2.B.7.2 | `POST delete-service-instances.json` | `serviceInstanceIds`(List&lt;Long&gt;) + OC | `OperationResponse` |
| 2.B.7.3 | `POST get-service-instances.json` | `serviceId` / `instanceId` | `serviceInstances` / responseStatus |

`ServiceInstance`：`id`(Long) / `serviceId` / `instanceId` / `groupId` / `ip` / `machineName` / `metadata`(Map) / `port`(int) / `protocol` / `regionId` / `zoneId` / `healthCheckUrl` / `url` / `description`。

证据：`ManagementGroupController.java`（各方法行号见报告索引）、`GroupServiceImpl.java:135,239,367,724-732`。

### 2.C ManagementLogController — `/api/management/log/`（9 端点，全 POST-only）

errorCode 统一：success / bad-request（仅最早的方法）/ internal-service-error。**无 service-unavailable、无 rate-limited。**

| # | 端点 | 请求字段（过滤） | 响应 |
|---|---|---|---|
| 2.C.1 | `instance-operation-logs.json` | `regionId` / `serviceId` / `instanceId` / `operation` / `operatorId` / `complete`(Boolean) | `logs`(List&lt;InstanceOperationLog&gt;) |
| 2.C.2 | `server-operation-logs.json` | `regionId` / `serverId` / `operation` / `operatorId` / `complete` | `logs`(List&lt;ServerOperationLog&gt;) |
| 2.C.3 | `group-operation-logs.json` | `groupId`(Long) / `operation` / `operatorId` / `complete` | `logs`(List&lt;GroupOperationLog&gt;) |
| 2.C.4 | `group-logs.json` | `serviceId` / `regionId` / `zoneId` / `name` / `appId` / `operation` / `operatorId` | `logs`(List&lt;GroupLog&gt;) |
| 2.C.5 | `route-rule-logs.json` | `serviceId` / `name` / `operation` / `operatorId` | `logs`(List&lt;RouteRuleLog&gt;) |
| 2.C.6 | `route-rule-group-logs.json` | `groupId`(Long) / `routeRuleId`(Long) / `operation` / `operatorId` | `logs`(List&lt;RouteRuleGroupLog&gt;) |
| 2.C.7 | `zone-operation-logs.json` | `regionId` / `serviceId` / `zoneId` / `operation` / `operatorId` / `complete` | `logs`(List&lt;ZoneOperationLog&gt;) |
| 2.C.8 | `group-instance-logs.json` | `groupId`(Long) / `instanceId` / `operation` / `operatorId` | `logs`(List&lt;GroupInstanceLog&gt;) |
| 2.C.9 | `service-instance-logs.json` | `serviceId` / `instanceId` / `operation` / `operatorId` | `logs`(List&lt;ServiceInstanceLog&gt;) |

**日志实体字段**（均含 `createTime` / `updateTime`，`java.sql.Timestamp`）：
- `InstanceOperationLog`：id / regionId / serviceId / instanceId / operation / operatorId / token / complete(boolean) / extensions
- `ServerOperationLog`：同上去 serviceId/instanceId，改 `serverId`
- `ZoneOperationLog`：id / regionId / serviceId / zoneId / operation / operatorId / token / complete（**无 extensions**）
- `GroupLog`：id / serviceId / regionId / zoneId / name / appId / status / operation / operatorId / token / extensions / reason
- `GroupOperationLog`：id / groupId / operation / operatorId / token / complete / extensions / reason
- `RouteRuleLog`：id / serviceId / name / status / operation / operatorId / token / **complete** / extensions / reason
- `RouteRuleGroupLog`：id / routeRuleId / groupId / **weight** / operation / operatorId / token / extensions / reason
- `GroupInstanceLog`：id / groupId / instanceId / operation / operatorId / token / reason（**无 extensions / complete**）
- `ServiceInstanceLog`：id / serviceId / instanceId / ip / machineName / **metadata(String)** / port / protocol / regionId / zoneId / healthCheckUrl / url / groupId / operation / operatorId / token（**无 extensions / complete / reason**）

⚠ **过滤能力**：`token` / `reason` / 时间段**不可作过滤字段**（请求类无对应字段）——与 [operations-audit-spec](operations-audit-spec.md) FR-OA-06 一致。

### 2.D ManagementZoneController — `/api/management/zone/`（5 端点，全 POST-only）

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.D.1 | `get-all-zone-operations.json` | `regionId` | `allZoneOperations`(List&lt;ZoneOperations&gt;) / responseStatus |
| 2.D.2 | `get-zone-operations.json` | `zoneKey`(ZoneKey) | `operations`(ZoneOperations) / responseStatus |
| 2.D.3 | `get-zone-operations-list.json` | `regionId` / `serviceId` / `zoneId` | `zoneOperationsList` / responseStatus |
| 2.D.4 | `is-zone-down.json` | `zoneKey`(ZoneKey) | `down`(boolean) / responseStatus |
| 2.D.5 | `operate-zone-operations.json` | `zoneOperationsList`(List&lt;ZoneOperations&gt;) / `operationComplete`(boolean) / `operatorId` / `token` + OC | 仅 responseStatus |

`ZoneKey`：`regionId` / `serviceId` / `zoneId`。`ZoneOperations`：`zoneKey` / `operations`(List&lt;String&gt;)。errorCode：读/写均含 bad-request；写含 service-unavailable。

### 2.E CanaryController — `/api/management/canary/`（1 端点）

| # | 端点 | 请求体 | 响应体 |
|---|---|---|---|
| 2.E.1 | `POST update-canary-ips.json` | `UpdateCanaryIPsRequest`: `serviceId` / `appId` / `canaryIps`(List&lt;String&gt;) + OC | 仅 responseStatus |

errorCode：success / bad-request / service-unavailable（checkCurrentNode）/ internal-service-error。

## 3. 死端点 / 死路径 / 无仓库内调用方

| 对象 | 判定 | 证据 |
|---|---|---|
| `POST /api/registry/heartbeat.json` | **死端点**——唯一调用方 `RegistryServiceClient.heartbeat`，而该类**全仓库无 `new` 实例化点**；客户端心跳走 WS | `RegistryController.java:35`、`RegistryServiceClient.java:27,55`、`AddressManager.java:60` |
| `POST /api/discovery/services-delta.json` | **死端点**——常量无外部引用，客户端增量靠 WS 推送 | `DiscoveryController.java:69` |
| `/api/cluster/nodes.json` | **死路径常量**——定义了但 Controller 未注册、无调用方 | `RestPaths.java:32,35` |
| `POST /api/replication/registry/heartbeat.json` | **活**（复制通道客户端调用）——非死端点 | `RegistryReplicationServiceClient.java:50` |
| `discovery/service.json`、`discovery/services.json` | **无 in-repo 调用方**（SDK 只用 `lookup.json`），属公开 API | `ArtemisDiscoveryHttpClient.java:41` |
| 全部 57 个 `/api/management/*` | **无 in-repo 调用方**（管理面由外部控制台消费），属公开 API | grep |

## 4. 对既有文档的补充与勘误

| # | 内容 | 说明 |
|---|---|---|
| 1 | **GET 参数名 vs body 字段名不一致**（leases 的 `appIds` vs `serviceIds`）——基线/features 未记 | §1.D.3 |
| 2 | `management/GetServiceRequest` 构造器 **regionId/zoneId 写反** | §2.A.10 |
| 3 | `GroupInstance` 用 **public 字段**（无 getter/setter）；`DeleteGroupsInstancesRequest` 类名**单复数错位**；`service-instance` insert 返回 **`OperationResponse`**（异类） | §2.B.6/§2.B.7 |
| 4 | `LeaseStatus.evitionTime` **拼写错误**（原文如此，复刻须保留字段语义但可选是否保留拼写） | §1.D |
| 5 | `GetLeasesStatusResponse.isSafe` 的 getter 为 `isIsSafe` | §1.D |
| 6 | CONTEXT_PATH 澄清：management `RestPaths` 是**独立接口未继承 ArtemisPaths** | §0.1 |
| 7 | `cluster/nodes.json` 死常量确认（features §1.2 已记） | §3 |
| 8 | HTTP 心跳端点确认为**死端点**（原记为「无调用方」） | §3 |

## 5. 复刻完备性自检（本制品）

- 端点总数：数据面 **20**（registry 3 + replication 4 + cluster 2 + status 6 + WS-status 1 + discovery 4）+ 管理面 **57**（management 10 / group 32 / log 9 / zone 5 / canary 1）= **77 REST** ✓ 与 features §4 逐行核对一致
- 每端点：HTTP 方法 / 路径 / 请求体字段 / 响应体字段 / GET 参数名与 required / errorCode 集 ✓
- 通用子结构（含 OperationContext）字段 ✓
- 死端点 / 死路径 / 无调用方标记 ✓
- 管理面按族分组，族内同构端点合并 ✓

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
