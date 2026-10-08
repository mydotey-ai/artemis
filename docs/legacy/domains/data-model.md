# 数据字典（字段级）

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**契约层**制品——全部实体的字段级字典（类型 / JSON 键名 / 可空 / 默认 / 约束）+ 枚举字典 + 身份语义 + clone 深度，供 1:1 对标复刻编码。行为语义见各域 spec/logic；DB 持久化见 [db-schema.md](db-schema.md)；接口契约见 [api-contract.md](api-contract.md)（REST）与 [client-sdk-api.md](client-sdk-api.md)（SDK / WS / 复制）。
> 证据路径均相对原仓库根。标「待验证」为外部依赖（`org.mydotey.codec` / `org.mydotey.lang` 系列源码不在本仓库）无法取证者。

## 1. 序列化与命名规则

**两套 JSON 路径**（会话级细节见 [client-sdk-api.md](client-sdk-api.md) §1）：

| 路径 | 使用者 | 配置 | 键名规则 |
|---|---|---|---|
| **A. REST** | 全部 `/api/*.json` | `CustomObjectMapper` + `JsonSerializationHack` | Jackson 默认 bean 命名：键 = getter 去 `get`/`is` 前缀后首字母小写（驼峰保持）→ `_regionId` → `regionId`；`isComplete` → `complete`。**出站键按字母序**；入站**大小写不敏感**；未知属性忽略；`null` 原始类型按 0/false；**null 不省略** |
| **B. WS + 客户端** | WS 心跳/推送、client SDK | 外部 `JacksonJsonCodec.DEFAULT`（`codec-util 1.1.0`） | 同为默认 bean 命名（camelCase，与 A 兼容）；**字段顺序与 null 处理待验证** |

**全仓库无任何 Jackson 注解**（`@JsonProperty` / `@JsonIgnore` / `@JsonInclude` / `@JsonNaming` 等 grep 为空）——键名完全由 getter 派生。

证据：`artemis-server/.../rest/CustomObjectMapper.java:18-25`、`JsonSerializationHack.java:25-32`、`artemis-common/.../util/StringUtil.java:22-24`。

## 2. 核心实体（artemis-common）

### 2.1 `Instance`（13 字段可变 POJO，`implements Cloneable`）

| 字段 | 类型 | JSON 键名 | 可空 | 默认 | 约束 |
|---|---|---|---|---|---|
| `_regionId` | String | `regionId` | 是 | null | 无 |
| `_zoneId` | String | `zoneId` | 是 | null | 无 |
| `_groupId` | String | `groupId` | 是 | null | blank 视作默认分组 |
| `_serviceId` | String | `serviceId` | 是 | null | **注册时非 blank** |
| `_instanceId` | String | `instanceId` | 是 | null | **注册时非 blank** |
| `_machineName` | String | `machineName` | 是 | null | 无 |
| `_ip` | String | `ip` | 是 | null | 无 |
| `_port` | int | `port` | **否（原始类型）** | 0 | 无范围校验；JSON 传 null → 0 |
| `_protocol` | String | `protocol` | 是 | null | 无 |
| `_url` | String | `url` | 是 | null | **注册时非 blank** |
| `_healthCheckUrl` | String | `healthCheckUrl` | 是 | null | 无 |
| `_status` | String | `status` | 是 | null | 取值见 §7.1；**服务端不校验、不比较，原样存储** |
| `_metadata` | Map&lt;String,String&gt; | `metadata` | 是 | null | 无 |

- **身份**：`equals` / `hashCode` 委托 `InstanceKey.of(this)`——身份 = **(regionId, serviceId, instanceId)**，**大小写不敏感**。无 `compareTo`。
- **`toString`**：`regionId/zoneId/serviceId[/groupId]/instanceId`，整体小写（`groupId` 非 blank 才插入该段）。
- **clone**：`super.clone()` 浅拷贝全部标量 + `_metadata` 做 `new HashMap<>` 一层深拷贝。
- 证据：`artemis-common/src/main/java/org/mydotey/artemis/Instance.java:14-22,24-36,60-205`。

### 2.2 `InstanceKey`

字段：`regionId` / `serviceId` / `instanceId`（均 String，可空）。静态 `EMPTY`（setter 抛 `IllegalStateException`）。

- **`toString()` = `String.format("%s.%s.%s", regionId, serviceId, instanceId).toLowerCase()`**——`regionId.serviceId.instanceId`，**点分隔、小写**。
- `hashCode` / `equals` **均基于 `toString()`**（故大小写不敏感）；`equals` 含 `this==other` 短路 + `getClass()` 比较。
- 证据：`InstanceKey.java:10-32,73-94`。

