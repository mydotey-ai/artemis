# v0.3 版本规划

版本: 1.0    更新时间: 2026-10-23

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v0.3（discovery），此处不复述条目原文——一句话概括：交付 discovery-service（投影构建、订阅推送、三层兜底轮询、查询 API、新鲜度标记）与多集群聚合，接 management 摘除规则（0..1），补发现管理 API、消费方 SDK（本地缓存/磁盘快照/地址容灾）与发现侧 starter，并交付 registry + discovery all-in-one 打包。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：推送亚秒可见、断连自愈、多集群 union 查询、all-in-one 单包起。

设计不在本版展开（projection/discovery 契约形状、投影模型、推送/轮询时序、SDK 缓存与换址策略等由各 phase ③ 设计定稿）；架构基线见 [架构设计](../../arch/artemis-next-architecture.md) §2.1–§2.2 与 §4.2，关键决策见 [ADR-002](../../decisions/adr-002-discovery-no-cluster.md)（discovery 不组集群）、[ADR-007](../../decisions/adr-007-snapshot-push-unit.md)（推送单元 = service 实例集快照）、[ADR-013](../../decisions/adr-013-group-as-instance-metadata.md)（分组不进数据模型）、[ADR-014](../../decisions/adr-014-cluster-out-of-service-key.md)（cluster 在投影边界注入）。

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-projection-source | `projection.v1` 契约定稿 + registry 侧数据分发（全量 bootstrap + 变更流 + 上游保护态出口），承载于内部 peer 端口 | 3 | 契约评审；进程内 registry + 测试订阅者 IT：bootstrap 分片、增量事件流、断流重连 re-bootstrap、未就绪拒绝 |
| phase-2-discovery-projection | discovery 投影内核：`(cluster, namespace, service)` 模型 + 单集群 bootstrap/变更流消费 + 重连全量覆盖 + 查询 API | 2 | 单测：投影增删改/重连覆盖幂等；IT：registry 写入后 discovery 查询亚秒可见 |
| phase-3-subscription-push | `discovery.v1` 订阅契约 + 推送（service 实例集快照原子覆盖、一流多目标、有界队列）+ 推送指标 | 2 | IT：订阅推送亚秒可见、快照原子/幂等、慢消费者不阻塞他流；指标可抓取 |
| phase-4-fallback-freshness | 三层兜底轮询（空服务 60s 主动拉、15min 全量刷新、推送失败收敛）+ 新鲜度标记（上游断连、自我保护 `stale` 出口） | 2 | 单测：轮询定时与触发条件；IT：断流后推送/轮询自愈、断连与 stale 标记可见 |
| phase-5-multi-cluster | 1..N registry 集群订阅、独立上游状态、查询并集 + 选择器过滤（cluster/namespace） | 2 | IT：多集群 union 查询、按 cluster 过滤、单源故障不影响他源 |
| phase-6-ejection-filter | management 摘除规则订阅流（`management.v1` 扩展）+ discovery 过滤器链 SPI + 摘除过滤 + fail-open/最后已知规则 | 3 | IT：摘除后查询与推送视图被过滤、management 不可用沿用最后规则、恢复后收敛 |
| phase-7-discovery-admin-api | 发现管理 API：订阅关系查看、服务视图、上游状态/新鲜度查询 | 2 | IT：订阅关系、服务视图、上游状态与新鲜度可查 |
| phase-8-consumer-sdk | Java 8 消费方 SDK：订阅、本地缓存、磁盘快照、兜底轮询、地址容灾（配置地址 + 历史成功连接缓存 + 熔断换址） | 3 | SDK 单测 + 对接 discovery 的 IT：订阅推送、断连自愈、冷启动回放快照、server 全挂兜底、换址 |
| phase-9-starter-all-in-one | 发现侧 Spring Boot starter 自动装配 + registry + discovery all-in-one 打包 + 版本验收场景收尾 | 2 | starter 自动装配测试；all-in-one 单进程双端口起停 IT；推送亚秒/断连自愈/多集群 union 端到端场景全绿 |

