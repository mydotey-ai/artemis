# management-service Admin 与状态 API

版本: 1.5    更新时间: 2026-10-24

> 本文定义 management-service 对外 HTTP API（v0.1 内存态管理面）：实例管理 Admin API 与 registry 状态排障面。有效视图 = registry 只读事实 + management 摘除记录（ADR-016）；registry 不感知摘除。内部 gRPC 查询契约见 [registry-query-api.md](registry-query-api.md)。

## 1. 部署与认证

| 项 | 值 |
|---|---|
| 外部 HTTP 端口 | **8081**（`server.port`） |
| 管理端口 | **9092**（`management.server.port`，默认仅绑 `127.0.0.1`；actuator health/info/prometheus） |
| 认证 | 默认开启 HTTP Basic 单账户（默认 `artemis`/`artemis`，生产必须修改） |
| 关闭认证 | `artemis.management.auth.enabled=false`（仅 eval） |
| 上游 | registry 只读查询 gRPC（registry 内部 peer 端口；端点**无默认值**，未配置时调用返回 REGISTRY_UNAVAILABLE；内部认证默认关闭，配置了 username 才附加 Basic 认证） |

配置项（SCF，静态）：

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.management.auth.enabled` | `true` | 认证开关 |
| `artemis.management.auth.username` / `password` | `artemis` / `artemis` | 外部 HTTP 凭证 |
| `artemis.management.registry.endpoint` | （无默认值） | registry 内部端口 `host:port`；未配置时 management 正常启动，查询返回 REGISTRY_UNAVAILABLE |
| `artemis.management.registry.username` / `password` | 空 / 空 | 调用 registry 内部端口的凭证（**动态**，可运行期轮换）；username 非空才发送 Basic 认证 |
| `artemis.management.registry.timeoutMs` | `3000` | 单次查询 deadline（1..60000，**动态**，每次调用现取） |

> SCF 将空白字符串视为「未设置」并回落默认值。registry 内部认证启用（`artemis.registry.internal.auth.enabled=true`）时，须同时配置 management 侧 `artemis.management.registry.username/password` 为内部账户。

## 2. 有效视图语义

- **摘除记录归 management**，键为 `(namespace, service, instanceId)`；内存态，**重启丢失**（v0.4 DB 接管）。
- 摘除/恢复**不修改 registry** 的注册数据、租约与心跳；registry 查询仍返回完整事实。
- 详情默认只返回**有效实例**（已过滤摘除）；`includeEjected=true` 返回全量并逐条标注。
- 摘除幂等：重复摘除保留首条记录（原因与时间不变）；允许摘除当前未注册实例（之后重注册的同一 `instanceId` 仍处于摘除态）。
- **规则下发**（v0.3 phase-6）：摘除/恢复经 management 内部 gRPC 端口的**摘除规则流**推送订阅中的 discovery 节点（at-most-once，断流重连快照自愈）——discovery 对查询/推送/对账一致应用过滤。规则流契约见 [ejection-rule-stream-api.md](ejection-rule-stream-api.md)。
- 恢复幂等：删除记录后立即回到有效视图（下一次查询生效）。

## 3. 通用约定

- JSON（camelCase），`Content-Type: application/json`。
- 实例状态取值：`UP` / `DOWN` / `UNKNOWN`（registry 未设置状态按 `UP` 处理）。
- 时间戳为 ISO-8601 UTC（如 `2026-10-19T10:00:00Z`）。
- 输出排序稳定：服务按 `(namespace, service)`，实例按 `instanceId`。

## 4. 实例管理 Admin API

Base path：`/admin/v1`

### 4.1 服务列表 `GET /admin/v1/services?namespace={ns}`

`namespace` 可选；缺省/空白 = 全部。

```json
{
  "services": [
    {
      "key": {"namespace": "default", "service": "order-service"},
      "instanceCount": 2,
      "ejectedCount": 1
    }
  ]
}
```

- `instanceCount`：registry 事实计数。
- `ejectedCount`：该 service 当前摘除记录数（原样计数，可能含当前未注册实例）；精确有效计数见详情。

### 4.2 服务详情 `GET /admin/v1/services/{namespace}/{service}?includeEjected={bool}`

`includeEjected` 默认 `false`。

```json
{
  "key": {"namespace": "default", "service": "order-service"},
  "instanceCount": 2,
  "ejectedCount": 1,
  "effectiveInstanceCount": 1,
  "instances": [
    {
      "instanceId": "i2",
      "host": "10.0.0.1",
      "port": 8080,
      "status": "UP",
      "weight": 100,
      "metadata": {},
      "ejected": false,
      "reason": null,
      "ejectedAt": null
    }
  ]
}
```

- `ejectedCount` / `effectiveInstanceCount` 只统计**当前注册实例**中被摘除者。
- `includeEjected=true` 时 `instances` 含被摘除实例，`ejected=true` 且携带 `reason`/`ejectedAt`。
- 未注册 service → 200，计数全 0、`instances=[]`（查询语义，非 404）。

### 4.3 摘除 `POST /admin/v1/services/{namespace}/{service}/instances/{instanceId}/eject`

请求体可选：

```json
{"reason": "maintenance"}
```

`reason` 最长 1024 字符。响应 200：

```json
{
  "namespace": "default",
  "service": "order-service",
  "instanceId": "i1",
  "reason": "maintenance",
  "ejectedAt": "2026-10-19T10:00:00Z"
}
```

幂等：已摘除时返回首条记录；不要求实例当前已注册。

### 4.4 恢复 `POST /admin/v1/services/{namespace}/{service}/instances/{instanceId}/recover`

- 成功 204 No Content；幂等（未摘除同样 204）。

## 5. 状态 API（registry 排障面）

数据直接来自 registry 只读查询，不缓存、不聚合；敏感配置已在 registry 侧脱敏。

### 5.1 租约 `GET /admin/v1/status/leases?namespace={ns}&service={svc}`

```json
{
  "protection": {"windowRenewals": 10, "maxCount": 3, "protecting": false},
  "leases": [
    {
      "service": {"namespace": "default", "service": "order-service"},
      "instanceId": "i1",
      "ageMs": 400,
      "remainingTtlMs": 600
    }
  ],
  "truncated": false
}
```

- `remainingTtlMs` 可为负（已到期待清理）。
- 单次最多 10000 条；超限 `truncated=true`，应按 namespace/service 分别查询。

### 5.2 配置 `GET /admin/v1/status/config`

```json
{
  "properties": [
    {
      "key": "artemis.registry.auth.password",
      "value": "******",
      "source": "springEnvironment",
      "isStatic": true,
      "defaultValue": "******",
      "doc": "authentication password (static)"
    }
  ]
}
```

- 返回 **registry** 的配置项；敏感 key（password/passwd/pwd/secret/token/credential/apikey/accesskey/privatekey）的 `value` 与 `defaultValue` 固定 `******`。

### 5.3 自我保护 `GET /admin/v1/status/protection?namespace={ns}&service={svc}&includeNormal={bool}`

数据来自 registry `GetSelfProtectionStatus`（ADR-017），不缓存、不聚合。

```json
{
  "trackedServices": 3,
  "protectingServices": 1,
  "services": [
    {
      "service": {"namespace": "default", "service": "order-service"},
      "windowRenewals": 0,
      "maxCount": 100,
      "nodeFallback": false,
      "protecting": true,
      "protectingForMs": 12000
    }
  ],
  "truncated": false,
  "node": {"windowRenewals": 10, "maxCount": 100, "protecting": false}
}
```

- `includeNormal` 默认 `false`：未指定 `service` 时仅返回服务级保护中条目；指定 `namespace + service` 时该服务总是返回（即使 NORMAL 或无本地窗口）。
- `protecting` 是服务级状态；`nodeFallback=true` 表示服务级未启用（无本地窗口或峰值未达 `minCount`），有效判定为 `protecting || (nodeFallback && node.protecting)`。
- `protectingForMs` 为服务级保护持续时长（单调钟计算，跨进程不可比）；NORMAL 为 0。
- 单次最多 10000 条；超限 `truncated=true`。`node` 为节点级窗口统计，用于区分节点级故障与单服务异常。
- `/admin/v1/status/leases` 的 `protection` 字段保持节点级语义不变。

## 6. 错误语义

错误体：

```json
{"error": "REGISTRY_UNAVAILABLE", "message": "registry unavailable: ..."}
```

| HTTP | error | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 路径/查询参数空白、`reason` 超长、body 不可解析、参数类型错误 |
| 401 | `UNAUTHORIZED` | Basic 缺失或错误 |
| 404 | `NOT_FOUND` | 未知路径 |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持 |
| 502 | `REGISTRY_ERROR` | registry 返回 `ACK_FAIL` 或其它 gRPC 错误（含响应超过 16 MiB 入站上限） |
| 503 | `REGISTRY_UNAVAILABLE` | registry `UNAVAILABLE` / `DEADLINE_EXCEEDED` |
| 500 | `INTERNAL` | 未预期异常（堆栈只进日志） |

management 启动不依赖 registry 可用；registry 故障时管理面仍可启动，查询类 API 返回 503。

## 7. 已知限制（v0.1）

- 摘除记录内存态、重启丢失；仅 instance 级，无四级级联、审计、审批（v0.4/v1.x）。
- RBAC 随 v1.0；v0.1 外部 HTTP 单账户凭证。
- registry 地址单值、无重试/熔断（v0.2 集群后引入地址列表与容灾）。
- registry 查询无分页；客户端入站上限 16 MiB，超限返回 502。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.5 | 2026-10-24 | v0.3 phase-6：摘除/恢复语义补规则流下发说明（discovery 侧过滤生效） |
| 1.4 | 2026-10-21 | v0.2 phase-4：新增 `GET /admin/v1/status/protection`（按服务保护态与节点级统计） |
| 1.3 | 2026-10-20 | v0.2：上游单一 endpoint（无默认值）；凭证与 timeout 动态化 |
| 1.2 | 2026-10-20 | v0.2：上游地址/端口分离（已废弃，回归单一 endpoint） |
| 1.1 | 2026-10-20 | v0.2：上游 registry 只读查询改连内部 peer 端口 |
| 1.0 | 2026-10-19 | 初版：实例管理 Admin API、状态 API、有效视图语义、认证配置与错误语义 |