### 2.3 `InstanceChange`

| 字段 | 类型 | JSON 键名 | 可空 | 默认 |
|---|---|---|---|---|
| `_instance` | Instance | `instance` | 是 | null |
| `_changeType` | String | `changeType` | 是 | null（取值见 §7.2） |
| `_changeTime` | long | `changeTime` | 否 | 0；两参构造用 `System.currentTimeMillis()` |

- **`equals` / `hashCode` 仅以 `_instance` 为准**（忽略 changeType/changeTime）——事件集合折叠去重的根源。
- 无 clone / compareTo。证据：`InstanceChange.java:10-15,27-29,53-84`。

### 2.4 `Service`

字段：`serviceId`（String）、`metadata`（Map）、`instances`（List&lt;Instance&gt;）、`logicInstances`（List&lt;Instance&gt;）、`routeRules`（List&lt;RouteRule&gt;）——键名同字段名。

- **身份**：`equals` / `hashCode` 以 `serviceId` **小写化后**比较。
- `toString()` 返回 `serviceId` **原值**（不小写）。
- ⚠ **clone**：`metadata` / `instances` / `logicInstances` 各做一层容器深拷贝（**元素 Instance 仍共享引用**）；**`routeRules` 完全不拷贝**（克隆体与本体共享同一 list 引用——改克隆体会污染本体）。
- 证据：`Service.java:12-16,72-120`。

### 2.5 `ServiceGroup`

字段：`groupKey`（String）、`weight`（**Integer，默认 null——非 5**；5 仅由 `ServiceGroups.fixWeight` 在展开时施加）、`instanceIds`（List&lt;String&gt;）、`instances`（List&lt;Instance&gt;）、`metadata`（Map）。

- ⚠ **无 equals / hashCode / toString / compareTo**（Object 身份语义）。
- clone：仅 `metadata` 深拷贝；`instances` 共享引用。
- 证据：`ServiceGroup.java:72-81`。

### 2.6 `RouteRule`

字段：`routeId`（String）、`strategy`（String，见 §7.3）、`groups`（List&lt;ServiceGroup&gt;）。

- `toString()` = **JSON 串**（经 `JacksonJsonCodec.DEFAULT.encode(this)`）。
- 无 equals / hashCode / compareTo（Object 身份）。
- ⚠ **clone 丢数据 bug**：`return new RouteRule(routeId, strategy)`——两参构造等价 `this(routeId, null, strategy)`，**`groups` 被丢弃**。
- 证据：`RouteRule.java:11-14,22-24,56-64`。

### 2.7 `Region` / `Zone` / `ServerKey`

- `Region`：`regionId` / `metadata` / `zones`（List&lt;Zone&gt;）。身份 = `regionId` 小写。
- `Zone`：`regionId` / `zoneId` / `metadata`。身份 = `regionId + "/" + zoneId` 小写。
- `ServerKey`：`regionId` / `serverId`（**serverId = `instance.getIp()`**）。静态 `EMPTY`。`toString()` = `regionId.serverId` 小写；equals/hashCode 基于之。
- 均无 clone / compareTo。证据：`Region.java:56-59`、`Zone.java:55-58`、`ServerKey.java:10-29,59-62`。

### 2.8 `ResponseStatus` / `HasResponseStatus`

| 字段 | 类型 | JSON 键名 | 可空 | 默认 |
|---|---|---|---|---|
| `_status` | String | `status` | 是 | null（取值见 §7.5） |
| `_errorCode` | String | `errorCode` | 是 | null（取值见 §7.6） |
| `_message` | String | `message` | 是 | null |

⚠ 三参构造器签名顺序为 **`(status, message, errorCode)`**（与字段声明顺序 `status, errorCode, message` 不一致）；序列化键名以 getter 为准，不受影响。证据：`ResponseStatus.java:8-15,17-29,55-62`。

### 2.9 `HasInstances` / `HasFailedInstances` / `FailedInstance`

- `HasInstances`：`List<Instance> getInstances()`。
- `HasFailedInstances` **extends `HasResponseStatus`**：`List<FailedInstance> getFailedInstances()`。
- `FailedInstance`：`instance` / `errorCode` / `errorMessage`（键名同字段名）。
- 证据：`registry/{HasInstances,HasFailedInstances,FailedInstance}.java`。

## 3. registry 请求 / 响应

