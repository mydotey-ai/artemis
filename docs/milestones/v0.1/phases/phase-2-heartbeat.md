# Phase 2：注册模型四操作 + 租约过期清理（registry-core + gRPC 装配）

版本: 2.4    更新时间: 2026-10-14

## 目标

在 registry-core 内落地注册模型显式四操作语义与心跳全量上报对账（续约 + 缺席补注册、不做集合 diff）、租约续约 / 过期清理语义（D12，架构 §4.1），按 DDD 四层（接入层 / 应用层 / 领域层 / 基础设施层，ADR-015）组织代码；在 registry-server 用 Spring Boot 官方 gRPC 装配启起真实服务，以集成测试端到端验证。

## 范围

做什么：

- **分层**（[ADR-015](../../../decisions/adr-015-layered-model-inside-core.md)）：**接入层**（server 模块：协议接入，dto/vo 收发）、**应用层**（core `application`：用例编排 + dto↔domain 转换）、**领域层**（core `domain`：模型 + 聚合 + 端口）、**基础设施层**（core `infrastructure`：领域端口实现）。依赖方向单向指向领域；领域不依赖其它层；端口由领域定义、基础设施实现（依赖倒置），装配层注入（控制反转）。
- **内核语义**：register（无条件覆盖发 NEW）/ deregister（意图幂等、命中 DELETE）/ update（幂等 upsert：命中 UPDATE、缺席 NEW）/ 心跳（逐实例续约 + 缺席 upsert + 内容变化覆盖）、租约续约与 TTL 过期清理、批量与部分失败语义。
- **数据结构**：不可变快照 + volatile 发布、读路径无锁、零深克隆（guidelines §4.1）。
- **gRPC 装配**（registry-server）：用 Spring Boot 4.1 官方 starter（`org.springframework.boot:spring-boot-starter-grpc-server`，`@GrpcService` 自动装配），仅 registry 四 rpc 的传输绑定 + 进程内起停——**不自写 Server/生命周期**。
- **测试**：内核单测 + mapper 往返单测 + **进程内起服务的端到端集成测试**（failsafe `*IT`）。

不做什么：自我保护（phase-3）、服务端快照落盘回放与 readiness 门控（phase-3）、HTTP 辅助通道与内外端口分离、默认认证、metrics/log/trace、成员抽象接口、Spring Boot 完整装配（phase-4）、管理 API 与有效视图（phase-5）、集群复制与事件扇出（v0.2）、SDK（phase-6）、SCF 配置源接入（phase-4）。

## 涉及模块

- `java/server/artemis-registry-core`：三层实现 + 单测。
- `java/server/artemis-registry-server`：Spring Boot 官方 gRPC 装配（`grpc/` 包 + `@GrpcService`）+ 集成测试（`*IT`）。
- `java/server/pom.xml`：MapStruct 依赖管理 + failsafe 正式绑定（pluginManagement → 模块 build）。

（路径为 ⑩ 期间目录重组后的现状：java/ 拆 `server/` 服务端线与 `client/` SDK 线两目录，`java/pom.xml` 为纯聚合器——实现期产物路径见「实现结果」节开头沿用旧路径的记录，历史表述不回改。）

契约：`proto/registry/v1`（Heartbeat / Register / Deregister / Update）、`proto/common/v1`——**契约零变更**（phase-1 已定稿）；新增依赖组件 **MapStruct** 与 **Spring gRPC**（已进 tech-stack 组件总表 #32/#33）。

## 验收方式

对应测试文档：[phase-2-heartbeat-test.md](phase-2-heartbeat-test.md)

- 单测（surefire `*Test`）：四操作语义（覆盖 / 意图幂等 / upsert 事件）、心跳续约 + 缺席补注册（无集合 diff，缺席等 TTL）、租约续约/过期清理、校验归一、mapper 往返。
- 集成测试（failsafe `*IT`）：进程内起 registry 服务（官方 gRPC starter），经真实调用驱动四操作与心跳，端到端断言响应、事件与租约过期。

执行入口 `java/scripts/dev-test.sh`（all 模式；本 phase 起 integration 模式首次真正有 `*IT` 覆盖）。

## 产品设计（③ 补）

### 1. 分层结构、依赖方向与命名（DDD 四层）

```text
接入层（registry-server 模块）        协议接入：grpc/http 端点绑定；dto/vo（proto 生成消息）收发

registry-core 模块内：
org.mydotey.ai.artemis.registry
├── application/     应用层：应用服务（用例编排、契约级校验/归一）+ dto↔domain mapper + proto 门面；依赖 domain
├── domain/          领域层（核心）：领域模型 + 请求/响应模型 + 聚合（租约语义）+ 端口接口；不依赖其它层
└── infrastructure/  基础设施层：领域端口实现、调度/配置等技术实现；依赖 domain
```

- **领域层是核心**：`application` 与 `infrastructure` 依赖 `domain`，接入层（server）→ `application`；`domain` 不依赖任何其它层（也不依赖 proto、Spring、配置源）；application 与 infrastructure 互不依赖。
- **依赖倒置 + 控制反转**：领域层涉及具体实现的能力只定义**端口接口**（时间源用 JDK `java.time.Clock`；TTL 提供 `LeaseTtlSource`；变更事件出口 `ChangeListener`），由基础设施层实现、装配层（server，Spring）注入。
- **dto（proto 生成消息）只出现在 server 接入层与 application 的转换边界**（mapper/门面）；domain 不出现 proto 类型。
- **命名统一为「请求/响应」**（不用 Command/Result）：`XxxRequest`/`XxxResponse` 定义在**领域层**，应用层直接使用（不重复建模型）。
- **同名类以包名区分层次**（`proto.common.v1.Instance` / `domain.Instance`），由 application 的 mapper 做同名类转换。
- 传输绑定在 server 模块（`@GrpcService` 实现类一行委派 application 的 proto 门面）；core 不感知网络。

