# Phase 7：Java 8 SDK 注册侧

版本: 1.8    更新时间: 2026-10-19

## 目标

交付 Java 8 注册侧 SDK：心跳客户端（含本地实例集管理）、生命周期 API 与三级地址容灾骨架。

## 范围

做什么：

- 心跳客户端：注册/心跳/注销请求的本地实例集管理与发送调度
- 生命周期 API：客户端启动、停止与实例注册/更新的对外入口
- 三级地址容灾骨架：地址解析与故障切换的抽象结构

不做什么：

- 磁盘快照：注册侧不落盘（决策见「设计决策」；归发现侧 SDK，v0.3）
- Spring Boot starter 自动装配与 CI（phase-8）
- discovery 查询侧 SDK 能力（v0.3）

## 涉及模块

- `java/client/`（artemis-client，release=8）
- proto：`registry.v1`（client 侧生成与调用）

## 验收方式

对应测试文档：[phase-7-client-sdk-test.md](phase-7-client-sdk-test.md)

## 产品设计（③ 补）

### 公共 API 面（api 层，Java 8）

SDK 公共面用**自研不可变值类型**，不暴露 proto 生成类：proto 字段演化（弃用/更名走 wire 兼容）不应传导为 SDK API 破坏（SDK 是长期承诺面，v1.0 发 Maven Central 后不可收回）；proto↔值类型转换集中一个内部 mapper。

```java
// 值类型（不可变，手写 builder + 构造期防御性复制；零 Lombok——SDK 线纪律）
public final class ServiceId {          // namespace + service（D10）
public final class InstanceId {         // ServiceId + instanceId
public final class ServiceInstance {    // ServiceId + instanceId/host/port/status(UP|DOWN)/weight/metadata

// 可观测：Micrometer 标准端口（技术选型 #34，Counter/Timer/Gauge + 标签维度）
// newClient(properties, meterRegistry) 注入；缺省挂 Metrics.globalRegistry（库模式，空转无害）
// 指标集：
//   artemis.client.operation.failure  Counter  tag: operation   重试耗尽/终态失败
//   artemis.client.report.duration    Timer    tag: operation   每次上报尝试耗时
//   artemis.client.heartbeat.failure  Counter                   心跳发送失败（断流）
//   artemis.client.reconnect          Counter                   重连成功
//   artemis.client.connected          Gauge 0/1                 心跳流连接态
//   artemis.client.instances          Gauge                     本地实例数

// fire-and-forget：操作 void、异步上报、成败内化（WARN 日志 + metrics + 自愈补投）
public interface ArtemisClient extends AutoCloseable {
    void start();
    void register(ServiceInstance instance);
    void update(ServiceInstance instance);   // 幂等 upsert
    void deregister(InstanceId instance);    // 意图幂等
    @Override void close();   // 优雅下线（见「生命周期语义」）
}

// 全配置经 SCF 键 artemis.client.*（单一来源），无 builder：
ArtemisClients.newClient();                                  // 缺省源：系统属性 > 环境变量
ArtemisClients.newClient(hostProperties);                    // 宿主注入 StringProperties（可含文件源）
ArtemisClients.newClient(hostProperties, meterRegistry);     // + Micrometer registry（缺省 globalRegistry）
// 必配 artemis.client.serverAddresses 缺失时创建即 fail-fast
```

单实例粒度、不做批量重载：批量语义已由心跳全量上报贯穿（资产 #11 落点在心跳与 wire，不在用户 API）；多服务多实例 = 多次调用。有真实需求随 discovery 侧（v0.3）一并评估。

### 行为语义