三个 Request 均 `implements HasInstances`，结构相同：`_instances`（List&lt;Instance&gt;）→ 键 `instances`。服务端要求**非空**且每个实例合法（serviceId / instanceId / url 非 blank）。

三个 Response 均 `implements HasResponseStatus, HasFailedInstances`：`_failedInstances` → `failedInstances`、`_responseStatus` → `responseStatus`。

⚠ **`UnregisterResponse` 字段名为 `_failedFailedInstances`**（拼写错误），但 getter 为 `getFailedInstances` → **JSON 键仍是 `failedInstances`**。

证据：`registry/{RegisterRequest,HeartbeatRequest,UnregisterRequest,RegisterResponse,HeartbeatResponse,UnregisterResponse}.java`。

## 4. discovery 请求 / 响应

### 4.1 `DiscoveryConfig`

| 字段 | 类型 | JSON 键名 | 可空 | 默认 |
|---|---|---|---|---|
| `serviceId` | String | `serviceId` | 是 | null（查询时非 blank） |
| `regionId` | String | `regionId` | 是 | null |
| `zoneId` | String | `zoneId` | 是 | null |
| `discoveryData` | Map&lt;String,String&gt; | `discoveryData` | 是 | null（纯协议预留，服务端不消费） |

- 常量 `DISCOVERY_GENERIC_SERVICE_ID = "discovery_generic_service_id"`；静态 `GENERIC`（setter 全抛异常）；`isGenericConfig` 用 `equals`（**大小写敏感**）。
- ⚠ **clone 丢失 regionId/zoneId**（只留 serviceId + discoveryData）。
- 证据：`discovery/DiscoveryConfig.java:11,13-37,89-104`。

### 4.2 其余

| 类 | 字段 → 键 |
|---|---|
| `LookupRequest` | `regionId` / `zoneId` / `discoveryConfigs`（List&lt;DiscoveryConfig&gt;） |
| `LookupResponse` | `responseStatus` / `services`（List&lt;Service&gt;） |
| `GetServiceRequest` | `discoveryConfig` / `regionId` / `zoneId` |
| `GetServiceResponse` | `responseStatus` / `service`（Service） |
| `GetServicesRequest` | `regionId` / `zoneId` |
| `GetServicesResponse` | `services`（List&lt;Service&gt;）/ **`version`（long，全量版本号）** / `responseStatus` |
| `GetServicesDeltaRequest` | `regionId` / `zoneId` / `version`（long） |
| `GetServicesDeltaResponse` | **`delta`（Map&lt;Service, List&lt;InstanceChange&gt;&gt;）** / `version` / `responseStatus` |

⚠ `GetServicesDeltaResponse.delta` 的 **Map 键是 `Service` 对象**——序列化时 Jackson 用 `Service.toString()`（返回 serviceId）作键，反序列化无法从字符串还原 `Service`（无双 String 构造器 / `@JsonCreator`）→ **该响应结构存在往返隐患**；客户端实际走 WS 推送而非该 REST delta。⚠ `LookupRequest` 三参构造器签名顺序 `(discoveryConfigs, regionId, zoneId)`；⚠ `GetServiceRequest` 有构造器参数互换 bug（基线 §6-23 已录）。

## 5. cluster

| 类 | 字段 → 键 | 备注 |
|---|---|---|
| `GetServiceNodesRequest` | `regionId` / `zoneId` | |
| `GetServiceNodesResponse` | `nodes`（List&lt;ServiceNode&gt;）/ `responseStatus` | |
| `ServiceNode` | `zone`（Zone）/ `url`（String） | 身份 = `regionId/zoneId/url` 小写；⚠ `_zone` 为 null 时 `toString` NPE |
| `ServiceNodeStatus` | `node` / `status` / `canServiceDiscovery` / `canServiceRegistry` / `allowRegistryFromOtherZone` / `allowDiscoveryFromOtherZone` | ⚠ **实际位于 `artemis-service`，不在 common**；4 个布尔用 `isXxx()` getter；`status` 与布尔为 `volatile` |

⚠ `ServiceNodeStatus.equals` 只比 **node + status + canServiceRegistry + canServiceDiscovery**（**漏两个 allow\***），而 `hashCode` **含全部 6 字段**——equals/hashCode 契约不一致。证据：`artemis-service/.../cluster/ServiceNodeStatus.java:10-17,94-124`、`cluster/ServiceNode.java:13-14,41-44`。

## 6. artemis-management 模型

