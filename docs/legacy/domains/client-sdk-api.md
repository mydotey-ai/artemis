# 客户端 SDK 接口契约与报文协议

版本: 1.1    更新时间: 2026-10-09

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：**契约层**制品——客户端 SDK 的接口签名与契约、WS 报文格式、节点间复制协议报文、HTTP 通用约定，供 1:1 对标复刻。行为语义见 [client-sdk-spec](client-sdk-spec.md) / [client-sdk-logic](client-sdk-logic.md)。
> 证据路径均相对原仓库根。原「待验证」外部依赖项（`org.mydotey.codec` / `org.mydotey.rpc` / `org.mydotey.java`）已于 2026-10-09 从 **Maven Central sources jar** 取证完毕（`jackson-codec-util:1.1.0`、`http-rpc-util:1.2.3`、`lang-extension:1.2.0`；坐标与版本覆盖见[基线](../legacy-product-analysis.md) §8-14），结论随文标注。

## 1. 序列化总纲（决定全部 JSON 形态）

仓库存在**两套 Jackson mapper**，报文形态不同，复刻必须区分：

| 通道 | 编码器 | 证据 |
|---|---|---|
| 客户端发出的全部 WS 帧与 outbound JSON | `JacksonJsonCodec.DEFAULT`（外部依赖 `org.mydotey.codec:jackson-codec-util` 1.1.0，**配置已取证**见下注） | `artemis-client/.../registry/InstanceRepository.java:87`、`.../discovery/ServiceDiscovery.java:179-181`、`.../discovery/ArtemisDiscoveryHttpClient.java:41` |
| 服务端 WS 帧（心跳响应 / InstanceChange 推送） | 同上 `JacksonJsonCodec.DEFAULT`（统一经 `StringUtil#toJson`） | `artemis-server/.../websocket/HeartbeatWsHandler.java:35,43-44`、`ServiceChangeWsHandler.java:43,73`、`AllServicesChangeWsHandler.java:46` |
| 服务端 HTTP REST（registry / discovery / replication / status / 管理面） | `CustomObjectMapper`（经 `JsonSerializationHack` 注入 `MappingJackson2HttpMessageConverter`） | `artemis-server/.../rest/JsonSerializationHack.java:25-32` |

**`CustomObjectMapper` 确切配置**（`artemis-server/.../rest/CustomObjectMapper.java:18-25`）：
`FAIL_ON_IGNORED_PROPERTIES=false`、`FAIL_ON_NULL_FOR_PRIMITIVES=false`、`FAIL_ON_UNKNOWN_PROPERTIES=false`、`FAIL_ON_EMPTY_BEANS=false`、`ACCEPT_CASE_INSENSITIVE_PROPERTIES=true`（**服务端解析请求时字段名大小写不敏感**）、`SORT_PROPERTIES_ALPHABETICALLY=true`（**HTTP REST 响应键按字母序输出**）。

**JSON 键名 = Java bean property 名**（Jackson 默认命名，全仓库无 `@JsonProperty` / 自定义命名策略 / `@JsonIgnore`——grep 仅命中 `CustomObjectMapper` / `JsonSerializationHack` 两处）。

> **已取证**（2026-10-09，Maven Central `jackson-codec-util-1.1.0-sources.jar`）：`JacksonJsonCodec.DEFAULT` = `new ObjectMapper()` 配置：`AUTO_CLOSE_TARGET=false`、`IGNORE_UNKNOWN=true`、`ALLOW_UNQUOTED_CONTROL_CHARS=true`、`AUTO_CLOSE_SOURCE=false`、`IGNORE_UNDEFINED=true`、`FAIL_ON_EMPTY_BEANS=false`、`FAIL_ON_UNKNOWN_PROPERTIES=false`、`FAIL_ON_IGNORED_PROPERTIES=false`、`FAIL_ON_NULL_FOR_PRIMITIVES=false`、`READ_UNKNOWN_ENUM_VALUES_AS_NULL=true`、`ACCEPT_CASE_INSENSITIVE_PROPERTIES=true`。**未设置**：命名策略（默认 bean 驼峰）、`SORT_PROPERTIES_ALPHABETICALLY`（**WS 键序 = 声明序，非字母序**）、序列化 inclusion（**null 不省略**）。与 `CustomObjectMapper` 逐项对比：容错与大小写不敏感一致，**唯一实质差异 = 键序**（REST 字母序 / WS 声明序）。受检异常包装为 `CodecException`。

