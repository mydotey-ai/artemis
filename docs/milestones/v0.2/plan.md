# v0.2 版本规划

版本: 1.4    更新时间: 2026-10-23

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v0.2（registry 集群），此处不复述条目原文——一句话概括：把 v0.1 单节点 registry 升级为静态成员集群（peer 全量 bootstrap + 增量变更扇出 + 丢失自愈链），补自我保护 2.0、节点状态 API、优雅停机，SDK 侧完成三级地址容灾与连接 TTL 轮换。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：3 节点集群、节点故障客户端无感换址、杀节点数据最终一致、保护态可观测。

设计不在本版展开（peer 复制协议细节、成员自身识别、节点状态 API 形状等由各 phase ③ 设计定稿）；架构基线见 [架构设计](../../arch/artemis-next-architecture.md) §4.1 与 [ADR-004](../../decisions/adr-004-full-mesh-replication.md)/[ADR-006](../../decisions/adr-006-no-peer-reconciliation.md)。

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-peer-protocol | `replication.v1` 契约定稿 + peer 内部端口 + 静态多节点成员（配置驱动、自身识别） | 3 | proto 编译与 wire 契约评审；成员解析/自身识别单测；peer 内部端口起停 + 与外部端口认证隔离 IT；进程内 3 节点成员互见 |
| phase-2-replication-fanout | 变更事件扇出：有界队列异步投递 + 增量批量报文 + 指数退避重试（抖动）+ 复制指标 | 2 | 3 节点进程内 IT：任一节点写入后其余节点最终一致；稳态心跳 RENEW 复制维持副本存活；peer 暂不可达重试后收敛；复制 metrics 可抓取 |
| phase-3-peer-bootstrap | peer 全量 bootstrap + readiness 分支① + 重启优先 peer 同步 + 节点状态 API | 3 | 新节点加入全量拉取；节点重启优先 peer 同步（peer 不可用回退本地快照）；有已就绪 peer 时同步失败保持 not ready；节点状态 API 返回成员/就绪态 |
| phase-4-self-protection-2 | 自我保护 2.0：按服务粒度滑动窗口 + 保护态指标/API | 2 | 单测：单服务续约骤降只保护该服务、其余服务正常过期；指标带 service 维度；保护态经 API 可查；3 节点各节点独立判定 |
| phase-5-sdk-failover | SDK 三级地址容灾完整（节点列表 + 熔断换址）+ 连接 TTL 轮换（10min ± 抖动） | 2 | SDK 单测：熔断阈值/换址/恢复、TTL 轮换抖动；3 节点 IT：杀节点客户端无感换址并续约，TTL 轮换修复新节点数据 |
| phase-6-graceful-shutdown | 优雅停机（先标记自身下线再停止）+ 集群验收场景收尾 | 2 | IT：停机前节点标记 down、客户端凭容灾换址；3 节点集群故障/恢复端到端场景全绿；运维说明同步 |

phase-1 说明：`replication.v1` 一次定稿四类报文——成员/节点状态、全量 bootstrap（拉取 + 应答分片）、增量变更批量投递、投递确认/失败语义；同 phase 落地 registry 内部 gRPC 端口，management → registry 只读事实查询随之内移（架构 §2.2 的 v0.2 端口分离承诺）。静态成员配置需支持节点自身识别（哪个成员是 me）与对外地址/peer 端口区分。

phase-2 说明：扇出实现挂在 v0.1 已预留的 `ChangeListener` 端口上（回调在聚合锁内，必须排队移交专属有界 executor，不在回调内做 IO）；增量报文按 service 聚合批量。副本租约语义继承原产品（phase-2 设计 review 定案）：纯续约发 RENEW 事件扇出维持 peer 副本（副本 = 真实租约，靠续约复制保活），TTL 清理本地各自发生、DELETE 不扇出，复制入站零事件防回环、RENEW 缺席补注册重建——稳态复制流量与实例数解耦（详见架构 §4.1）。复制指标按 [架构 §6](../../arch/artemis-next-architecture.md)「复制 v0.2」内建。

