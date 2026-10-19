# Phase 6：management-service 骨架与内存态管理面

版本: 2.2    更新时间: 2026-10-19

## 目标

交付独立 management-service（内存态管理面）：实例管理 Admin API（列表/详情/摘除/恢复）与状态 API，经 registry 只读事实查询 + management 内存态摘除记录组合出**有效视图**。

## 范围

**做**：

- management-service 独立服务骨架：独立进程与端口、认证、SCF 配置、registry 只读查询 gRPC client。
- 内存态摘除记录与恢复语义（摘除记录归 management，registry 不感知——ADR-016）。
- 实例管理 Admin API：列表 / 详情 / 摘除 / 恢复，返回应用摘除过滤后的有效视图。
- 状态 API：leases/config 类排障面，数据来自 registry 只读查询。

**不做**：

- DB 持久化（v0.4）、账户体系 / RBAC（v1.0）。
- console（v0.4）、discovery 侧摘除规则消费（v0.3）。
- registry 侧摘除状态或扇出（v0.2 明确不引入）。
- 摘除审批流、操作审计、四级摘除级联（v0.4）；v0.1 仅 instance 级、单条记录、重启丢失。
- registry 查询缓存、分页、限流（管理流量低；分页随 console/v0.4）。

## 涉及模块

- `java/server/artemis-management-core`：领域模型、摘除记录端口与内存实现、管理应用服务、registry 查询端口、proto→domain 映射。
- `java/server/artemis-management-server`：独立服务装配、HTTP Admin/状态 API、认证、SCF 配置、registry gRPC client 适配与传输。
- `java/server/artemis-common`：`BasicAuth` 从 registry-server 上移复用；`management.v1` 继续单源生成（本 phase 不改 proto）。
- `proto/management/v1/management.proto`：**无变更**（Admin/状态 API 走 HTTP/JSON；registry 只读查询契约 phase-5 已交付）。

## 验收方式

对应测试文档：[phase-6-management-service-test.md](phase-6-management-service-test.md)

## 产品设计（③ 补）

### 1. 总体结构与职责边界

```text
运维/console ──HTTP Basic──► management-server（外部 HTTP 8081）
                                  │
                                  ├─ 接入层：Admin/状态 REST + 认证过滤器
                                  ├─ application：有效视图组合（事实 + 摘除记录）
                                  ├─ domain：ServiceKey/Instance/EjectionRecord/端口
                                  ├─ infrastructure：InMemoryEjectionStore
                                  └─ 出站端口 RegistryQueryPort
                                          │ gRPC Basic（外部端口，phase-5 契约）
                                          ▼
                                     registry-server
```

- **摘除记录归 management**（ADR-016）：memory 态，键 `(namespace, service, instanceId)`；摘除/恢复**不调用 registry 写接口**，不改变租约与心跳。
- **有效视图 = registry 事实 + management 摘除记录**，在 management 查询时组合；registry 只返回事实。
- **management 启动不依赖 registry 可用**：gRPC channel 惰性建连，registry 故障不阻断管理服务启动；查询时下游不可用返回 503。
- 三服务边界与端口约定沿用架构 §2.1/§2.2；management 自身不提供 gRPC server。

### 2. 领域模型与端口（`management-core/domain`）