文本帧编码：客户端与服务端均 `new String(bytes)` / `String.getBytes()`（**无显式 charset，平台默认**，Linux=UTF-8）——证据 `InstanceRepository.java:87`、`InstanceRegistry.java:105-107`、`HeartbeatWsHandler.java:43`。

## 2. 客户端 SDK 公开 API 契约

包根：`artemis-client/src/main/java/org/mydotey/artemis/client/`。

### 2.1 `ArtemisClientManager`

```java
public static ArtemisClientManager getManager(String managerId, ArtemisClientManagerConfig managerConfig);
public DiscoveryClient            getDiscoveryClient();
public RegistryClient             getRegistryClient();
public String                     getManagerId();
public ArtemisClientManagerConfig getManagerConfig();
```

| 契约项 | 内容 |
|---|---|
| 单例 | 静态 `ConcurrentHashMap<String,ArtemisClientManager>` + `computeIfAbsent`——按 managerId 全局单例，线程安全 |
| 异常 | managerId 空白或 config 为 null → `IllegalArgumentException` |
| 副作用 | 同 managerId 二次调用**静默丢弃新 config**（取首次） |
| 懒加载 | `getDiscoveryClient` / `getRegistryClient` 双检锁；**字段未加 `volatile`**（⚠ DCL 可见性隐患，§6.2） |
| 生命周期 | ⚠ **无 `close()` / `shutdown()`**——持有的 WS 与线程池无法释放（`WebSocketSessionContext#shutdown` 存在但未对外暴露） |

证据：`ArtemisClientManager.java:16,21-22,30,34-71`。

### 2.2 `ArtemisClientManagerConfig`

构造器（5 个重载）：`(StringProperties)`；`(+EventMetricManager, AuditMetricManager)`；`(+RegistryClientConfig)`；`(+DiscoveryClientConfig)`；`(+RegistryClientConfig, +DiscoveryClientConfig)`。

访问器：`properties()` / `eventMetricManager()` / `valueMetricManager()` / `registryClientConfig()` / `discoveryClientConfig()`。

- 全参构造校验非 null（否则 `IllegalArgumentException`）。
- ⚠ **缺陷（勿继承）**：接收 `DiscoveryClientConfig` 的第 4 个构造**丢弃入参**，转而 `new RegistryClientConfig(), new DiscoveryClientConfig()`——传入的 discovery config 永不生效。证据：`ArtemisClientManagerConfig.java:37-42`。

### 2.3 `RegistryClientConfig` / `DiscoveryClientConfig`

- `RegistryClientConfig`：`RegistryClientConfig()`（filter 列表为空）/ `RegistryClientConfig(List<RegistryFilter>)`（null 抛 `IllegalArgumentException`）/ `getRegistryFilters()`——**返回内部可变引用，非防御性拷贝**。证据：`RegistryClientConfig.java:14-25`。
- `DiscoveryClientConfig`：**空类，无任何字段与方法**（占位）。证据：`DiscoveryClientConfig.java:6-8`。

### 2.4 `RegistryClient`

```java
void register(Instance... instances);
void unregister(Instance... instances);
```

实现 `RegistryClientImpl`：

| 方法与契约 | 内容 |
|---|---|
| `register` | 先 `Conditions.verifyInstances`（失败抛 `IllegalArgumentException`）；**发一次 HTTP `unregister` 清旧租约**（⚠ 基线 §2 已述）→ 并入本地实例集；**不发 HTTP register**，真正注册靠下一轮 WS 心跳。**无返回值、无 checked exception、网络失败不向上抛**（HTTP 层 catch + log + metric） |
| `unregister` | 校验（⚠ `RegistryClientImpl.java:39-40` **重复调用两次**）→ HTTP unregister + 本地移除 |
| 构造 | `RegistryClientImpl(String clientId, ArtemisClientManagerConfig)`；clientId 空白抛 `IllegalArgumentException` |

