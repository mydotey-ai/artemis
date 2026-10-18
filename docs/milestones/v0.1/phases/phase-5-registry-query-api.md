# Phase 5：registry 只读事实查询 RPC

版本: 2.4    更新时间: 2026-10-19

> 主题修订：原 phase-5-admin-api（registry 内嵌实例管理 Admin API）按 [ADR-016](../../../decisions/adr-016-management-service-day-one.md) 拆分——本 phase 只交付 registry 侧只读事实查询；管理面（摘除记录、有效视图、Admin/状态 API）移入 phase-6-management-service。

## 目标

为 management-service 提供 registry 数据面的只读事实查询 RPC（服务列表、实例快照、租约明细与保护统计、配置状态），敏感配置值在 registry 侧脱敏。

## 范围

**做**：

- `management.v1` 新增内部只读查询契约 `RegistryQueryService`（四个 unary RPC；管理面契约组，不进 client、不冻结），服务端统一生成于 `artemis-common`。
- registry-core：新增查询应用服务 `RegistryQueryService` + 实现 + proto 门面/mapper；domain 新增查询结果模型；`InstanceRegistry` 暴露租约明细与生产配置构造。
- registry-server：`@GrpcService` 接入（一行委派门面），复用现有全局认证/readiness 拦截器与外部 gRPC 端口。
- 敏感配置脱敏（`password/passwd/pwd/secret/token/credential/apikey/accesskey/privatekey` 的 value 与 default_value 均固定 `******`），registry 永不返回明文凭证。

**不做**：

- 摘除记录、有效视图、Admin/状态 API（phase-6 management-service）。
- HTTP 暴露与认证改造（复用现有 gRPC 认证）。
- 内部独立端口（ADR-016：v0.1 复用外部 gRPC 端口，v0.2 统一规划）。
- 查询指标/审计（v0.1 无此需求）。
- 分页（10 万级全量排障响应先直出，见 §4 天花板说明）。

## 涉及模块

- `proto/management/v1/management.proto`：新增查询消息与 `RegistryQueryService`（管理面契约随治理面演进，不随数据面冻结）。
- `java/server/artemis-registry-core`：application 查询服务 + mapper/门面；domain 查询模型与 `InstanceRegistry` 扩展。
- `java/server/artemis-registry-server`：`@GrpcService` 接入 + `InstanceRegistry` bean 装配调整。
- `java/server/artemis-common`：统一生成 `management.v1`（registry 实现 / management 消费双端共享，避免 all-in-one 同 JVM 双份生成类）；registry-core 与 management-core 经 Maven 依赖引用。

## 验收方式

对应测试文档：[phase-5-registry-query-api-test.md](phase-5-registry-query-api-test.md)

## 产品设计（③ 补）

### 1. 契约（management.v1）

```proto
// ---- 内部管理只读查询（management-service 消费，ADR-016）----
// registry 只返回事实，不感知摘除；敏感配置值在本侧脱敏后返回。

message ListServicesRequest {
  string namespace = 1;            // 空 = 全部 namespace
}

message ServiceSummary {
  artemis.common.v1.ServiceKey key = 1;
  int32 instance_count = 2;
}

message ListServicesResponse {
  artemis.common.v1.ResponseStatus status = 1;
  ListServicesResponseData data = 2;
}

message ListServicesResponseData {
  repeated ServiceSummary services = 1;
}

message GetServiceInstancesRequest {
  artemis.common.v1.ServiceKey key = 1;
}

message GetServiceInstancesResponse {
  artemis.common.v1.ResponseStatus status = 1;
  GetServiceInstancesResponseData data = 2;
}

message GetServiceInstancesResponseData {
  repeated artemis.common.v1.Instance instances = 1;   // 未注册 service → 空数组 + SUCCESS
}

message GetLeaseStatusRequest {
  // 可选过滤：空 = 全部；大集群按 service 查询避免单次响应超限
  string namespace = 1;
  string service = 2;
}

message SelfProtectionStats {
  int64 window_renewals = 1;
  int64 max_count = 2;
  bool protecting = 3;
}

message LeaseDetail {
  artemis.common.v1.ServiceKey service = 1;
  string instance_id = 2;
  int64 age_ms = 3;
  int64 remaining_ttl_ms = 4;      // 可为负：已到期待清理
}

message GetLeaseStatusResponse {
  artemis.common.v1.ResponseStatus status = 1;
  GetLeaseStatusResponseData data = 2;
}

message GetLeaseStatusResponseData {
  SelfProtectionStats protection = 1;
  repeated LeaseDetail leases = 2;
  bool truncated = 3;              // 超过单次上限（10000）时为 true
}

message GetConfigStatusRequest {}

message ConfigProperty {
  string key = 1;
  string value = 2;                // 敏感项固定 "******"
  string source = 3;               // 配置来源名；未命中任何 source 时为空
  bool is_static = 4;
  string default_value = 5;
  string doc = 6;
}

message GetConfigStatusResponse {
  artemis.common.v1.ResponseStatus status = 1;
  GetConfigStatusResponseData data = 2;
}

message GetConfigStatusResponseData {
  repeated ConfigProperty properties = 1;   // 按 key 升序
}

service RegistryQueryService {
  rpc ListServices(ListServicesRequest) returns (ListServicesResponse);
  rpc GetServiceInstances(GetServiceInstancesRequest) returns (GetServiceInstancesResponse);
  rpc GetLeaseStatus(GetLeaseStatusRequest) returns (GetLeaseStatusResponse);
  rpc GetConfigStatus(GetConfigStatusRequest) returns (GetConfigStatusResponse);
}
```

