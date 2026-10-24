# Phase 7：discovery 发现管理 API

版本: 1.3    更新时间: 2026-10-24

## 目标

交付治理期 2 的发现管理面（只读）：订阅关系查看（调用方视角）、服务视图查询、上游状态/新鲜度查询，落 discovery 外部端口。

## 范围

做什么（据 plan phase-7 说明）：

- 发现管理只读 API：订阅关系查看、服务视图查询、上游状态/新鲜度查询。
- 契约形态与端口落点随 ③ 设计定稿。

不做什么：

- 消费方 SDK（phase-8）、starter/all-in-one（phase-9）。
- 管理操作（发现管理面只读；写操作归 management 治理面）。
- 订阅历史/审计、分页与模糊搜索（console 交付时再定，v0.3 只做当前态全量快照）。
- 摘除规则内容查看与「未过滤事实」查看（摘除记录归 management Admin API；discovery 只出**有效发现视图**）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `proto/` | **无变更**（管理面读本地投影，走既有 HTTP 外部端口，不新增 wire 契约） |
| `java/server/artemis-discovery-core` | 订阅登记表扩展（调用方标识/时间）、上游与规则源状态模型、管理查询用例 |
| `java/server/artemis-discovery-server` | HTTP Admin 端点 + Basic 认证过滤器 + 调用方地址捕获拦截器 + 装配 |

## 验收方式

对应测试文档：[phase-7-discovery-admin-api-test.md](phase-7-discovery-admin-api-test.md)

## 产品设计（③ 补）

### 1. 现状与缺口

- discovery 已建成投影（phase-2）、订阅推送（phase-3）、对账/新鲜度（phase-4）、多集群（phase-5）、摘除过滤（phase-6），但对外只有数据面 gRPC（查询/订阅/对账），**无管理/排障出口**。
- `SubscriptionManager` 只暴露 `size()`，不记录订阅方与选择器，无法回答「谁在订阅什么」（原产品局限 #18「无订阅关系/调用方视图」，治理期 2 修复项）。
- `EjectionRuleSynchronizer` 无状态出口；phase-6 D-E9 已定案「规则源状态出口归 phase-7 发现管理 API 范畴」。
- 上游新鲜度目前只搭在查询/对账响应的数据面上；运维需要一个独立的当前态视图。

### 2. 形态定案（D-A1/D-A7）

| 项 | 定案 |
|---|---|
| 协议/端口 | **HTTP**，discovery 外部 HTTP 端口（`server.port`，默认 8090），base path `/admin/v1`；资源导向只读 GET |
| 为何不用 gRPC | 外部 gRPC 端口是 **SDK 数据面**语义（查询/订阅/对账）；管理/console 面与 management Admin API 同形态（架构 §2.2「console / Admin API = HTTP」），JSON 直接可被 console 消费 |
| 认证 | 复用 discovery 外部账户 `artemis.discovery.auth.*`（Basic，默认关闭，D-D10 不变），新 HTTP 过滤器只作用于 `/admin/*`；gRPC 拦截器与 HTTP 过滤器共享同一 `AuthConfig` |
| 就绪门控 | **不门控**（D-A4）：启动期也要能排障；未首轮同步时返回空视图 + `upstreams.state=SYNCING`，新鲜度字段自解释——与数据面「未就绪 UNAVAILABLE、空数据不作真相」的取舍不同（数据面回答调用方，管理面回答运维） |
| wire 影响 | 无 proto 变更，无兼容性影响 |

### 3. 接口（只读）

通用约定（对齐 management Admin API）：JSON camelCase；输出排序稳定；错误语义 `400 INVALID_ARGUMENT` / `401 UNAUTHORIZED` / `404 NOT_FOUND` / `500 INTERNAL`（管理面读本地内存投影，无下游依赖，故无 503）。

#### 3.1 订阅关系 `GET /admin/v1/subscriptions`

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
- `subscriber` = gRPC 流连接的远端地址（调用方标识，见 §4）；`establishedAt` = 订阅登记时刻（UTC）。
- 只含活跃订阅：流取消/断流/服务端断流后即消失，**不保留历史**（无有界增长风险；历史/审计不在 v0.3）。

