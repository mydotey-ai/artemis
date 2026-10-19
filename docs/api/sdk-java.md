# Artemis Java SDK（注册侧）

版本: 1.0    更新时间: 2026-10-19

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

## 2. 快速开始

```java
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

// 地址与凭证经 SCF 配置（键见 §3；也可注入宿主 StringProperties）
System.setProperty("artemis.client.serverAddresses", "localhost:9090");
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

## 3. 配置

全部配置经 SCF 键 `artemis.client.*`（键定义在各消费类内）。缺省配置源为**系统属性 > 环境变量**（环境变量做容器友好映射：`ARTEMIS_CLIENT_SERVERADDRESSES`）；宿主可注入 `StringProperties` 装配文件源等。

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.client.serverAddresses` | **必配** | 逗号分隔 `host:port`（IPv6 用 `[addr]:port`），创建时校验失败即抛 `IllegalArgumentException` |
| `artemis.client.auth.username` / `auth.password` | 空 | 两者都配置才发送 Basic 认证头；未配置不发（认证裁决归服务端）；只配一项时 WARN 且不发 |
| `artemis.client.operation.retryTimes` | 2 | 瞬态失败（UNAVAILABLE/DEADLINE_EXCEEDED）重试次数，0–10 |
| `artemis.client.operation.retryIntervalMs` | 500 | 重试间隔（100–10000ms） |
| `artemis.client.heartbeat.intervalMs` | 5000 | 心跳周期（500–60000ms），动态 |
| `artemis.client.heartbeat.ackTimeoutMs` | 30000 | ack 看门狗：超时未收 ack 判定半开连接并重连（1000–300000ms） |
| `artemis.client.reconnect.baseDelayMs` / `maxDelayMs` | 1000 / 30000 | 断流重连指数退避起点/上限（含 ±20% 抖动），动态 |

配置变更的生效时机由**配置源**决定：文件源重载或自定义源触发变更事件后生效；系统属性/环境变量是启动期源（变更需重建 client）。地址在每次选址（开启/断流重连）时读取；凭证在每次请求时构造。

## 4. 操作语义

- **fire-and-forget**：`register` / `update` / `deregister` 同步更新本地集后异步上报，立即返回、无返回值。
- **失败自愈**：瞬态失败先按配置重试；重试耗尽或终态拒绝时记 WARN + 指标，并由**即时心跳补投 + 周期心跳**（register/update）或**租约 TTL**（deregister）最终收敛。调用方不需要（也拿不到）操作结果。
- **幂等**：register 无条件覆盖；update 幂等 upsert；deregister 意图幂等（目标缺席即成功）。
- **重试前重读本地集**：期间被注销则放弃、被更新则上报最新值，不会用陈旧状态覆盖。

## 5. 生命周期

| 调用 | 行为 |
|---|---|
| `start()` | 校验必配配置 → 建心跳流（bidi）→ 立即首跳；重复调用抛 `IllegalStateException` |
| 操作 | `start()` 前或 `close()` 后调用抛 `IllegalStateException`（编程错误，不静默） |
| `close()` | 停心跳并等待调度终止 → 等待在途上报（≤5s）→ 全量注销（尽力而为）→ 关传输；幂等 |

崩溃恢复：进程重启后业务重新注册即可（心跳 upsert 幂等收敛），SDK 不做注册侧磁盘快照。

## 6. 可观测（Micrometer）

`newClient(properties, meterRegistry)` 注入宿主 registry；缺省挂 `Metrics.globalRegistry`（Micrometer 官方库模式，宿主 `Metrics.addRegistry` 接管，不接管则空转）。

| 指标 | 类型 | 标签 |
|---|---|---|
| `artemis.client.operation.failure` | Counter | `operation`、`client` |
| `artemis.client.operation.dropped` | Counter | `operation`、`client`（队列上限丢弃） |
| `artemis.client.report.duration` | Timer | `operation`、`client` |
| `artemis.client.heartbeat.failure` | Counter | `client` |
| `artemis.client.reconnect` | Counter | `client` |
| `artemis.client.connected` | Gauge 0/1 | `client` |
| `artemis.client.instances` | Gauge | `client` |

日志：操作失败/断流 WARN、重连成功与启停 INFO、热路径 DEBUG；SDK 仅依赖 SLF4J API，日志实现由宿主提供。

## 7. 已知限制

- 同 JVM 多 client 共用缺省配置源时同键共享（v0.1 单 client 假设）；多 client 场景请注入隔离的 `StringProperties`。
- 服务发现（消费方）SDK、Spring Boot starter（含 Micrometer 自动注入）分别在 v0.3 与 phase-8 交付。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-19 | 初版：phase-7 注册侧 SDK 公开 API、配置、语义、可观测与限制 |
