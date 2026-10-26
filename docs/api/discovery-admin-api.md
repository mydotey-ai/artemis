# discovery 发现管理 API

版本: 1.2    更新时间: 2026-10-26

> 本文定义 discovery-service 的**只读**发现管理面 HTTP API（`/admin/v1`，v0.3 phase-7）：订阅关系查看（调用方视角）、服务视图查询、上游状态/新鲜度查询。数据面 gRPC 契约见 [查询](discovery-query-api.md)、[订阅](discovery-subscription-api.md)、[对账](discovery-reconciliation-api.md)。

## 1. 部署与认证

| 项 | 值 |
|---|---|
| 端口 | discovery 外部 HTTP 端口（`server.port`，默认 **8090**） |
| Base path | `/admin/v1` |
| 认证 | 复用 discovery 外部账户（HTTP Basic，`artemis.discovery.auth.*`）；**默认关闭**（D-D10：发现面不持有可写事实） |
| 就绪门控 | **无**——启动期即可排障；未完成首轮同步时返回当前（可能为空的）视图，由 `upstreams.state` 自解释 |
| 依赖 | 仅本地内存投影与订阅登记表，无下游调用（无 503 语义） |

认证启用时，无凭证/凭证错误返回 `401`；过滤器只作用于 `/admin/*`，不影响 gRPC 数据面。

## 2. 通用约定

- JSON（camelCase），`Content-Type: application/json`。
- 时间戳 ISO-8601 UTC（如 `2026-10-24T10:00:00Z`）。
- 输出排序稳定：订阅按 `id` 升序；服务视图按 `(cluster, namespace, service)`；详情 cluster 按名、实例按 `instanceId`。
- 错误响应统一 `{"error": "<code>", "message": "<可读信息>"}`：

| HTTP | error | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 路径参数空白、参数类型非法 |
| 401 | `UNAUTHORIZED` | 认证启用但凭证缺失/错误 |
| 404 | `NOT_FOUND` | 未知路由 |
| 405 | `METHOD_NOT_ALLOWED` | 非 GET 方法 |
| 500 | `INTERNAL` | 未预期异常（堆栈只进日志） |

## 3. 订阅关系 `GET /admin/v1/subscriptions`

当前**活跃**订阅快照（调用方视角），按 `id` 升序：

```json
{
  "subscriptions": [
    {
      "id": 7,
      "subscriber": "/127.0.0.1:54321",
      "establishedAt": "2026-10-24T10:00:00Z",
      "services": [{"namespace": "default", "service": "order-service"}],
      "labels": {"group": "a"},
      "clusters": ["cluster-a"]
    }
  ]
}
```

- `services` 空数组 = 全部服务；`clusters` 空数组 = 全部来源 cluster（与查询/订阅选择器同语义）。
- `subscriber` = gRPC 流连接的传输层远端地址（`ip:port`）。当前认证为单账户，没有可靠的 per-caller 身份；客户端自报标识不可校验，故以传输层地址为调用方标识（v0.4 账户体系接管后升级）。
- `establishedAt` = 订阅登记时刻（UTC）。
- 只含活跃订阅：流取消/断流/服务端断流后即从快照移除，不保留历史（无界增长风险为零）。

## 4. 服务视图

### 4.1 列表 `GET /admin/v1/services?namespace={ns}&cluster={cluster}`

两个参数均可选；缺省/空白 = 全部。按 `(cluster, namespace, service)` 升序：

```json
{
  "services": [
    {"cluster": "cluster-a", "namespace": "default", "service": "order-service", "instanceCount": 2}
  ]
}
```

- 口径 = **有效发现视图**：摘除规则过滤链后的结果（与数据面查询/推送/对账一致），只含 ≥1 可见实例的条目。
- 行粒度 = `(cluster, service)`，多集群不合并（来源 cluster 是排障维度）。

### 4.2 详情 `GET /admin/v1/services/{namespace}/{service}`

```json
{
  "namespace": "default",
  "service": "order-service",
  "clusters": [
    {
      "cluster": "cluster-a",
      "instances": [
        {"instanceId": "i1", "host": "10.0.0.1", "port": 8080, "status": "UP", "weight": 100, "metadata": {}}
      ]
    }
  ]
}
```

- 逐来源 cluster 返回可见实例；cluster 按名升序、实例按 `instanceId` 升序。
- 未注册、或全部实例被过滤 → `200` + `clusters: []`（查询语义，非 404）；路径参数空白 → `400`。
- 不出「未过滤事实」与摘除记录——那是 management Admin API 的有效视图职责（ADR-016）。

## 5. 上游状态/新鲜度 `GET /admin/v1/upstreams`

```json
{
  "upstreams": [
    {"cluster": "cluster-a", "state": "SYNCED"}
  ],
  "management": {
    "enabled": true,
    "state": "SYNCED",
    "appliedVersion": 3,
    "routeRules": {"state": "SYNCED", "appliedVersion": 5}
  }
}
```

- `upstreams`：各 registry 来源的同步状态（按 `cluster` 升序）。`SYNCING` / `SYNCED` / `DISCONNECTED`，语义见 [对账与新鲜度](discovery-reconciliation-api.md) §4——非 `SYNCED` 即投影可能陈旧。
- `management`：摘除规则源状态（phase-6 D-E9 出口）：
  - `enabled=false, state=DISABLED`：未配置 management（0..1），不订阅规则、不过滤。
  - `SYNCING` / `SYNCED` / `DISCONNECTED`：连接与首轮快照中 / 已同步消费增量 / 断流重连或已停机（规则冻结、最后已知规则继续生效）。
  - `appliedVersion`：本节点已应用摘除规则集版本（v0.4 phase-5；-1 = 尚未应用任何版本）——与 management 生效水位（`/admin/v1/status/rules`）对照排障。
  - `routeRules`：路由规则流对照节（v0.5 phase-2）——独立状态与版本域：`state` 语义同上（SYNCING/SYNCED/DISCONNECTED，未配置 management 时 DISABLED）、`appliedVersion` 为本节点已应用路由规则集版本（-1 = 尚未应用），与 management `status/rules` 的 `routeRules` 节对照排障（两段发布下发面健康）。

## 6. 配置

无新增配置键；认证与上游配置见 [discovery 查询 API](discovery-query-api.md) §3。管理端口（actuator health/readiness/prometheus）仍默认 9096 仅绑本机。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-26 | v0.5 phase-2：§5 upstreams 的 management 节补嵌套 `routeRules:{state, appliedVersion}`（独立状态与版本域排障对照） |
| 1.1 | 2026-10-26 | v0.4 phase-5：`management` 字段增 `appliedVersion`（本节点已应用规则集版本，与 management 水位对照） |
| 1.0 | 2026-10-24 | 初版：v0.3 phase-7 只读发现管理面（订阅关系/服务视图/上游与规则源状态） |