> 用于 DB 行映射与部分 REST 响应。除注明外**均无 equals / hashCode / clone / toString**（Object 身份）。DB 列约束见 [db-schema.md](db-schema.md)。

### 6.1 业务模型

| 模型 | 表 | 字段 → 键（类型） |
|---|---|---|
| `GroupModel` | service_group | `id`(Long) / `serviceId` / `regionId` / `zoneId` / `name` / `appId` / `description` / `status` / `createTime`(Timestamp) / `updateTime`(Timestamp)。⚠ **DB 的 `type` 与 `DELETED` 列模型无对应字段** |
| `RouteRuleModel` | service_route_rule | `id` / `serviceId` / `name` / `description` / `status` / `strategy` / `createTime` / `updateTime`。⚠ DB 的 `DELETED` 列无字段 |
| `RouteRuleGroupModel` | service_route_rule_group | `id` / `routeRuleId`(Long) / `groupId`(Long) / `weight`(Integer) / `unreleasedWeight`(Integer) / `createTime` / `updateTime` |
| `GroupInstanceModel` | service_group_instance | `id` / `groupId`(Long) / `instanceId`(String) / 时间戳 |
| `ServiceInstanceModel` | service_instance | `id` / `serviceId` / `instanceId` / `ip` / `machineName` / **`metadata`(String，JSON 串非 Map)** / `port`(int) / `protocol` / `regionId` / `zoneId` / `healthCheckUrl` / `url` / `description` / **`groupId`(String，非 Long)** / 时间戳 |
| `GroupOperationModel` | service_group_operation | `id` / `groupId`(Long) / `operation` / 时间戳 |
| `GroupTagModel` | service_group_tag | `id` / `groupId` / `tag` / `value` / 时间戳（**唯一重写了 `toString`**） |
| `InstanceModel` | instance | `id` / `regionId` / `serviceId` / `instanceId` / `operation` / `operatorId` / `token` / `createTime` / `updateTime`。⚠ 时间字段为 **`java.sql.Date`**（比 Timestamp 低精度） |
| `ServerModel` | server | `id` / `regionId` / `serverId` / `operation` / `operatorId` / `token` / 时间戳（`java.sql.Date`） |
| `ZoneOperationModel` | service_zone | `id` / `regionId` / `serviceId` / `zoneId` / `operation` / 时间戳。⚠ 两个构造器**参数顺序不同**（`(regionId,serviceId,zoneId)` vs `(operation,zoneId,serviceId,regionId)`），易错 |

### 6.2 日志模型（继承对应 Model，追加审计字段）

| LogModel | 继承 | 追加字段（→ 键） | 备注 |
|---|---|---|---|
| `GroupLogModel` | GroupModel | `operation` / `extensions` / `operatorId` / `token` / `reason` | 表另有 PARENT_ID / WEIGHT / TYPE 三列模型缺失 |
| `RouteRuleLogModel` | RouteRuleModel | 同上 5 字段 | 表无 description / strategy 列但模型继承含之 |
| `RouteRuleGroupLogModel` | RouteRuleGroupModel | 同上 5 字段 | 继承 `unreleasedWeight` 但表**无该列** |
| `GroupInstanceLogModel` | GroupInstanceModel | `operation` / `operatorId` / `token` / `reason` | **无 extensions** |
| `ServiceInstanceLogModel` | ServiceInstanceModel | `operation` / `operatorId` / `token` | **无 extensions / reason** |
| `GroupOperationLogModel` | GroupOperationModel | `complete`（`isComplete()` → 键 `complete`）/ `extensions` / `operatorId` / `token` / `reason` | 默认 complete=false |
| `GroupTagLogModel` | GroupTagModel | `operation` / `extensions` / `operatorId` / `token` | **无 reason** |
| `InstanceLogModel` | InstanceModel | `complete` / `extensions` | 静态 `of()` 强制 `extensions="{}"` |
| `ServerLogModel` | ServerModel | `complete` / `extensions` | 同上 |
| `ZoneOperationLogModel` | ZoneOperationModel | `operatorId` / `token` / `reason` / `complete` | 表无 EXTENSIONS |

⚠ **日志模型与日志表列普遍不对齐**（模型字段多于/少于表列），DAO 层靠显式列名兜底——详见 [db-schema.md](db-schema.md) §6。

## 7. 枚举字典（集中定义）

### 7.1 `Instance.Status`（`Instance.java:14-22`）

`starting` / `up` / `down` / `unhealthy` / `unknown`。**无判等方法**；代码中仅被写入，从不比较；**`starting` / `unhealthy` / `unknown` 全仓库零引用 = 事实死值**。需判等时只能自行 `equals`（**大小写敏感**）。

