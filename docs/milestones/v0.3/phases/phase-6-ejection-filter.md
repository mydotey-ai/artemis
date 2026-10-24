# Phase 6：management 摘除规则订阅与 discovery 过滤

版本: 1.3    更新时间: 2026-10-24

## 目标

discovery 订阅 management 的摘除规则并应用到发现视图（查询与推送一致过滤），规则本地缓存、management 不可用时用最后已知规则继续、逐 filter fail-open；未配置 management 时不过滤（0..1 可选）。

## 范围

做什么（据 plan phase-6 说明）：

- `management.v1` 增加摘除规则订阅流：连上即全量快照 + 增量（无版本/ack——版本 + ack 生效水位归 v0.4 完整规则链）。
- discovery 过滤器链 SPI + 摘除过滤器 + 规则本地缓存 + fail-open / 最后已知规则。
- management 侧摘除操作触发规则流增量（复用既有内存态摘除记录）。

不做什么：

- 规则版本 / ack / 生效水位（v0.4）；分组路由、权重发布等其它治理 filter（v0.4–v0.5）。
- 发现管理 API（phase-7）、消费方 SDK（phase-8）。
- 新鲜度标记不携带规则源状态（D-E9）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `proto/management/v1` | 摘除规则订阅契约（新增 service/stream，只增） |
| `java/server/artemis-management-core` / `-server` | 规则流服务端（快照 + 增量）+ 摘除记录变更触发 + gRPC 内部端口装配 |
| `java/server/artemis-discovery-core` | 过滤器链 SPI、摘除 filter、规则缓存与 fail-open |
| `java/server/artemis-discovery-server` | management 上游配置（0..1）与规则流接入装配 |

## 验收方式

对应测试文档：[phase-6-ejection-filter-test.md](phase-6-ejection-filter-test.md)

## 产品设计（③ 补）

### 现状与缺口

v0.1 起 management 持有内存态摘除记录（`EjectionStore`：eject/recover/findByService/countByService），有效视图由 management 查询时组合（架构 §4.1）；discovery 侧投影/查询/订阅/对账/多集群聚合已建成（phase-2–5），但无任何过滤概念。缺口：①摘除规则无下发通道（management 无 gRPC 端口）；②discovery 无过滤器链（架构 §4.2 已载 SPI 设计）；③查询/推送/对账三出口无过滤口径。本 phase 补齐三者：规则流契约 → management 分发 → discovery 过滤链。

### 契约扩展（management.v1，只增）

```proto
// ---- 摘除规则订阅流（management → discovery，v0.3 phase-6）----

message EjectionRule {
  artemis.common.v1.ServiceKey service = 1;
  string instance_id = 2;
  string reason = 3;          // 排障用
}

message EjectionRulesRequest {
  string subscriber_id = 1;   // 订阅方标识（排障用）
}

message EjectionRulesSnapshot {
  repeated EjectionRule rules = 1;   // 可为空集（无摘除）
}

message EjectionRecovered {
  artemis.common.v1.ServiceKey service = 1;
  string instance_id = 2;
}

// 流事件 envelope（非 unary Response，不适用 status + data 规范，对齐 projection.v1 先例）
message EjectionRulesEvent {
  // 已设 = 全量快照（替换语义，含空集）；未设 = 增量。message presence 区分空快照与增量。
  EjectionRulesSnapshot snapshot = 1;
  EjectionRule ejected = 2;       // 与 snapshot/recovered 互斥
  EjectionRecovered recovered = 3;
}

service EjectionRuleService {
  rpc WatchEjections(EjectionRulesRequest) returns (stream EjectionRulesEvent);
}
```

- **流序契约**：首条必为快照（替换语义——消费方全量重建本地规则集），其后增量按产生序；gRPC 单流天然有序。断流由消费方整轮重连（重新收快照，幂等覆盖自愈）。
- **at-most-once、无版本/ack**（对齐 plan phase-6 说明）：推送不重试不确认；丢失窗口 = 断流期间 management 侧变更，重连快照全量收敛。慢消费者/推送失败由服务端断流注销。
- **management 内存态语义**：management 重启规则清零，discovery 重连收空快照即摘除消失（roadmap 期 1 设计内行为，v0.4 DB 接管闭环）。
- 空快照经 message presence 表达（`snapshot` 已设但 `rules` 空）；`ejected`/`recovered` 互斥、单条消息恰一 action。

