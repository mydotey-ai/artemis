# management-service Admin 与状态 API

版本: 1.16    更新时间: 2026-10-27

> 本文定义 management-service 对外 HTTP API（管理面：实例管理 Admin API、账户 API 与 registry 状态排障面）。有效视图 = registry 只读事实 + management 摘除记录（ADR-016）；registry 不感知摘除。内部 gRPC 查询契约见 [registry-query-api.md](registry-query-api.md)。

## 1. 部署与认证

| 项 | 值 |
|---|---|
| 外部 HTTP 端口 | **8081**（`server.port`） |
| 管理端口 | **9092**（`management.server.port`，默认仅绑 `127.0.0.1`；actuator health/info/prometheus） |
| 认证（内存态） | 默认开启 HTTP Basic 单账户（默认 `artemis`/`artemis`，生产必须修改）；凭证取自配置，支持运行期轮换 |
| 认证（DB 态，v0.4） | **账户表唯一源**（ADR-021）：PBKDF2 哈希验证；DB 态首启将配置凭证一次性 seed 物化进表，此后配置凭证失效、表非空永不覆盖 |
| RBAC（DB 态，v1.0） | 预置角色 ADMIN/OPERATOR/VIEWER（§5.2）：`/admin/v1` 认证后判权——401 未认证 / 403 越权，路由逐端点登记、未登记默认 deny；内存态单管理员全权不判权 |
| 关闭认证 | `artemis.management.auth.enabled=false`（仅 eval；关闭后无认证主体，`/admin/v1` 不判权） |
| console（v0.4 只读 → v0.5 管理版） | 外部端口根路径 `/`——内嵌静态 SPA（管理版：服务列表/详情 + 摘除/恢复操作、路由规则与两段发布、订阅关系视图、操作审计流、自我保护态展示），与 Admin API 同一认证 |
| 上游 | registry 只读查询 gRPC（registry 内部 peer 端口；端点**无默认值**，未配置时调用返回 REGISTRY_UNAVAILABLE；内部认证默认关闭，配置了 username 才附加 Basic 认证）；discovery 只读订阅关系查询 HTTP（0..N，见 §4.13） |
| 账户后端不可用 | 认证裁决 fail-closed：401 拒绝（账户 API 503，见 §7 错误语义） |

