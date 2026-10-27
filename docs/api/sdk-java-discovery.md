# Artemis Java SDK（发现侧）

版本: 1.2    更新时间: 2026-10-27

> `artemis-client` 中的消费方发现 SDK（v0.3 phase-8）：订阅推送 + 本地视图 + 磁盘快照 + 兜底拉取 + discovery 专属地址容灾。协议契约见 [discovery-query-api.md](discovery-query-api.md) / [discovery-subscription-api.md](discovery-subscription-api.md) / [discovery-reconciliation-api.md](discovery-reconciliation-api.md)；节点目录端点见 [discovery-nodes-api.md](discovery-nodes-api.md)。注册侧 SDK 见 [sdk-java.md](sdk-java.md)。

## 1. 坐标与环境

与注册侧共用同一 artifact（Java 8 / 零 Spring）：

```xml
<dependency>
  <groupId>org.mydotey.ai.artemis</groupId>
  <artifactId>artemis-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

运行时依赖封闭清单不变：grpc-netty-shaded / grpc-stub / grpc-protobuf / scf-simple / SLF4J API / micrometer-core。Spring Boot 宿主可用 starter 自动装配（v0.3 phase-9 起）；库模式手工创建：

```java
DiscoveryClient client = ArtemisClients.newDiscoveryClient();
```

## 2. 快速开始

```java
import org.mydotey.ai.artemis.client.*;

System.setProperty("artemis.client.discovery.addresses", "http://artemis.example.com:8090");
System.setProperty("artemis.client.clientId", "order-app");   // 多 client 必须区分
System.setProperty("artemis.client.auth.username", "artemis"); // 与服务端外部账户一致（未启用可不配）
System.setProperty("artemis.client.auth.password", "artemis");

DiscoveryClient client = ArtemisClients.newDiscoveryClient();
client.start();

ServiceId order = ServiceId.newBuilder()
        .namespace("default")
        .service("order-service")
        .build();

Subscription subscription = client.subscribe(
        DiscoverySelector.newBuilder().addService(order).build(),
        service -> System.out.println("view changed: " + service));

// 任意时刻读取本地视图（不可变列表引用，零拷贝；无数据返回空列表）
List<ServiceInstance> instances = subscription.getInstances(order);

// ... 进程运行期间订阅流自动推送、60s 对账、15min 全量刷新 ...

subscription.close();   // cancel 本订阅流 + 终写快照
client.close();         // 关闭全部订阅 + 释放共享连接/线程
```

- 选择器空集合语义与服务端一致：`services` 空 = 全部服务；`labels` 空 = 不过滤；`clusters` 空 = 全部来源（union）。
- 同一 client 可运行期建多个订阅：各自一条流、独立视图/对账/快照；共享连接、地址寻址、applier、调度与监听队列。
- `subscribe(selector)` 为无监听便捷重载。

## 3. 公开 API

| 类型 | 说明 |
|---|---|
| `DiscoveryClient` | `start()` / `subscribe(selector, listener)` / `close()`；start 前或 close 后 subscribe 抛 `IllegalStateException` |
| `Subscription` | `selector()` / `getInstances(service)` / `status()` / `close()`；close 后 `getInstances`/`status` 抛 `IllegalStateException` |
| `DiscoverySelector` | 不可变选择器；`services`/`labels`/`clusters` 防御性拷贝；空白键值 fail-fast；同内容指纹相同（FNV-1a-64） |
| `DiscoveryListener` | `onServiceChanged(ServiceId)`；仅信号，回调内读 `getInstances` 取最新视图 |
| `DiscoveryStatus` | `isConnected()` / `isSnapshotReplay()` / `snapshotTimestampMs()` / `upstreamStates()` |
| `UpstreamState` | `UNKNOWN` / `SYNCING` / `SYNCED` / `DISCONNECTED`；**非 SYNCED 即数据可能陈旧** |

`ServiceInstance` 为不可变值类型（`service`/`instanceId`/`host`/`port`/`status`/`weight`/`metadata`）。`InstanceStatus` 相对注册侧新增 `UNSPECIFIED`/`UNKNOWN`（additive）：发现侧按 wire 原值保留，以与服务端 checksum canonical 规则一致。

## 4. 地址与容灾（与 registry 同管线，ADR-020）

```text
HTTP 引导地址（discovery.addresses）
  → GET <url>/cluster/nodes（DiscoveryNodesResponse protobuf）
  → 节点列表（gRPC 端点）
  → 历史最近成功优先 + 高优组内随机 + 熔断换址