### discovery 过滤器链 SPI 与规则模型（discovery-core domain）

```java
/** 发现视图过滤器（继承资产 #7，架构 §4.2）：投影事实读出后的视图构成时刻判定。
 *  实现须无锁可重入（热路径逐实例调用）。 */
public interface DiscoveryFilter {
    /** false = 实例不进发现视图（查询/推送/对账一致口径）。 */
    boolean isVisible(ServiceKey service, Instance instance);
}

/** 过滤器链：有序执行；单 filter 抛异常视为放行（逐 filter fail-open）+ WARN。 */
public final class DiscoveryFilterChain {
    public static final DiscoveryFilterChain empty();
    public boolean isVisible(ServiceKey service, Instance instance);
    /** 全可见返回原 List 引用（checksum 零计算路径依赖此约定，D-E8）。 */
    public List<Instance> filter(ServiceKey service, List<Instance> instances);
}

/** 摘除规则（management 摘除记录的 discovery 侧投影）。 */
public record EjectionRule(ServiceKey service, String instanceId, String reason) {}

/** 不可变摘除规则集（volatile 发布，读路径无锁 O(1) 判定；管理操作级频率，增量重建整集）。 */
public final class EjectionRules {
    public static final EjectionRules EMPTY;
    public boolean isEjected(ServiceKey service, String instanceId);
    public boolean isEmpty();
    public EjectionRules with(EjectionRule rule);                    // 增量 eject
    public EjectionRules without(ServiceKey service, String instanceId);  // 增量 recover
}

/** 摘除过滤器：规则本地缓存；缓存只在收到新快照时替换，断连不清空（D-E3）。 */
public final class EjectionFilter implements DiscoveryFilter {
    public void replace(EjectionRules rules);   // 规则同步线程唯一写入口
    @Override public boolean isVisible(ServiceKey service, Instance instance);
}
```

- `isVisible` 带 `ServiceKey`：ejection 判定是 `(namespace, service, instanceId)` 三元组（`Instance` 不携带所属 service）。
- **跨 cluster 生效（D-E5）**：规则按三元组匹配所有来源 cluster 的同键实例——多环境聚合下同一实例身份全源摘除，规则不感知来源拓扑。

### 规则流端口与同步引擎（discovery-core）

```java
public interface EjectionRuleSource {
    EjectionRuleWatch watch(EjectionRuleListener listener);
}

public interface EjectionRuleListener {
    void onSnapshot(List<EjectionRule> rules);   // 连接后首条（含空集，替换语义）
    void onEjected(EjectionRule rule);
    void onRecovered(ServiceKey service, String instanceId);
    void onClosed();
}

public interface EjectionRuleWatch extends AutoCloseable { @Override void close(); }
```

`infrastructure/EjectionRuleSynchronizer`（单例，参照 `ProjectionSynchronizer` 模式简化）：

- 单同步线程循环：watch（async 流，回调只 offer 到 per-round 有界队列）→ 消费事件：快照 = `EjectionRules` 全量重建 + `EjectionFilter.replace` + 受影响扇出；增量 = `with/without` 重建 + replace + 扇出。断流/失败 → 全抖动指数退避（默认 1s→10s，同上游模式）→ 整轮重来（重连重新收快照）。
- **断连不清缓存（D-E3）**：`EjectionFilter` 的规则集只在收到快照时替换，断流期间最后已知规则继续生效；退避重连直至 management 恢复。
- **规则变更扇出（D-E6）**：新旧规则集对称差（recovered-now / ejected-now）→ 按受影响 `ServiceKey` 聚合 → 逐 cluster 取投影当前条目中的实例完整数据（投影无此实例则该条无推送——视图本无效果）→ 构造视图语义 `ServiceChange`（ejected-now → `DELETE(instance)`、recovered-now → `NEW(instance)`）→ 表锁内 `runWithFanout(onViewChanges)`。管理操作级频率 + 受影响面 = 摘除记录规模，性能无压力。
- readiness 不受规则同步影响（readiness = 投影首轮 bootstrap；规则是过滤增强）。