#### 3.2 服务视图列表 `GET /admin/v1/services?namespace={ns}&cluster={cluster}`

两个参数均可选；缺省/空白 = 全部。按 `(cluster, namespace, service)` 升序：

```json
{
  "services": [
    {"cluster": "cluster-a", "namespace": "default", "service": "order-service", "instanceCount": 2}
  ]
}
```

- 口径 = **有效发现视图**（摘除过滤链后，与数据面查询/推送一致，D-A6）；只含 ≥1 可见实例的条目。
- 响应行粒度 = `(cluster, service)`（多集群不合并：来源 cluster 是排障维度）。

#### 3.3 服务视图详情 `GET /admin/v1/services/{namespace}/{service}`

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

- 逐来源 cluster 返回可见实例，cluster 按名升序、实例按 `instanceId` 升序。
- 未注册/全部实例被过滤 → `200` + `clusters: []`（查询语义，非 404）；路径参数空白 → `400`。

#### 3.4 上游状态/新鲜度 `GET /admin/v1/upstreams`

```json
{
  "upstreams": [
    {"cluster": "cluster-a", "state": "SYNCED"}
  ],
  "management": {"enabled": true, "state": "SYNCED"}
}
```

- `upstreams` = 各 registry 来源的同步状态（`SYNCING` / `SYNCED` / `DISCONNECTED`，语义同数据面新鲜度：非 `SYNCED` 即投影可能陈旧）。
- `management` = 摘除规则源状态（phase-6 D-E9 落点）：未配置 management 时 `enabled=false, state=DISABLED`；配置后 `SYNCING` / `SYNCED` / `DISCONNECTED`（断连时最后已知规则仍生效，D-E3）。

### 4. 订阅关系模型（D-A2/D-A3）

```java
/** 活跃订阅快照条目（调用方视角）。 */
public record SubscriptionInfo(long id, DiscoverySelector selector, String subscriber, Instant establishedAt) {}
```

- `SubscriptionManager` 登记时分配进程内单调递增 `id`（`AtomicLong`），记录远端地址与登记时刻（注入 `Clock`）；关闭即从登记表移除（既有语义），快照弱一致迭代、按 `id` 排序。
- **调用方标识 = gRPC 传输层远端地址**（`ip:port`）：当前认证是单账户，没有可靠的 per-caller 身份；客户端自报 id 不可校验，不作为事实。地址由 server 侧 `ServerInterceptor` 从 `Grpc.TRANSPORT_ATTR_REMOTE_ADDR` 取出，经 gRPC `Context` 传给订阅用例；取不到时记 `unknown`。
  - `ponytail:` 单账户下的最佳可用标识；v0.4 账户体系（RBAC）接管后升级为账户/主体标识。
- 选择器在订阅边界已校验；快照直出不做二次校验。

### 5. 服务视图口径（D-A6）

- 列表与详情都经 `DiscoveryFilterChain` 过滤（与查询/推送/对账四出口同语义），即「调用方此刻实际能发现什么」。
- 不复用 gRPC `Query` 的 labels 维度：管理面按 namespace/cluster 浏览，不构造任意 labels 选择器。
- 实现复用 `ProjectionRegistry.query(selector, chain)`；列表为全量扫描 + 聚合，`ponytail:` 10 万服务级全量响应/扫描，console 分页与搜索随 v0.4 控制台交付，届时改增量索引。
- 投影存储仍持有 registry 事实（未过滤，D-E2），管理面**不**出「事实/摘除对照」——那是 management 的有效视图职责（ADR-016）。

### 6. 规则源状态（D-A5）

```java
public enum ManagementState { DISABLED, SYNCING, SYNCED, DISCONNECTED }

/** 规则源状态持有者：同步引擎单写、管理查询无锁读（volatile）。 */
public final class ManagementStatus {
    public ManagementState state();
    public void update(ManagementState state);
}
```

状态转移（`EjectionRuleSynchronizer` 上报，语义对齐 `UpstreamState`）：