证据：`registry/RegistryClient.java:10-12`、`registry/RegistryClientImpl.java:22-23,32-35,38-42`。

### 2.5 `DiscoveryClient`

```java
Service getService(DiscoveryConfig discoveryConfig);
void    registerServiceChangeListener(DiscoveryConfig discoveryConfig, ServiceChangeListener listener);
```

| 方法与契约 | 内容 |
|---|---|
| `getService` | 校验（config 为 null → `NullPointerException`；serviceId 空白 → `IllegalArgumentException`）→ `ServiceRepository.getService`。**副作用**：serviceId 首次出现即注册服务（同步 lookup + 打开 WS 订阅）。⚠ **可能抛未受检 `RuntimeException`**：查找失败 `"not found any service by discoveryConfig:..."`、lookup 非 success `"lookup services failed..."`——**与「永不抛不存在异常」的行为规格（discovery FR-DIS-02）张力见 §6.6** |
| `registerServiceChangeListener` | 校验同上；listener 为 null 抛 `IllegalArgumentException`（消息 `"listener is null"`；`ObjectExtension.requireNonNull`，**已取证** lang-extension 1.2.0 sources）；服务未注册则先注册；listener 加入 `Set`（**幂等**，同一实例不重复）；回调经**单线程 executor 串行异步**执行，listener 抛错被吞并 log |

证据：`discovery/DiscoveryClient.java:11-13`、`discovery/DiscoveryClientImpl.java:32-44`、`discovery/ServiceRepository.java:37,139-148`。

### 2.6 `RegistryFilter`（SPI）

```java
String getRegistryFilterId();
void   filter(final List<Instance> instances);
```

契约：在 **register 通道**（`registerToRemote`）与 `getAvailableInstances` 应用；按 `registryFilters` 顺序**原地修改** List（可增删）；filter 抛 `Throwable` 被 catch + warn **不中断**；返回 null 视为空集；`getRegistryFilterId()` 空白则跳过埋点（metricId = `filter-instances.<id>`）；**线程安全由实现者保证**（客户端不加锁）。证据：`registry/InstanceRepository.java:53-77,102,80,150-153`。

### 2.7 `ServiceChangeListener` / `ServiceChangeEvent`

```java
void    onChange(ServiceChangeEvent event);   // ServiceChangeListener
String  changeType();                         // ServiceChangeEvent → "new"|"delete"|"change"|"reload"
Service changedService();                     // 新服务的浅拷贝快照
```

内部线程安全（供复刻参考）：`ServiceContext` 全部实例方法 `synchronized`；`ServiceRepository.services` / `discoveryConfigs` 为 `ConcurrentHashMap`，**以 lowercase serviceId 为键**。证据：`ServiceChangeListener.java:6-10`、`ServiceChangeEvent.java:8-13`、`ServiceContext.java:44,50,60,71,75,95,100,104`、`ServiceRepository.java:61,72,84,100`。

## 3. WebSocket 报文协议

路径常量（`artemis-common/.../config/WebSocketPaths.java:10-12`，`CONTEXT_PATH = "/"`）：

| 通道 | 路径 | 服务端 handler |
|---|---|---|
| 心跳 | `/websocket/registry/heartbeat` | `HeartbeatWsHandler` |
| 订阅 | `/websocket/discovery/instance-change` | `ServiceChangeWsHandler` |
| 广播 | `/websocket/discovery/all-instance-change` | `AllServicesChangeWsHandler` |

三通道均 `setAllowedOrigins("*")`，加 `WsIPBlackList` 拦截器（id 分别为 `service-heartbeat` / `service-discovery` / `service-discoveries`）。证据：`WebSocketEndpointConfig.java:30-45`。