### 2. 领域层（`domain`，核心）

```java
// ---- 领域模型 ----
record ServiceKey(String namespace, String service) {}
record InstanceKey(ServiceKey service, String instanceId) {}
enum InstanceStatus { UNSPECIFIED, UNKNOWN, UP, DOWN }        // 语义与契约对齐（UNSPECIFIED 同 UP 由消费侧解释）
record Instance(String instanceId, String host, int port, InstanceStatus status, int weight, Map<String, String> metadata) {}
record ServiceInstances(ServiceKey key, List<Instance> instances) {}

enum ChangeType { NEW, UPDATE, DELETE }
record InstanceChange(ServiceKey key, Instance instance, ChangeType type, long changeTimeMs) {}

// ---- 请求/响应模型（领域层所有；与契约同名同构，应用层直接使用） ----
record RegisterRequest(List<ServiceInstances> services) {}
record DeregisterRequest(List<InstanceKey> instances) {}
record UpdateRequest(List<ServiceInstances> services) {}
record HeartbeatRequest(List<ServiceInstances> services, long seq) {}

enum Ack { SUCCESS, FAIL }
record ErrorInfo(String code, String message) {}
record InstanceError(InstanceKey instance, ErrorInfo error) {}
record ServiceInstanceErrors(ServiceKey key, List<InstanceError> failed) {}
record ResponseStatus(Ack ack, ErrorInfo error) {}
record RegisterResponseData(List<ServiceInstanceErrors> services) {}
record DeregisterResponseData(List<InstanceError> instances) {}
record UpdateResponseData(List<ServiceInstanceErrors> services) {}
record HeartbeatResponseData(long serverTimeMs, long seq) {}
record RegisterResponse(ResponseStatus status, RegisterResponseData data) {}
record DeregisterResponse(ResponseStatus status, DeregisterResponseData data) {}
record UpdateResponse(ResponseStatus status, UpdateResponseData data) {}
record HeartbeatResponse(ResponseStatus status, HeartbeatResponseData data) {}

// ---- 端口（领域定义，基础设施实现，装配层注入） ----
@FunctionalInterface interface LeaseTtlSource { Duration leaseTtl(); }        // 动态 TTL（清理时取最新）
@FunctionalInterface interface ChangeListener { void onChange(InstanceChange change); }
@FunctionalInterface interface MonotonicClock { long monoMillis(); }          // 单调时间源（D14）：一切时长判定
interface LeaseEvictionScheduler extends AutoCloseable {                      // 租约过期清理调度（D12）
    void start();
    @Override void close();
}

// ---- 聚合与领域服务 ----
final class InstanceRegistry {
    InstanceRegistry(LeaseTtlSource leaseTtl, MonotonicClock monotonicClock, ChangeListener listener) {}

    void register(ServiceKey key, List<Instance> instances);           // 逐实例覆盖 + NEW
    List<String> deregister(ServiceKey key, List<String> instanceIds);  // 返回实际摘除的 instanceId（DELETE）
    void update(ServiceKey key, List<Instance> instances);             // 命中覆盖 UPDATE / 缺席 NEW
    void heartbeatUpsert(ServiceKey key, List<Instance> instances);    // 续约 / 内容变化覆盖 UPDATE / 缺席 NEW
    int evictExpired();                                                // 过期清理，返回摘除数（DELETE）
    Optional<ServiceInstances> snapshot(ServiceKey key);                // 共享不可变快照，零克隆
}
```

存储结构（沿用 guidelines §4.1）：

```java
ConcurrentHashMap<ServiceKey, ServiceHolder> services;   // holder 内：CHM<instanceId, Lease> + volatile ServiceInstances snapshot
final class Lease { private final Instance instance; private volatile long renewalTimeMs; }
```

- **快照仅在成员/内容变更时重建**（holder 锁内），纯续约只写 `Lease.renewalTimeMs`（volatile）→ 稳态零快照分配、零复制流量。
- 读路径无锁（volatile 读 + 返回共享引用）；结构变更 `synchronized (holder)` 短临界区；**事件在锁内派发、空 holder 回收后置于派发**（D13 事件定序）——listener 须非阻塞（D4）、不得跨 key 回调注册表。
- 最后一个 lease 摘除后回收空 service（`services.remove(key, holder)`），防长跑泄漏。
- 领域只依赖 JDK 类型与自身端口，不依赖 proto / Spring / 配置源；不做重复防御（校验在应用层一次完成，guidelines §5）。

### 3. 应用层（`application`）

