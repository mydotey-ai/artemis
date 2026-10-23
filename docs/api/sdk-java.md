# Artemis Java SDK（注册侧）

版本: 1.3    更新时间: 2026-10-23

> `artemis-client` 是注册侧 Java 8 SDK：本地实例集为唯一事实源，显式操作 + 心跳全量上报。协议契约（`registry.v1`）见[proto 契约规范](../dev/proto-contract.md)，内部只读查询契约见 [registry-query-api.md](registry-query-api.md)。

## 1. 坐标与环境

```xml
<dependency>
  <groupId>org.mydotey.ai.artemis</groupId>
  <artifactId>artemis-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

- Java 8 运行时（`release=8`），零 Spring 依赖。
- 运行时依赖封闭清单：grpc-netty-shaded / grpc-stub / grpc-protobuf / scf-simple / SLF4J API / micrometer-core。

Spring Boot 宿主使用 starter（Java 8 / Spring Boot 2.7+）：

```xml
<dependency>
  <groupId>org.mydotey.ai.artemis</groupId>
  <artifactId>spring-boot-starter-artemis-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. 快速开始

```java
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

// 地址与凭证经 SCF 配置（键见 §3；也可注入宿主 StringProperties）
// registry.addresses = 前置寻址 http url（生产配域名/LB，多活天然），SDK 经 GET <url>/cluster/nodes 发现节点列表
System.setProperty("artemis.client.registry.addresses", "http://artemis.example.com");
System.setProperty("artemis.client.auth.username", "artemis");
System.setProperty("artemis.client.auth.password", "artemis");

ArtemisClient client = ArtemisClients.newClient();
client.start();                                  // 建心跳流 + 立即首跳

ServiceId order = ServiceId.newBuilder()
        .namespace("default")
        .service("order-service")
        .build();
client.register(ServiceInstance.newBuilder()
        .service(order)
        .instanceId("order-1")
        .host("10.0.0.1")
        .port(8080)
        .weight(100)
        .build());

// ... 进程运行期间心跳自动续约 ...

client.close();                                  // 优雅下线：停心跳 → 注销全部实例 → 释放资源
```

### Spring Boot 自动装配

引入 starter 后无需手工创建 client：

```properties
# application.properties（也支持 YAML kebab-case：artemis.client.registry.addresses）
artemis.client.registry.addresses=http://artemis.example.com
artemis.client.auth.username=artemis
artemis.client.auth.password=artemis
```

```java
@Autowired ArtemisClient client;   // 容器启动即 start()，关闭时自动优雅下线
client.register(instance);
```

- 自动接管生命周期（`start`/`close`；关闭经 `ContextClosedEvent` 在容器关闭第一时机主动注销）、把 Boot/actuator 的 `MeterRegistry` 注入 SDK（无 registry 或多个同型 bean 时回落 `Metrics.globalRegistry`）。
- `artemis.client.enabled=false` 关闭自动装配；宿主自定义 `ArtemisClient` bean 时自动装配让位。
- 未配置 `registry.addresses` 时启动 fail-fast（`IllegalArgumentException`），不会静默不注册。

## 3. 配置

全部配置经 SCF 键 `artemis.client.*`（键定义在各消费类内）。缺省配置源为**系统属性 > 环境变量**（环境变量做容器友好映射：`ARTEMIS_CLIENT_REGISTRY_ADDRESSES`）；宿主可注入 `StringProperties` 装配文件源等。starter 额外支持 `application.properties`/YAML 与命令行，并接受同一键的 camelCase、kebab-case、小写蛇形与对应环境变量写法。

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.client.registry.addresses` | **必配** | 逗号分隔前置寻址 `http(s)://host[:port]` url（拒绝 query/fragment）；创建时校验失败即抛 `IllegalArgumentException`。生产实际配一个域名地址（域名/LB/DNS 天然多活） |
| `artemis.client.auth.username` / `auth.password` | 空 | 两者都配置才发送 Basic 认证头（gRPC 与寻址 HTTP 同凭证）；未配置不发（认证裁决归服务端）；只配一项时 WARN 且不发 |
| `artemis.client.operation.retryTimes` | 2 | 瞬态失败（UNAVAILABLE/DEADLINE_EXCEEDED）重试次数，0–10 |
| `artemis.client.operation.retryIntervalMs` | 500 | 重试间隔（100–10000ms） |
| `artemis.client.heartbeat.intervalMs` | 5000 | 心跳周期（500–60000ms），动态 |
| `artemis.client.heartbeat.ackTimeoutMs` | 30000 | ack 看门狗：超时未收 ack 判定半开连接并重连（1000–300000ms） |
| `artemis.client.reconnect.baseDelayMs` / `maxDelayMs` | 1000 / 30000 | 断流重连指数退避起点/上限（含 ±20% 抖动），动态 |
| `artemis.client.registry.connection.ttlMs` | 600000 | 连接 TTL 轮换周期（实际 = ttl × [0.8, 1.2) 随机抖动，防集群同步轮换）；到期主动换址并全量上报对账；0 禁用，动态 |
| `artemis.client.registry.address.refreshIntervalMs` | 300000 | 节点列表稳态刷新周期（1000–3600000ms），动态 |
| `artemis.client.registry.address.retryBaseMs` / `retryMaxMs` | 2000 / 60000 | 寻址失败指数退避起点/上限（含抖动，成功归零），动态 |
| `artemis.client.registry.address.circuitThreshold` | 3 | 同址连续失败熔断阈值（1–100），动态 |
| `artemis.client.registry.address.circuitOpenMs` | 30000 | 熔断窗口：期内选址跳过、到期惰性恢复（1000–600000ms），动态 |