**本地实例集（唯一事实源，资产 #1 继承）**：不可变快照 + volatile 发布（guidelines §4.1 同款模式）；`Map<ServiceId, Map<String, ServiceInstance>>` 整体替换。**fire-and-forget 操作**：显式操作同步更新本地集（必成、纳秒级）后**异步上报**（单线程 ops executor）立即返回——成败不向用户传导，由 SDK 内部保证最终达成。**失败处理三级链**：① 瞬态传输失败（UNAVAILABLE/DEADLINE_EXCEEDED）按配置**有界重试**（`operation.retryTimes` 次 × `operation.retryIntervalMs` 间隔，调度式重试不占线程；业务拒绝 ACK_FAIL 与配置性失败不重试——重试无意义）；② 重试耗尽或终态拒绝 → 结论性 WARN + Micrometer `artemis.client.operation.failure` 计数 + register/update **自动补投即时心跳**（`sendNow()`，服务端 upsert 幂等快速收敛）；③ 周期心跳无限兜底 / deregister 由服务端 TTL 过期收敛（架构 §4.1——心跳即终极重试）。close 的全量注销单次尽力（不重试，TTL 兜底）。

**心跳引擎**：单条 gRPC bidi stream，单线程命名 daemon 调度器（`artemis-client-heartbeat`，lazy 创建——未 start 的 client 不占线程）默认 5s 周期全量上报（间隔 < 服务端租约 TTL 默认 30s，周期任务自续式每跳重读配置值——动态生效）；`start()` 立即首跳。seq 进程内 `AtomicLong` 单调递增（重启重置）；ack 仅记录 `lastAckSeq`（日志/排障），不阻塞等待特定 seq（超时重发，全量幂等自愈）。流断开 = 重连：指数退避 1s 起至 30s 上限，±20% 随机抖动（防多客户端同步重连风暴；完整限流随 v0.2 容灾）。重连成功即恢复周期心跳，无需重注册动作。**崩溃恢复语义**：SDK 进程崩溃重启 = 业务代码初始化时重新 `register()` + 心跳 upsert 幂等收敛，天然自愈，无需任何落盘。

**生命周期（修复局限 #11）**：`start()` 前调用操作抛 `IllegalStateException`；`start()` = 校验必填（fail-fast）→ 建 channel → 首跳。`close()` 顺序固定：**停心跳调度（先停，防注销后再被心跳补注册回来）→ deregister 全量（同步、5s 有界超时，尽力而为失败仅 WARN）→ 关 channel/executor（有界 await）**。零非 daemon 线程遗留（guidelines §9）。

**三级地址容灾骨架（资产 #2 骨架，v0.2 完整）**：

```java
interface ServerAddressProvider { String select(); }   // internal 端口
// v0.1 唯一实现 ConfigAddressProvider：配置列表随机选址；断流重连时重新 select（列表内换址）
```

存活节点列表拉取、`markUnavailable` 熔断、TTL 强制轮换为 v0.2 扩展点（registry peer 列表 API 随 v0.2 集群引入）；v0.1 单节点列表即单元素，结构就位、机制最简。

**认证**：username/password **默认空**（未配置不发认证头，认证裁决归服务端；显式配置方可认证，与服务端「显式配置方可关闭」对称）→ Basic `authorization` metadata interceptor（对齐 GrpcAuthInterceptor）。

### 配置项（SCF，前缀 `artemis.client.*`）