### 过滤应用点（三出口 + 增量翻译）

| 出口 | 落点 | 语义 |
|---|---|---|
| 查询 | `ProjectionRegistry.query(selector, chain)` | 与 labels 过滤同级串联；显式 services 命中返回条目（过滤后可为空实例集）、全量查询滤空不返回（对齐既有 labels 语义） |
| 对账 checksum | `ProjectionRegistry.checksums(selector, chain)` | **过滤后视图口径**（D-E1）；chain 无效果（`filter` 返回原引用）+ labels 空 → 仍读条目缓存零计算（D-E8） |
| 订阅基线 | `SubscriptionManager.baseline` 经 `registry.query(selector, chain)` | 基线即过滤后视图 |
| 订阅增量翻译 | `SubscriptionManager.matches` 扩为 labels 匹配 && `chain.isVisible` | 投影 NEW/UPDATE/DELETE × 摘除态的组合经既有视图语义矩阵自然翻译：NEW(X) 且已摘除 → 无事件；UPDATE(X) 且新摘除 → `DELETE(previous)`（退出视图）；DELETE(X) 且已摘除 → 无事件 |
| 规则变更 | `SubscriptionManager.onViewChanges(cluster, batch)`（新方法） | 视图语义直投：batch 已是视图效果，chain 不再判定；labels/cluster/services 过滤照做（`DELETE` 携带投影实例完整数据） |

- `DiscoveryFilterChain` 注入 `SubscriptionManager`（matches 与 baseline）、`DiscoveryQueryService` / `DiscoveryReconciliationService`（经 registry 方法签名传递）——同链同语义，基线/增量/查询/对账四者口径一致。
- 投影存储保持 registry 事实（未过滤）——过滤只发生在视图构成时刻，与 phase-5「聚合只在查询/推送时刻本地合并」同构（D-E2）。

### management 侧：存储扩展 + 分发 + 触发

- `EjectionStore` 端口新增：

  ```java
  /** 全部摘除记录（规则流快照），按 (namespace, service, instanceId) 升序（确定性）。 */
  List<EjectionRecord> findAll();
  ```

- domain 新端口 `EjectionRuleSubscriptions`（subscribe/unsubscribe），infrastructure 实现 `EjectionRuleDispatcher`：

  ```java
  public final class EjectionRuleDispatcher implements EjectionRuleSubscriptions, AutoCloseable {
      public EjectionRuleDispatcher(EjectionStore store);
      /** 摘除/恢复后由应用服务调用；无订阅者零开销。 */
      public void onEjected(EjectionRecord record);
      public void onRecovered(InstanceKey instance);
  }
  ```

  - **单锁全序（D-E7）**：subscribe（登记 + 快照现读 `findAll`）/ onEjected / onRecovered 推送在同一锁内串行——订阅建立与摘除操作互斥，无「快照读到半增量」竞态；管理操作低频 + 单订阅者 observer，锁开销可忽略（`ProjectionDispatcher` 的队列 + 专属线程管线对规则流过重，不采用）。
  - per-subscriber synchronized onNext（gRPC observer 非线程安全，`WatchSubscriber` 先例）；推送失败/慢消费者 = 断流注销（at-most-once，D-E10）。
  - 快照后增量经同一锁，流内全序；消费方按「快照替换 + 增量叠加」实现（双保险语义自洽）。

- `ManagementServiceImpl.eject/recover` 写存储成功后通知 dispatcher（分发异常不影响操作——管理操作先落本地，规则流 at-most-once 断流重连快照自愈）。

### management gRPC 内部端口装配（management-server）