### 3.1 心跳通道

**客户端 → 服务端**：`HeartbeatRequest`（全量本地实例；空集返回 null 不发送）

```json
{
  "instances": [
    { "regionId": "r", "zoneId": "z", "groupId": "g", "serviceId": "s", "instanceId": "i",
      "machineName": "host", "ip": "1.2.3.4", "port": 8080, "protocol": "http",
      "url": "http://1.2.3.4:8080", "healthCheckUrl": "http://1.2.3.4:8080/health",
      "status": "up", "metadata": { "k": "v" } }
  ]
}
```

**服务端 → 客户端**：`HeartbeatResponse`

```json
{
  "failedInstances": [ { "instance": { }, "errorCode": "data-not-found", "errorMessage": "msg" } ],
  "responseStatus": { "status": "success", "message": "", "errorCode": "success" }
}
```

- `FailedInstance` 字段：`instance` / `errorCode` / `errorMessage`。
- `ResponseStatus` 字段：`status`（`success`|`fail`|`partial_fail`|`unknown`）/ `errorCode` / `message`；⚠ 构造器参数顺序为 `(status, message, errorCode)`，**序列化键名以 getter 为准**。
- 服务端解码失败 → 回**静态默认报文**：`{"failedInstances":null,"responseStatus":{"status":"success","message":"","errorCode":"success"}}`（`HeartbeatWsHandler.java:32-36,38-54`）——错误被静默吞掉（client-sdk 域已知缺陷）。
- 客户端处理：`isServiceDown` → `markdown` 断连；`failedInstances` 中 `errorCode ∈ {data-not-found, unknown}` → HTTP 补注册。
- 服务端续租：租约不存在时**不自动注册**（仅返回 failed），注册由 HTTP register / replication 完成。

证据：`artemis-common/.../registry/{HeartbeatRequest,HeartbeatResponse,FailedInstance,ResponseStatus}.java`、`artemis-client/.../registry/InstanceRepository.java:83-95`、`InstanceRegistry.java:103-131,170-187`。

### 3.2 订阅通道

**客户端 → 服务端（订阅消息）= `DiscoveryConfig`**

```json
{ "serviceId": "s", "regionId": null, "zoneId": null, "discoveryData": null }
```

客户端只填 `serviceId`（可能带 `discoveryData`）；region/zone 通常为 null（走 HTTP `LookupRequest`）。服务端**以原样大小写 serviceId 为键**建 `serviceId → Set<sessionId>` 映射——与客户端 lowercase 键**大小写语义分裂**（discovery FR-DIS-13）。证据：`ServiceDiscovery.java:171-185`、`ServiceChangeWsHandler.java:41-52,110,116`。

**服务端 → 客户端（推送）= `InstanceChange`**

```json
{
  "instance": { "regionId": "r", "zoneId": "z", "groupId": null, "serviceId": "s",
                "instanceId": "i", "machineName": "host", "ip": "1.2.3.4", "port": 8080,
                "protocol": "http", "url": "http://1.2.3.4:8080", "healthCheckUrl": null,
                "status": "up", "metadata": { } },
  "changeType": "new",
  "changeTime": 1700000000000
}
```

`changeType ∈ {"new","delete","change","reload"}`；⚠ **`change` 在服务端无产生点**（仅 NEW / DELETE / RELOAD，discovery FR-DIS-06）。

**`reload` 伪实例的确切字段值**（`InstanceChanges.java:11-29`；由管理面三处产生）：

| 字段 | 值 |
|---|---|
| `serviceId` | 真实 serviceId |
| `instanceId` | `"reload"` |
| `ip` | `"0.0.0.0"` |
| `url` | `"http://serviceId/reload"`（**字面量 `serviceId`，非占位替换**） |
| `changeType` | `"reload"` |
| `changeTime` | `System.currentTimeMillis()` |
| 其余字段 | `null`；`port` = `0` |