| 转移 | 时机 |
|---|---|
| `DISABLED` | 未配置 management（0..1），同步器 `disable()` |
| `SYNCING` | 同步轮开始（含曾同步后的重连轮次） |
| `SYNCED` | 收到首条快照并应用成功 |
| `DISCONNECTED` | 已同步过，流断开/轮次失败，等待重连（规则冻结、最后已知规则继续生效） |

### 7. 装配与实现落点

| 模块/层 | 内容 |
|---|---|
| discovery-core `domain` | `SubscriptionInfo`、`ManagementState`、`ManagementStatus`、`ServiceView`；`SubscriptionManager` 登记扩展与快照方法 |
| discovery-core `application` | `DiscoveryAdminService`：订阅/服务视图/上游状态三个只读用例（参数校验在信任边界一次） |
| discovery-core `infrastructure` | `EjectionRuleSynchronizer` 上报 `ManagementStatus` 转移 |
| discovery-server `http` | `AdminController`（`/admin/v1/*`，一行委派 `DiscoveryAdminService`）、`DiscoveryExceptionHandler`、`HttpBasicAuthFilter`（仅 `/admin/*`，白名单 `/error`） |
| discovery-server `grpc` | `RemoteAddressInterceptor`（`Context` key 传远端地址）+ `DiscoverySubscriptionGrpcService` 取用 |
| discovery-server 装配 | `DiscoveryAdminService`/`ManagementStatus`/过滤器/拦截器 bean；无新增配置键 |

### 8. 定案汇总

| # | 定案 | 触及的原始文档与同步落点 |
|---|---|---|
| D-A1 | 发现管理面 = discovery 外部 HTTP 端口上的只读 `/admin/v1` JSON API；gRPC 外部端口保持 SDK 数据面语义 | 架构 §2.2「v0.3 端口落点」补 discovery HTTP Admin API 一行（⑩ 执行；与 phase-6 D-E11 同型的新增落点） |
| D-A2 | 调用方标识 = gRPC 远端地址（单账户下唯一可靠标识），经拦截器 + `Context` 注入 | 无既有文档定义，phase 文档为单一来源；API 文档记载 |
| D-A3 | 订阅关系只保留**活跃**订阅：登记时分配 id、记录远端地址与时刻，关闭即移除；无历史/审计 | 无既有文档定义；架构 §5.3 的「订阅关系视图（调用方视角）」在本 phase 的 v0.3 最小实现，审计归 v0.4 期 3 |
| D-A4 | 管理 API **不做 readiness 门控**：启动期可排障，未同步用 `upstreams.state` 自解释 | 与架构 §4.2 数据面「未就绪 UNAVAILABLE」不冲突（数据面 vs 管理面）；API 文档记载 |
| D-A5 | 规则源状态出口落在 `/admin/v1/upstreams.management`（`ManagementState`） | phase-6 D-E9 已登记本 phase 范畴；API 文档记载 |
| D-A6 | 服务视图 = **过滤后有效视图**（摘除链生效），不出未过滤事实 | 架构 §4.2 过滤器链「查询/推送/对账一致口径」的第五出口；phase-6 D-E1 语义延伸；API 文档记载 |
| D-A7 | 认证复用 `artemis.discovery.auth.*`（默认关闭不变），HTTP 过滤器仅覆盖 `/admin/*` | 架构 §6「认证 v0.1 起默认开启」与 discovery D-D10 的既有差异延续；API 文档记载 |
| D-A8 | 无 proto/wire 变更；无新增配置键 | proto-contract 无变更 |

### 架构同步声明

本 phase 是治理期 2（架构 §5.1 期 2 行：发现管理＝订阅关系/服务视图/新鲜度，无状态）的落地，**不改动已记载机制**：只新增外部 HTTP 管理端点与只读查询用例。同步动作：⑩ 在架构 §2.2「v0.3 端口落点」补 discovery HTTP Admin API 一行（D-A1）。无 ADR：只读、无持久化、无跨服务协议变更，未引入新的重要取舍（HTTP 形态与 management Admin API 一致）。proto 契约零改动。

## 实现结果（⑦ 补）

### 实现要点