配置项（SCF，静态）：

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.management.auth.enabled` | `true` | 认证开关 |
| `artemis.management.auth.username` / `password` | `artemis` / `artemis` | 外部 HTTP 凭证 |
| `artemis.management.registry.endpoint` | （无默认值） | registry 内部端口 `host:port`；未配置时 management 正常启动，查询返回 REGISTRY_UNAVAILABLE |
| `artemis.management.registry.username` / `password` | 空 / 空 | 调用 registry 内部端口的凭证（**动态**，可运行期轮换）；username 非空才发送 Basic 认证 |
| `artemis.management.registry.timeoutMs` | `3000` | 单次查询 deadline（1..60000，**动态**，每次调用现取） |
| `artemis.management.discovery.endpoints` | 空 | 逗号分隔 `host:port`（discovery 外部 HTTP 端口，0..N）——订阅关系视图（§4.13）数据源；空 = 无订阅数据（视图自解释），不报错（**静态**） |
| `artemis.management.discovery.auth.username` / `password` | 空 / 空 | 调用 discovery `/admin/*` 的 Basic 凭证（discovery 认证启用时配置；username 非空才发送，**静态**） |
| `artemis.management.discovery.timeoutMs` | `3000` | 单源请求超时（**静态**，启动后不变） |

> SCF 将空白字符串视为「未设置」并回落默认值。registry 内部认证启用（`artemis.registry.internal.auth.enabled=true`）时，须同时配置 management 侧 `artemis.management.registry.username/password` 为内部账户。

## 2. 有效视图语义

- **摘除记录归 management**：四级目标（v0.4 phase-3）——INSTANCE 级键 `(namespace, service, instanceId)`；SERVER / ZONE / GROUP 级为 selector（按实例 metadata 键 `server` / `zone` / `group` 值匹配，全局生效不绑服务；实例不带键不参与该级摘除）。
- **双轨持久化**：未配置 DB 时内存态（重启丢失，期 1 语义）；配置 DB 后记录持久化（`artemis_ejection_record`），management 重启后摘除经规则流快照重下发恢复。
- **操作记录即状态**：下线 = 追加 EJECT 记录（可叠加，各带原因与操作者）；恢复 = 追加 RECOVER 反向记录（FIFO 对消，不删历史）；生效判定 = 每目标 EJECT 与 RECOVER 计数差 > 0。允许摘除当前未注册实例（之后重注册的同一目标仍处于摘除态）。
- 摘除/恢复**不修改 registry** 的注册数据、租约与心跳；registry 查询仍返回完整事实。
- 详情默认只返回**有效实例**（已过滤摘除）；`includeEjected=true` 返回全量并逐条标注（命中标注优先序 INSTANCE > SERVER > ZONE > GROUP）。
- 服务列表 `ejectedCount` 为 INSTANCE 级有效目标计数（selector 级不折算——列表无实例明细，精确视图走详情）。
- **规则下发**（v0.3 phase-6 起，v0.4 四级）：摘除规则经 management 内部 gRPC 端口的**摘除规则流**推送订阅中的 discovery 节点（at-most-once，断流重连快照自愈）——discovery 按实例 metadata 实时匹配四级规则，对查询/推送/对账一致应用过滤。规则流契约见 [ejection-rule-stream-api.md](ejection-rule-stream-api.md)。
- 恢复幂等：无可对消记录时 no-op（204，不追加记录）；完全对消后回到有效视图（下一次查询生效）。

## 3. 通用约定

- JSON（camelCase），`Content-Type: application/json`。
- 实例状态取值：`UP` / `DOWN` / `UNKNOWN`（registry 未设置状态按 `UP` 处理）。
- 时间戳为 ISO-8601 UTC（如 `2026-10-19T10:00:00Z`）。
- 输出排序稳定：服务按 `(namespace, service)`，实例按 `instanceId`。

## 4. 实例管理 Admin API

Base path：`/admin/v1`

### 4.1 服务列表 `GET /admin/v1/services?namespace={ns}&keyword={kw}&offset={n}&limit={n}`

`namespace` 可选；缺省/空白 = 全部。`keyword`/`offset`/`limit` 可选（v0.4 phase-6，console 只读版）：

| 参数 | 语义 |
|---|---|
| `keyword` | 大小写不敏感 substring，命中 `namespace` 或 `service` 任一即保留；缺省/空白 = 不过滤 |
| `offset` | ≥ 0，缺省 0；越界返回空页（不报错） |
| `limit` | 显式传入 clamp 1..1000；缺省/0 = 全量（既有行为不变） |

```json
{
  "total": 1,
  "services": [
    {
      "key": {"namespace": "default", "service": "order-service"},
      "instanceCount": 2,
      "ejectedCount": 1
    }
  ]
}
```

- `total`（v0.4 phase-6 新增）= keyword 过滤后总数；页内容按 `(namespace, service)` 升序排序后过滤再切片，页间稳定不重不漏。
- `instanceCount`：registry 事实计数。
- `ejectedCount`：该 service 当前摘除记录数（原样计数，可能含当前未注册实例）；精确有效计数见详情。

### 4.2 服务详情 `GET /admin/v1/services/{namespace}/{service}?includeEjected={bool}&offset={n}&limit={n}`

`includeEjected` 默认 `false`。`offset`/`limit` 可选（v0.4 phase-6）：作用于**过滤后可见实例集**（摘除过滤先于切片；`limit` 显式传入 clamp 1..1000、缺省/0 = 全量，`offset` 越界为空列表）；`instanceCount`/`ejectedCount`/`effectiveInstanceCount` 始终为全量计数。

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

### 4.3 摘除 `POST /admin/v1/ejections`（v0.4 phase-3，统一四级端点）

四级一套请求体；`scope` 缺省 `INSTANCE`。INSTANCE 级填 `namespace`/`service`/`instanceId`，selector 级填 `value`：

```json
{"scope": "INSTANCE", "namespace": "default", "service": "order-service", "instanceId": "i1", "reason": "maintenance"}
{"scope": "ZONE", "value": "dc-2", "reason": "zone down"}
```

校验：`reason` ≤ 1024、`namespace`/`service` ≤ 255、`instanceId`/`value` ≤ 512 字符；scope 非法或字段组合不符返回 400。不要求实例当前已注册。响应 200：

```json
{
  "scope": "ZONE",
  "namespace": null,
  "service": null,
  "instanceId": null,
  "value": "dc-2",
  "action": "EJECT",
  "reason": "zone down",
  "operator": "artemis",
  "timestamp": "2026-10-25T10:00:00Z",
  "sequence": 42
}
```

叠加：同目标重复摘除追加新记录（各带原因与操作者），目标保持摘除态。

### 4.4 恢复 `POST /admin/v1/ejections/recover`

请求体同 4.3（`reason` 忽略）。追加 RECOVER 反向记录（FIFO 对消最早未对消 EJECT）：

- 成功 204 No Content；幂等（无可对消记录同样 204，不追加）。
- 部分对消（叠加多条 EJECT 逐条恢复）目标仍摘除态；完全对消后回到有效视图。

### 4.5 摘除记录查询 `GET /admin/v1/ejections`

查询参数：`activeOnly`（默认 false，true 只返回生效目标代表集）、`scope`（可选过滤）、`limit`（默认 100，上界 1000）。响应：

```json
{"records": [{"scope": "...", "action": "...", "reason": "...", "operator": "...", "timestamp": "...", "sequence": 1}]}
```

时间倒序（最新在前）；全量列表含 EJECT/RECOVER 全部操作时间线，active 集每目标一条代表记录。

### 4.6 审计查询 `GET /admin/v1/audit-logs`

查询参数：`operation`（可选：`EJECT` / `RECOVER` / `CHANGE_PASSWORD` 等）、`limit`（默认 100，上界 1000）。时间倒序：

```json
{"logs": [{"operation": "EJECT", "target": "zone:dc-2", "operator": "artemis", "result": "SUCCESS", "detail": "zone down", "timestamp": "...", "sequence": 43}]}
```

摘除/恢复在摘除记录表完整留痕（4.5），审计表为统一查询入口的摘要行；改密等其余治理操作全量记录。

### 4.7 逻辑实例 upsert `PUT /admin/v1/logical-instances`（v0.4 phase-4）

请求体（全量覆盖语义；内容与当前一致 → no-op，不写库不推送不审计）：

```json
{
  "namespace": "default",
  "service": "third-party-service",
  "instanceId": "db-proxy-1",
  "host": "10.0.0.1",
  "port": 3306,
  "status": "UP",
  "weight": 100,
  "metadata": {"protocol": "mysql", "url": "jdbc://..."}
}
```

- 响应 = 落库后视图（同构 JSON）。`status` 缺省 `UP`（只接受 UP/DOWN）；`weight` ≤ 0 或缺省归一 100；`metadata` 值不可为 null、序列化后 ≤ 16KB。
- 校验上限：`namespace`/`service`/`host` ≤ 255，`instanceId` ≤ **255**（与 V4 唯一索引键长约束对齐，区别于摘除目标的 512——逻辑实例 id 为运维命名非 SDK 生成），`port` ∈ 1..65535。
- 逻辑实例无心跳无租约（静态事实，不参与 registry 租约/TTL/自我保护）；经专属订阅流下发、discovery 以保留 cluster `logical` 进统一发现视图（[logical-instance-stream-api.md](logical-instance-stream-api.md)）；四级摘除对其同样生效。

### 4.8 删除逻辑实例 `DELETE /admin/v1/logical-instances?namespace=&service=&instanceId=`

幂等：不存在时 204 no-op。命中删除即经流推送增量（discovery 订阅方收 DELETE）。

### 4.9 逻辑实例列表 `GET /admin/v1/logical-instances?namespace=&service=&limit=`

过滤可选（空白 = 不过滤），按 `(namespace, service, instanceId)` 升序；`limit` 默认 100、上界 1000。

### 4.10 路由规则 CRUD `POST/GET/PUT/DELETE /admin/v1/route-rules`（v0.5 phase-1）

资源导向 CRUD（分组路由 / canary / 两段发布的规则载体，架构 §5.2；分组以实例 metadata 表达，ADR-13）。请求体（创建；PUT 同构，`namespace`/`service`/`name` 必须与路径一致）。**targets 只收编辑列 `unreleasedWeight`——生效列 `weight` 不在请求体**（两段式：create 初始生效列 0、update 按 selector 等值继承旧生效列、release/rollback 是生效列唯一写入口，见 §4.11）：

```json
{
  "namespace": "default",
  "service": "order-service",
  "name": "beta-release",
  "status": "ACTIVE",
  "targets": [
    {"selector": {"group": "beta"}, "unreleasedWeight": 80},
    {"selector": {}, "unreleasedWeight": 20}
  ]
}
```

| 端点 | 语义 |
|---|---|
| `POST /admin/v1/route-rules` | 创建（insert-if-absent），201 返回完整视图；同名已存在 → 409 |
| `GET /admin/v1/route-rules?namespace=&service=&limit=` | 列表，过滤可选，`(namespace, service, name)` 升序，`limit` 默认 100 上界 1000 |
| `GET /admin/v1/route-rules/{namespace}/{service}/{name}` | 单条查询；缺失 → 404 |
| `PUT /admin/v1/route-rules/{namespace}/{service}/{name}` | 全量替换（createdAt 继承、updatedAt 刷新）；缺失 → 404；body 键与路径不一致 → 400 |
| `DELETE /admin/v1/route-rules/{namespace}/{service}/{name}` | 删除，幂等 204（缺失 no-op，不审计） |

- **模型**：目标 = metadata **等值选择器**（多键 AND；空 `selector` = 兜底目标，承接默认组语义）+ 双权重列——`weight` 生效 / `unreleasedWeight` 编辑中（两段式发布，§4.11 release 拷贝）；`status` ACTIVE（缺省）/INACTIVE（discovery 不应用，执行语义 phase-3）。
- **域约束（400）**：`name` ≤255 且 `[A-Za-z0-9._-]+`（URL 路径段安全）；`namespace`/`service` ≤255；`targets` 1–10 条；selector 键 1–8 个、键 ≤128、值非空 ≤512；权重 0–10000；targets 序列化 UTF-8 ≤ 60KB。目标间 selector 命中集合可重叠（消费侧语义），**等值重复拒绝**（同规则内两 target selector 完全相等 → 400，保证 update 生效列继承的唯一匹配）。
- 每条变更全量进审计表（operation = `CREATE_ROUTE_RULE` / `UPDATE_ROUTE_RULE` / `DELETE_ROUTE_RULE`，target = `route-rule:{ns}/{svc}/{name}`）。
- 响应视图含双列权重（生效列 + 编辑列），`operator` 取认证用户名。

### 4.11 两段式发布 `POST .../release` / `POST .../rollback` / `GET .../diff` / `GET .../releases`（v0.5 phase-2）

发布语义（继承资产 §5-5，架构 §5.2 资源 `release`）：编辑写 `unreleasedWeight`，一个 release 调用完成一个运维意图（拷贝到 `weight` 生效）；回滚 = 指向历史版本的反向 release。

| 端点 | 语义 |
|---|---|
| `POST /admin/v1/route-rules/{ns}/{svc}/{name}/release` | 发布：targets 逐项 `weight ← unreleasedWeight`，200 返回发布后完整视图（两列相等）；落 append-only 发布记录并推规则流 |
| `POST /admin/v1/route-rules/{ns}/{svc}/{name}/rollback` | 一键回滚：body `{"releaseId": 5}`，`weight` 与 `unreleasedWeight` **两列写回**源记录快照（完全恢复该发布时刻状态）；200 返回回滚后视图 |
| `GET /admin/v1/route-rules/{ns}/{svc}/{name}/diff?releaseId=` | 发布差异预览：基 = **发布快照生效列**（缺省取最新发布记录，从未发布 = 空基全量新增；带 `releaseId` 取该历史记录 = 回滚预览），next = 当前编辑列；selector 等值对齐并集——`currentWeight: null` = 新增、`nextWeight: null` = 将被移除 |
| `GET /admin/v1/route-rules/{ns}/{svc}/{name}/releases?limit=` | 发布记录列表（回滚历史源）：`{id, type, rollbackTo, snapshot, operator, releasedAt}`，id 降序最新在前 |

```json
// diff 响应
{
  "namespace": "default", "service": "order-service", "name": "beta-release", "baseReleaseId": null,
  "targets": [
    {"selector": {"group": "beta"}, "currentWeight": 20, "nextWeight": 30},
    {"selector": {"group": "canary"}, "currentWeight": null, "nextWeight": 10},
    {"selector": {}, "currentWeight": 80, "nextWeight": null}
  ]
}
```

- 发布记录 `type` = `RELEASE` / `ROLLBACK`（`rollbackTo` 指向源记录 id）；记录 append-only：规则删除不级联删（审计语义），内存态与规则同清零、DB 态持久（重启后回滚仍可用）。
- 两写边界：规则表生效优先于发布记录——append 失败（503）时规则已生效且流已通知，重试 release 幂等补录记录。
- 审计：`RELEASE_ROUTE_RULE` / `ROLLBACK_ROUTE_RULE` 摘要行（detail 含发布后权重序列与 `releaseId`/`rollbackTo` 关联）。
- 规则流下发（ACTIVE 集、版本化 + ack）与 discovery 生效水位见 §6.5；discovery 执行（Group filter selector 过滤与视图扇出）已于 v0.5 phase-3 交付。

### 4.12 canary 一键部署 `POST /admin/v1/canary/{ns}/{svc}` / `DELETE /admin/v1/canary/{ns}/{svc}`（v0.5 phase-4）

canary = route-rule + release 的预置派生（架构 §5.2「由 management 生成专属规则组并下发，不新增独立资源类型」）：调用方只声明金丝雀实例圈定与承接权重，一个 API 完成部署（派生保留名 `canary` 规则 + 发布记录 + 规则流下发）。

| 端点 | 语义 |
|---|---|
| `POST /admin/v1/canary/{namespace}/{service}` | 一键部署：body `{"selector": {...}, "weight": 1000}`——selector（非空 metadata 等值）圈金丝雀实例、weight（0–10000）承接权重；派生规则 targets = canary 目标（`weight = unreleasedWeight = weight`）+ 兜底目标（`10000 - weight`，两列一致即已发布态）；不存在则创建、存在则全量替换（重圈定时旧组回归兜底承接）；每次部署落一条 `RELEASE` 记录（既有 rollback / releases / diff 对 canary 规则直接可用）。200 返回派生后完整规则视图 |
| `DELETE /admin/v1/canary/{namespace}/{service}` | 结束灰度：删除 canary 规则 = 无治理意图 = 全实例回归可见；幂等（缺失 204 no-op）；发布记录 append-only 保留 |

- **weight 运维语义**（配 phase-3 二值承接）：0 = 金丝雀实例摘出视图（dark launch）；1–9999 = 两组均可见，比例意图（weight : 10000-weight）由调用方消费；10000 = 稳定组摘出（全量切流）。
- **保留名 `canary`**：CRUD 通道 create/update 拒绝（400 `INVALID_ARGUMENT`，"name is reserved: canary"）；GET / DELETE / release / rollback 等对保留名规则不特判（规则是规则）。
- 每服务一组 canary；多组灰度 / 复杂圈定用 route-rule 手工用法。与服务既有手工规则叠加时多规则 AND（phase-3 语义）。
- 回滚到上一权重：复用 `POST /admin/v1/route-rules/{ns}/{svc}/canary/rollback`（§4.11）；查询 / 历史 / 预览同理经既有 route-rule API。
- 审计：`DEPLOY_CANARY` / `DELETE_CANARY` 摘要行（target = `route-rule:{ns}/{svc}/canary`，detail 含 selector 摘要与 weight、releaseId）。

### 4.13 订阅关系查询 `GET /admin/v1/subscriptions`（v0.5 phase-5）

console 订阅关系视图（调用方视角）数据源：management 拉取所配 discovery 节点（`artemis.management.discovery.endpoints`，0..N）的活跃订阅快照（[discovery 发现管理 API](discovery-admin-api.md) §3），组合输出：

```json
{
  "sources": [
    {
      "endpoint": "127.0.0.1:8090",
      "error": null,
      "subscriptions": [
        {
          "id": 7,
          "subscriber": "/127.0.0.1:54321",
          "establishedAt": "2026-10-27T10:00:00Z",
          "services": [{"namespace": "default", "service": "order-service"}],
          "labels": {"group": "a"},
          "clusters": ["cluster-a"]
        }
      ]
    }
  ]
}
```

- **逐源并列、不跨源合并去重**：discovery 节点互为独立投影副本（架构 §2.1），同一调用方可对多节点各持一条流，去重无事实依据；`endpoint` 列即排障维度。
- **单源失败不拖垮视图**（per-source fail-open）：某源不可达/超时/认证失败时该源 `error` 置可读信息、`subscriptions` 为空数组，其余源照常返回，整体仍 200——只读排障面语义（与 discovery `/admin/v1` 免 readiness 门控哲学一致）。
- 订阅条目字段与 discovery 侧输出同构（`subscriber` 为 gRPC 流远端地址——传输层调用方标识，账户体系升级后置，见 discovery-admin-api §3）；`services`/`clusters` 空数组 = 全部语义同 discovery 选择器。
- 排序稳定：源按配置序、订阅按 `id` 升序。
- 未配置 endpoints 时返回 `{"sources": []}`（不报错）。
- 该端点为只读查询，无审计。

## 5. 账户与用户管理 API（v0.4 ADR-021 → v1.0 RBAC）

DB 态账户表唯一认证源；v1.0 起多账户 + 预置角色 RBAC。内存态凭证事实源是配置（单管理员全权），本节端点均返回 409 `NOT_SUPPORTED_IN_MEMORY_MODE`。

### 5.1 自助改密 `PUT /admin/v1/account/password`

请求体（任何已认证用户可改**自己**密码——以认证主体定位账户，不进权限矩阵）：

```json
{"oldPassword": "artemis", "newPassword": "new-s3cret-9"}
```

- 成功 200 `{"username": "artemis"}`；**旧凭证即时失效**（新凭证即用）。
- `newPassword` 最少 8 字符（seed 的默认密码 7 字符属豁免——默认凭证本就要求立即改）；`oldPassword` 不匹配拒绝，均 400；认证关闭时无主体可定位，400。
- 内存态 409 `{"error": "NOT_SUPPORTED_IN_MEMORY_MODE", ...}`。
- 改密成功进审计（`CHANGE_PASSWORD`，尽力而为）。

### 5.2 预置角色与权限矩阵（v1.0 phase-2）

角色→权限矩阵为**代码常量**（产品语义，无自定义角色）；账户→角色映射持久化于账户表 `role` 列（V7 迁移，存量与 seed 账户均 ADMIN）。

| Permission | ADMIN | OPERATOR | VIEWER | 覆盖端点（method + path） |
|---|---|---|---|---|
| `service:read` | ✓ | ✓ | ✓ | GET `/admin/v1/services/**`、`/admin/v1/subscriptions`、`/admin/v1/status/**` |
| `ejection:read` | ✓ | ✓ | ✓ | GET `/admin/v1/ejections` |
| `ejection:write` | ✓ | ✓ | — | POST `/admin/v1/ejections`、`/admin/v1/ejections/recover` |
| `route-rule:read` | ✓ | ✓ | ✓ | GET `/admin/v1/route-rules/**` |
| `route-rule:write` | ✓ | ✓ | — | POST/PUT/DELETE `/admin/v1/route-rules/**`（含 release/rollback）、POST/DELETE `/admin/v1/canary/**` |
| `logical-instance:read` | ✓ | ✓ | ✓ | GET `/admin/v1/logical-instances` |
| `logical-instance:write` | ✓ | ✓ | — | PUT/DELETE `/admin/v1/logical-instances` |
| `audit:read` | ✓ | ✓ | — | GET `/admin/v1/audit-logs` |
| `user:manage` | ✓ | — | — | `/admin/v1/users/**`、GET `/admin/v1/roles` |

资源归并语义：release/rollback/canary 是 route-rule 的生命周期操作/预置派生用法，权限域不拆；订阅视图与排障面是服务事实读视图，归 `service:read`。enforcement 于 `/admin/v1` 认证后判权：**未登记路径默认 deny**（403 fail-closed）；认证显式关闭时不判权（eval 语义）。

### 5.3 用户管理（全部 `user:manage`，即仅 ADMIN）

| 端点 | 语义 | 响应/错误 |
|---|---|---|
| `GET /admin/v1/users` | 账户列表（username/role/createdAt/updatedAt，**不含密码哈希**） | 200 `{"users":[...]}` |
| `POST /admin/v1/users` | 建户 `{"username","password","role"}` | 200；400 参数非法（username 空白/>64/含 `/`、password <8、role 未知）；409 `ACCOUNT_ALREADY_EXISTS` |
| `PUT /admin/v1/users/{username}/role` | 改角色 `{"role"}` | 200；404 `ACCOUNT_NOT_FOUND`；409 `LAST_ADMIN_REQUIRED`（最后 ADMIN 不可降级） |
| `PUT /admin/v1/users/{username}/password` | admin 重置密码 `{"newPassword"}` | 200；404；重置后旧凭证即时失效 |
| `DELETE /admin/v1/users/{username}` | 删户 | 200；404；409 `SELF_DELETE`（不可删自己）/ `LAST_ADMIN_REQUIRED` |
| `GET /admin/v1/roles` | 预置角色列表 | 200 `{"roles":["ADMIN","OPERATOR","VIEWER"]}` |

建户/改角色/重置密码/删户全量进审计（`CREATE_USER` / `UPDATE_USER_ROLE` / `RESET_USER_PASSWORD` / `DELETE_USER`，operator = 认证用户，尽力而为）；403 拒绝不进审计表（审计是治理操作记录，非安全事件日志）。

## 6. 状态 API（registry 排障面）

数据直接来自 registry 只读查询，不缓存、不聚合；敏感配置已在 registry 侧脱敏。

### 6.1 租约 `GET /admin/v1/status/leases?namespace={ns}&service={svc}`

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

### 6.2 配置 `GET /admin/v1/status/config`

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

### 6.3 自我保护 `GET /admin/v1/status/protection?namespace={ns}&service={svc}&includeNormal={bool}`

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

### 6.4 持久化状态 `GET /admin/v1/status/persistence`

management 自身持久化运行形态（v0.4 phase-1 起，不透传 registry）。

```json
{
  "mode": "DATABASE",
  "databaseProduct": "PostgreSQL",
  "schemaVersion": "1"
}
```

- `mode`：`MEMORY`（未配置 `spring.datasource.url`，内存态运行）或 `DATABASE`（已配置，HikariCP + Flyway 启用）。
- `databaseProduct`：JDBC 元数据产品名（如 `MySQL` / `PostgreSQL`）；MEMORY 态为 `null`。
- `schemaVersion`：最新已应用 Flyway migration 版本（字符串原样，如 `"1"`）；`spring.flyway.enabled=false` 或 history 不可读时为 `null`。
- 配置了 DB 时业务仍为内存实现（v0.4 过渡期语义，持久化逐表接管中）。

### 6.5 规则生效水位 `GET /admin/v1/status/rules`（v0.4 phase-5）

规则版本 + discovery ack 生效闭环的观测出口（哪个版本、多少节点已生效）。

```json
{
  "currentVersion": 3,
  "appliedLatestCount": 1,
  "subscribers": [
    {
      "subscriberId": "artemis-discovery@host-a-1a2b3c4d",
      "appliedVersion": 3,
      "lastAckAt": "2026-10-26T10:00:00Z",
      "atLatest": true
    }
  ],
  "routeRules": {
    "currentVersion": 5,
    "appliedLatestCount": 1,
    "subscribers": [
      {"subscriberId": "artemis-discovery@host-b-5e6f7a8b", "appliedVersion": 5, "lastAckAt": "2026-10-26T10:01:00Z", "atLatest": true}
    ]
  }
}
```

- `currentVersion`：当前摘除规则集状态版本 = 最后一次生效态翻转的记录 sequence（查询时重放派生；无记录 = 0）。
- `routeRules`：路由规则流水位节（v0.5 phase-2，与外层同构；`currentVersion` = 该流 dispatcher tick——**独立版本域**，与摘除流数值不可比；release/rollback 与 ACTIVE 集变更均推进版本）。
- `subscribers`：**活订阅**水位（断流即清除条目——断连节点生效态未知不冒充已生效）。`appliedVersion` 为 `null` = 已订阅尚未 ack（快照在途）；`atLatest` = 已应用当前版本。
- `appliedLatestCount`：已应用当前版本的订阅方数（「多少节点已生效」）。
- 水位为内存观测态不持久化：management 重启后经 discovery 重连快照 + 重新 ack 重建；内存态 management 重启版本归零（快照全量替换语义下无害）。

## 7. 错误语义

错误体：

```json
{"error": "REGISTRY_UNAVAILABLE", "message": "registry unavailable: ..."}
```

| HTTP | error | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 参数空白、scope 非法或字段组合不符、字段超长（`reason` > 1024 / `namespace`·`service` > 255 / `instanceId`·`value` > 512；逻辑实例 `instanceId` > 255、`host` > 255、metadata 值为 null 或序列化 > 16KB）、body 不可解析、参数类型错误、改密参数非法（old 不匹配 / newPassword < 8 字符） |
| 401 | `UNAUTHORIZED` | Basic 缺失或错误 |
| 403 | `FORBIDDEN` | 已认证但角色无该端点权限，或路径未登记（默认 deny，v1.0 RBAC） |
| 404 | `NOT_FOUND` | 未知路径 |
| 404 | `ROUTE_RULE_NOT_FOUND` | 路由规则查询/替换/发布缺失（v0.5 phase-1） |
| 404 | `ROUTE_RULE_RELEASE_NOT_FOUND` | 回滚源发布记录缺失或属于其它规则（v0.5 phase-2） |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持 |
| 404 | `ACCOUNT_NOT_FOUND` | 用户管理目标账户不存在（v1.0） |
| 409 | `NOT_SUPPORTED_IN_MEMORY_MODE` | 内存态调用改密/用户管理端点（凭证事实源是配置，v0.4 ADR-021；v1.0 用户管理） |
| 409 | `ACCOUNT_ALREADY_EXISTS` | 建户重名（含并发竞态 DB 唯一键兜底，v1.0） |
| 409 | `LAST_ADMIN_REQUIRED` | 降级/删除最后 ADMIN 账户（v1.0） |
| 409 | `SELF_DELETE` | 删除当前认证账户自身（v1.0） |
| 409 | `ROUTE_RULE_ALREADY_EXISTS` | 路由规则创建重名（v0.5 phase-1） |
| 502 | `REGISTRY_ERROR` | registry 返回 `ACK_FAIL` 或其它 gRPC 错误（含响应超过 16 MiB 入站上限） |
| 503 | `REGISTRY_UNAVAILABLE` | registry `UNAVAILABLE` / `DEADLINE_EXCEEDED` |
| 503 | `ACCOUNT_STORE_UNAVAILABLE` | 账户表访问失败（DB 态 fail-closed，v0.4 ADR-021） |
| 503 | `EJECTION_STORE_UNAVAILABLE` | 摘除/审计存储访问失败（DB 态，操作未落库可重试） |
| 503 | `LOGICAL_INSTANCE_STORE_UNAVAILABLE` | 逻辑实例存储访问失败（DB 态，v0.4 phase-4） |
| 503 | `ROUTE_RULE_STORE_UNAVAILABLE` | 路由规则存储访问失败（DB 态，v0.5 phase-1） |
| 500 | `INTERNAL` | 未预期异常（堆栈只进日志） |

management 启动不依赖 registry 可用；registry 故障时管理面仍可启动，查询类 API 返回 503。

## 8. 已知限制

- 摘除四级与审计已交付（v0.4 phase-3）；逻辑实例已交付（v0.4 phase-4，management 侧服务视图合并逻辑实例归 phase-6 console）；路由规则 CRUD 与持久化已交付（v0.5 phase-1）；两段式发布（release/rollback/diff/releases）与规则流下发 + ack 水位已交付（v0.5 phase-2）；分组路由执行（Group filter selector 过滤与视图扇出，三出口一致）已交付（v0.5 phase-3）；canary 一键部署/结束已交付（v0.5 phase-4）；console 管理版（发布/摘除操作、订阅关系视图）归 v0.5 phase-5；内存态（未配置 DB）仍重启丢失（期 1 设计内行为，配置 DB 即持久化——重启后经流快照重下发恢复）；审批流随 v1.x 按社区需求排期。
- RBAC 已交付（v1.0 phase-2，§5.2 三预置角色）。限制：无自定义角色/权限矩阵（代码常量，真实需求再评估）；内存态单管理员无多账户（用户管理 409）；console 暂无用户管理页面（API 先行）；最后 ADMIN 保护为 check-then-act，并发降级/删除（尤其多副本）可绕过——锁死恢复路径 = DBA 直接 SQL 更新 `role` 列；认证缓存回填/失效存在小竞态，凭证或角色变更后旧值最长残留 60s（TTL 有界）。
- registry 地址单值、无重试/熔断（v0.2 集群后引入地址列表与容灾）。
- registry 查询无分页；客户端入站上限 16 MiB，超限返回 502。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.16 | 2026-10-27 | v1.0 phase-2：§5 重写为多账户 + RBAC（§5.2 权限矩阵、§5.3 用户管理端点）；§1 加 RBAC 行；§7 补 403/404/409 账户管理错误码；§8 已知限制更新 |
| 1.15 | 2026-10-27 | v0.5 phase-5：新增 §4.13 订阅关系查询（discovery 多源组合 + per-source fail-open）；§1 console 行升管理版、上游补 discovery 只读通道；配置表补 `artemis.management.discovery.*` 三键 |
| 1.14 | 2026-10-27 | v0.5 phase-4：新增 §4.12 canary 一键部署/结束两端点（派生语义、weight 视图、保留名、rollback 复用）；§4.11 尾注与 §8 边界更新（phase-3 执行已交付、phase-4 canary 已交付，下一边界 phase-5 console） |
| ------ | ------ | ------ |
| 1.13 | 2026-10-26 | v0.5 phase-2：§4.10 请求体删 weight（编辑通道收紧，等值重复 400）；新增 §4.11 两段式发布四端点（release/rollback/diff/releases）；§6.5 补 routeRules 水位节；错误表补 `ROUTE_RULE_RELEASE_NOT_FOUND`；§8 边界更新 |
| 1.12 | 2026-10-26 | v0.5 phase-1：新增路由规则 CRUD（§4.10 五端点，等值选择器 + 两段双权重 + 域约束与审计语义）；错误表补 404/409/503 route-rule 三码；§8 限制补 phase-2/3 边界 |
| 1.11 | 2026-10-26 | v0.4 phase-6：console 只读版——§4.1 服务列表增 `keyword`/`offset`/`limit` 与响应 `total`；§4.2 详情增实例分页（计数始终全量）；§1 登记 console 入口 |
| 1.10 | 2026-10-26 | v0.4 phase-5：新增 §6.5 `GET /admin/v1/status/rules` 规则生效水位（当前版本 + 活订阅水位 + 已生效节点数） |
| 1.9 | 2026-10-25 | v0.4 phase-4：新增逻辑实例 API（§4.7–4.9 upsert/delete/list，幂等语义与 255 校验上限）；错误表补 `LOGICAL_INSTANCE_STORE_UNAVAILABLE` |
| 1.8 | 2026-10-25 | v0.4 phase-3：统一四级 ejection API（替换 v0.1 实例级端点）、记录/审计查询、双轨持久化与操作记录即状态语义、503 `EJECTION_STORE_UNAVAILABLE` |
| 1.7 | 2026-10-25 | v0.4 phase-2：新增账户 API（§5 改密端点，DB 态闭环）；§1 认证双态语义（DB 态账户表唯一源 + seed 物化，ADR-021）；错误表补 409/账户 503；状态 API 重编号为 §6 |
| 1.6 | 2026-10-25 | v0.4 phase-1：新增 `GET /admin/v1/status/persistence`（DB 双轨运行形态与 schema 版本） |
| 1.5 | 2026-10-24 | v0.3 phase-6：摘除/恢复语义补规则流下发说明（discovery 侧过滤生效） |
| 1.4 | 2026-10-21 | v0.2 phase-4：新增 `GET /admin/v1/status/protection`（按服务保护态与节点级统计） |
| 1.3 | 2026-10-20 | v0.2：上游单一 endpoint（无默认值）；凭证与 timeout 动态化 |
| 1.2 | 2026-10-20 | v0.2：上游地址/端口分离（已废弃，回归单一 endpoint） |
| 1.1 | 2026-10-20 | v0.2：上游 registry 只读查询改连内部 peer 端口 |
| 1.0 | 2026-10-19 | 初版：实例管理 Admin API、状态 API、有效视图语义、认证配置与错误语义 |