- 查询失败语义：非法 ServiceKey（空白 namespace/service）→ `status.ack=FAIL` + `INVALID_ARGUMENT`；其余查询不存在的资源一律成功返回空集合（查询语义，不用 404）。
- 契约遵守 proto 规范 §2：Response 统一 `status + data`；错误码走既有注册表。
- `management.v1` 是管理面契约组：client SDK 不生成、不随数据面冻结；后续治理规则契约在同一组演进。
- 该 service 不进入 client 生成组（SDK 契约保持 `common.v1 + registry.v1 + discovery.v1`），内部管理接口不污染 SDK。

### 2. 领域模型（core `domain`）

```java
public record ServiceSummary(ServiceKey key, int instanceCount) {}

public record LeaseDetail(ServiceKey service, String instanceId, long ageMs, long remainingTtlMs) {}

public record LeaseStatus(SelfProtectionStats protection, List<LeaseDetail> leases, boolean truncated) {
    public LeaseStatus { leases = List.copyOf(leases); }
}

public record ConfigProperty(
        String key, String value, String source, boolean isStatic, String defaultValue, String doc) {}
```

- `SelfProtectionStats` 复用既有领域 record。
- 输出排序固定：服务按 `(namespace, service)`；租约按 `(namespace, service, instanceId)`；配置按 key 升序。
- `ageMs`/`remainingTtlMs` 在 `InstanceRegistry` 内用单调钟计算（age 钳为 ≥ 0，容忍扫描期并发续约），不对外暴露单调时刻。
- `leaseStatus` 支持 namespace/service 可选过滤，单次最多返回 10000 条并置 `truncated`（约 1 MB，低于 gRPC 默认 4 MiB 入站上限）。

### 3. `InstanceRegistry` 扩展

```java
/** 全量租约明细（排障用，弱一致全表扫描）。 */
public List<LeaseDetail> leaseDetails();
```

- 构造 `InstanceRegistry(StringProperties properties, MonotonicClock clock, ChangeListener listener)`：类内定义动态 TTL property（`artemis.registry.lease.ttlMs`，沿用既有键/默认值/值域）；TTL 配置定义点从 `RegistryServiceImpl` 迁入本类（租约 TTL 的真正消费方）。
- 原 `LeaseTtlSource` 端口**删除**：生产路径上它只是 SCF property 的 lambda 适配，单测用 `TestProperties`（内存 source）即可注入并动态改值；保留单一构造，去掉冗余抽象。
- `// ponytail: 全量扫描 O(实例数)，排障端点非热路径；需要分页/按 service 查询时再加参数。`

### 4. 应用层（core `application`）

```java
public interface RegistryQueryService {
    List<ServiceSummary> listServices(String namespace);   // null/空白 = 全部
    Optional<ServiceInstances> getInstances(ServiceKey key);
    LeaseStatus leaseStatus();
    List<ConfigProperty> configStatus();
}
```