- **discovery-core domain**：`SubscriptionInfo`（id/选择器/远端地址/登记时刻）、`ManagementState`/`ManagementStatus`（volatile 发布）、`ServiceView`/`ServiceDetail`/`UpstreamsView`；`SubscriptionManager` 登记时分配单调 id、记录调用方标识与时钟时刻，新增 `snapshot()`（弱一致、按 id 升序、关闭即移除）。
- **discovery-core application**：`DiscoveryAdminService` —— 订阅快照、服务视图列表（过滤链口径、namespace/cluster 过滤、稳定排序）、服务详情（逐 cluster 可见实例，空 cluster 条目剔除）、上游/规则源状态（upstreams 按 cluster 排序）。
- **discovery-core infrastructure**：`EjectionRuleSynchronizer` 上报 `SYNCING → SYNCED → DISCONNECTED`，`disable()` = DISABLED，`close()` 落 DISCONNECTED（停机）。
- **discovery-server**：`AdminController`（`/admin/v1` 四端点 + 契约输出形态：选择器拍平、management 嵌套、稳定排序）、`DiscoveryExceptionHandler`（400/404/405/500）、`HttpBasicAuthFilter`（仅 `/admin/*`）、`RemoteAddressInterceptor`（gRPC `Context` 传远端地址）、`DiscoveryConfiguration` 装配。无 proto 变更、无新增配置键。

### review 修复记录（⑥ findings，7 条）

| # | severity | 位置 | 问题 | 处理 |
|---|---|---|---|---|
| 1 | low | `AdminController` 注释 | 引用的 `docs/api/discovery-admin-api.md` 尚未创建 | 归 ⑩ 文档同步：新建契约文档并登记索引（本表跟踪） |
| 2 | low | `HttpBasicAuthFilter` | 与 registry/management 同构复制（第三处） | 不重构：与 phase-6 GrpcAuthInterceptor 的 D-D9 结论一致，上移公共实现 defer v0.4；类注释注明 |
| 3 | low | 本文件 | 「实现结果」仍为待实现而 state 已标 done | 本节补全（本修复） |
| 4 | medium | 测试文档/IT | 宣称覆盖取消后消失但未断言；用例计数失真 | 新增 `should_remove_subscription_when_stream_cancelled` IT（真实取消 → 快照移除）；计数与用例名同步 |
| 5 | low | `HttpBasicAuthFilter` | `/error` 白名单在仅 `/admin/*` 注册下为死代码 | 删除白名单与路径分支 |
| 6 | low | `application.properties` | 「HTTP 辅助通道按需递补」注释过时 | 更新为外部 HTTP 管理面说明 |
| 7 | low | `EjectionRuleSynchronizer.close` | 关闭后规则源状态可能仍显示启用/已同步 | `close()` 在启用时置 DISCONNECTED；枚举语义补「含停机」 |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` | v1.22：新增 v0.3 phase-7 交付节（只读管理面、订阅关系/视图/上游与规则源状态、工程形态） |
| `docs/api/discovery-admin-api.md` | **新建** v1.0：四端点契约、认证/无 readiness 门控、错误语义、调用方标识、有效视图口径、规则源状态 |
| `docs/api/README.md` | 登记 discovery-admin-api.md |
| `docs/arch/artemis-next-architecture.md` | v1.38：§2.2 端口表与 v0.3 落点补 discovery HTTP 只读管理面（D-A1/D-A4） |
| `java/server/artemis-discovery-server/src/main/resources/application.properties` | 端口注释更新为数据面 gRPC + HTTP 管理面（⑦ 已同步） |
| `docs/decisions/` | 无新增 ADR——纯新增只读端点/查询用例，未引入新的重要取舍；HTTP 形态与 management Admin API 一致 |
| `CLAUDE.md` | 无变更（「当前状态」只指向 dev-state，不含 phase 进度） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-24 | ⑩ 文档同步：产品文档 v1.22、新建 discovery-admin-api.md、架构 §2.2 v1.38 |
| 1.2 | 2026-10-24 | ④⑦：实现结果补全 + review 修复记录（7 条，含补取消订阅 IT） |
| 1.1 | 2026-10-24 | ③ 设计初稿落盘：HTTP Admin API 形态、四端点、订阅关系模型、规则源状态、定案 D-A1–A8（review 通过） |
| 1.0 | 2026-10-24 | 开工骨架 |