phase-3 说明：启动数据源三分支在 v0.1 已实现 ②/③，本 phase 插入分支①（存在已就绪 peer → 全量同步成功才 ready；集群模式异步执行、全抖动指数退避重试、每轮尝试全部 UP 候选、确认过数据源后不降级本地快照；not ready 期间 `Replicate`/`Bootstrap` 返回 UNAVAILABLE 且快照不落盘）；`RegistryBootstrap` 的「重启优先从 peer 同步」与本 phase 一并落地。成员配置扩展为三段式（`<id>@<peer>@<external>`）；节点状态 API 落 `replication.v1.NodeStatusService`（外部端口、认证保留、readiness 按方法豁免），由后台单线程调度 + 虚拟线程并发探测全部 peer 维护成员观测态（周期/超时动态可配，失败=UNKNOWN），同时作为 SDK 存活节点列表来源（phase-5 消费，优先 UP + 熔断兜底）。探测只服务 API/观测，不参与复制扇出门控。

phase-4 说明：沿用 v0.1 的滑动窗口算法，升级为**节点级兜底 + 服务级双层判定**（ADR-017）：服务峰值达 `minCount` 后服务级状态权威，否则回落节点级；显式状态机 + 1s 周期评估 + 节点健康时超时回落（限制单服务客户端迁移造成的永久保护）；指标加 service 维度（仅保护中服务注册）、独立 protection 状态 API。`stale` 数据标记的消费方出口归 v0.3 discovery，本版只保证保护态可观测。

phase-5 说明：三级容灾 = 配置引导地址 → 服务端节点列表（phase-3 节点状态 API）→ 随机选址 + 熔断；连接 TTL 轮换（默认 10min ± 抖动）承担「客户端轮换即分布式对账」职责（[ADR-006](../../decisions/adr-006-no-peer-reconciliation.md)），修复丢失事件在节点上的残留缺口。

phase-6 说明：优雅停机 = 先把自身标记为下线（节点状态 API 可见）再停止服务，客户端凭容灾换址，不做连接迁移；本 phase 收口 roadmap v0.2 验收要点的端到端场景（3 节点、杀节点、换址、最终一致、保护态可观测），验收对照表在版本收尾时填写。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|
| 3 节点集群 | `ClusterMembershipIT`（3 节点成员互见/端点可达）、`ReplicationFanoutIT`（3 节点写入最终一致）、`SdkFailoverIT`/`GracefulShutdownIT`（3 节点 + 真实 SDK 端到端） | 通过 |
| 节点故障客户端无感换址 | `SdkFailoverIT.should_failoverAndKeepLeaseAlive_when_connectedNodeKilled`（杀节点后续约不断）、`GracefulShutdownIT.should_failover_when_connectedNodeShutdownGracefully`（优雅停机换址） | 通过 |
| 杀节点数据最终一致 | `ReplicationFanoutIT`（副本扇出/猝死清理收敛）、`SdkFailoverIT.should_reconcileNodeData_when_ttlRotation`（轮换即对账）、`GracefulShutdownIT.should_converge_when_nodeRestartedAfterShutdown`（停机重启 bootstrap 收敛） | 通过 |
| 保护态可观测 | `SelfProtectionClusterIT`（节点独立判定/服务级隔离/指标）、`GetSelfProtectionStatus` 经 `RegistryQueryIT`/`ManagementAdminIT` 可查 | 通过 |

> 验收执行：`java/scripts/dev-test.sh all`（2026-10-23，v0.2 最终态）BUILD SUCCESS，全 reactor 0 失败。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-23 | 版本收尾：验收对照表填齐（四条验收要点均映射可执行测试并通过） |
| 1.3 | 2026-10-21 | phase-4 设计定案（ADR-017）：节点级兜底 + 服务级双层、显式状态/周期评估/超时回落、有界指标与独立状态 API |
| 1.2 | 2026-10-21 | phase-3 设计定案：说明补异步 bootstrap/退避/全候选、三段式成员配置、对外节点状态 API 与后台并发探测（不参与扇出门控） |
| 1.1 | 2026-10-21 | phase-2 副本租约语义 review 定案（继承原产品：续约复制/清理不扇出/缺席补注册），phase-2 说明与验收方式同步修订 |
| 1.0 | 2026-10-20 | 版本启动，初始 phase 拆分（6 phase） |