推送链路：`NotificationCenter`（10 worker）从 `pollInstanceChange` 取变更 → 逐条 `subscriber.accept` → `ServiceChangeWsHandler.accept` 按 serviceId 找会话 + 对 session 加锁 `sendMessage`。`DELETE`/`RELOAD` **绕过 filter 强制推送**。

### 3.3 广播通道

`AllServicesChangeWsHandler.accept`：把**每一条** `InstanceChange` 推给**所有**已连接会话；未覆写 `handleTextMessage`（收到的文本被忽略）。⚠ **客户端 SDK 不使用该通道**（`AddressManager` 只连 `SERVICE_CHANGE_DESTINATION`）——复刻客户端时可不实现。证据：`AllServicesChangeWsHandler.java:29-71`、`common/AddressManager.java:45-47`。

### 3.4 帧编码 / 大小限制 / ping-pong

- **文本帧上限**：`websocket-session.text-message.buffer-size` 默认 8（**单位 KB，代码乘 1024**；范围 8–32）→ 设于 `container.setDefaultMaxTextMessageBufferSize(size*1024)`；⚠ 构造期一次生效（构造快照）。证据：`WebSocketSessionContext.java:60-64`。
- **ping/pong**：**仅客户端主动 ping**（`isAlive()` 发 `PingMessage` 等 `PongMessage`，超时 `ping-timeout` 默认 1000ms / 50–10000ms）；服务端 `handlePongMessage` 为**空实现**，靠 `DelayQueue` 过期踢会话（`session.ttl` 默认 6min / 5min–5h）。
- **客户端重连限流**：`reconnect-times` 默认 5 次 / 20s；会话 TTL 5min；connect-timeout 5s；健康检查周期默认 1s（区间 100ms–10min）。
- **WS 地址推导**：HTTP url 正则 `^http(s?)://` → `ws://`，再拼 WS path。证据：`common/AddressContext.java:20-21,42-45`。

## 4. 节点间复制协议（`/api/replication/registry/*`）

路径：`register.json` / `unregister.json` / `heartbeat.json` / `services.json`。前三个仅 POST；`services.json` 另有 GET 变体（`regionId` 必填、`zoneId` 可选）。均 `consumes/produces=application/json`。证据：`RestPaths.java:16-29`、`RegistryReplicationController.java:31-64`。

### 4.1 请求体

| 端点 | 请求类型 | JSON |
|---|---|---|
| register / unregister / heartbeat | `RegisterRequest` / `UnregisterRequest` / `HeartbeatRequest` | `{"instances":[{...Instance...}]}` |
| services | `GetServicesRequest` | `{"regionId":"r","zoneId":"z"}` |

### 4.2 响应体

| 端点 | 响应类型 | JSON |
|---|---|---|
| register / unregister / heartbeat | `RegisterResponse` / `UnregisterResponse` / `HeartbeatResponse` | `{"failedInstances":[...],"responseStatus":{...}}` |
| services | `GetServicesResponse` | `{"services":[{...Service...}],"responseStatus":{...}}` |

- ⚠ `UnregisterResponse` 字段名为 `_failedFailedInstances`（拼写错误），但 getter 为 `getFailedInstances` → **序列化键仍是 `failedInstances`**。
- `Service` JSON：`{"serviceId":..,"metadata":{..},"instances":[Instance],"logicInstances":[Instance],"routeRules":[RouteRule]}`。

**响应语义**：
- 限流 → `failedInstances=null` + `{status:"fail",errorCode:"rate-limited",message:"Request is rate limited."}`。
- 全部成功 → `SUCCESS_STATUS`；有失败实例 → `PARTIAL_FAIL`（`{status:"partial_fail",errorCode:"partial_fail",message:""}`）；请求异常 → `{status:"fail",errorCode:"internal-service-error",message:<ex>}`。
- 每失败实例 `errorCode ∈ {no-permission, data-not-found, internal-service-error}`；`errorMessage` 形如 `"<instance.toString()>: <msg>"`（**累积串**）。
- `services.json` 额外分支：registry 非 up → `service-unavailable`；region/zone 不匹配 → `no-permission`。