### 7.2 `InstanceChange.ChangeType`（`InstanceChange.java:10-15`）

`new` / `delete` / `change` / `reload`。⚠ **`change` 无服务端产生点**（discovery FR-DIS-06）。无判等方法。

### 7.3 `RouteRule.Strategy`（`RouteRule.java:11-14`）

`weighted-round-robin` / `close-by-visit`。无判等方法。

### 7.4 `ServiceNodeStatus.Status`（`ServiceNodeStatus.java:10-17`）

`starting` / `up` / `down` / `unknown`（**比 `Instance.Status` 少 `unhealthy`**）。

### 7.5 `ResponseStatus.Status`（`ResponseStatus.java:8-15`）

`success` / `fail` / `partial_fail` / **`UKNOWN`（拼写错误，值 `unknown`，零引用 = 死常量）**。判定统一走 `ResponseStatusUtil`（`isSuccess` / `isFail` / `isPartialFail` 用 `Status.X.equals(...)`，**大小写敏感**）。

### 7.6 `ErrorCodes`（`ErrorCodes.java:12-20`）

`success` / `partial_fail` / `bad-request` / `rate-limited` / `no-permission` / `data-not-found` / `internal-service-error` / `service-unavailable` / `unknown`。
集合：`rerunnableErrorCodes() = {rate-limited, unknown}`；`serviceDownErrorCodes() = {internal-service-error, service-unavailable}`。

### 7.7 组 / 规则状态与操作类型（`GroupRepository.java:42-56`）

- `GroupStatus`：`active` / `inactive`
- `RouteRuleStatus`：`active` / `inactive`
- `Operation`：`create` / `delete` / `update`
- 判等用法：`equalsIgnoreCase` → **大小写不敏感**。新建默认 ACTIVE（`BusinessDao.java:82,87,98,109`）。

### 7.8 `RegisterType`（`artemis-client/.../client/common/RegisterType.java:6-9`）

`enum { register, unregister }`（**小写枚举名，非 Java 惯例**；⚠ **位于 artemis-client 模块**，非 common）。

### 7.9 常量

| 常量 | 值 | 位置 |
|---|---|---|
| `ServiceGroups.DEFAULT_GROUP_ID` | `"default"` | `util/ServiceGroups.java:15` |
| `ServiceGroups.MAX_WEIGHT_VALUE` / `MIN` / `DEFAULT` | 10000 / 0 / **5** | `util/ServiceGroups.java:16-18` |
| `RouteRules.DEFAULT_ROUTE_RULE` | `"default-route-rule"` | `util/RouteRules.java:21` |
| `RouteRules.CANARY_ROUTE_RULE` | `"canary-route-rule"` | `util/RouteRules.java:22` |
| `RouteRules.DEFAULT_GROUP_KEY` | `"default-group-key"` | `util/RouteRules.java:23` |
| `RouteRules.DEFAULT_ROUTE_STRATEGY` | `weighted-round-robin` | `util/RouteRules.java:24` |
| `DiscoveryConfig.DISCOVERY_GENERIC_SERVICE_ID` | `"discovery_generic_service_id"` | `DiscoveryConfig.java:11` |
| `InstanceChanges.RELOAD_FAKE_*` | instanceId `"reload"` / ip `"0.0.0.0"` / url `"http://serviceId/reload"` | `util/InstanceChanges.java:13-15` |
| `OperationContext.extensions` 默认 | `"{}"` | `common/OperationContext.java:14` |

**大小写敏感性速查**：`isDefaultGroupId` / `isDefaultRouteRule` / `isCanaryRouteRule` / 组规则状态判等 = **不敏感**（`equalsIgnoreCase` + trim）；`isGenericConfig` / `ResponseStatusUtil.Status.X.equals` = **敏感**。

### 7.10 `groupKey` 格式

`ServiceGroupKeys.of(serviceId, regionId, zoneId, groupId, instanceId)` → `FileExtension.concatPathParts(...)` 后 `toLowerCase()`，分隔符 `/`：**`serviceId/regionId/zoneId/groupId/instanceId`（小写）**；`groupId` blank 或 `default` 归一为 `"default"`。`ServiceGroupKey.toString()` = groupKey 小写；equals/hashCode 基于之。
> 待验证：`FileExtension.concatPathParts`（外部 `mydotey-java` 库）的边界归一化行为。

### 7.11 管理操作 `operation` 字段