```java
public record ServiceKey(String namespace, String service) {}

public record InstanceKey(ServiceKey service, String instanceId) {}

public enum InstanceStatus { UP, DOWN, UNKNOWN }

public record Instance(
        String instanceId, String host, int port, InstanceStatus status,
        int weight, Map<String, String> metadata) {}

public record ServiceSummary(ServiceKey key, int instanceCount) {}

public record SelfProtectionStats(long windowRenewals, long maxCount, boolean protecting) {}

public record LeaseDetail(ServiceKey service, String instanceId, long ageMs, long remainingTtlMs) {}

public record LeaseStatus(SelfProtectionStats protection, List<LeaseDetail> leases, boolean truncated) {}

public record ConfigProperty(
        String key, String value, String source, boolean isStatic, String defaultValue, String doc) {}

/** 摘除记录：操作记录即状态（v0.1 单条/实例级；v0.4 扩展叠加记录与持久化）。 */
public record EjectionRecord(InstanceKey instance, String reason, Instant ejectedAt) {}

/** 摘除记录端口（内存态实现由 infrastructure 提供）。 */
public interface EjectionStore {
    /** 幂等：已存在则保留首条记录并返回之。 */
    EjectionRecord eject(EjectionRecord record);
    /** 幂等：未摘除返回 false。 */
    boolean recover(InstanceKey instance);
    /** 指定 service 的摘除记录，按 instanceId 升序。 */
    List<EjectionRecord> findByService(ServiceKey service);
    /** 按 service 聚合记录数（单次遍历，供服务列表）。 */
    Map<ServiceKey, Long> countByService();
}

/** registry 只读事实查询出站端口（gRPC 实现由 server 模块提供）。 */
public interface RegistryQueryPort {
    List<ServiceSummary> listServices(String namespace);   // null/空白 = 全部
    List<Instance> getInstances(ServiceKey key);            // 未注册 → 空列表
    LeaseStatus leaseStatus(String namespace, String service);
    List<ConfigProperty> configStatus();
}

/** 下游查询失败（错误码稳定字符串）。 */
public final class RegistryQueryException extends RuntimeException {
    public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";
    public static final String REGISTRY_ERROR = "REGISTRY_ERROR";
    private final String code;
}
```

- 模型与 registry 领域同名但**不共享类**（management-core 不依赖 registry-core）；跨进程只走 wire，映射在 management 侧完成。
- `InstanceStatus` 由 proto 映射：`UNSPECIFIED` → `UP`（与 common.v1 语义一致）、未知枚举 → `UNKNOWN`。
- 输出排序稳定：服务按 `(namespace, service)`、实例按 `instanceId`、租约与配置沿用 registry 契约顺序。

### 3. 应用服务与有效视图语义（`management-core/application`）

```java
public record ServiceView(ServiceKey key, int instanceCount, int ejectedCount) {}

/** 实例事实字段平铺 + 摘除标注（HTTP 契约即此形状）。 */
public record InstanceView(
        String instanceId, String host, int port, InstanceStatus status, int weight,
        Map<String, String> metadata, boolean ejected, String reason, Instant ejectedAt) {}

public record ServiceDetail(
        ServiceKey key, int instanceCount, int ejectedCount,
        int effectiveInstanceCount, List<InstanceView> instances) {}

public record EjectionView(
        String namespace, String service, String instanceId, String reason, Instant ejectedAt) {}

public interface ManagementService {
    List<ServiceView> listServices(String namespace);
    ServiceDetail getService(ServiceKey key, boolean includeEjected);
    EjectionView eject(ServiceKey key, String instanceId, String reason);
    void recover(ServiceKey key, String instanceId);
    LeaseStatus leaseStatus(String namespace, String service);
    List<ConfigProperty> configStatus();
}
```

语义定案：

| 项 | 语义 |
|---|---|
| 服务列表 | `instanceCount` 为 registry 事实计数；`ejectedCount` 为该 service 当前摘除记录数（原样计数，可能含当前未注册实例）；列表不计算 effective 计数（避免为准确计数逐 service 回查） |
| 服务详情 | `ejectedCount`/`effectiveInstanceCount` 只统计**当前注册实例**中被摘除者，准确；`instanceCount = registry 事实总数` |
| 详情实例集 | 默认只返回有效实例（已过滤摘除）；`includeEjected=true` 返回全量并逐条标注 `ejected`/`reason`/`ejectedAt` |
| 摘除 | 幂等；记录按实例身份生效，**实例当前未注册也允许记录**（之后心跳重注册的同一 instanceId 仍处于摘除态，避免"摘除后复活"）；重复摘除保留首条原因与时间 |
| 恢复 | 幂等；删除记录后该实例立即回到有效视图（下一次查询生效） |
| 状态 API | 直接透传 registry 查询结果（不缓存、不聚合、不改写；敏感配置已在 registry 侧脱敏） |

