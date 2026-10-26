# management 路由规则流 API

版本: 1.1    更新时间: 2026-10-27

> 本文定义 management → discovery 的路由规则订阅流契约（v0.5 phase-2，架构 §2.2 通道①）。**非 SDK 公开 API**：契约位于 `management.v1`（管理面组），client SDK 不生成；消费者是 discovery-service。形态复用 v0.4 版本化规则流（快照/增量 + envelope version + ack 生效水位，[ejection-rule-stream-api.md](ejection-rule-stream-api.md) 同构）；承载两段式权重发布的规则下发（管理侧语义见 [admin-api.md](admin-api.md) §4.10–4.11）。

## 1. 契约与部署

| 项 | 值 |
|---|---|
| proto | `proto/management/v1/management.proto`（`RouteRuleService`：`WatchRouteRules` server-stream + `AckRouteRules` unary） |
| Java 生成 | 服务端统一生成于 `artemis-common`（management 实现与 discovery 调用共享单份生成类） |
| 传输 | management **内部 gRPC 端口**（`artemis.management.grpc.port`，与摘除规则流/逻辑实例流同端口）；条件启用、独立认证默认关闭、消费方配置均同摘除流（见 [ejection-rule-stream-api.md](ejection-rule-stream-api.md) §1） |
| 独立流决策 | 不并入 `EjectionRuleService`：两规则结构差异大、混流需 oneof envelope 且已交付摘除流 wire 迁移复杂；管理操作级频率下独立连接的代价可忽略（v0.5 phase-2 定案，架构 §2.2） |

## 2. 事件语义

流事件 envelope `RouteRulesEvent`，**message presence 区分快照与增量**：

| 事件 | 字段 | 语义 |
|---|---|---|
| 全量快照 | `snapshot`（`rules` 可为空集） | **替换语义**——消费方全量重建本地规则集。首条必为快照；**流内容 = ACTIVE 规则集**（INACTIVE 规则不进流，`RouteRule` 视图含 status 字段供消费方透传） |
| upsert 增量 | `upserted`（`RouteRule`：service + name + status + targets[]，target = selector/weight/unreleased_weight） | **覆盖语义**（键 = (namespace, service, name)），承载 CRUD / release / rollback 后的规则视图；INACTIVE 化以 `deleted` 事件表达（ACTIVE 集判定收敛在 management 分发器单点） |
| 删除增量 | `deleted`（`RouteRuleKey`） | 规则删除或 INACTIVE 化——离开 ACTIVE 集；消费方幂等（不存在时 no-op） |
| 版本 | `version`（envelope 级，快照与增量均携带） | **规则集状态版本 = management 分发器内存 tick**（每次变更 +1；快照取当前 tick）。**水位/观测语义，非顺序协议**——跳号合法、等号安全（消费方事件应用不依赖版本序）；management 重启归零（必断流 → 重连快照重新 ack 后水位重新收敛，无跨重启持久 sequence 源：route-rule 变更含 CRUD，无记录表可派生） |

- **流序 / 服务端全序 / at-most-once / 断流自愈**：与摘除流同构（订阅登记与快照读取同锁段、慢消费者断流注销、重连快照全量覆盖）。
- **双态语义**：内存态 management 重启规则清零（重连空快照收敛）；DB 态规则与发布记录持久，重连快照恢复。

## 3. 生效 ack 上行（通道②水位半边）

`AckRouteRules(AckRouteRulesRequest{subscriber_id, applied_version}) → AckRouteRulesResponse`（unary，status + data 规范形式）——语义与 `AckEjections` 同构：discovery 每次应用后经同端点短超时 unary 上报，幂等取 max、未知 subscriber 忽略、失败容忍（重连快照后重新 ack 收敛）。

- 生效水位出口：management `GET /admin/v1/status/rules` 的 **routeRules 节**（currentVersion + 活订阅水位 + appliedLatestCount；与摘除流**独立版本域**，数值不可比）。
- 本地对照：discovery `GET /admin/v1/upstreams` 的 `management.routeRules.appliedVersion`（-1 = 尚未应用）。

## 4. discovery 侧应用语义

- **执行已接入（phase-3，Group filter）**：规则视图整集替换（快照）/幂等集合操作（增量 upsert/delete）+ 应用后 ack；执行 = 过滤器链可见性判定（selector 等值匹配 + 生效权重二值承接，产品文档 v0.5 phase-3 节）+ 规则变化「视图变更直投」扇出（摘除流同型：退出 DELETE / 进入 NEW，与投影扇出/基线互斥）——查询/推送/对账三出口一致。
- **fail-open / 未配置 management（0..1）**：断流不清缓存（最后已知规则继续）、未配置不订阅不执行——均同摘除流纪律。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-27 | phase-3 执行接入：§4 「只缓存不执行」边界更新为 Group filter 执行 + 视图直投扇出 |
| 1.0 | 2026-10-27 | 初版：v0.5 phase-2 路由规则订阅流契约（ACTIVE 集 / upsert-deleted 增量 / version = dispatcher tick / AckRouteRules 水位 / 只缓存不执行边界） |
