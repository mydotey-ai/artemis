# ADR-016：管理面从 v0.1 独立交付（摘除记录归 management-service）

版本: 1.2    更新时间: 2026-10-19

## 状态

Accepted（2026-10-18 v0.1 phase-5 设计 review 中用户定案）

## 背景

原方案（[路线图](../product/artemis-next-roadmap.md) 初版、[架构](../arch/artemis-next-architecture.md) §5.1、[plan](../milestones/v0.1/plan.md) 原 phase-5）把治理期 1 前半的实例管理 Admin API 与状态 API 放在 registry 进程内：摘除记录由 registry 内存持有、查询出口过滤；management-service 到 v0.4 才交付（DB、账户、审计、四级摘除）。review 判定该切分有问题：

- 管理面状态（摘除记录）与数据面临时注册数据同进程同生命周期，但二者演进方向不同（前者要持久化/审计/多级级联，后者 AP 临时数据）；
- v0.2 为让 registry 集群各节点一致感知摘除，需要把摘除记录纳入变更事件扇出；v0.4 再迁往 management 时又需反向改造，迁移成本叠加；
- 管理 API 与注册数据面耦合，后续 discovery（v0.3）要感知摘除还得跨服务取管理规则，边界更乱。

## 决策

1. **management-service（管理面服务）从 v0.1 起独立交付**，不再等 v0.4。v0.1 交付内存态形态：实例列表/详情、摘除/恢复、有效视图、状态 API（leases/config 排障面）；v0.4 补齐 DB 持久化、账户体系、四级摘除、审计与 console。服务命名保持 `management-service`，模块前缀 `artemis-management-*`。
2. **摘除记录归 management-service**（v0.1 内存态、重启丢失）。`registry` 数据面不持有摘除状态、不感知摘除。
3. **registry 只提供只读事实查询**：新增内部只读 RPC（`RegistryQueryService`，契约落 `management.v1`，不进 client、不冻结；服务端统一生成于 `artemis-common`）——服务列表、实例事实、租约明细与保护统计、配置状态（敏感值在 registry 侧脱敏后返回）。管理面查询时组合「registry 事实 + 自身摘除记录」得到有效视图。
4. **v0.1 管理查询复用 registry 外部 gRPC 端口**（受外部认证保护）：Spring Boot 官方 gRPC starter 只支持单网络 server，v0.1 不自写第二个 Server；内部/外部端口分离随 v0.2 peer 内部端口统一规划。
5. **v0.3 起 discovery 的摘除出口经 management 规则流**（治理启用时订阅 management 规则），不再由 registry 过滤注册数据。

## 理由

- 管理面与数据面生命周期解耦：摘除记录、账户、审计、console 都在 management 演进，registry 保持「零外部依赖的 AP 数据面」（架构 §2.1 职责表）不变。
- 有效视图成为管理面的组合视图（事实 + 治理状态），v0.4 四级摘除级联、持久化与重下发都在同一服务闭环，不需要迁移数据面语义。
- registry 只读查询面窄且无状态，实现与测试成本低；management 成为唯一管理写入口，安全边界清晰（外部 HTTP 默认认证）。
- v0.2 不再需要「摘除记录随 registry 变更事件扇出」——减少一条跨节点一致性路径。

## 与能力基线对照

- **§5 资产 #12（状态 API 即排障面）**：继续提供 leases/config 排障面，数据来自 registry 只读查询；资产形态不变，归属改为管理面。
- **§5 资产 #4（四级摘除级联 + 操作记录即状态）**：v0.1 先落 instance 级内存记录，v0.4 在 management 内扩展四级与持久化——归属调整后级联演进不需要跨服务迁移管理状态。
- **§5 双轨分离（§2.5/§2.7）**：本决策强化双轨——数据面（registry，AP 临时数据）与管理面（management，治理状态与持久化）分进程、分生命周期。
- **§6 局限清单**：消解「管理状态与临时注册数据同生命周期」的耦合；v0.1 管理查询仍与 SDK 共用单账户凭证（RBAC v1.0 前的已知限制），v0.2 内部端口分离降低暴露面。

## 否决方案

- **维持 Admin API 内嵌 registry（原方案）**：数据面节点承载管理状态，v0.2 需扇出、v0.4 需迁出；管理面与数据面职责混装。
- **摘除记录仍在 registry、management 只做 API 代理**：管理状态留在数据面，management 无法独立持久化/审计，v0.4 迁移成本仍在。
- **为管理查询新起内部 gRPC 端口**：官方 starter 单 server，v0.1 自写 Server/生命周期违反技术纪律（`docs/dev/coding-guidelines-java.md` §1）；内部端口随 v0.2 统一规划更稳。
- **management 只做只读状态、管理写操作留 registry**：管理入口分裂，与「admin 相关都在管理面」的定案相悖。

## 后果

- 路线图调整：v0.1 增加 management-service 交付；v0.2 删除「摘除记录集群扇出」；v0.4 只补 DB/账户/四级摘除/console 等增量。
- v0.1 phase 重排：registry 只读查询 RPC（phase-5）与 management-service（phase-6）两个 phase；SDK/starter 顺延。
- 契约：`management.v1` 新增只读查询 service（管理面契约，不随数据面冻结、client 不生成）；服务端由 `artemis-common` 单源生成，registry 实现与 management 调用共享同一 artifact（避免 all-in-one 同 JVM 双份生成类）。
- 架构 §2.1/§2.2/§4.1/§5.1 同步修订；新增 API 文档 `docs/api/admin-api.md`（management 管理/状态 API）。
- 部署：v0.1 从「单节点 registry + SDK」变为「registry + management + SDK」两个服务进程；all-in-one 打包仍归 v0.3。
- 安全：管理查询走 registry 外部 gRPC 认证；management 外部 HTTP 默认 Basic 认证。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-19 | 只读查询契约从 registry.v1 迁到 management.v1（client 不生成、不冻结；artemis-common 单源生成） |
| 1.1 | 2026-10-18 | review 修复：补能力基线 §5 资产 / §6 局限对照；明确 v0.1 管理查询共用凭证的已知限制 |
| 1.0 | 2026-10-18 | 初版：管理面从 v0.1 独立交付，摘除记录归 management，registry 提供只读事实 RPC |