- `RegistryQueryServiceImpl` 依赖 `InstanceRegistry`、`SelfProtection`、`ConfigurationManager`（SCF 配置枚举）。
- `configStatus()`：`ConfigurationManager#getProperties()` 全量输出（key/value/source/isStatic/default/doc）。
  - source 取 `property.getSource().getConfig().getName()`；未命中任何 source（使用默认值）时为空串。
  - **脱敏**：key 不区分大小写包含敏感标记（password/passwd/pwd/secret/token/credential/apikey/accesskey/privatekey）时，`value` 与 `default_value` 均输出 `******`，其余字段保留。默认值在默认部署下即生效凭证，必须同样脱敏。
- 非法 key 的契约校验在 proto 门面完成（信任边界一次），应用服务对 null/空白 key 返回 empty（不重复防御）。
- `RegistryQueryProtoFacade`（application）：proto ↔ domain 转换与 `INVALID_ARGUMENT` 组装；映射以既有 `RegistryProtoMapper` 的 default 方法扩展（复用 `toProto(Instance/ServiceKey)`），不新增 mapper 类型。

### 5. 接入层（registry-server）

- `grpc/RegistryQueryGrpcService`：`@GrpcService`，四个方法一行委派 `RegistryQueryProtoFacade`。
- 认证与就绪：复用现有全局 `GrpcAuthInterceptor`（默认 Basic 认证）与 `ReadinessInterceptor`；新 service 自动受覆盖，无白名单。
- 端口：复用 `spring.grpc.server.port`（外部 gRPC，默认 9090），不新增端口（ADR-016）。
- **已知限制（v0.1）**：查询面与 SDK 共用单账户凭证，任何持注册凭证的调用方可读管理视图（含跨 namespace 枚举）；RBAC 随 v1.0、内部端口分离随 v0.2。部署建议不将该端口暴露到不可信网络。

### 6. 装配调整

- `RegistryConfiguration` 暴露 `InstanceRegistry` bean（注入 `StringProperties`、`MonotonicClock`、`ChangeListener`）。
- `RegistryServiceImpl` 构造改为注入 `InstanceRegistry`，删除内部 TTL property 定义与内部 `new InstanceRegistry`；其余行为不变。
- 新增 `RegistryQueryService`、`RegistryQueryProtoFacade` bean。
- 单测适配：`RegistryServiceImplTest` / `RegistryBootstrapTest` 构造注入 `InstanceRegistry`（`TestProperties` 提供 TTL）；`InstanceRegistryTest` 增补租约明细用例，TTL 动态用例改经 `TestProperties` 改值。

### 7. 文档与契约同步

- `docs/dev/proto-contract.md`：契约组织不变（查询 service 归 `management.v1`，本就不在数据面冻结范围），无需改规范。
- 架构 §2.2 端口/协议表已按 ADR-016 记录管理查询通道（v1.22 已同步），本设计不再新增架构变更。
- API 文档（Admin/状态 HTTP API）属 phase-6 交付，本 phase 不新增 `docs/api/` 文档。

## 设计 review 定案（③）

| # | 决策 | 理由 | 原始文档同步 |
|---|---|---|---|
| D1 | 管理面从 v0.1 独立交付，摘除记录归 management-service；registry 只提供只读事实查询 | 管理面状态与数据面生命周期解耦；避免 v0.2 扇出、v0.4 迁移的叠加改造 | ADR-016 新增；架构 §2.1/§2.2/§4.1/§5.1/§7 v1.22；roadmap v1.8；plan v1.12 |
| D2 | 查询契约放 `management.v1`（新增 `RegistryQueryService`），不新增 proto 组 | 管理面契约组本就不进 client、不冻结；比放 `registry.v1` 更贴合消费方与演进边界；生成矩阵改为 artemis-common 单源 | 本设计 §1；技术选型 §2.2 v1.19 |
| D3 | v0.1 复用 registry 外部 gRPC 端口，不自写第二 server | Spring Boot 官方 starter 单网络 server；技术纪律禁自写 Server；内部端口随 v0.2 统一规划 | ADR-016；架构 §2.2 v1.22 |
| D4 | 敏感配置在 registry 侧脱敏，管理面不接触明文凭证 | 凭证不跨进程；管理面查询面最小化 | 本设计 §4；phase-6 状态 API 直接透传脱敏值 |

## 实现结果（⑦ 补）

### 实现要点

