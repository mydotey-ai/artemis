# 架构决策记录索引

一决策一文件：`adr-<序号>-<名称>.md`。总览（决策一句话）亦见[架构设计](../arch/artemis-next-architecture.md) §7。

| 文档 | 状态 | 说明 |
|---|---|---|
| [adr-001-three-services-uniform-protocol.md](adr-001-three-services-uniform-protocol.md) | Accepted | D1：三服务拆分 + 统一网络协议（all-in-one 也是） |
| [adr-002-discovery-no-cluster.md](adr-002-discovery-no-cluster.md) | Accepted | D2：discovery 不组集群 |
| [adr-003-ap-no-versioning.md](adr-003-ap-no-versioning.md) | Accepted | D3：数据面 AP + 无版本管理 |
| [adr-004-full-mesh-replication.md](adr-004-full-mesh-replication.md) | Accepted | D4：全对全 + 增量报文，负责制后置 |
| [adr-005-dual-track-external-db.md](adr-005-dual-track-external-db.md) | Accepted | D5：双轨 + 外部 DB（治理元数据） |
| [adr-006-no-peer-reconciliation.md](adr-006-no-peer-reconciliation.md) | Accepted | D6：peer 间无拉对账 |
| [adr-007-snapshot-push-unit.md](adr-007-snapshot-push-unit.md) | Superseded | D7：推送单元 = service 实例集快照（订阅通道形态被 D19 取代，快照单元语义保留） |
| [adr-008-java-first-rust-hybrid.md](adr-008-java-first-rust-hybrid.md) | Accepted | D8：Java 25 + Spring Boot 4.1 先行，Rust 后补混合集群 |
| [adr-009-security-by-default.md](adr-009-security-by-default.md) | Accepted | D9：安全内建（v0.1 默认认证） |
| [adr-010-namespace-day-one.md](adr-010-namespace-day-one.md) | Accepted | D10：namespace 进 v0.1 数据模型 |
| [adr-011-monorepo-top-level-proto.md](adr-011-monorepo-top-level-proto.md) | Accepted | D11：多语言 monorepo，契约居仓库顶层 proto/ 纯目录 |
| [adr-012-explicit-registration-ops.md](adr-012-explicit-registration-ops.md) | Accepted | D12：注册模型显式四操作化（显式操作 + 心跳全量上报续约/补注册） |
| [adr-013-group-as-instance-metadata.md](adr-013-group-as-instance-metadata.md) | Accepted | D13：分组退出数据模型（以实例 metadata 元素表达） |
| [adr-014-cluster-out-of-service-key.md](adr-014-cluster-out-of-service-key.md) | Accepted | D14：cluster 不进 ServiceKey（来源标识在投影边界注入） |
| [adr-015-layered-model-inside-core.md](adr-015-layered-model-inside-core.md) | Accepted | D15：DDD 四层（接入层 server 承载 / application / domain / infrastructure）与层间模型转换 |
| [adr-016-management-service-day-one.md](adr-016-management-service-day-one.md) | Accepted | D16：管理面从 v0.1 独立交付（management-service 持有摘除记录；registry 只提供只读事实查询） |
| [adr-017-self-protection-two-layer.md](adr-017-self-protection-two-layer.md) | Accepted | D17：自我保护 2.0——节点级兜底 + 服务级双层保护、显式状态与超时回落 |
| [adr-018-projection-distribution-contract.md](adr-018-projection-distribution-contract.md) | Accepted | D18：registry → discovery 投影分发契约——三值事件、at-most-once、订阅建立确认、保护态不进契约 |
| [adr-019-hybrid-push-pull-subscription.md](adr-019-hybrid-push-pull-subscription.md) | Accepted | D19：订阅推送推拉结合——实时增量流 + 全量拉兜底 + hash 对账（取代 D7 订阅通道快照形态） |
| [adr-020-discovery-static-node-directory.md](adr-020-discovery-static-node-directory.md) | Accepted | D20：discovery 静态节点目录与客户端前置寻址（仍不组集群） |