`ManagementServiceImpl(RegistryQueryPort, EjectionStore, Clock)`：`eject` 用注入 `Clock` 生成 `ejectedAt`；应用层不做契约级校验（信任边界在 HTTP 接入层一次完成）。

`ManagementProtoMapper`（application，手写）：`management.v1` 查询响应 → 领域模型，及领域 `ServiceKey` → proto 请求；映射集中在 application，server 传输层不散落字段转换。本 phase 映射量小，不引入 MapStruct。

### 4. HTTP API 契约（management-server 接入层）

Base path `/admin/v1`，JSON（camelCase），Basic 认证。

#### 4.1 `GET /admin/v1/services?namespace={ns}`

```json
{
  "services": [
    {"key": {"namespace": "default", "service": "order-service"},
     "instanceCount": 2, "ejectedCount": 1}
  ]
}
```

- `namespace` 缺省/空白 = 全部；按 `(namespace, service)` 升序。

#### 4.2 `GET /admin/v1/services/{namespace}/{service}?includeEjected={bool}`

```json
{
  "key": {"namespace": "default", "service": "order-service"},
  "instanceCount": 2,
  "ejectedCount": 1,
  "effectiveInstanceCount": 1,
  "instances": [
    {"instanceId": "i1", "host": "10.0.0.1", "port": 8080,
     "status": "UP", "weight": 100, "metadata": {},
     "ejected": false, "reason": null, "ejectedAt": null}
  ]
}
```

- `includeEjected` 默认 `false`；为 `true` 时 `instances` 含被摘除实例，字段 `ejected=true` + `reason`/`ejectedAt`。
- 未注册 service → 200，`instanceCount/ejectedCount/effectiveInstanceCount = 0`，`instances = []`（查询语义，非 404）。

#### 4.3 `POST /admin/v1/services/{namespace}/{service}/instances/{instanceId}/eject`

请求体（可选）：`{"reason": "maintenance"}`；`reason` ≤ 1024 字符。

响应 200：

```json
{"namespace": "default", "service": "order-service", "instanceId": "i1",
 "reason": "maintenance", "ejectedAt": "2026-10-19T10:00:00Z"}
```

- 幂等：已摘除时返回首条记录（原因/时间不变）；不要求实例当前已注册。

#### 4.4 `POST /admin/v1/services/{namespace}/{service}/instances/{instanceId}/recover`

- 成功 204 No Content；幂等（未摘除同样 204）。

#### 4.5 `GET /admin/v1/status/leases?namespace={ns}&service={svc}`

```json
{
  "protection": {"windowRenewals": 10, "maxCount": 3, "protecting": false},
  "leases": [
    {"service": {"namespace": "default", "service": "order-service"},
     "instanceId": "i1", "ageMs": 400, "remainingTtlMs": 600}
  ],
  "truncated": false
}
```

- 过滤/截断/保护统计语义与 phase-5 契约一致，management 原样透传。

#### 4.6 `GET /admin/v1/status/config`

```json
{"properties": [
  {"key": "artemis.registry.auth.password", "value": "******", "source": "springEnvironment",
   "isStatic": true, "defaultValue": "******", "doc": "authentication password (static)"}
]}
```

- 返回 **registry** 的配置状态（敏感值已脱敏）。

#### 4.7 错误语义

| HTTP | body.error | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 路径/查询参数空白、`reason` 超长 |
| 401 | `UNAUTHORIZED` | Basic 缺失或错误（认证过滤器直接返回） |
| 404 | `NOT_FOUND` | 未知路径（保留 Spring MVC 语义） |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持 |
| 502 | `REGISTRY_ERROR` | registry 返回 `ACK_FAIL` 或其它 gRPC 错误 |
| 503 | `REGISTRY_UNAVAILABLE` | registry `UNAVAILABLE` / `DEADLINE_EXCEEDED` |
| 500 | `INTERNAL` | 未预期异常（堆栈只进日志） |