- management 首个 gRPC 端口（架构 §2.2「discovery ↔ management 规则流：内部端口」落点）：官方 starter `@GrpcService` 主 server 承载 `EjectionRuleGrpcService`（接入层，一行委派 dispatcher，cancel → unsubscribe，对齐 `ProjectionGrpcService` 模式）。
- **条件启用**：配置 `artemis.management.grpc.port` 存在才装配 gRPC server；未配置必须显式禁用 starter 默认端口（Spring gRPC starter 默认 9090 监听，`spring.grpc.server.port=-1` 关闭）——management 可无 discovery 依赖独立运行（0..1 的 management 侧）。
- 认证：内部端口独立账户 `artemis.management.grpc.auth.username/password`，**默认关闭**（对齐 registry 内部 peer 端口哲学：启用须显式配置凭证，关闭认证的部署以网络策略隔离）；拦截器复用 `GrpcAuthInterceptor` 同构模式。

### discovery 装配与配置（discovery-server）

- 新配置类 `ManagementConfig`（`upstream` 包，静态）：

  | 键 | 默认 | 说明 |
  |---|---|---|
  | `artemis.discovery.management.endpoints` | 空 = **不启用**（0..1） | management 规则流端点，逗号分隔 host:port |
  | `artemis.discovery.management.auth.username/password` | 空（认证关） | 上行凭证，模式同 `UpstreamAuthConfig` |
  | `artemis.discovery.management.reconnectBackoffMinMs/MaxMs` | 1000 / 10000 | 断流退避（同上游模式） |

- `GrpcEjectionRuleSource`（server 模块，领域端口 gRPC 实现，参照 `GrpcProjectionSource` 简化）：channel per endpoint 惰性建 + 端点轮换 + 认证头现取；单 async server-stream，快照/增量按 presence 分派 listener 回调；proto→domain 映射进 `DiscoveryProtoMapper`（application）。
- 装配：endpoints 空 → `DiscoveryFilterChain.empty()`（零过滤零开销，D-E4）且不建同步器；非空 → chain = `[EjectionFilter]` + `EjectionRuleSynchronizer`（单 bean `@Bean(initMethod="start", destroyMethod="close")`）。

### 语义定案汇总

| # | 定案 | 触及的原始文档与同步落点 |
|---|---|---|
| D-E1 | 过滤口径三出口一致：查询/推送（基线 + 增量 + 规则扇出）/对账 checksum 均为**过滤后视图**——checksum 不过滤会使 SDK 永久不一致、每对账周期触发无谓全量拉 | 架构 §4.2「过滤器链 SPI」实现细化；`docs/api/discovery-reconciliation-api.md` checksum 口径说明随 ⑩ 补充 |
| D-E2 | 投影存储保持 registry 事实（未过滤），过滤只发生在视图构成时刻——与 phase-5 聚合「查询/推送时刻本地合并」同构 | 无文档修订（架构 §4.2 既有语义） |
| D-E3 | fail-open 两层：management 不可用 = 最后已知规则继续（缓存只在收到快照时替换、断连不清空）；filter 实现异常 = chain 统一 catch 放行 + WARN | 架构 §4.2「规则本地缓存，management 不可用时用最后已知规则继续（逐 filter fail-open）」原文一致，无修订 |
| D-E4 | 未配置 management（0..1）= 空链直通（`filter` 返回原引用），零过滤零开销 | roadmap v0.3「治理依赖（0..1）」一致；`docs/product/artemis.md` 配置说明随 ⑩ 补 |
| D-E5 | 摘除规则按 `(namespace, service, instanceId)` 匹配**所有来源 cluster** 同键实例（规则不感知来源拓扑，多环境聚合全源摘除） | 架构 §4.2 未载此细节，为新增实现级语义——phase 文档为单一来源；`docs/api/discovery-query-api.md` 过滤语义随 ⑩ 补充 |
| D-E6 | 规则变更扇出 = 视图语义直投（eject → DELETE / recover → NEW，实例数据取投影当前条目），labels/cluster/services 过滤照做、chain 不再判定 | 无文档修订（phase-3 视图语义矩阵的规则维度扩展，机制内部） |
| D-E7 | 服务端 subscribe/eject/recover 推送单锁全序 + 消费方「快照替换 + 增量叠加」双保险 | `proto/management/v1` 流序契约注释（本次新增，单一来源） |
| D-E8 | checksum 零计算路径保留：chain 无效果返回原引用 + labels 空 → 读条目缓存 | phase-4 D-F3 语义不变（实现级优化约定） |
| D-E9 | 新鲜度标记不携带规则源状态（新鲜度 = registry 数据面上游状态，语义不混）；规则源状态出口归 phase-7 发现管理 API 范畴 | 架构 §4.2「新鲜度标记」无修订；phase-7 范围登记 |
| D-E10 | 规则流 at-most-once：慢消费者/推送失败服务端断流注销，消费方重连全量自愈；规则量小（管理操作级）不做组批聚合 | proto 契约注释（本次新增） |
| D-E11 | management gRPC 内部端口条件启用：未配置不监听（0..1 management 侧），认证默认关闭（对齐 registry peer 端口哲学） | **架构 §2.2 端口落点段落补 v0.3 一行**（management 内部 gRPC 端口落点未载，⑩ 执行） |