```java
public interface RegistryService extends AutoCloseable {
    RegisterResponse register(RegisterRequest request);        // 领域层请求/响应模型
    DeregisterResponse deregister(DeregisterRequest request);
    UpdateResponse update(UpdateRequest request);
    HeartbeatResponse heartbeat(HeartbeatRequest request);
    Optional<ServiceInstances> getInstances(ServiceKey key);   // 领域 ServiceInstances（读快照）
    void start();                                              // 启动清理调度（实现见 infrastructure）
    @Override void close();
}

final class ErrorCodes { public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT"; }

// dto（proto 生成消息）↔ 领域模型转换：同名类按包转换（proto.* ↔ domain.*）
@Mapper interface RegistryProtoMapper {
    domain.RegisterRequest toDomain(proto.registry.v1.RegisterRequest request);
    domain.DeregisterRequest toDomain(proto.registry.v1.DeregisterRequest request);
    domain.UpdateRequest toDomain(proto.registry.v1.UpdateRequest request);
    domain.HeartbeatRequest toDomain(proto.registry.v1.HeartbeatRequest request);

    proto.registry.v1.RegisterResponse toProto(domain.RegisterResponse response);
    proto.registry.v1.DeregisterResponse toProto(domain.DeregisterResponse response);
    proto.registry.v1.UpdateResponse toProto(domain.UpdateResponse response);
    proto.registry.v1.HeartbeatResponse toProto(domain.HeartbeatResponse response);

    // 枚举整表手写：proto 常量带前缀（INSTANCE_STATUS_UP）与领域名（UP）不对应，MapStruct 无法按名映射
    default domain.InstanceStatus toDomain(proto.common.v1.InstanceStatus status) {
        return switch (status) {
            case INSTANCE_STATUS_UNSPECIFIED -> domain.InstanceStatus.UNSPECIFIED;
            case INSTANCE_STATUS_UNKNOWN -> domain.InstanceStatus.UNKNOWN;
            case INSTANCE_STATUS_UP -> domain.InstanceStatus.UP;
            case INSTANCE_STATUS_DOWN -> domain.InstanceStatus.DOWN;
            case UNRECOGNIZED -> domain.InstanceStatus.UNKNOWN;   // 未知 wire 值 → UNKNOWN 哨兵
        };
    }
    // 缺省值语义、repeated/map 展开同样以显式手写方法补齐
}

/** proto 门面（dto 进出）：mapper 转换 + 委派应用服务；server 的 @GrpcService 一行委派。 */
final class RegistryProtoFacade {
    proto.registry.v1.RegisterResponse register(proto.registry.v1.RegisterRequest request);
    proto.registry.v1.DeregisterResponse deregister(proto.registry.v1.DeregisterRequest request);
    proto.registry.v1.UpdateResponse update(proto.registry.v1.UpdateRequest request);
    proto.registry.v1.HeartbeatResponse heartbeat(proto.registry.v1.HeartbeatRequest request);
}
```

- `RegistryServiceImpl`：编排 = 契约级校验/归一（§8）→ 调 `InstanceRegistry` → 组装领域响应模型；**不定义自己的模型**（直接用领域层的 request/response）。
- **dto ↔ domain 转换在应用层**（接口层模型与领域模型互相转换，ADR-015）：只有**一个** mapper，字段级主映射由 MapStruct 编译期生成，语义性转换以显式手写方法补齐——**枚举整表手写**（proto 常量带前缀 `INSTANCE_STATUS_UP` 与领域名 `UP` 不对应，MapStruct 无法按名映射）、缺省值语义、repeated/map 结构展开（tech-stack §4.6）。
- 应用层依赖 domain（单向）；不依赖 Spring。

### 4. 基础设施层（`infrastructure`）

```java
/** 领域端口 LeaseEvictionScheduler 实现：单平台线程 scheduleWithFixedDelay 定时触发清理入口。 */
final class ScheduledLeaseEvictionScheduler implements LeaseEvictionScheduler { /* start() 起调度 / close() 释放 */ }
```

- 基础设施层只做**领域端口的具体实现与技术关注**（调度/配置实现；未来：TTL 来源的 SCF 实现、集群复制通信的协议无关编排——gRPC 传输绑定按 enforcer 约束留 server）；不承载领域语义、不做协议接入。
- 领域端口（`LeaseTtlSource`、`ChangeListener`、`LeaseEvictionScheduler`）的实现由基础设施/装配层提供（依赖倒置）；`Clock` 用 JDK 抽象，测试注入可控实现。

### 5. 接入层与 gRPC 装配（registry-server，Spring Boot 官方组件）

```java
// org.mydotey.ai.artemis.registry.server.grpc
@GrpcService
final class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {
    // 四方法各一行委派 RegistryProtoFacade（application）
}
```

- server 模块承载**接入层**（协议端点绑定，dto 收发）与**装配**（IoC 注入）：`@GrpcService` 一行委派 application 的 proto 门面，装配层负责端口、实现与领域端口的注入（控制反转）。
- 用 **Spring Boot 4.1 官方 starter**：`org.springframework.boot:spring-boot-starter-grpc-server`（测试支持 `org.springframework.boot:spring-boot-grpc-test`）。服务实现以官方注解注册，端口/传输/生命周期由 starter 管理，**不自写 `Server`/生命周期类**。坐标沿革（实施期定案）：spring-grpc 自身 starter 止于 1.0.3（SB 3.x 线），SB 4.0 起收编 Boot 官方模块、spring-grpc 1.1.x 只发布 core（`@GrpcService` 注解同包）。
- 组件总表登记见 tech-stack #33（grpc-java 版本线：SB 管理 1.83.1，本 reactor 窄版本线先声明锁 1.84.2，实施期验证兼容）。
- 本 phase 仅四 rpc 的绑定；HTTP、认证、指标、trace、成员抽象（phase-4）在此装配上叠加。
- 集成测试经官方 test 支持在进程内起服务（in-process 或随机端口，按官方文档选定），配真实调用链路断言。

### 6. 四操作语义与事件派生