全部配置走 SCF 单一来源（宿主可注入 `StringProperties`，缺省系统属性 + 环境变量源——原产品「配置源由宿主注入」模式延续，文件源宿主按需自装配）；键定义在消费类内（guidelines §10 分散纪律）。创建期校验必配项（fail-fast）；地址在每次选址时读取、凭证在每次请求时构造。运行时变更的生效时机由配置源决定：文件源重载/自定义源 raiseChangeEvent 后生效，系统属性/环境变量为启动期源：

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.client.serverAddresses` | **无（必配）** | 逗号分隔 `host:port` 列表，创建时 fail-fast 校验 |
| `artemis.client.auth.username` / `auth.password` | 空 | **两者都配置**才发 Basic 认证头；未配置不发（认证裁决归服务端，与服务端「显式配置方可关闭」对称）；半配置 WARN |
| `artemis.client.operation.retryTimes` | 2 | 瞬态失败重试次数（值域 0–10，0 = 不重试直接进兜底链） |
| `artemis.client.operation.retryIntervalMs` | 500 | 重试间隔（值域 100–10000；固定间隔——次数少无退避必要，退避语义在断流重连） |
| `artemis.client.heartbeat.intervalMs` | 5000 | 心跳周期，动态（值域 500–60000，周期任务自续式重读） |
| `artemis.client.heartbeat.ackTimeoutMs` | 30000 | ack 看门狗阈值（值域 1000–300000）：超时未收 ack 判半开连接并重连 |
| `artemis.client.reconnect.baseDelayMs` / `maxDelayMs` | 1000 / 30000 | 断流重连退避起点/上限，动态 |

### 内部结构（internal 包，api/internal 二分——guidelines §9）

```text
org.mydotey.ai.artemis.client
├── ArtemisClient / ArtemisClients（工厂）/ 值类型   # api（可观测经 Micrometer 标准 API）
└── internal/
    ├── DefaultArtemisClient        # 编排：生命周期、操作委派
    ├── LocalInstanceStore          # 本地实例集（不可变快照发布）
    ├── HeartbeatChannel            # bidi 流管理：seq/ack/重连退避
    ├── ClientProperties            # SCF string 配置工具（不设 static，防键缓存固化首建值）
    ├── GrpcRegistryClient          # 地址/凭证配置 + unary 操作 + 流 stub + auth interceptor + proto mapper
    ├── ServerAddressProvider / ConfigAddressProvider（select 重读配置，动态）
    └── （无快照存储——注册侧不落盘）