### 5. 出站 registry 只读查询（management-server）

- `grpc/RegistryQueryGrpcClient implements RegistryQueryPort`：`ManagedChannel`（plaintext，入站上限放宽至 16 MiB 以覆盖 10 万实例级全量响应）+ blocking stub；每次调用现算 deadline（默认 3s）。
- 认证：channel stub 经 `MetadataUtils.newAttachHeadersInterceptor` 附加 `authorization: Basic ...`（复用 `BasicAuth`），凭证来自 `RegistryClientConfig`。
- 失败映射：`UNAVAILABLE`/`DEADLINE_EXCEEDED` → `RegistryQueryException(REGISTRY_UNAVAILABLE)`；其余 gRPC 失败与响应 `status.ack=FAIL` → `RegistryQueryException(REGISTRY_ERROR)`。
- channel 生命周期：`@Bean(destroyMethod = "shutdownNow")`，随 Spring 上下文关闭；v0.1 不重试、不做多实例容灾（registry 地址单值，集群地址列表随 v0.2）。
- 组件复用：grpc-java + grpc-netty（技术选型 #8）、`management.v1` 契约（#2.2 矩阵，artemis-common 单源生成），**不新增依赖组件**。

### 6. 认证与配置（SCF）

认证（ADR-009）：外部 HTTP 默认开启单账户 Basic；显式关闭仅用于 eval。

| 配置键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `artemis.management.auth.enabled` | 静态 bool | `true` | 关闭时 WARN |
| `artemis.management.auth.username` | 静态 string | `artemis` | 启用时空白回落默认值（见下） |
| `artemis.management.auth.password` | 静态 string | `artemis` | 默认值启动 WARN |
| `artemis.management.registry.endpoint` | 静态 string | `localhost:9090` | registry gRPC target |
| `artemis.management.registry.username` | 静态 string | `artemis` | 调 registry 的 Basic 用户 |
| `artemis.management.registry.password` | 静态 string | `artemis` | 调 registry 的 Basic 密码 |
| `artemis.management.registry.timeoutMs` | 静态 long | `3000` | 单次查询 deadline（1..60000） |

- 配置项定义在使用类内：认证项在 `auth/ManagementAuthConfig`，registry 客户端项在 `grpc/RegistryClientConfig`（guidelines §10）。
- **SCF 空白语义**：SCF 把空白/纯空白字符串视为「未设置」（`ObjectExtension.isNullOrEmpty`），会回落默认值而非把空白原样交给使用方。因此空白凭证/端点不会 fail-fast；部署时应确保凭证非空或显式设置 `artemis.management.auth.enabled=false`（eval）。类内不再重复空白校验（SCF 语义已保证）。
- source 装配同 registry：工作目录 `artemis.properties`（reloadable）< 环境变量 < 系统属性 < Spring Environment；manager 名 `artemis-management`。
- 框架/三方组件配置（端口、actuator、trace 导出）仍归 Spring Boot。

### 7. 装配与运行形态

- `ManagementConfiguration`：SCF manager/properties、`Clock`、`EjectionStore`（内存实现）、`RegistryQueryPort`（gRPC client，`destroyMethod="close"`）、`ManagementService`、mapper、认证过滤器。
- 端口：外部 HTTP `server.port=8081`；管理端口 `management.server.port=9092`（默认绑 `127.0.0.1`，暴露 health/info/prometheus）。
- 可观测：结构化 JSON 日志（logback-spring.xml 与 registry 同构）、Micrometer/Prometheus、OpenTelemetry 传播与 span；本 phase 不新增自定义指标（管理流量低，先复用 HTTP/gRPC client 内建指标）。
- `BasicAuth` 上移至 `artemis-common/.../common/auth`，registry-server 与 management-server 共用（HTTP 过滤器与出站 gRPC 拦截器各自实现）。