**无枚举、无校验**——`isInstanceDown` 系列只判「记录是否存在」，operation 仅是审计标签（测试用随机串）。见 [operations-audit-spec](operations-audit-spec.md) FR-OA-01。

## 8. 身份 / clone / 死字段汇总

### 8.1 身份语义速查

| 实体 | 身份依据 | 大小写 |
|---|---|---|
| `Instance` | `InstanceKey.of`（region+service+instance） | 不敏感 |
| `InstanceKey` / `ServerKey` / `ServiceGroupKey` | `toString()`（点 / 斜杠分隔） | 不敏感 |
| `Service` | `serviceId` | 不敏感 |
| `InstanceChange` | 仅 `_instance` | 不敏感 |
| `Region` / `Zone` / `ServiceNode` | `toString()` 小写 | 不敏感 |
| `ServiceNodeStatus` | node + status + can*（**漏 allow\***） | 敏感 |
| 所有 management Model / LogModel、`ServiceGroup` / `RouteRule` / `Group` | Object 身份 | — |

### 8.2 clone 深度汇总

| 类 | 深度 | 隐患 |
|---|---|---|
| `Instance` | 标量 + metadata 一层深拷贝 | 无 |
| `Service` | metadata / instances / logicInstances 容器深拷贝（**元素共享**）；**routeRules 完全共享** | 改克隆体 routeRules 污染本体 |
| `ServiceGroup` | 仅 metadata 深拷贝；instances 共享 | — |
| `RouteRule` | **只留 routeId + strategy，groups 丢失** | ⚠ 数据丢失 bug |
| `DiscoveryConfig` | 只留 serviceId + discoveryData | ⚠ regionId/zoneId 丢失 |
| 其余（`Region`/`Zone`/`InstanceChange`/`ResponseStatus`/请求响应/mgmt Model） | 无 clone | — |

### 8.3 死字段 / 死常量

- `Instance.Status.STARTING` / `UNHEALTHY` / `UNKNOWN`——零引用。
- `ResponseStatus.Status.UKNOWN`——零引用且拼写错误。
- 模型与表不对齐字段（`GroupModel.type`/`deleted` 缺失、`GroupLogModel` 缺 3 列、`RouteRuleGroupLogModel.unreleasedWeight` 无表列、`ServiceInstanceModel.description` 在 log 表无列）——DAO 显式列名兜底，详见 [db-schema.md](db-schema.md) §6。

### 8.4 待验证（外部依赖）

`JacksonJsonCodec.DEFAULT` 的 feature / 命名策略 / null 处理 / 键序；`FileExtension.concatPathParts` 边界归一化；`ServiceNodeUtil.isUp/isDown` 比较实现。

## 9. 对既有文档的勘误

| # | 位置 | 原表述 | 实际 |
|---|---|---|---|
| 1 | 基线 §2.1 | `ServiceGroup.weight[0–10000 默认 5]` | 字段默认 **null**，5 仅由 `fixWeight` 展开时施加 |
| 2 | 基线 §2.1 | `Instance` 「13 字段可变 POJO」（未列类型） | 本制品补全（含 `port` 为原始 int、`status` 死值等） |
| 3 | 基线 §2.1 / features §1.1 | 未记 JSON 键名规则 | 两套 mapper，键名 = getter 派生；HTTP 字母序 + 大小写不敏感 |
| 4 | 基线 §6.23 | 「GetServiceRequest 构造函数字段互换 bug」 | 确认存在；另发现 `RouteRule.clone` 丢 groups、`ServiceNodeStatus.equals/hashCode` 不一致、`UnregisterResponse` 字段名拼写错误 |
| 5 | features §1.5 | `ServiceNodeStatus` 记于 common | ⚠ 实际在 **artemis-service** 模块 |
| 6 | features §1.5 | `RegisterType` 记于 common | ⚠ 实际在 **artemis-client** 模块 |

## 10. 复刻完备性自检（本制品）

- common 核心实体：`Instance` / `InstanceKey` / `InstanceChange` / `Service` / `ServiceGroup` / `RouteRule` / `Region` / `Zone` / `ServerKey` / `ResponseStatus` / `FailedInstance` 全字段 ✓
- 请求响应：registry 6 类 + discovery 8 类 + cluster 4 类 ✓
- management 模型：10 业务 + 10 日志 ✓
- 枚举字典：11 组枚举/常量集中定义 + 大小写敏感性速查 ✓
- 身份语义 / clone 深度 / 死字段汇总 ✓
- 待验证项显式标注（外部依赖）✓