```

### 测试基础设施（形态扩展）

- client 单测（surefire，fork JDK 8）：SDK 协议行为以**进程内裸 gRPC 假服务**（`ServerBuilder` + 测试用 `RegistryServiceImplBase`，随机端口）驱动——不起 Spring registry-server。两个硬边界决定此形态：client 模块 test 依赖服务端模块撞 enforcer 硬约束；JDK 8 测试 JVM 无法加载 release=25 的服务端类。
- 端到端 IT 落 **registry-server 侧**新增 `ClientSdkIT`（`*IT`，failsafe，JDK 25）：registry-server test 依赖 `artemis-client`（反向依赖不违禁，enforcer 只禁 client→服务端），复用既有 `@SpringBootTest` + `LocalGrpcServerPort` 设施，SDK 驱动真服务端验证注册/心跳/注销/close 链路。SDK 类 release=8 在 JDK 25 JVM 正常加载；Java 8 运行时兼容由 client 单测覆盖。

### 设计决策（对照基线 §5/§6 与架构）

- 显式四操作 + 心跳自愈、不做重试状态机：架构 §4.1 / D12 已定案，SDK 是其客户端侧落地。
- **注册侧不做磁盘快照（review 定案 2026-10-19）**：注册的事实源是业务代码自身（实例地址/端口/元数据来自业务配置），重启后业务重新 `register()` + 心跳 upsert 幂等收敛天然自愈；落盘回放反而是用陈旧自我认知覆盖当前事实。磁盘快照的正确归属是发现侧 SDK——消费方缓存服务列表、server 全挂时兜底（roadmap v0.3 已列；架构 §4.1/§8 与 roadmap v0.1 条目已同步修正）。
- 值类型隔离 proto：SDK 公共 API 承诺纪律（理由如上）；否决直接暴露 proto 生成类（wire 演化传导 API 破坏）。
- IT 布局 server 侧：见测试基础设施节；否决 client 侧 IT（enforcer + JDK 双硬边界）。
- 全配置 SCF 化、无 builder（review 定案 2026-10-19 第二轮）：地址/凭证/行为参数统一走 `artemis.client.*` 键（guidelines §10 分散纪律），`ArtemisClients.newClient()` 两重载即全部入口；必配项创建时 fail-fast。SCF 配置不设 static：manager 按键缓存 PropertyConfig 且 static 固化首建值，同 JVM 多次 newClient（测试逐例重建）会读到缓存旧值（原产品勘误 §8-13 同根坑）；newClient 时取值固化衍生数据（地址列表/认证头）即该 client 的启动边界。

## 实现结果（⑦ 补）

实现要点：

- api 7 类（`ArtemisClient`/`ArtemisClients` + 值类型 `ServiceId`/`InstanceId`/`ServiceInstance`/`InstanceStatus`），internal 类：`DefaultArtemisClient`/`LocalInstanceStore`/`HeartbeatChannel`/`GrpcRegistryClient`/`ProtoMapper`/`ServerAddressProvider`/`ConfigAddressProvider`/`ClientProperties`/`ClientBasicAuth`/`BoundedScheduledExecutor`/`RelaxedEnvironmentConfigurationSource`。
- 心跳调度：周期任务**自续式**（每跳重读 `intervalMs`，动态生效——弃 `scheduleAtFixedRate`：既固化间隔，抛错即永久停摆）；调度器单线程 daemon、lazy 到 `start()` 创建。
- 流代数（generation）隔离陈旧回调：重连后旧流迟到的 `onError` 不拆新流；`reconnectAttempt` 用 `AtomicInteger` 消跨线程竞争。
- unary 操作 5s deadline；`catch RuntimeException` 全兜底转失败结果（合约「失败不抛异常」），gRPC `StatusRuntimeException` 提取 status code、其余以 `INTERNAL` 记。
- `GrpcRegistryClient.close()` 置 closed 后 `channel()` 拒绝再建（防 close 与操作并发泄漏 channel）；`resetChannel()` 异步 `shutdown()` 不阻塞心跳调度线程。
- registry-server test 依赖 `artemis-client`（反向依赖不违 enforcer），`ClientSdkIT` 复用 `RegistryGrpcIT` 测试装配（MutableClock + TTL 1s + 内存快照隔离）。

review 修复记录（/code-review max 15 findings，全修）：

1. `ServiceInstance` metadata 构造期防御性复制（防别名可变、CME）。
2. 心跳 tick 异常杀死周期任务 → 自续式调度 + 全捕获（异常不再停摆心跳链）。
3. 陈旧流回调拆新流 → generation 代数守卫。
4. `reconnectAttempt` 数据竞争 → `AtomicInteger`。
5. 文档声称的换址用例不存在 → 补 `should_selectRandomlyAndSwitch_when_reconnect`（双 fake 断流重连）。
6. unary 非 Status 异常逃逸 → `catch RuntimeException` 兜底转失败结果。
7. close 与 lazy channel 竞争泄漏 → closed 标志 + synchronized 建立拒绝。
8. `intervalMs` 动态语义未实现 → 自续式每跳重读。
9. 配置值域文档漂移（1000 → 500–60000）。
10. 设计文档 API 签名与实现漂移（`register(ServiceInstance)`/`deregister(InstanceId)`/`serverAddress()` 链式）→ 本节与「产品设计」节对齐。
11. 删 `ProtoMapper.deregisterRequest(InstanceId)` 死重载。
12. 删 `FakeRegistryServer.failStatus` 死设施；保留 `failHeartbeatEcho` 并补 `should_keepSending_when_ackLost`（ack 丢失不停发）。
13. `resetChannel` 在调度线程阻塞 2s → 异步 shutdown 不等待。
14. 调度器急切创建 → lazy 到 `start()`。
15. 首跳测试 sleep 速度依赖 → 有界轮询（400ms < 500ms 周期）。

第二轮 /code-review 修复（15 findings，全修）：

1. 退避计数改为**收到 ack 后归零**（本地建流成功不代表服务端可达）；2. 重试前重读本地集（注销则放弃、更新则报最新值，防陈旧重放）；3. ServiceInstance 构建期校验 metadata key/value（防 protobuf NPE 逃逸）；4. Gauge 加 `client` tag 并在 close 移除（多 client 不互相覆盖/不残留）；5. ack 看门狗（`heartbeat.ackTimeoutMs`）+ gRPC keepAlive（半开连接可感知）；6. close 屏障：停心跳并等待调度终止、ops 等待在途任务完成后再注销；7. 修正配置动态语义表述（SCF 变更事件驱动；系统属性/环境变量为启动期源）；8. 自备 `RelaxedEnvironmentConfigurationSource`（容器可配 `ARTEMIS_CLIENT_SERVERADDRESSES`）；9. heartbeat.failure 覆盖 onError/onCompleted/开流失败；10. 两个调度器改 `BoundedScheduledExecutor`（有界队列 + dropped 指标）；11. 代数自增先于 channel 重置（防旧流回调重复重连）；12. 地址严格校验（端口数字/范围、IPv6 括号）；13/14/15. 文档版本与历史排序、验证 demo 编译、设计文档默认值/类清单对齐。

用户 review 修订（验证前，第六轮——可观测标准化）：

- **Micrometer 替换自建 `ClientMetrics`**（技术选型 #34 评审入表）：Counter/Timer/Gauge 全套 + 标签维度，Java 8 兼容（1.17.x 线自钉）；`newClient(properties, meterRegistry)` 注入、缺省 `Metrics.globalRegistry`（官方库模式）。指标集六项（见「公共 API 面」节），failure Counter 带 `operation` 标签、report Timer 记上报耗时、connected/instances Gauge 反映实时状态。starter（phase-8）自动注入 Boot `MeterRegistry`，指标经宿主 actuator 供 Prometheus 抓取——与架构 §6「Micrometer + Prometheus」对齐，client 侧补位。依赖纪律同步：封闭清单 + tech-java.md + guidelines §9。

用户 review 修订（验证前，第五轮——操作级重试）：

- **瞬态失败有界重试**：`GrpcRegistryClient` 上报返回三态（REPORTED/RETRIABLE/REJECTED，瞬态 = UNAVAILABLE/DEADLINE_EXCEEDED）；`DefaultArtemisClient` 以 scheduled ops executor 调度式重试（`operation.retryTimes` 默认 2 × `retryIntervalMs` 默认 500ms，动态配置）——间隔等待不占线程、同队列保序。重试期间失败细节 DEBUG，耗尽/终态才有结论性 WARN（+ metrics + 兜底链）。与原产品「固定 5 次 × 100ms 无退避」（局限 #2）对照：次数少 + 心跳兜底接管，固定间隔即可（退避语义留给断流重连）。close 全量注销保持单次（best-effort，TTL 兜底）。

用户 review 修订（验证前，第四轮——API 形态重塑）：

- **操作 void 化（fire-and-forget）**：删 `InstanceOperationResult`；上报移入单线程 ops executor（`artemis-client-ops`，daemon），调用方线程只更新本地集。register/update 失败 → Micrometer failure Counter + `heartbeat.sendNow()` 即时补投；deregister 失败不补投（TTL 收敛）。
- **可观测**：Micrometer 标准端口（`newClient(properties, meterRegistry)` 注入，缺省 globalRegistry；#34）；日志补全——操作失败 WARN（Grpc 层带实例 + status + 自愈提示）、首次断流 WARN、重连成功 INFO、启停 INFO、热路径 DEBUG。
- **配置去静态固化**：地址 `ConfigAddressProvider.select()` 每次读取、凭证 interceptor 每请求读取、心跳/重连键自续式读取（均未 setStatic）；运行时变更由配置源事件驱动（文件源重载等），系统属性/环境变量为启动期源。删 `StaticAddressProvider`。
- close 顺序补强：停心跳 → **停 ops 队列**（防 pending register 迟于注销到达服务端）→ 全量注销 → 关传输。
- 测试断言全面改经服务端观察（fire-and-forget 无返回值）；bad 凭证 IT 改断言「实例不可见」。

用户 review 修订（验证前）：

- **fire-and-forget 操作 + 可观测内化 + 配置全动态（review 定案 2026-10-19 第四轮）**：操作 void 化（删 `InstanceOperationResult`）——心跳全量上报本就是无限重试，一次性 RPC 成败对用户无意义且诱导错误用法（失败后自行重试/抛异常）；失败处理内化为 WARN 日志 + Micrometer 标准端口（`newClient(properties, meterRegistry)`，缺省 `Metrics.globalRegistry`——Micrometer 官方库模式，宿主不接管则空转）+ register/update 失败即时心跳补投。地址与凭证**使用时读取**（未静态固化）——配置源触发变更事件后，重连换址与凭证变更即生效；系统属性/环境变量为启动期源；创建期仅校验必配项（fail-fast）。已知边界：同 JVM 多 client 共享缺省源时同键互串（v0.1 单 client 假设，多 client 需宿主隔离源或 managerId 前缀，后置）。
- **凭证默认空（review 定案 2026-10-19 第三轮）**：凭证是部署环境数据而非产品常量——服务端生产必改密码，SDK 默认 `artemis`/`artemis` 只在「服务端也用默认」的 eval 组合有效，服务端一改即成隐式错凭证（UNAUTHENTICATED 难归因）。默认空 + 未配置不发认证头：认证裁决归服务端（服务端关认证时无凭证组合合法），显式配置方可认证。
- **全配置 SCF 化、删 builder**：`ArtemisClientBuilder` 删除，`ArtemisClients.newClient()` / `newClient(StringProperties)` 即全部入口；`serverAddresses`（必配）/`auth.username`/`auth.password` 走 `artemis.client.*` 键，创建期 fail-fast 校验。SCF 配置不设 static（manager 键缓存 + static 固化首建值会污染同 JVM 多次 newClient——测试逐例重建场景）。

## 文档同步（⑩ 填）

- `docs/product/artemis.md` v1.7：新增 phase-7 交付节（SDK 公开 API、fire-and-forget、心跳容错、生命周期、配置、Micrometer、工程形态）。
- 新增 `docs/api/sdk-java.md` v1.0：公开 API、配置项、操作语义、生命周期、指标与已知限制；`docs/api/README.md` 登记。
- `docs/dev/tech-stack-java.md` v1.20：组件总表 #34 Micrometer + #17/#30 依赖清单（⑥ 轮已同步）。
- `.claude/rules/tech-java.md`、`docs/dev/coding-guidelines-java.md` v1.23：SDK 运行时依赖封闭清单补 micrometer-core。
- 架构文档 v1.24（客户端磁盘快照归属发现侧）、roadmap v1.10、plan v1.13（phase-7 去快照）——设计 review 定案时已同步。
- 无架构机制变更，无需新增 ADR。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.8 | 2026-10-19 | /code-review 第二轮修复：退避计数待 ack 归零、重试重读本地集、metadata 校验、client tag gauge、ack 看门狗/keepalive、close 屏障、env 映射、有界 executor、地址严格校验；文档与实现对齐 |
| 1.7 | 2026-10-19 | 用户修订：Micrometer 替换自建 ClientMetrics（#34 评审入表，指标集六项 + 库模式注入）；API/决策/实现结果同步 |
| 1.6 | 2026-10-19 | 用户修订：操作级有界重试（三态结果 + retryTimes/retryIntervalMs 配置）；行为/配置表/实现结果同步 |
| 1.5 | 2026-10-19 | 用户修订：操作 void 化（fire-and-forget）+ ClientMetrics 端口 + 地址/凭证使用时重读（全动态）；API/行为/决策节同步 |
| 1.4 | 2026-10-19 | 用户修订：凭证默认空（未配置不发认证头，半配置 WARN），配置表/决策节/测试同步 |
| 1.3 | 2026-10-19 | 用户修订：全配置 SCF 化删 builder（newClient 工厂 + 键驱动）；API/配置表/决策节同步 |
| 1.2 | 2026-10-19 | code-review 15 findings 修复落记录；API 节与实现对齐 |
| 1.1 | 2026-10-19 | review 修订：注册侧不做磁盘快照（归发现侧 v0.3，架构/roadmap/plan 同步修正） |
| 1.0 | 2026-10-19 | 开工骨架 |