phase-1 说明：`projection.v1` 一次定稿两类报文——全量 bootstrap（server-stream 分片，流正常结束 = 同步完成）与变更流（订阅后增量推送，at-most-once、无 ack/版本，断流即重连 re-bootstrap 全量覆盖自愈）；bootstrap 与变更流均携带上游按服务自我保护态，供 discovery 出 `stale` 标记（v0.4 再演进为规则版本 + ack 的规则流，与本数据面流无关）。数据分发 service 绑定 registry 内部 peer 端口（架构 §2.2），独立认证、未就绪返回 UNAVAILABLE。变更事件复用既有 `ChangeListener` 出口——回调在聚合锁内，必须排队移交专属有界 executor，不在回调内做 IO；入站零事件防回环语义不变。

phase-2 说明：投影模型按 `(cluster, namespace, service)` 组织，cluster 为投影边界注入的来源标识（ADR-014），不进 `ServiceKey`；启动/重连向配置的 registry 集群 bootstrap 全量拉取，之后消费变更流，重连即重新 bootstrap（全量覆盖幂等）。本 phase 只做单集群与查询闭环，多集群聚合归 phase-5；查询 API 与订阅选择器共用同一选择器模型（service/label）。

phase-3 说明：推送单元 = **service 实例集快照**（原子、幂等覆盖，ADR-007）；gRPC server-stream 单流内天然有序，一流多目标（订阅选择器 service/label）；每订阅者独立有界队列，满时合并/丢弃并以兜底轮询收敛（at-most-once 自愈底线），慢消费者不得阻塞他流。推送指标按架构 §6「推送 v0.3」内建。

phase-4 说明：三层兜底轮询继承资产 #8——订阅推送失败或空服务 60s 主动拉、15min 全量刷新；推送流断开先重建订阅，失败由轮询兜底。新鲜度标记携带上游断连中与上游自我保护态（`stale`），出口在查询响应与推送快照的状态元信息；消费方（SDK）感知数据降级。

phase-5 说明：discovery 不组集群（ADR-002）——每个 discovery 节点独立订阅 1..N 个 registry 集群，各源投影独立维护、查询/推送时刻本地合并（默认 union，可按 cluster/namespace 过滤），聚合逻辑只发生在本地、无跨源污染。上游状态按集群独立维护。

phase-6 说明：v0.3 只接**摘除规则**（发现侧过滤链路）：management 在 `management.v1` 增加摘除规则订阅流（连上即全量快照 + 增量，无版本/ack——版本 + ack 生效水位归 v0.4 完整规则链）；discovery 以过滤器链 SPI 应用，规则本地缓存，management 不可用时用最后已知规则继续、逐 filter fail-open。management 保持 0..1 可选：未配置时不过滤。

phase-7 说明：治理期 2 的发现管理面——订阅关系查看（调用方视角）、服务视图查询、上游状态/新鲜度。只读 API，落 discovery 外部端口（形态随 ③ 设计定稿）。

phase-8 说明：消费方 SDK 补全注册侧 SDK 的发现能力——订阅、本地缓存（订阅快照引用，避免深克隆）、磁盘快照（冷启动回放、server 全挂兜底）、兜底轮询；地址容灾为 discovery 专属形态：**配置地址列表 + 历史成功连接缓存 + 熔断换址**（discovery 无服务端存活列表 API，ADR-002）。SDK 并入既有 `artemis-client` artifact（复用 Java 8 生成线与地址容灾基础）还是新增模块由 ③ 设计定稿；如新增模块须同步技术选型 §2.1/§2.2 与 reactor。

phase-9 说明：发现侧 starter 自动装配（与注册侧共存，按配置条件装配）；all-in-one 打包 = 同进程起 registry + discovery 两个 server、各自端口（架构 §2.1 统一网络协议，不做进程内直连优化），模块/入口形态由 ③ 设计定稿并同步技术选型；本 phase 收口 roadmap v0.3 验收要点的端到端场景，验收对照表在版本收尾时填写。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|
| 推送亚秒可见 | | |
| 断连自愈 | | |
| 多集群 union 查询 | | |
| all-in-one 单包起 | | |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-23 | 版本启动，初始 phase 拆分（9 phase） |