证据：`artemis-service/.../registry/replication/{GetServicesRequest,GetServicesResponse}.java`、`UnregisterResponse.java:13,37-43`、`Service.java:12-16`、`RegistryReplicationServiceImpl.java:63-145`、`RegistryTool.java:78-102`、`util/ResponseStatusUtil.java:21-23`。

### 4.3 复制客户端构造请求（`RegistryReplicationServiceClient`）

- POST；url = `FileExtension.concatPathParts(serviceUrl, FULL_PATH)`。
- 编码：`HttpRequestFactory.createRequest(url, "POST", request, JacksonJsonCodec.DEFAULT)`。
- **gzip**：每次调用 `HttpRequestFactory.gzipRequest(httpRequest)`。
- 超时：**仅 heartbeat 与 getServices 设 socket-timeout**（200ms / 2000ms；范围 50–5000 / 100–60000）；⚠ register / unregister 用默认。
- 共享客户端：静态 `DynamicPoolingHttpClientProvider("artemis.service.registry.replication", ...)`。

证据：`registry/replication/RegistryReplicationServiceClient.java:28-37,49-101`。

## 5. HTTP 通用约定

- **Content-Type**：请求/响应 `application/json`（REST 端点显式声明；复制客户端由 `HttpRequestFactory` 携带 codec 设置）。
- **字符编码**：UTF-8（`FilterConfig` 的 CrossDomainFilter / ziplet `CompressingFilter` / HiddenHttpMethodFilter 均 `encoding=UTF-8, forceEncoding=true`）；WS 帧为平台默认。
- **gzip**：客户端全部 outbound 请求体压缩——`HttpRequestFactory.gzipRequest()` **显式**包装 `GzipCompressingEntity` + `Content-Encoding: gzip` 头（幂等，已包装则跳过）；服务端响应由 ziplet `CompressingFilter` 压缩（`/*` 全覆盖，REST 与 WS 握手都过）。**响应解压无显式代码**（已取证 http-rpc-util 1.2.3 sources）：`HttpRequestExecutors.execute` 直接 `entity.getContent()` 交 codec，解压依赖 Apache HttpClient 4 **内建内容压缩协商**（未禁用时请求自动带 `Accept-Encoding: gzip,deflate`、响应自动解压）。charset：`Content-Type` 头不带 charset 参数（值 = codec mime，如 `application/json`），请求字节由 codec 产生（Jackson → UTF-8）、响应靠 Jackson 编码自检（UTF-8）。异常映射：`SocketTimeoutException`→`HttpTimeoutException`、其它 `IOException`→`HttpConnectException`、状态码 ≥300 或无 statusLine→`ApacheHttpRequestException`、实体流读失败→`CodecException`。
- **时间格式**：仅 `leases.json` 的租约时间有格式 `yyyy-MM-dd HH:mm:ss.SSS`（`SimpleDateFormat` 局部变量，非线程安全但无共享）；其余协议无日期格式字段（`changeTime` 为 epoch millis）。
- **端口/上下文**：`application.properties` 无 `server.port` / `context-path` → Spring Boot 默认 **8080** + `/`。
- ⚠ **错误响应统一结构 = `ResponseStatus`**（`{status, errorCode, message}`），业务错误一律 **HTTP 200 + body 内 responseStatus**；**无全局 `@RestControllerAdvice` / `ExceptionHandler`** → 畸形 JSON body 由 Spring 默认处理，返回框架默认 400 结构，**不是** `ResponseStatus`（复刻时需决定是否补齐）。
- **客户端重试**：`<id>.http-client.retry-times` 默认 5（1–10）/ `retry-interval` 默认 100ms（0–1000）；`isServiceDown → markUnavailable` 换节点；`isRerunnable → 重试`；重试耗尽抛异常但**被 HTTP 客户端吞掉**，故 `RegistryClient.register/unregister` **不对调用者抛网络异常**。
- **up-nodes 请求**：POST `/api/cluster/up-{registry,discovery}-nodes.json`，请求 `GetServiceNodesRequest{regionId,zoneId}`，响应 `GetServiceNodesResponse{nodes:[{zone:{...},url:<base>}],responseStatus}`，随机取一节点，domain-url 兜底。