- **契约**：查询消息与 `RegistryQueryService` 落 `management.v1`（管理面组，client 不生成、不冻结）；`registry.v1` 恢复纯 SDK 数据面契约。服务端统一由 `artemis-common` 生成（gRPC 插件 + stub 依赖），registry-core / management-core 经依赖共享。
- **domain**：新增 `ServiceSummary` / `LeaseDetail` / `LeaseStatus`（含 `truncated`）/ `ConfigProperty`；`InstanceRegistry` 类内定义动态 TTL property 并新增 `leaseDetails()`（age 钳为 ≥ 0）；删除冗余 `LeaseTtlSource` 端口。
- **application**：`RegistryQueryService` + 实现（服务列表/实例快照/租约过滤与 10000 条截断/配置状态）；`RegistryQueryProtoFacade` 做契约校验与响应组装；查询响应映射扩展既有 `RegistryProtoMapper` default 方法。
- **接入/装配**：`RegistryQueryGrpcService`（一行委派，复用全局认证与 readiness 拦截器）；`InstanceRegistry` 提升为共享 bean，`RegistryServiceImpl` 改为构造注入。
- **测试**：core 89 / server 22 单测、20 服务级集成测试（含认证、查询、脱敏、截断与错误语义）。

### review 修复记录（⑦）

| # | finding | 处置 |
|---|---|---|
| 1 | 敏感配置 `default_value` 明文 | value 与 default_value 同时脱敏 |
| 2 | 全量租约超 gRPC 4 MiB | namespace/service 过滤 + 10000 条上限 + `truncated` |
| 3 | 管理查询无权限边界 | ADR-016/设计文档明示已知限制（RBAC v1.0、端口分离 v0.2） |
| 4 | 敏感键 denylist 不全 | 扩展 passwd/pwd/credential/apikey/accesskey/privatekey |
| 5 | 测试固化明文默认值 | 断言改为脱敏期望 |
| 6–9 | 状态源/roadmap/产品文档/ADR 文档漂移 | 逐项修正（dev-state、roadmap v1.9、产品文档、ADR legacy 对照） |
| 10–11 | 负 age、瞬时 0 计数 | age 钳制；弱一致语义文档明示 |
| 12–15 | phase-4/phase-5 文档与用例名漂移 | 同步 owner、mapper/source 语义、用例名 |

验证期补充：按用户定案将查询契约从 `registry.v1` 迁至 `management.v1`，并删除 `LeaseTtlSource` 冗余端口；回归全绿。

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` v1.5 | phase-5 交付节（查询能力/脱敏/上限/契约归属）；common 生成组补 management.v1 |
| `docs/arch/artemis-next-architecture.md` v1.23 | §2.2 注明 management.v1 含 registry 只读查询 service（client 不生成） |
| `docs/decisions/adr-016-management-service-day-one.md` v1.2 | 查询契约落点由 registry.v1 改为 management.v1；artemis-common 单源生成 |
| `docs/dev/tech-stack-java.md` v1.19 | 生成矩阵：common 生成 management.v1；registry-core/management-core 不再自生成；Rust registry 补 management.v1 |
| `docs/dev/coding-guidelines-java.md` v1.22、`.claude/rules/tech-java.md` | 跨模块共享生成边界补 management.v1 |
| `docs/api/registry-query-api.md`（新增）、`docs/api/README.md`、`docs/README.md` | 内部只读查询契约文档与索引 |
| `docs/milestones/v0.1/plan.md` v1.12 | phase 重排（设计阶段已同步） |
| `CLAUDE.md` | 无需改（六组契约、构建命令与模块结构未变） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.4 | 2026-10-19 | ⑩ 文档同步完成；补实现结果与 review 修复记录 |
| 2.3 | 2026-10-19 | 契约从 registry.v1 迁到 management.v1（不进 client、不冻结），服务端统一生成于 artemis-common |
| 2.2 | 2026-10-19 | 删除冗余 `LeaseTtlSource` 端口：TTL 统一走 SCF property，单测经 TestProperties 注入/改值 |
| 2.1 | 2026-10-18 | review 修复：敏感 defaultValue 脱敏、租约查询过滤/截断、age 钳制、mapper 落点与 source 语义统一 |
| 2.0 | 2026-10-18 | 按 ADR-016 重写：phase-5 改为 registry 只读事实查询 RPC；管理面移交 phase-6-management-service |
| 1.1 | 2026-10-18 | review 修订：摘除/恢复产生领域变更事件（原 registry 内嵌方案） |
| 1.0 | 2026-10-18 | 开工骨架 + 产品设计定稿（原 Admin API 方案） |
