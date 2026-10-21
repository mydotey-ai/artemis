# registry 内部只读查询 API

版本: 1.3    更新时间: 2026-10-21

> 本文定义 registry 提供给管理面的只读事实查询契约（management-service 消费、registry 实现，ADR-016）。**非 SDK 公开 API**：契约位于 `management.v1`（管理面组），client SDK 不生成；面向用户的 Admin HTTP API 属 management-service（phase-6）。

## 1. 契约与部署

| 项 | 值 |
|---|---|
| proto | `proto/management/v1/management.proto`（`package artemis.management.v1`） |
| Java 生成 | 服务端统一生成于 `artemis-common`（registry 实现与 management 调用共享单份生成类） |
| 传输 | gRPC（unary），registry **内部 peer 端口**（`artemis.registry.internal.port`，默认 9093） |
| 认证 | 内部端口独立账户 `artemis.registry.internal.auth.*`，**默认关闭**；启用时 management 侧须配置 `artemis.management.registry.username/password`。认证开关与凭证均为动态配置，可运行期轮换 |
| 就绪 | 复用 registry readiness 门控；未就绪返回 `UNAVAILABLE` |

## 2. 通用约定

- 所有响应统一 `status + data`：`status.ack` 为 `ACK_SUCCESS` / `ACK_FAIL`，失败时 `status.error` 携带 `ErrorInfo`（`code` + `message`）。
- 查询成功恒为 `ACK_SUCCESS`；查询不存在的资源返回空集合，不视为错误。
- 输出排序稳定：服务按 `(namespace, service)`，租约为 `(namespace, service, instanceId)`，配置按 `key` 升序。
- registry 只返回事实，**不感知摘除**；有效视图由 management 组合自身摘除记录。

## 3. 方法

### 3.1 `GetConfigStatus`

`GetConfigStatusRequest {}` → `GetConfigStatusResponse`

返回全部已创建的 SCF 配置项：

| 字段 | 说明 |
|---|---|
| `key` | 配置键 |
| `value` | 当前值；敏感项固定 `******` |
| `source` | 命中的配置源名；未命中任何源（使用默认值）时为空 |
| `is_static` | 是否为静态项（启动后不可变） |
| `default_value` | 默认值；敏感项固定 `******` |
| `doc` | 配置说明 |

**脱敏规则**：key 不区分大小写包含 `password` / `passwd` / `pwd` / `secret` / `token` / `credential` / `apikey` / `accesskey` / `privatekey` 时，`value` 与 `default_value` 均为 `******`。registry 永不返回明文凭证。

### 3.2 `ListServices`

`ListServicesRequest { string namespace; }`（空 = 全部）→ `ListServicesResponse`

`data.services[]`：`key`（`ServiceKey`）+ `instance_count`（当前注册实例数，弱一致读）。

### 3.3 `GetServiceInstances`

`GetServiceInstancesRequest { artemis.common.v1.ServiceKey key; }` → `GetServiceInstancesResponse`

`data.instances[]` 为注册表事实（`common.v1.Instance`）。未注册 service 返回空数组 + `ACK_SUCCESS`；`namespace`/`service` 空白返回 `ACK_FAIL` + `INVALID_ARGUMENT`。

### 3.4 `GetLeaseStatus`

`GetLeaseStatusRequest { string namespace; string service; }`（均可选，空 = 全部）→ `GetLeaseStatusResponse`

| 字段 | 说明 |
|---|---|
| `data.protection` | 自我保护统计：`window_renewals`、`max_count`、`protecting` |
| `data.leases[]` | `service`、`instance_id`、`age_ms`（自续约起算，≥ 0；单调钟计算）、`remaining_ttl_ms`（可为负，表示已到期待清理） |
| `data.truncated` | 结果超过单次上限（**10000** 条）时为 true |

大集群应按 namespace/service 过滤查询；无过滤时最多返回 10000 条（约 1 MB，低于 gRPC 默认 4 MiB 入站上限）。

### 3.5 `GetSelfProtectionStatus`（v0.2 phase-4，ADR-017）

`GetSelfProtectionStatusRequest { string namespace; string service; bool include_normal; }` → `GetSelfProtectionStatusResponse`

自我保护 2.0 状态：节点级兜底窗口 + 按服务窗口。**`GetLeaseStatus.protection` 仍只返回节点级统计**，本方法是按服务排障的独立入口。

| 请求字段 | 说明 |
|---|---|
| `namespace` | 可选过滤；空 = 全部 |
| `service` | 可选过滤；指定 `namespace + service` 时该服务总是返回（NORMAL 也返回，无本地窗口时返回零计数条目） |
| `include_normal` | 默认 `false`：未指定 `service` 时仅返回服务级 PROTECTING 条目；指定 `service` 时该字段不影响该服务返回 |

响应 `data`：

| 字段 | 说明 |
|---|---|
| `tracked_services` | 本节点有本地心跳窗口的服务数 |
| `protecting_services` | 服务级 PROTECTING 服务数（与默认 `services` 列表同口径；节点级兜底服务不计入） |
| `services[]` | 按 `(namespace, service)` 升序；默认仅 PROTECTING |
| `services[].window_renewals` | 当前窗口续约量（查询时只读求和，不受清理周期影响） |
| `services[].max_count` | 历史峰值（可能已按 `maxCountResetMs` 或窗口调参回落） |
| `services[].node_fallback` | 服务级未启用（无本地窗口或峰值 < `minCount`），清理判定取 `node.protecting` |
| `services[].protecting` | 服务级显式状态 |
| `services[].protecting_for_ms` | 服务级 PROTECTING 持续时长（单调钟计算，跨进程不可比）；NORMAL 为 0 |
| `truncated` | 条目超过单次上限（**10000**）时为 true |
| `node` | 节点级窗口统计（`window_renewals`/`max_count`/`protecting`） |

**有效保护判定**：`protecting || (node_fallback && node.protecting)`。无本地窗口（仅副本）或峰值未达 `minCount` 的小服务由节点级兜底；服务级达门槛后以服务级状态为准。

## 4. 错误语义

| 场景 | 结果 |
|---|---|
| 空白 namespace/service（`GetServiceInstances`） | `ACK_FAIL` + `INVALID_ARGUMENT` |
| 资源不存在 | `ACK_SUCCESS` + 空集合 |
| 未认证 | gRPC `UNAUTHENTICATED` |
| 未就绪 | gRPC `UNAVAILABLE` |
| 服务端内部异常 | gRPC `INTERNAL` |

## 5. 已知限制（v0.1）

- 查询面在内部端口提供；内部认证默认关闭，部署须以网络策略隔离内部端口（默认绑 `0.0.0.0` 以支持 peer 跨节点通信）；启用后仍为单账户、无 per-namespace 权限隔离；RBAC 随 v1.0。
- 租约明细为弱一致全表扫描（并发续约时 age 钳为 ≥ 0）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-21 | v0.2 phase-4：新增 `GetSelfProtectionStatus`（按服务保护态、`node_fallback`、10000 条上限） |
| 1.2 | 2026-10-20 | v0.2：认证开关/凭证动态化（支持运行期轮换） |
| 1.1 | 2026-10-20 | v0.2：查询迁移到 registry 内部 peer 端口，独立内部认证且默认关闭 |
| 1.0 | 2026-10-19 | 初版：registry 内部只读查询四条 RPC、脱敏、排序、上限与错误语义 |