| 操作 | 目标已存在 | 目标缺席 | 事件 |
|---|---|---|---|
| register | 无条件覆盖（内容不比较） | 建租约 | 逐实例 **NEW**（无论是否已存在） |
| deregister | 摘除 | 幂等成功（目标状态已达成） | 命中 **DELETE**；缺席**无事件** |
| update | 全量覆盖（非字段 patch） | 自动注册 + 建租约 | 命中 **UPDATE**；缺席 **NEW** |
| heartbeat | 内容不同 → 覆盖；内容相同 → 仅续约 | 自动注册 + 建租约 | 内容变化 **UPDATE**；缺席 **NEW**；内容相同**无事件** |

- **心跳不做集合 diff**：上报中缺席的实例不摘除，等租约 TTL 过期清理（ADR-012）。断网重连 → 心跳全量 upsert 自动收敛。
- **心跳内容变化即覆盖**是 update 丢失的自愈路径（ADR-012 理由「register/update 丢失由心跳 upsert 即时自愈」）；比较发生在**归一化之后**（§8），避免归一化差异误判。
- 事件时序：先写存储，后回调 listener；**回调与派发在 holder 锁内**（D13）。
- **事件定序（D13，⑨ 第 2 次设计反馈定案）**：同 key 操作锁内全同步（写存储 → 重建快照 → 派发事件 → 回收空 holder），事件到达顺序即存储变更顺序；回收后置使新操作在锁上排队、经乐观重试换新 holder，其事件必然晚于回收者的 DELETE——无幻影终态事件。跨 key 事件无全局定序（registry 状态按 key 分区，复制按 key 收敛，跨 key 序不影响正确性）。listener 在锁内回调：须非阻塞、不得跨 key 回调注册表（防锁交叉；synchronized 可重入，同 key 读安全）。
- register 覆盖已存在实例仍发 NEW：register 是客户端「显式声明注册」语义，事件类型不依赖内容比较。
- **deregister 不受自我保护拦截**是架构 §4.1 边界；本 phase 无保护态，phase-3 引入时守卫落点见 §11 D7。

### 7. 租约与过期清理

- 过期判定：`now - lease.renewalTimeMs >= leaseTtl.leaseTtl().toMillis()`，`now` 取自注入 `MonotonicClock`；TTL 每轮清理时读取（动态）。
- **时间源定案（D14，⑨ 第 3 次设计反馈）**：一切**时长判定**（租约续约时刻、过期判定、事件时刻）走领域端口 `MonotonicClock`（单调递增，不受 NTP 校时影响；装配层 `System.nanoTime()/1e6` 实现）——NTP 前跳不再致整表过期、后跳不再冻结清理（修复 legacy `currentTimeMillis` 同款缺陷）。原点进程内任意，跨进程/跨重启不可比，只保证同源差值即真实时长。**切分边界**：跨进程传递的时间戳（`serverTimeMs`）保留壁挂钟——单调值跨进程无意义且本身即「单机进程时间」，壁挂值不参与任何内部判定、校时跳变影响面为零。
- 清理产出 DELETE 事件，事件携带被摘实例内容。
- **心跳流断开的语义**：连接断开 = 该连接实例停止续约，自最后一次续约起算 TTL 自然过期——本 phase 提供该时间语义（连接生命周期本体归 phase-4）。
- 扫描为全表 O(服务数 × 实例数)；`ponytail:` 10 万实例下按 1s 扫描实测容量，若成瓶颈改按到期时间分桶（v1.0 压测驱动）。
- 本 phase **不引入保护态守卫**（phase-3 在清理入口加判定）。

### 8. 校验与归一化（应用层一次完成）

- **归一化**（不算失败）：`weight <= 0` → `100`（契约明示「入口归一为 100」）；`status` 保留原值（`UNSPECIFIED` 语义同 UP，消费侧解释）。
- **校验失败**（错误码取契约注册表唯一已分配项 `INVALID_ARGUMENT`）：
  - `ServiceKey` 非法（namespace/service 为空）→ 该 service 分组**整体拒绝不写入**，整体 `ack=FAIL` + `error`（结构上无法表达实例级明细）。
  - `Instance.instanceId` 为空 → 该实例记 `InstanceError(INVALID_ARGUMENT)` 并跳过；其余实例照常生效。
  - `deregister` 的 `InstanceKey` 同规则（service 非法 → 整请求 FAIL；instanceId 空 → 该条记明细）。
- **成败判定**：任一明细失败 → `ack=FAIL`；成功项照常生效（批量贯穿资产 #11 的部分失败语义）；`failures` 仅携带失败明细。
- 校验范围只含上述项（host/port/metadata 不做语义校验）；传输级校验（认证、报文上限）在 phase-4 信任边界。
- `HeartbeatResponseData.serverTimeMs` = 壁挂 `clock.millis()`（跨进程时间戳，D14 切分边界）；`seq` 原样回填。

### 9. 并发与线程纪律

- 读路径无锁（volatile 快照）；结构变更 `synchronized (holder)` 短临界区；事件锁内派发与空 holder 回收后置（D13 定序，listener 非阻塞纪律见 §6）。
- 定时任务平台线程 + 命名 + `close()` 全量释放；禁 `Thread.sleep` 轮询；时间推进经注入 `Clock`（测试零 sleep）。

### 10. 自动化测试入口（dev-test.sh + /dev-java-test command）

模式与形态↔模式映射的**单一来源**在本节（`java/scripts/dev-test.sh` 与 `.claude/commands/dev-java-test.md` 指针至此）：