证据：`FilterConfig.java:24-55`、`StatusServiceImpl.java:209,216-218`、`InstanceChange.java:53-59`、`ArtemisHttpClient.java:45-96`、`AddressRepository.java:52,65-71,99-104`、`GetServiceNodesResponse.java:13-14`。

## 6. 缺陷与勘误

| # | 内容 | 证据 |
|---|---|---|
| 1 | `ArtemisClientManagerConfig` 第 4 构造**丢弃入参**（DiscoveryClientConfig 永不生效） | `ArtemisClientManagerConfig.java:37-42` |
| 2 | `ArtemisClientManager` 双检锁字段**未加 `volatile`**——DCL 可见性隐患 | `ArtemisClientManager.java:21-22,34-56` |
| 3 | `RegistryClientImpl#unregister` **重复调用校验**两次 | `RegistryClientImpl.java:39-40` |
| 4 | `UnregisterResponse` 字段名拼写错误 `_failedFailedInstances`（getter 正确，序列化键无误） | `UnregisterResponse.java:13` |
| 5 | **两套 mapper 键序不一致（已取证为确定事实）**：HTTP REST 键按字母序（`SORT_PROPERTIES_ALPHABETICALLY=true`）；WS 通道 `JacksonJsonCodec.DEFAULT` 未设排序（**键序 = 声明序**）。null 行为两通道一致（均不省略）。复刻时若追求字节级一致需分别配置 | §1 |
| 6 | `DiscoveryClient.getService` **可能抛未受检 `RuntimeException`**，与行为规格「永不返回 null / 永不抛『不存在』」的表述存在张力（规格描述的是缓存语义；首次同步 lookup 失败路径确实抛错）——[discovery-spec](discovery-spec.md) FR-DIS-02 已按缓存语义表述，此处补 SDK 边界 | `ArtemisDiscoveryHttpClient.java:34,47` |
| 7 | 畸形 JSON 请求无统一错误结构（无全局异常处理） | §5 |
| 8 | 无 `close()` / `shutdown()`——SDK 不可优雅停止（与 client-sdk FR-CS-02 一致，此处给出实现层证据：内部 `shutdown` 存在但未暴露） | `WebSocketSessionContext.java:242-244` |
| 9 | `reload` 伪实例 `url` 为**字面量** `"http://serviceId/reload"`（非真实 serviceId 替换） | `InstanceChanges.java:15,27` |
| 10 | 订阅键大小写分裂（服务端原样 / 客户端 lowercase）——行级证据 | `ServiceChangeWsHandler.java:110,116` vs `ServiceRepository.java:61` |

## 7. 复刻完备性自检（本制品）

- SDK 公开 API：`ArtemisClientManager` + 两个 Config + `RegistryClient` + `DiscoveryClient` + `RegistryFilter` + `ServiceChangeListener/Event` 全签名与契约 ✓
- WS 三通道：路径 / 拦截器 id / 请求与响应 JSON / 编码 / 大小限制 / ping-pong ✓
- 复制协议：四端点请求响应体 + 客户端构造方式 + 超时 + gzip ✓
- HTTP 通用约定：Content-Type / 编码 / gzip / 时间格式 / 端口 / 错误结构 / 重试 / up-nodes ✓
- 序列化总纲（两套 mapper）✓
- 外部依赖项已从 Maven Central sources jar 取证 ✓：`JacksonJsonCodec.DEFAULT` 策略（§1）、`ObjectExtension.requireNonNull` 异常类型（§2.5）、`HttpRequestExecutors` 响应解压细节（§5）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-09 | 外部依赖三处待验证取证完毕（Central sources jar）：§1 codec 全配置（WS 键序=声明序、null 不省略）、§2.5 requireNonNull=IllegalArgumentException、§5 gzip/charset/异常映射；§6-5 改确定结论 |
| 1.0 | 2026-10-08 | 初版 |
