# management 摘除规则流 API

版本: 1.1    更新时间: 2026-10-25

> 本文定义 management → discovery 的摘除规则订阅流契约（v0.3 phase-6，架构 §2.2 通道①）。**非 SDK 公开 API**：契约位于 `management.v1`（管理面组），client SDK 不生成；消费者是 discovery-service。版本 + ack 生效水位归 v0.4 phase-5 完整规则链，本流为无版本盲下发（at-most-once）；四级规则扩展于 v0.4 phase-3 交付。

## 1. 契约与部署

| 项 | 值 |
|---|---|
| proto | `proto/management/v1/management.proto`（`EjectionRuleService`） |
| Java 生成 | 服务端统一生成于 `artemis-common`（management 实现与 discovery 调用共享单份生成类） |
| 传输 | gRPC（server-stream `WatchEjections`），management **内部 gRPC 端口**（`artemis.management.grpc.port`） |
| 端口启用 | **条件启用**：配置 `artemis.management.grpc.port` 才监听（未配置不监听，management 可独立运行）；`spring.grpc.server.port` 经占位符映射（默认 -1 = 不监听） |
| 认证 | 独立账户 `artemis.management.grpc.auth.*`，**默认关闭**（对齐 registry 内部 peer 端口哲学；关闭认证的部署以网络策略隔离） |
| 消费方配置 | discovery 侧 `artemis.discovery.management.*`（见 [discovery-query-api.md](discovery-query-api.md) §3） |

## 2. 事件语义

流事件 envelope `EjectionRulesEvent`，**message presence 区分快照与增量**：

| 事件 | 字段 | 语义 |
|---|---|---|
| 全量快照 | `snapshot`（已设，`rules` 可为空集） | **替换语义**——消费方全量重建本地规则集（含空集 = 无摘除）。首条必为快照；快照 = **有效规则集**（未对消目标的代表记录，v0.4） |
| 摘除增量 | `ejected`（`EjectionRule`：scope + service/instanceId 或 targetValue + reason） | 作用于最新集；**仅目标生效态翻转入 active 时产生**（叠加不推，v0.4） |
| 恢复增量 | `recovered`（scope + service/instanceId 或 targetValue） | 作用于最新集；**仅完全对消翻转入 inactive 时产生**（部分对消不推，v0.4） |

- **流序**：首条快照 → 其后增量按产生序；gRPC 单流天然有序。三字段皆未设 = 无效事件，消费方丢弃（重连快照自愈）。
- **服务端全序**：订阅登记（含快照读取）/摘除/恢复推送在同一锁内串行——快照与增量无重叠无丢失窗口；消费方按「快照替换 + 增量叠加」实现即双保险。
- **at-most-once（无版本/ack）**：慢消费者（outbound `isReady` 背压）或推送失败由服务端断流注销（`UNAVAILABLE`），消费方重连重新收快照全量覆盖自愈；丢失窗口 = 断流期间的变更，重连快照收敛。
- **四级规则**（v0.4 phase-3）：`EjectionRule` 扩展 `scope`（缺省 `INSTANCE`，wire 兼容承载 v0.3 既有事件）与 `target_value`——SERVER/ZONE/GROUP 级为 selector，按实例 metadata 键 `server`/`zone`/`group` 值匹配，全局生效不绑服务；`EjectionRecovered` 同构扩展。
- **management 内存态**：management 重启规则清零，discovery 重连收**空快照**即摘除消失（roadmap 期 1 设计内行为）。**DB 态**（v0.4）：快照现读持久化记录，management 重启后 discovery 重连收快照即恢复摘除态（跨重启重下发）。

## 3. discovery 侧应用语义

- **过滤口径（四级）**：INSTANCE 级按 `(namespace, service, instanceId)` 匹配**所有来源 cluster** 的同键实例（跨 cluster 生效，规则不感知来源拓扑）；SERVER/ZONE/GROUP 级按实例 `metadata[scope 对应键] == target_value` 实时匹配（新注册实例天然命中）；查询/订阅推送/对账 checksum 三出口一致过滤（过滤后视图）。
- **fail-open**：management 不可用时 discovery 沿用**最后已知规则**继续过滤（规则缓存只在收到事件时替换、断连不清空）；单 filter 实现异常由链统一放行。
- **规则变更扇出**：摘除 → 订阅流收视图语义 `DELETE`（携带实例完整数据）、恢复 → `NEW`；selector 级规则变化扇出投影中全部命中服务（值集变化触发全投影枚举）；labels/clusters/services 订阅过滤照常生效。
- **未配置 management（0..1）**：discovery 不订阅、不过滤（空过滤链直通零开销）。

契约原文与流序论证见 phase 设计文档 [phase-6-ejection-filter.md](../milestones/v0.3/phases/phase-6-ejection-filter.md)。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-25 | v0.4 phase-3：四级规则（scope + target_value，wire 兼容扩展）、快照 = 有效规则集、增量仅生效态翻转时产生、DB 态跨重启恢复、selector 扇出语义 |
| 1.0 | 2026-10-24 | 初版：v0.3 phase-6 摘除规则订阅流契约（快照 presence / 流序全序 / at-most-once / 内存态语义） |