| 模式 | 命令 | 覆盖 |
|---|---|---|
| unit | `mvn test` | surefire `*Test` 单测，快速迭代 |
| integration | `mvn test-compile failsafe:integration-test failsafe:verify` | failsafe `*IT` **服务级集成测试（进程内起真实服务）**；本 phase 起首次真正覆盖（failsafe 于本 phase 正式绑定入 parent pom 与 registry-server） |
| all | `mvn verify` | 全量：spotless + enforcer + 单测 + 集成测试（**默认模式**，工作流 ⑤/⑧ 验收入口） |

本 phase 用例含单测与 `*IT` 两类，均落在既有映射内 → **无模式扩展**；failsafe 绑定与 `dev-test.sh` 注释的指针措辞（章节号 → 章节标题，防章节重排漂移）随本 phase 落地。参数边界与 command 职责同 phase-1 设计「自动化测试入口」节，不复制。

### 11. 关键设计决策（review 确认项）与架构同步落点

| # | 决策 | 触及的原始文档与同步落点 | 状态 |
|---|---|---|---|
| D1 | **DDD 四层**：接入层（server：协议接入、dto/vo 收发）→ 应用层（core `application`：用例编排 + **dto↔domain 转换**——mapper/proto 门面在此层）→ 领域层（core `domain`：模型 + 聚合 + 端口，不依赖他层）+ 基础设施层（core `infrastructure`：领域端口实现，纯化无协议接入）；依赖倒置 + 控制反转；应用层复用领域层 request/response；同名模型按包名区分 | **ADR-015**（v1.3 重写）、**架构 §3.1 + §7 D15**、**guidelines §1/§2**、**tech-stack #32 + §4.6**、**plan.md** | ✅ 已同步 |
| D2 | 心跳对已存在实例的 upsert：**内容变化 → 覆盖 + 派生 UPDATE**；内容相同 → 纯续约零事件 | **ADR-012**（决策/后果补一句）、**架构 §4.1**（补「命中内容变化同 update 覆盖」） | ✅ 已同步 |
| D3 | 领域端口 `LeaseTtlSource` 由 phase-4 以 SCF Property 实现 + 值域校验；TTL 动态读取 | guidelines §10.1/§10.2 已定；phase-4 落地，本 phase 登记约束 | 无需改原始文档 |
| D4 | 变更事件为进程内 Java 接口（同步、须非阻塞）；跨节点 wire 契约 v0.2 定稿 | `proto/replication/v1` 保持占位 | 无需改原始文档 |
| D5 | 读路径返回共享不可变快照，快照仅在成员/内容变更时重建 | guidelines §4.1 已定 | 无需改原始文档 |
| D6 | 默认 `leaseTtl=30s`、`cleanupInterval=1s`（原产品 20s，6 次漏心跳容忍） | **产品文档 `docs/product/artemis.md`**（⑩ 配置节） | ⑩ 执行 |
| D7 | phase-3 在「过期清理入口」加保护判定；deregister 路径不经该判定 | 架构 §4.1 已载该边界；phase-3 设计承接 | 无需改原始文档 |
| D8 | gRPC 装配与端到端集成测试在本 phase 交付（phase-4 补 HTTP/认证/指标/trace/成员/完整装配） | **plan.md**（phase-2/4 分工）、**guidelines §7** | ✅ 已同步 |
| D9 | 引入 MapStruct（编译期 mapper，零运行时）；不支持则回落手写 | **tech-stack #32 + §4.6** | ✅ 已同步 |
| D10 | gRPC 服务端用 **Spring Boot 4.1 官方 starter**（`org.springframework.boot:spring-boot-starter-grpc-server` + `@GrpcService`），不自写 Server/生命周期（实施期坐标定案，见 §5） | **tech-stack #33**、**guidelines §1** | ✅ 已同步 |
| D11 | **集群内部节点间通信（数据复制/同步，v0.2）先例**：领域层定义出站端口、基础设施层具体实现——协议无关编排（扇出/重试）落 core `infrastructure`，gRPC 传输（channel/stub）按 enforcer 约束落 server 模块 | **ADR-015**（v1.3 决策节）、**guidelines §1**（内部 RPC 纪律）、**架构 §3.1** | ✅ 已同步 |
| D12 | **租约清理调度升格领域端口**（⑨ 设计反馈定案，取代偏差 #1 后半「调度器由装配层组合」）：domain 定义 `LeaseEvictionScheduler`（`start()`/`close()` 生命周期端口），infrastructure 提供 `ScheduledLeaseEvictionScheduler` 实现（单线程 scheduleWithFixedDelay），装配层 `@Bean(initMethod="start", destroyMethod="close")` 以领域类型组合并注入清理入口——补齐 ADR-015「端口由领域定义」在调度任务上的不彻底；`RegistryService` 仍只暴露 `evictExpired()`（application 不依赖 infrastructure 不变） | **ADR-015**（v1.6 端口枚举）、本节 §2 端口清单 / §4 基础设施层 | ✅ 已同步 |
| D13 | **同 key 事件定序**（⑨ 第 2 次设计反馈定案）：同 key 操作在 holder 锁内全同步——写存储 → 重建快照 → 派发事件 → 回收空 holder 依次完成（统一提交尾 `commit`），事件序 = 存储序；holder 回收后置于派发（新操作锁上排队 + 乐观重试换新 holder），消除「先 NEW 后幻影 DELETE」；deregister/evict 补回收竞态重试。取代既有「锁内不回调 listener」规则——listener 非阻塞（D4）与禁跨 key 回调为前提，契约落在 `ChangeListener` 端口 javadoc（v0.2 实现者的读取点）。**替代方案已权衡**：锁内按序入队 + 锁外单点有序 drain（listener 延迟零锁覆盖）——不采纳：D4 非阻塞前提下锁内派发成本≈0，缓冲机制当前无需求，派发机制随 v0.2 复制设计一并定 | 本节 §2 存储结构 / §6 事件定序 / §9 并发纪律；`ChangeListener`/`InstanceRegistry` javadoc；guidelines §4.1 无该规则原文（无需改） | ✅ 已同步 |
| D14 | **时长判定单调钟化**（⑨ 第 3 次设计反馈定案）：领域端口 `MonotonicClock`（`System.nanoTime()/1e6` 装配实现）驱动一切内部时长判定（续约/过期/事件时刻），聚合不再持有壁挂钟；`serverTimeMs` 保留壁挂钟（跨进程时间戳，不参与内部判定）——修复 legacy 壁挂钟 NTP 跳变缺陷（前跳整表过期/后跳清理冻结） | 本节 §2 端口清单与构造签名 / §7 时间源定案 / §8 serverTimeMs；**ADR-015**（端口枚举）；MutableClock 同源驱动两端口 | ✅ 已同步 |