配置变更的生效时机由**配置源**决定：文件源重载或自定义源触发变更事件后生效；系统属性/环境变量是启动期源（变更需重建 client）。地址在每次选址（开启/断流重连）时读取；凭证在每次请求时构造。

## 4. 地址与容灾（三级管线）

地址不是静态直连列表，而是一条发现管线：**配置 url（前置寻址入口）→ 节点列表（选址池）→ 失败熔断换址**。

1. **前置寻址（HTTP 发现）**：SDK 周期（默认 5min）调 `GET <url>/cluster/nodes`（复用 Basic 凭证，响应体 = 节点列表 protobuf bytes）取得全部成员的 external gRPC 端点与观测态。多个配置 url 依次尝试；**拉取失败保留旧列表**不清空；持续失败按指数退避重试。未就绪节点对该端点返回 503（不作寻址应答者），与连接失败同链路处理。单节点部署 = 1 成员集群，同管线无特殊分支。
2. **选址池优先级**：成员 `state` 只作优先级不作排除——`UP` > `UNKNOWN`/`STARTING` > `DOWN`，高优先级组内随机；高优非空不落低优（可用性优先，正确性由失败上报兜底）。
3. **熔断与换址**：断流/开流失败/ack 超时计入当前地址连续失败（熔断计数仅由心跳引擎驱动，归因无竞态）；达阈值（默认 3）熔断该地址（窗口内选址跳过、到期惰性恢复，全部熔断时仍全量随机不清零），并**立即重新前置寻址**刷新列表。重连退避期间选址排除刚断开的地址（换址）；当前节点死亡换到列表中其它节点，心跳全量上报使新节点 upsert 自愈——业务实例不过期。
4. **连接 TTL 轮换**：心跳流按 TTL（默认 10min ± 20% 抖动）主动换址重建——开流全量上报即修复新节点可能丢失的事件残留（分布式对账），轮换为健康换址：无退避、不计失败、不进熔断。

## 5. 操作语义

- **fire-and-forget**：`register` / `update` / `deregister` 同步更新本地集后异步上报，立即返回、无返回值。
- **失败自愈**：瞬态失败先按配置重试；重试耗尽或终态拒绝时记 WARN + 指标，并由**即时心跳补投 + 周期心跳**（register/update）或**租约 TTL**（deregister）最终收敛。调用方不需要（也拿不到）操作结果。
- **幂等**：register 无条件覆盖；update 幂等 upsert；deregister 意图幂等（目标缺席即成功）。
- **重试前重读本地集**：期间被注销则放弃、被更新则上报最新值，不会用陈旧状态覆盖。

## 6. 生命周期

| 调用 | 行为 |
|---|---|
| `start()` | 校验必配配置 → 建心跳流（bidi）→ 立即首跳；重复调用抛 `IllegalStateException` |
| 操作 | `start()` 前或 `close()` 后调用抛 `IllegalStateException`（编程错误，不静默） |
| `close()` | 停心跳并等待调度终止 → 等待在途上报（≤5s）→ 全量注销（尽力而为）→ 关传输；幂等 |

崩溃恢复：进程重启后业务重新注册即可（心跳 upsert 幂等收敛），SDK 不做注册侧磁盘快照。

**Spring Boot 容器关闭**：starter 监听 `ContextClosedEvent`，在容器关闭第一时机（宿主停流与 bean 销毁之前）调用 `close()`——注销在程序仍可用时主动发出；`destroyMethod=close` 保留为幂等兜底（重复调用 no-op）。

## 7. 可观测（Micrometer）

`newClient(properties, meterRegistry)` 注入宿主 registry；缺省挂 `Metrics.globalRegistry`（Micrometer 官方库模式，宿主 `Metrics.addRegistry` 接管，不接管则空转）。

| 指标 | 类型 | 标签 |
|---|---|---|
| `artemis.client.operation.failure` | Counter | `operation`、`client` |
| `artemis.client.operation.dropped` | Counter | `operation`、`client`（队列上限丢弃） |
| `artemis.client.report.duration` | Timer | `operation`、`client` |
| `artemis.client.heartbeat.failure` | Counter | `client` |
| `artemis.client.reconnect` | Counter | `client` |
| `artemis.client.rotation` | Counter | `client`（TTL 轮换次数） |
| `artemis.client.address.circuit.opened` | Counter | `client`、`address`（地址熔断打开事件） |
| `artemis.client.nodelist.refresh` | Counter | `client`、`outcome`（success/failure/empty） |
| `artemis.client.connected` | Gauge 0/1 | `client` |
| `artemis.client.instances` | Gauge | `client` |

日志：操作失败/断流 WARN、重连成功与启停 INFO、热路径 DEBUG；SDK 仅依赖 SLF4J API，日志实现由宿主提供。

## 8. 已知限制

- 同 JVM 多 client 共用缺省配置源时同键共享（v0.1 单 client 假设）；多 client 场景请注入隔离的 `StringProperties`。
- 服务发现（消费方）SDK 与发现侧 starter 随 v0.3 交付。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-23 | v0.2 phase-6：starter 关闭改由 `ContextClosedEvent` 第一时机触发 `close()`（注销提前到程序可用时），destroyMethod 为幂等兜底 |
| 1.2 | 2026-10-23 | v0.2 phase-5：地址改前置寻址 url（`registry.addresses`，原 `serverAddresses` 弃用）；新增「地址与容灾」三级管线与 TTL 轮换节；配置表补 registry.* 六键；指标补 rotation/circuit/nodelist |
| 1.1 | 2026-10-19 | phase-8：补 Spring Boot starter 坐标、自动装配用法与条件；修正环境变量映射名并列出 starter 支持的键写法 |
| 1.0 | 2026-10-19 | 初版：phase-7 注册侧 SDK 公开 API、配置、语义、可观测与限制 |