### 8. 已知限制

- 摘除记录内存态，**重启丢失**（设计内行为，v0.4 DB 接管）。
- 仅 instance 级摘除；无 server/zone/group 级联、无审计、无审批（v0.4/v1.x）。
- management 与 SDK 共用单账户凭证模式的替代是各自服务本地单账户；RBAC v1.0。
- registry 地址单值、无重试/熔断（v0.2 集群后引入地址列表与容灾）。
- 服务列表的 `ejectedCount` 为记录数原样计数，可能含未注册实例；有效计数以详情为准。
- registry 查询响应无分页；客户端入站上限放宽至 16 MiB（约 10 万实例级），超限返回 502（`RESOURCE_EXHAUSTED` → `REGISTRY_ERROR`）。分页随 console/v0.4。

## 设计 review 定案（③）

| # | 决策 | 理由 | 原始文档同步 |
|---|---|---|---|
| D1 | Admin/状态 API 走 HTTP/JSON，不扩 proto、不新增 gRPC 服务 | 架构 §2.2 已定管理面 HTTP；无 wire 契约冻结负担；v0.1 管理流量低 | 架构无变更（§2.2 已记载）；⑩ 新增 `docs/api/admin-api.md` |
| D2 | 详情默认返回已过滤摘除的有效视图，`includeEjected=true` 展开全量并标注 | 直接满足验收要点；恢复操作需要看到被摘除实例 | ⑩ `docs/api/admin-api.md` + 产品文档 |
| D3 | 摘除以 `(namespace, service, instanceId)` 为键、允许未注册实例、幂等且保留首条记录 | 防止实例重注册后"复活"；幂等运维语义；与 v0.4「操作记录即状态」兼容 | ADR-016 已定归属；无新增 ADR |
| D4 | 摘除/恢复不写 registry，有效视图在 management 查询时组合 | ADR-016 决策 2/3 | ADR-016 已记载 |
| D5 | registry 查询用 grpc-java 原生 ManagedChannel + blocking stub（deadline 3s、无重试） | 复用已选组件（技术选型 #8），不引入新依赖；v0.1 管理查询低频，失败可见优于静默重试 | tech-stack 组件表无变更 |
| D6 | management 启动不依赖 registry 可用；下游不可用 API 返回 503 | 管理面独立生命周期（ADR-016）；registry 故障不阻断管理服务起停 | 无变更；⑩ API 文档登记错误语义 |
| D7 | 状态 API 直接透传 registry 查询，不缓存不聚合 | 排障面要求看到事实；registry 侧已脱敏 | 无变更 |
| D8 | `BasicAuth` 上移 `artemis-common` 供两服务复用；HTTP 过滤器与配置类按服务各自实现 | 复用安全敏感代码（编码/常量时间比较），避免复制；配置键与装配仍属各自服务 | 实现内部调整，无文档变更 |

## 实现结果（⑦ 补）

### 实现要点

- **management-core**：domain（`ServiceKey`/`Instance`/`EjectionRecord`/`EjectionStore`/`RegistryQueryPort`/`RegistryQueryException` 等）、application（有效视图组合 `ManagementServiceImpl` + 平铺视图模型 + 手写 `ManagementProtoMapper`）、infrastructure（`InMemoryEjectionStore`，`ConcurrentHashMap` + `countByService` 单次聚合）。
- **management-server**：SCF source（`artemis.properties`/环境变量/系统属性/Spring Environment）、`ManagementAuthConfig`、`RegistryClientConfig`、`RegistryQueryGrpcClient`（plaintext、16 MiB 入站上限、每次调用现算 deadline、Basic metadata）、Admin/状态 REST、Basic 认证过滤器、错误映射、`ManagementConfiguration` 装配、JSON 日志与 actuator/trace。
- **artemis-common**：`BasicAuth` 从 registry-server 上移，registry 与 management 共用（gRPC metadata 与 HTTP header）。
- **测试**：management-core 19 单测、management-server 16 单测 + 11 IT（同 JVM 真实 registry + management，真实 gRPC/HTTP 协议）；registry 回归 89+22 单测、20 IT 全绿。

