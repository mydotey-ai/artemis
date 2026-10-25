# management 逻辑实例流 API

版本: 1.0    更新时间: 2026-10-25

> 本文定义 management → discovery 的逻辑实例订阅流契约（v0.4 phase-4，架构 §2.2 通道① 同内部端口）。**非 SDK 公开 API**：契约位于 `management.v1`（管理面组），client SDK 不生成；消费者是 discovery-service。逻辑实例是数据而非规则——本流无版本/ack（快照自愈，语义对齐通道③投影流；规则版本化归 v0.4 phase-5 规则流范畴）。

## 1. 契约与部署

| 项 | 值 |
|---|---|
| proto | `proto/management/v1/management.proto`（`LogicalInstanceService`） |
| Java 生成 | 服务端统一生成于 `artemis-common`（management 实现与 discovery 调用共享单份生成类） |
| 传输 | gRPC（server-stream `WatchLogicalInstances`），management **内部 gRPC 端口**（`artemis.management.grpc.port`，与摘除规则流同端口、同条件装配与认证） |
| 端口启用 | **条件启用**：配置 `artemis.management.grpc.port` 才监听（未配置不监听，management 可独立运行） |
| 认证 | 与摘除规则流共用 `artemis.management.grpc.auth.*`（独立账户，默认关闭；关闭认证的部署以网络策略隔离） |
| 消费方配置 | discovery 侧 `artemis.discovery.management.endpoints`（0..1：未配置不启用逻辑源） |

## 2. 事件语义

流事件 envelope `LogicalInstancesEvent`，**message presence 区分快照与增量**：

| 事件 | 字段 | 语义 |
|---|---|---|
| 全量快照 | `snapshot`（已设，`instances` 可为空集） | **替换语义**——消费方全量重建本地逻辑实例集（含空集 = 无逻辑实例）。首条必为快照 |
| upsert 增量 | `upserted`（`LogicalInstance`：service + `common.v1.Instance`） | 作用于最新集（**upsert 语义**；仅内容变化时产生，identical no-op 不推） |
| 删除增量 | `deleted`（`LogicalInstanceKey`：service + instance_id） | 作用于最新集（仅命中删除时产生，缺失 no-op 不推） |

- `LogicalInstance.instance` 复用 `common.v1.Instance` wire 模型（instance_id/host/port/status/weight/metadata）；protocol/url 等扩展属性以 metadata 表达（D13 延伸）。status 为管理面静态事实（无心跳无探活，客户端自报哲学延续——DOWN 照常分发，消费方自行决策）。
- **流序**：首条快照 → 其后增量按产生序；gRPC 单流天然有序。三字段皆未设 = 无效事件，消费方丢弃（重连快照自愈）。
- **服务端全序**：订阅登记（含快照读取）/upsert/删除推送在同一锁内串行——快照与增量无重叠无丢失窗口。
- **at-most-once（无版本/ack）**：慢消费者（outbound `isReady` 背压）或推送失败由服务端断流注销（`UNAVAILABLE`），消费方重连重新收快照全量覆盖自愈；丢失窗口 = 断流期间的变更，重连快照收敛。
- **双态**：management 内存态重启逻辑实例清零，discovery 重连收**空快照**即消失（期 1 设计内行为）；**DB 态**（v0.4）：快照现读持久化数据，management 重启后 discovery 重连收快照即恢复（跨重启重下发）。

## 3. discovery 侧应用语义

- **投影合并（保留 cluster）**：discovery 将本流适配为投影源，逻辑实例以保留 cluster id **`logical`** 进入本地投影——与真实实例 union 出现在查询/订阅推送/对账 checksum；按 cluster 过滤的选择器不命中 `logical`（逻辑实例不属任何 registry 集群）。上游 registry 集群 id 配置为 `logical` 时启动失败（保留名防静默合并）。
- **变更映射**：`upserted` → 投影/订阅的 UPDATE 恒映射（覆盖语义，订阅翻译天然区分新入视图 NEW / 视图内 UPDATE / 退出 DELETE）；`deleted` → DELETE（仅 instanceId，匹配判定用投影旧值）。
- **摘除生效**：四级摘除对逻辑实例同样生效——instance 级按 `(namespace, service, instanceId)`（跨 cluster 含 `logical`）、selector 级按实例 metadata 实时匹配；摘除/恢复经规则流扇出视图变更。
- **fail-open**：management 不可用时 discovery 沿用**最后已知逻辑实例**继续服务（断连不清投影，上游状态表标记 `DISCONNECTED`）；本源**不参与 readiness 门控**（0..1 治理增强，数据面可用性不受 management 影响）。
- **未配置 management（0..1）**：不建逻辑源、投影无 `logical` cluster（零连接零开销）。

契约原文与设计定案见 phase 设计文档 [phase-4-logical-instance.md](../milestones/v0.4/phases/phase-4-logical-instance.md)。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-25 | 初版：v0.4 phase-4 逻辑实例订阅流契约（快照 presence / upsert-UPDATE 恒映射 / at-most-once / 保留 cluster 投影合并 / fail-open） |