不新增其它 ADR（注册模型决策由 ADR-012 覆盖，本 phase 实现细化；分层决策即 ADR-015）。

## 实现结果（⑦ 补）

④ 实现完成（全 reactor `mvn verify` 通过：spotless + enforcer + checkstyle + 既有测试不破；registry-server `ApplicationContextTest` 起 context 含 gRPC 装配）：

- **domain**（`org.mydotey.ai.artemis.registry.domain`，30 文件）：模型 record（防御复制 `List.copyOf`/`Map.copyOf`）、四操作请求/响应模型、端口（`LeaseTtlSource`/`ChangeListener`/`LeaseEvictionScheduler`〔D12〕/`MonotonicClock`〔D14〕）、聚合 `InstanceRegistry`——CHM<ServiceKey, ServiceHolder> + volatile 快照；纯续约零快照分配；结构变更锁内乐观重试（`services.get(key) == holder` 与空 service 回收竞态）；**事件锁内派发 + 统一提交尾 `commit`（D13：重建快照〔含摘空〕→ 逐事件隔离派发 → 回收空 holder）**；空 service 随最后 lease 回收；过期清理全表扫描带 `ponytail:` 分桶升级注记。
- **application**：`RegistryService` 接口 + `RegistryServiceImpl`（校验/归一一次完成：key 非法整组拒绝、instanceId 空逐实例明细、weight ≤ 0 归一 100、任一失败 ack=FAIL）+ `RegistryProtoMapper`（toDomain 外层 MapStruct 生成，嵌套/枚举整表/toProto 手写）+ `RegistryProtoFacade` + `ErrorCodes`。
- **infrastructure**：`ScheduledLeaseEvictionScheduler`（领域端口 `LeaseEvictionScheduler`〔domain〕实现：命名单线程 `artemis-lease-eviction`、daemon、`start()` 起调度、任务内兜底异常防调度静默死亡、`close()` 释放；`@Slf4j`）。
- **server**：`RegistryConfiguration`（装配：Clock/TTL 源(30s 默认)/ChangeListener(v0.2 前 no-op)/应用服务/门面/调度器(1s 默认, initMethod=start + destroyMethod=close, 领域类型声明)）+ `grpc/RegistryGrpcService`（`@GrpcService`，unary 一行委派门面、heartbeat bidi 逐请求回 ack、断流注释租约语义）。
- **pom**：parent 加 MapStruct 1.6.3 + `lombok-mapstruct-binding` dm 与 guava jre 统一（grpc 线 flavor 混排，`requireUpperBoundDeps` 触发后对齐）；registry-core 加 mapper 依赖 + processor path（`combine.self=override`）+ slf4j-api（#19 已登记，实现由 server 提供）；registry-server 加官方 starter + `spring-boot-grpc-test`（test）+ failsafe 绑定（integration-test/verify）。

**与设计制品的偏差（实现定案）**：

1. `RegistryService` 接口去 `start()`/`close()`（不再 extends Closeable），新增 `evictExpired()`——设计草案 start()/close() 由 Impl 持有调度器，会使 application 依赖 infrastructure（违反 ADR-015「application 与 infrastructure 互不依赖」）；应用服务只暴露清理入口不变。~~改为调度器由装配层（server）组合~~ → **⑨ 设计反馈修订（D12）**：调度器升格领域端口（domain 定义 `LeaseEvictionScheduler`、infrastructure 实现），装配层以领域类型组合。
2. gRPC starter 坐标：`org.springframework.boot:spring-boot-starter-grpc-server`（SB 4.1 官方）——设计制品原坐标 `org.springframework.grpc:spring-grpc-server-spring-boot-starter` 实为 SB 3.x 适配线（止于 1.0.3），SB 4.0 起收编 Boot 官方、spring-grpc 1.1.x 只发 core（`@GrpcService` 注解同包名）。tech-stack #33/§4.7、guidelines §1、ADR-015、plan 已同步（v1.12/v1.14/v1.5/v1.10）。
3. D9 验证点结论：MapStruct 1.6.3 + `release=25` + record + proto source 组合**实测支持**；toDomain 外层 4 方法生成（`@Mapping(source = "xxxList")`），嵌套模型/枚举/toProto 方向手写（生成器不支持 proto builder target，tech-stack §4.6 已记手写分工）。


**⑦ code-review 修复（1 medium + 3 low + 2 info）**：