```

- **前置寻址**：周期（默认 5min）拉取节点目录；多个配置 url 依次尝试；拉取失败**保留旧列表**并指数退避；未就绪节点返回 503，不作应答。
- **选址**：发现侧开启「历史最近成功地址」粘滞（`lastSuccessful`，进程内记忆）；未熔断且在列表中才优先，否则随机。
- **熔断换址**：断流/开流失败计入当前地址连续失败（unary 查询/对账不计失败）；达阈值熔断，窗口内跳过；**全熔断仍全量随机**（可用性优先）；断流重连排除刚断开的地址。
- **慢消费者**：服务端 `RESOURCE_EXHAUSTED` 断流只重连该订阅，不熔断、不换址（共享 channel 健康）。
- 共享 channel 的流级失败按代次去重：仅首个失败计一次熔断并重建 channel，连带错误忽略。
- discovery **无服务端存活列表/探测**；节点目录是静态部署声明，详见 [discovery-nodes-api.md](discovery-nodes-api.md)。

## 5. 同步语义（推拉结合，ADR-019）

| 阶段 | 行为 |
|---|---|
| 建立 | 首条空事件 = 订阅确认；SDK 随后做一次 **bootstrap 全量 Query**，期间推送入有界缓冲 |
| 首次对齐 | Query 快照整体替换本地视图（本有而响应无 = 服务已消失），再按序重放缓冲事件；不制造空窗 |
| 稳态 | 实时增量推送（服务端已做 labels/clusters/services 视图语义翻译，SDK 无脑 apply） |
| 对账 | 每 **60s** 拉 per-service checksum 表与本地逐 `(cluster, service)` 比对；不一致触发全量刷新 |
| 全量兜底 | 每 **15min** 全量 Query（checksum 失效/碰撞的最终兜底） |
| 断连 | 本地最后视图继续服务（陈旧换可用）；指数退避 + 抖动换址重连；重连后重新 bootstrap 收敛 |
| 慢消费者 | at-most-once：服务端断流，SDK 重订阅重新基线 |

- 本地视图为来源 cluster 的 **union**（同 instanceId 可跨 cluster 并列）；不保证跨 cluster 顺序。
- 所有视图写收敛到 client 单线程 applier；`getInstances` 返回不可变列表引用（零深克隆）。
- 监听回调在共享有界队列执行：满时丢最旧并计数，慢监听不阻塞推送；异常 catch + WARN。

### 磁盘快照

- 每订阅一个文件：`<snapshot.dir>/<clientId>/selector-<fingerprint>.snapshot`。
- **随视图变更异步落盘**（单线程 writer、200ms 合并突发、只写最新），`Subscription.close()` 经 applier 屏障后同步终写；原子替换。
- 内容为 cluster 归属实例集（复用 `discovery.v1 QueryResponseData` proto bytes）+ magic/版本/写入时间/选择器指纹；**不落上游新鲜度**。
- 冷启动回放：`subscribe` 时加载；**不设过期**（陈旧换可用），`status.isSnapshotReplay()` 暴露来源。指纹不符/损坏忽略，不影响订阅启动。

## 6. 配置（SCF，`artemis.client.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.client.tls.*` | `false` / 空 | 出站 TLS（与注册侧 SDK 共用一组键，语义见 [sdk-java.md](sdk-java.md) §3：`enabled` + `trustCaPath`，gRPC 与 https 前置寻址同锚） |
| `artemis.client.clientId` | `default` | 静态；指标 `client` 标签与快照目录隔离键。同 JVM 多 client 必须配置不同值；仅允许 `[A-Za-z0-9._-]` 且不得为 `.`/`..` |
| `artemis.client.discovery.addresses` | **必配** | 逗号分隔 HTTP(S) 引导地址（`http(s)://host[:port]`，拒绝 query/fragment）；创建时校验失败即 `IllegalArgumentException` |
| `artemis.client.discovery.address.refreshIntervalMs` | 300000 | 节点目录稳态刷新周期（1000–3600000ms） |
| `artemis.client.discovery.address.retryBaseMs` / `retryMaxMs` | 2000 / 60000 | 拉取失败指数退避起点/上限（含抖动，成功归零） |
| `artemis.client.discovery.address.circuitThreshold` | 3 | 同址连续失败熔断阈值（1–100） |
| `artemis.client.discovery.address.circuitOpenMs` | 30000 | 熔断窗口（1000–600000ms） |
| `artemis.client.discovery.reconnect.baseDelayMs` / `maxDelayMs` | 1000 / 30000 | 订阅断流重连退避（含 ±20% 抖动） |
| `artemis.client.discovery.reconcile.intervalMs` | 60000 | checksum 对账周期（1000–3600000ms） |
| `artemis.client.discovery.refresh.intervalMs` | 900000 | 全量 Query 刷新周期（60000–86400000ms） |
| `artemis.client.discovery.snapshot.enabled` | true | 磁盘快照开关 |
| `artemis.client.discovery.snapshot.dir` | `artemis-client-discovery-snapshots` | 快照根目录（相对路径基于工作目录） |
| `artemis.client.discovery.listener.queueCapacity` | 1000 | 监听回调队列容量（1–100000；满丢最旧 + 计数） |
| `artemis.client.auth.username` / `auth.password` | 空 | 两者都配才发 Basic 头（gRPC 与节点目录 HTTP 同凭证）；只配一项 WARN 且不发 |