### 架构同步声明

本 phase 决策均落架构已载设计的实现细化（§4.2 过滤器链 SPI 与规则流、§2.1 依赖表 management 0..1、§2.2 通道①「规则流」、roadmap v0.3「治理依赖（0..1）」、plan phase-6 说明「无版本/ack」），无 ADR/roadmap/proto 契约规范修订。两处同步：①架构 §2.2 端口落点段补 v0.3 management 内部 gRPC 端口一行（D-E11，⑩ 执行）；②proto 变更为 management.v1 新增 service + 消息（治理面不在冻结范围；流 envelope 非 unary 不适用 status+data，对齐 projection.v1 注释先例）——契约组织与 wire 规则均无变更。

## 实现结果（⑦ 补）

### 实现要点

- **proto**：management.v1 新增 `EjectionRuleService.WatchEjections`（server-stream）+ 五消息（snapshot message presence 区分空快照与增量，D-E7）。
- **discovery-core**：domain 新增 `DiscoveryFilter`/`DiscoveryFilterChain`（fail-open + 全可见原引用 D-E8）/`EjectionRule`/`EjectionRules`（per-service 分组不可变集，对称差枚举接口）/`EjectionFilter`（volatile 规则集）/规则流端口三件套；`ProjectionRegistry.query/checksums` 加 chain 参数（旧签名便捷重载 = 空链，既有调用点零侵入）；`SubscriptionManager` 注入 chain（matches = labels && isVisible）、新 `onViewChanges` 视图语义直投（labels/cluster/services 过滤照做，chain 不判）；infrastructure 新增 `EjectionRuleSynchronizer`（单线程 + per-round 队列 + 退避重连，断连不清缓存 D-E3；`disable()` 支撑 0..1 不启线程）。
- **management-core**：`EjectionStore.findAll()`；domain 端口 `EjectionRulePublisher`（application 依赖）+ `EjectionRuleSubscriptions`（server 依赖），`EjectionRuleDispatcher` 单锁全序实现两端口；`ManagementServiceImpl` eject/recover 落库后通知分发。
- **management-server**：首个 gRPC 端口（官方 starter `@GrpcService`，条件装配 `artemis.management.grpc.port`，未配置 `spring.grpc.server.port=-1` 不监听）；`EjectionRuleGrpcService` + 独立认证拦截器（默认关闭，对齐 registry peer 哲学）；pom 加 gRPC starter。
- **discovery-server**：`ManagementConfig`/`ManagementAuthConfig`/`GrpcEjectionRuleSource`（端点轮换 + channel 池 bean 生命周期）；装配 chain = 启用时 `[EjectionFilter]` / 未配置空链；synchronizer bean 恒建、未启用 disable 零开销。

### review 修复记录（⑥ findings，5 条全修）