### review 修复记录（⑦）

| # | finding | 处置 |
|---|---|---|
| 1 | 兜底 `Exception` handler 吞掉 Spring MVC 4xx | 新增 400（消息不可读/参数类型不匹配/缺参）、404、405 显式映射；catch-all 仅兜未预期 500；IT 验证 404/400 |
| 2 | 空白配置 fail-fast 不可达（SCF 空白视为未设置） | 接受 SCF 语义并修订设计文档 §6；类内校验移除（不可达），测试断言回落默认值 |
| 3 | registry 响应无分页 + gRPC 默认 4 MiB 入站上限 | channel `maxInboundMessageSize` 放宽至 16 MiB；设计文档登记已知上限 |
| 4 | `reason` 长度按 UTF-16 code unit 计 | 改用 `codePointCount`（按字符计） |
| 5 | HTTP 过滤器缺错误凭证用例 | 补 `should_reject_request_when_credentials_wrong` |
| 6 | 空白 service 路径参数未覆盖 | IT 补空白 service 断言 |
| 7 | registry fixture 受 classpath `application.properties` 同名遮蔽 | fixture 显式命令行参数钉住 registry 应用名/端口/readiness 组，并注释原因 |
| 8 | 恢复幂等用例空转 | FakeRegistry 预置实例后断言恢复后仍可见 |
| 9 | 服务列表 O(S×R) 摘除计数 | 端口新增 `countByService()` 单次聚合；移除未被生产代码使用的 `find()` |
| 10 | 配置 source 装配与 registry 重复 | **不改**：`artemis-common` 不能依赖 Spring（core 经 common 传递依赖会被 enforcer 禁止），当前无 server-common 模块；两处各约 20 行，待公共装配需求增多再抽模块 |
| 11 | 设计文档与实现漂移 | 已同步：平铺视图模型、`MetadataUtils` 认证、`destroyMethod="close"`、SCF 空白语义、16 MiB 上限 |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` v1.6 | 新增 phase-6 交付节：独立进程/端口、Admin API 与有效视图、摘除/恢复语义、状态 API、出站查询与错误语义、工程形态 |
| `docs/api/admin-api.md`（新增）、`docs/api/README.md` | Admin/状态 HTTP API 契约：端点、示例、认证与配置、有效视图、错误语义、已知限制；索引登记 |
| `docs/arch/artemis-next-architecture.md` | 无变更（管理面独立、HTTP 通道、只读查询已在 ADR-016 与 §2.1/§2.2/§4.1/§5.1 记载） |
| `docs/decisions/` | 无新增 ADR（沿用 ADR-016 管理面独立与 ADR-009 默认认证） |
| `docs/dev/tech-stack-java.md` / `coding-guidelines-java.md` / `.claude/rules/tech-java.md` | 无变更（grpc-netty 等均已在组件表；分层与配置纪律未变） |
| `CLAUDE.md` | 无变更（模块结构、构建命令与当前状态指针未变） |
| 本设计文档 v2.1 | 实现结果、review 修复记录与实现同步（平铺视图、SCF 空白语义、16 MiB 上限、deadline 现算） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.2 | 2026-10-19 | 删除不可达的类内空白校验（SCF 空白视为未设置并回落默认值），同步 §6 与 review 记录 |
| 2.1 | 2026-10-19 | code-review 同步：平铺 InstanceView/EjectionView、countByService 聚合、16 MiB 入站上限、每次调用现算 deadline、SCF 空白回落语义、404/405 错误语义 |
| 2.0 | 2026-10-19 | 产品设计定稿：领域/应用模型、有效视图语义、HTTP 契约、出站查询、认证配置与装配 |
| 1.0 | 2026-10-19 | 开工骨架 |