选择器不走上表配置，由 `subscribe` 的 `DiscoverySelector` 参数传入。

## 7. 可观测（Micrometer）

`newDiscoveryClient(properties, meterRegistry)` 注入宿主 registry；缺省挂 `Metrics.globalRegistry`。

| 指标 | 类型 | 标签 |
|---|---|---|
| `artemis.client.discovery.connected` | Gauge 0/1 | `client`、`subscription` |
| `artemis.client.discovery.services` | Gauge | `client`、`subscription` |
| `artemis.client.discovery.subscribe.reconnect` | Counter | `client`、`subscription` |
| `artemis.client.discovery.push` | Counter | `client`、`subscription` |
| `artemis.client.discovery.refresh` | Counter | `client`、`subscription`、`trigger`（bootstrap/periodic/checksum）、`outcome` |
| `artemis.client.discovery.reconcile` | Counter | `client`、`subscription`、`outcome`（success/failure/mismatch） |
| `artemis.client.discovery.snapshot` | Counter | `client`、`subscription`、`outcome`（load-hit/load-miss/load-invalid/save-success/save-failure） |
| `artemis.client.discovery.listener.dropped` | Counter | `client`、`subscription` |
| `artemis.client.discovery.push.buffer.dropped` | Counter | `client`、`subscription` |
| `artemis.client.discovery.apply.dropped` | Counter | `client` |
| `artemis.client.nodelist.refresh` | Counter | `client`、`channel=discovery`、`outcome` |
| `artemis.client.address.circuit.opened` | Counter | `client`、`channel=discovery`、`address` |

`subscription` = 选择器指纹（16 位 16 进制）+ 本地序号，重复选择器订阅也可区分。

## 8. 已知限制

- 选择器在订阅建立时固定，不支持运行期修改；变更需 `close()` 后重新 `subscribe`。
- 磁盘快照不过期，回放数据可能陈旧；用 `status.isSnapshotReplay()` / `upstreamStates()` 判断降级。
- 同 `clientId` + 同选择器的重复订阅共享快照文件（内容语义等价）；多 client 请配置不同 `clientId`。
- 多订阅共享同一 gRPC channel：地址级故障影响全部订阅（失败计数按流去重，仍由成功事件清零）。
- 发现侧 Spring Boot starter 自动装配已随 v0.3 phase-9 交付（见下节）。
- 不做客户端负载均衡与 TLS（TLS 归 v1.0）。
- 服务端必须配置节点目录（`artemis.discovery.nodes`），否则 `/cluster/nodes` 返回空列表、SDK 无地址可用。

## 9. Spring Boot 自动装配（v0.3 phase-9）

与注册侧共用同一 starter（`spring-boot-starter-artemis-client`），按配置条件独立装配：

```properties
# application.properties（只做发现的消费方不必配 registry 地址）
artemis.client.discovery.addresses=http://artemis.example.com:8090
```

```java
@Autowired DiscoveryClient client;   // 容器启动即 start()，关闭时自动 close
Subscription subscription = client.subscribe(selector);   // 订阅时机归宿主业务（starter 不代建订阅）
```

- **条件**：配了 `artemis.client.discovery.addresses` 才创建 `DiscoveryClient`；地址非法仍构造期 fail-fast。两侧地址都配则两个 client 共存，共享同一 SCF 配置桥与 `MeterRegistry`。
- 配置桥让位、宿主 client bean 让位、`artemis.client.enabled=false`、生命周期与指标注入语义与注册侧一致（详见 [sdk-java.md](sdk-java.md) §Spring Boot 自动装配）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-27 | v1.0 phase-1：键表补共享 TLS 键组引用（语义见 sdk-java.md §3） |
| 1.1 | 2026-10-24 | v0.3 phase-9：新增 §9 Spring Boot 自动装配（与注册侧共用 starter，按 discovery.addresses 条件装配、不代建订阅） |
| 1.0 | 2026-10-24 | v0.3 phase-8 初版：发现侧 SDK 公开 API、配置、同步语义、磁盘快照、地址容灾与指标 |