| # | severity | 位置 | 问题 | 处理 |
|---|---|---|---|---|
| 1 | high | `EjectionRuleSynchronizer.applyAndFanout` | `filter.replace` 在表锁外先行发布：[replace, lock] 窗口内投影线程 translate DELETE（判不可见无事件）+ apply 移除投影，随后 fanoutDiff 判投影无实例跳过——已摘除实例的视图退出 DELETE 双路径皆丢（SDK 僵尸，重连快照 diff 亦空） | `replace + fanoutDiff` 整体移入 `runWithFanout` 表锁（与投影「translate+apply」互斥，FIX-1 同型），fanoutDiff 内层锁去除 |
| 2 | medium | `EjectionRuleGrpcService` | 规则流无 isReady 背压检查，慢消费者无断流（违背 D-E10 与禁无界队列） | 三回调前 `requireReady()`（isReady false 抛出 → dispatcher 断流注销 + close 流关断）；dispatcher push 失败路径补 `closeQuietly`；新增 `EjectionRuleGrpcServiceTest` |
| 3 | medium | management-server `application.properties` | gRPC 停机宽限默认 30s（GrpcServerLifecycle 阻塞 awaitTermination 等 discovery watch 流），IT 每次关 context 空等 30s、生产滚动重启同病 | 补 `spring.grpc.server.shutdown.grace-period=1s`（规则流 at-most-once 无排水语义，对齐 discovery/registry 停机哲学） |
| 4 | low | `GrpcEjectionRuleSource` | 快照超 `maxInboundMessageBytes`（原默认 4 MiB）→ 无限重连循环且无差异化日志 | 默认 16 MiB（对齐投影上游 bootstrap 上限）+ onError 带端点与异常 WARN 日志 |
| 5 | low | `GrpcEjectionRuleSource.subscriberId` | 每轮 watch 调 `InetAddress.getLocalHost()`（DNS 查找，重连循环放大收敛延迟） | 缓存为字段（进程一次） |

## 文档同步（⑩ 填）

变更文档清单：

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` | v1.21：新增 v0.3 phase-6 交付节（规则流/过滤链/跨 cluster 语义/fail-open/0..1/工程形态） |
| `docs/api/ejection-rule-stream-api.md` | 新建 v1.0：摘除规则流契约（快照 presence、流序全序、at-most-once、内存态语义、discovery 应用语义） |
| `docs/api/discovery-query-api.md` | v1.3：查询语义补摘除过滤（跨 cluster 生效 + fail-open）；配置表补 `management.*` 四键 |
| `docs/api/discovery-subscription-api.md` | v1.2：流语义补规则变更直投（摘除 DELETE / 恢复 NEW） |
| `docs/api/discovery-reconciliation-api.md` | v1.2：checksum 口径补摘除过滤（过滤后视图，三出口一致） |
| `docs/api/admin-api.md` | v1.5：摘除/恢复语义补规则流下发说明 |
| `docs/api/README.md` | 登记 ejection-rule-stream-api.md |
| `docs/arch/artemis-next-architecture.md` | v1.37：§2.2 补 v0.3 端口落点一行（D-E11 定案落点：management 内部 gRPC 端口条件启用、独立认证默认关闭） |
| `docs/decisions/` | 无新增 ADR——本 phase 决策均落架构已载设计的实现细化（§4.2 过滤器链、§2.2 通道①、roadmap v0.3 治理依赖 0..1），proto 变更为 management.v1 组内新增（治理面不在冻结范围） |
| CLAUDE.md | 无变更——模块结构、构建命令、当前状态表述均不受本 phase 影响 |
| 索引 | phases/README 于 ① 已登记；无其它索引变更 |

review 定案触及的原始文档逐条同步落点：D-E1/D-E5 → discovery-query/reconciliation/subscription API 文档；D-E11 → 架构 §2.2；D-E4/D-E3 → 产品文档 phase-6 节与规则流 API 文档；D-E7/D-E10 → ejection-rule-stream-api.md（契约注释单一来源 proto/management/v1）。


## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-24 | ⑩ 文档同步清单（产品文档 + 5 份 API 文档 + 架构 §2.2 落点；ADR/CLAUDE.md 无变更及理由） |
| 1.2 | 2026-10-24 | ④⑦ 实现要点与 review 修复记录（5 findings 全修） |
| 1.1 | 2026-10-24 | ③ 设计定稿：契约扩展（快照 presence/流序/at-most-once）、过滤器链 SPI（isVisible + 原引用约定）、三出口过滤口径、规则扇出直投、dispatcher 单锁全序、management gRPC 端口条件装配、语义定案 D-E1–E11 |
| 1.0 | 2026-10-24 | 开工骨架 |
