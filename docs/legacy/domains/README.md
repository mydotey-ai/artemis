# 业务域文档索引

状态: 定稿  日期: 2026-10-08

按业务域组织的原产品**规格层**文档，分两类：

- **域文档**（每域两份）：`*-spec.md`（功能规格，需求语言——系统应做什么）与 `*-logic.md`（业务逻辑蓝本——逻辑单元 L / 决策规则 D / 流程 F / 缺陷清单，怎么运转）。
- **契约制品**（跨域，4 份）：可据以编码的静态结构——数据字段、接口报文、表结构、协议格式。

域文档回答「怎么运转」，契约制品回答「长什么样」；两者合起来构成 **1:1 对标复刻的完整蓝本**。产品级总览与横切主题见 [../product-overview.md](../product-overview.md)；非功能性需求规格见 [../nfr-spec.md](../nfr-spec.md)。事实层（[features.md](../features.md) / [arch.md](../arch.md)）与判断层（基线）在上级目录。

## 域文档

| 文档 | 状态 | 说明 |
|---|---|---|
| [registry-lease-spec.md](registry-lease-spec.md) | 草案 2026-10-08 | 实例注册与租约生命周期 · 功能规格（16 条 FR） |
| [registry-lease-logic.md](registry-lease-logic.md) | 草案 2026-10-08 | 实例注册与租约生命周期 · 逻辑蓝本（L1–L10 / D1–D7 / F1–F7 / 8 缺陷） |
| [discovery-spec.md](discovery-spec.md) | 草案 2026-10-08 | 服务发现与变更通知 · 功能规格（13 条 FR） |
| [discovery-logic.md](discovery-logic.md) | 草案 2026-10-08 | 服务发现与变更通知 · 逻辑蓝本（L1–L7 / D1–D4 / F1–F5 / 11 缺陷） |
| [replication-cluster-spec.md](replication-cluster-spec.md) | 草案 2026-10-08 | 复制与集群一致性 · 功能规格（14 条 FR） |
| [replication-cluster-logic.md](replication-cluster-logic.md) | 草案 2026-10-08 | 复制与集群一致性 · 逻辑蓝本（L1–L8 / D1–D5 / F1–F5 / 11 缺陷） |
| [traffic-governance-spec.md](traffic-governance-spec.md) | 草案 2026-10-08 | 流量治理 · 功能规格（10 条 FR） |
| [traffic-governance-logic.md](traffic-governance-logic.md) | 草案 2026-10-08 | 流量治理 · 逻辑蓝本（L1–L7 / D1–D4 / F1–F4 / 12 缺陷） |
| [operations-audit-spec.md](operations-audit-spec.md) | 草案 2026-10-08 | 运维管控与审计 · 功能规格（10 条 FR） |
| [operations-audit-logic.md](operations-audit-logic.md) | 草案 2026-10-08 | 运维管控与审计 · 逻辑蓝本（L1–L7 / D1–D4 / F1–F4 / 9 缺陷） |
| [client-sdk-spec.md](client-sdk-spec.md) | 草案 2026-10-08 | 客户端 SDK 行为 · 功能规格（12 条 FR） |
| [client-sdk-logic.md](client-sdk-logic.md) | 草案 2026-10-08 | 客户端 SDK 行为 · 逻辑蓝本（L1–L8 / D1–D4 / F1–F5 / 9 缺陷） |

## 契约制品

| 文档 | 状态 | 说明 |
|---|---|---|
| [data-model.md](data-model.md) | 草案 2026-10-08 | **数据字典**——全部实体字段级（类型 / JSON 键名 / 可空 / 默认 / 约束）+ 枚举字典 + 身份语义 + clone 深度 + 死字段 |
| [api-contract.md](api-contract.md) | 草案 2026-10-08 | **REST API 契约**——77 端点逐一的请求/响应字段 / GET 参数 / errorCode + 死端点标记 |
| [db-schema.md](db-schema.md) | 草案 2026-10-08 | **DB Schema**——20 表 DDL 级（字段/类型/键/索引）+ 软删与时间戳语义 + MySQL↔SQLite 差异 + DDL↔DAO 不一致 |
| [client-sdk-api.md](client-sdk-api.md) | 草案 2026-10-08 | **SDK 接口与报文协议**——SDK 全签名与契约 + WS 三通道报文 + 复制协议报文 + HTTP 通用约定 |
| [config-reference.md](config-reference.md) | 草案 2026-10-08 | **配置项全量字典**——127 个键模式的默认值/范围/读取点/读取时机 + 死键与缺失键 + 「热更」真相 |
