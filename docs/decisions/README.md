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
| [adr-007-snapshot-push-unit.md](adr-007-snapshot-push-unit.md) | Accepted | D7：推送单元 = service 实例集快照 |
| [adr-008-java-first-rust-hybrid.md](adr-008-java-first-rust-hybrid.md) | Accepted | D8：Java 25 + Spring Boot 4.1 先行，Rust 后补混合集群 |
| [adr-009-security-by-default.md](adr-009-security-by-default.md) | Accepted | D9：安全内建（v0.1 默认认证） |
| [adr-010-namespace-day-one.md](adr-010-namespace-day-one.md) | Accepted | D10：namespace 进 v0.1 数据模型 |
| [adr-011-monorepo-top-level-proto.md](adr-011-monorepo-top-level-proto.md) | Accepted | D11：多语言 monorepo，契约居仓库顶层 proto/ 纯目录 |