1. [medium] IT 续约验证改为真实语义：t=60 再心跳（async stub，续约点推后）→ t=110 距续约 50 < 100 断言仍在（若续约失效距注册 110 ≥ 100 即摘，有区分度；心跳覆盖两实例避免 i2 竞态摘除断言抖动）→ t=161 摘。
2. [low] `ApplicationContextTest` 加 `spring.grpc.server.port=0`（并行构建不撞默认 9090）。
3. [low] `InstanceRegistry.register/update/heartbeatUpsert` javadoc 补前置条件（instances 非空由应用层保证，直传空列表残留空 holder）。
4. [low] tech-stack #8 版本字段更正 1.84.x + guava flavor 统一说明（v1.13）。
5. [info] IT @BeforeEach 清理硬编码 i1/i2 实例清单——登记为测试约定；mapper null key 传播链确认闭环，无需动作。

**⑦ code-review 修复（第 3 轮：D13+D14 增量，max effort 15 findings；2 项验证驳回）**：

1. [correctness ×3] 锁内派发的 listener 异常隔离：`fire` 逐事件 try/catch(Throwable) 降级日志——存储不变量（快照一致、空 holder 回收、批内剩余事件、操作不因 listener 故障报错）不依赖 listener 行为；空摘路径快照重建不再跳过（`commit` 统一提交尾：重建快照〔含摘空〕→ 派发 → 回收），DELETE 派发期间锁内回读快照与事件一致（D13 javadoc 承诺兑现）。
2. [correctness] 心跳 bidi `dead` 标记：流因单请求故障/客户端取消终止后不再处理已缓冲消息（死流上不再变更注册表）、不再对 closed call 二次 `onError`（grpc-java 1.84.2 `closeInternal` checkState 验证）；unary 取消路径经验证为 no-op（onNext 无害），不改。
3. [test gap] D14 两域时钟区分测试：`should_expire_by_monotonic_clock_when_wall_clock_steps_back`——壁挂后跳 60s 租约仍按单调钟过期、`serverTimeMs` 取壁挂值且异于单调值（此前全部测试两钟同值，壁挂回归可全绿通过）。
4. [reuse] D13 锁定协议收敛：三处内联合并为 `apply`（乐观重试）+ `commit`（统一提交尾）两级；evictExpired 单趟遍历（CHM 弱一致迭代器遍历中移除）；heartbeatUpsert 冗余 `changed` 标志删除（`!changes.isEmpty()` 可导出）；测试有界轮询 `TestAwait` 入 test-jar（两份私有实现已删）。
5. [build] `maven-jar-plugin` 3.4.2 锁入 server-parent pluginManagement（原按 Maven 核心默认绑定解析，版本随 Maven 版本漂移，构建告警实证）。
6. [docs/javadoc] `RegistryGrpcService` 类 javadoc 更正（bidi 单请求意外异常以流错误收尾——原表述与代码/文档矛盾）；`ChangeListener` 端口契约补齐（锁内回调上下文/非阻塞/禁跨 key/异常被隔离——D13 纪律落在 v0.2 实现者读取点）；`InstanceChange.changeTimeMs` 标注单调基准（非纪元、禁跨进程）；实现结果 domain 清单对齐（锁内派发/MonotonicClock/30 文件）。
7. [deferred] `@GrpcAdvice` 全局异常映射（spring-grpc 1.1.1 已提供）替代 per-rpc `dispatch`——单一服务类现状下保留显式 dispatch，phase-5 多服务接入时统一切换；dispatch 捕 `RuntimeException` 与调度器捕 `Throwable` 的差异为 grpc 框架约定（Error 由框架兜为 UNKNOWN），不改。

**⑦ code-review 修复（第 2 轮：⑨ 设计反馈回退后，max effort 15 findings）**：

1. [correctness] 调度器任务兜底 `RuntimeException` → `Throwable`（`scheduleWithFixedDelay` 对未捕获 Error 取消后续全部执行，恰为兜底注释声称已防场景）+ 补直接单测 2 例（重复 `start()` 快速失败——新生命周期面零直接测试；Error 后调度存活）。
2. [correctness] gRPC 接入层单请求异常隔离：unary/bidi `onNext` try/catch → `onError(INTERNAL)`，委派链意外异常不逃逸 StreamObserver（可预期失败仍由门面映射 FAIL ack；bidi 流不再被单请求故障杀流之外的异常逃逸波及）。
3. [correctness] 聚合三操作空分组 no-op 守卫：防直传空列表残留空 holder（应用层已过滤，此处为聚合入口不变量守卫；v0.2 复制端口将直喂聚合的前置），javadoc 前置条件改述。
4. [regression] reactor 重组丢失修复：failsafe 版本锁补齐（`java/pom.xml` 聚合器 + client parent pluginManagement，dev-test.sh integration 直调 goal 防远端元数漂移）；client 线 jacoco 恢复（曾随根 pom 拆分静默丢失）。
5. [cleanup] `RegistryService` javadoc 更正（端口在 domain、实现名 `ScheduledLeaseEvictionScheduler`、非 Spring 装配方组合责任明示）；IT `TestClock` 删除、经 registry-core test-jar 复用 `MutableClock`（补 `reset()`）；手写 ThreadFactory → JDK 21 `Thread.ofPlatform` builder；`evictExpired` ponytail 注记补无锁跳过升级路径（不实现，压测驱动）。
6. [docs] 测试文档用例清单与代码对齐（幽灵用例更正、补 mapper 3 + 调度器 2 用例）；5 文档更新历史表排序修正（doc.md「最新版本在前」）；phase-1 测试文档路径随目录重组更新；ArtemisRegistryServer javadoc「gRPC（phase-4）」过时表述更正。
7. [architecture] 两项记录待定案（§6 事件定序已知限制、§7 壁挂钟时间源取舍）——v0.2 前须决，见各节「待定案」条目。

## 文档同步（⑩ 填）

- **产品文档 `docs/product/artemis.md` v1.1（必须项）**：§4 交付状态补 phase-2 条目——四操作与心跳对账语义、TTL 30s / 清理 1s 默认值（D6 落地）、单调钟判定承诺、gRPC 接入（9090 默认）与有序事件、测试形态。
- **ADR-015 v1.6/v1.7**：端口枚举补 `LeaseEvictionScheduler`（D12）与 `MonotonicClock`（D14）。
- **测试文档 v2.2**：第 3 轮 fix 用例与验证清单结论回填；回归记录 6 轮。
- **phase-1 测试文档 v1.11**：路径随 reactor 目录重组更新。
- **guidelines / plan / coding-guidelines 更新历史表排序修正**（doc.md「最新版本在前」）。
- 无 API 文档变更（proto 契约零变更；`docs/api/` 随 phase-5 管理 API / phase-6 SDK 指南建立）；架构文档无需改（§4.1 语义表述与实现一致，事件定序/时间源为内核实现纪律，决策记录在本文档决策表与 ADR-015）；CLAUDE.md 无过时项（模块结构已于重组时更新）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.4 | 2026-10-14 | ⑩ 文档同步：产品文档 v1.1（phase-2 交付 + D6 默认值落地）、ADR-015 端口枚举、各文档登记见「文档同步」节；⑨ 人工验证通过 |
| 2.3 | 2026-10-14 | 第 3 轮 ⑦ fix（D13+D14 增量 15 findings）：listener 逐事件隔离 + commit 统一提交尾（空摘重建快照）、bidi dead 标记、两域时钟区分测试、TestAwait/maven-jar-plugin 版本锁、javadoc/契约 4 处；D13 补 enqueue/drain 替代方案权衡；@GrpcAdvice 登记缓期 |
| 2.2 | 2026-10-14 | ⑨ 第 3 次设计反馈定案（D14）：时长判定单调钟化——领域端口 MonotonicClock 驱动续约/过期/事件时刻，serverTimeMs 保留壁挂钟（跨进程时间戳）；§2/§7/§8/决策表同步 |
| 2.1 | 2026-10-14 | ⑨ 第 2 次设计反馈定案（D13）：同 key 事件定序——锁内全同步 + 回收后置 + deregister/evict 回收竞态重试；§2/§6/§9 同步，补并发定序守护单测；壁挂钟取舍（§7）仍待定 |
| 2.0 | 2026-10-14 | 第 2 轮 ⑦ fix（max effort 15 findings）：调度器 Throwable 兜底 + 生命周期单测、gRPC 单请求异常隔离、聚合空分组守卫、failsafe 版本锁/jacoco 重组回归修复、TestClock 并入 test-jar MutableClock、Thread.Builder、文档 6 项对齐；§6 事件定序 / §7 壁挂钟两项待定案登记 |
| 1.9 | 2026-10-14 | ⑨ 设计反馈定案（D12）：LeaseEvictionScheduler 升格领域端口——domain 定义接口、infrastructure 提供 ScheduledLeaseEvictionScheduler 实现、装配层 initMethod/destroyMethod 组合；§2/§4/决策表/偏差 #1 同步 |
| 1.8 | 2026-10-13 | ④ 实现期坐标定案：Spring Boot 4.1 官方 starter（`spring-boot-starter-grpc-server` / `spring-boot-grpc-test`）——spring-grpc 自身 starter 止于 1.0.3（SB 3.x 线），SB 4.0 起收编官方；MapStruct 1.6.3 + Java 25 + record 组合实测支持（D9 验证点结论） |
| 1.7 | 2026-10-13 | code-review 修复：Spring gRPC starter 坐标更正（`org.springframework.grpc:spring-grpc-server-spring-boot-starter` / `spring-grpc-test`）；§3 mapper 补「枚举整表手写」（proto 常量前缀与领域名不对应） |
| 1.6 | 2026-10-13 | 四次 review 修订：对齐标准 DDD 四层——接入层独立（server 承载协议接入与 dto/vo 收发）、dto↔domain 转换（mapper + proto 门面）移 application、infrastructure 纯化为领域端口实现；登记 D11 集群复制通信先例 |
| 1.5 | 2026-10-13 | D2 同步原始文档（ADR-012 v1.3、架构 v1.14），决策表全项 ✅；待 review 终审 |
| 1.4 | 2026-10-12 | ③ 设计 review 三次修订：改为领域为核心的 domain/application/infrastructure 三层（领域不依赖他层、依赖倒置 + 控制反转、应用层复用领域 request/response、接入层归基础设施、单一 mapper） |
| 1.3 | 2026-10-12 | ③ 设计 review 二次修订：应用服务命名改 `XxxRequest`/`XxxResponse`（三层同名模型按包名区分，mapper 同名转换）；gRPC 装配改用 Spring Boot 官方组件（不自写 Server）；补 D10 与 tech-stack #33 |
| 1.2 | 2026-10-12 | ③ 设计 review 修订：采纳分层（ADR-015 已同步）、MapStruct、应用服务命名 `RegistryService`、内扩 gRPC 装配与端到端集成测试；§10 模式表补 integration 覆盖，指针改章节标题 |
| 1.1 | 2026-10-12 | ③ 设计定稿（初版：内核 API 直用 proto、单测覆盖） |
| 1.0 | 2026-10-12 | 开工骨架 |
