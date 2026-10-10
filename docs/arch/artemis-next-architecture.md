# 新一代 Artemis 架构设计

版本: 1.5    更新时间: 2026-10-10

> 本文是新一代 Artemis（重设计产品）的架构设计基线：总体架构、核心机制、技术栈策略与关键决策记录。产品定位与版本切分见 [产品规划与路线图](../product/artemis-next-roadmap.md)；设计输入来自 [原产品能力基线](../legacy/legacy-product-analysis.md)（§5 可继承资产 / §6 局限清单）与[行业对标报告](../legacy/industry-benchmark.md)。

## 1. 产品定位与设计输入

**定位**：开源服务注册中心，对标 Nacos；外部用户可 eval、可上生产；单集群目标容量 10 万服务实例级。生态位：混合部署（VM + 容器）统一发现 + 治理规则面 + 多环境/多租户聚合（行业对标报告 §5.3 判断；本产品以多集群聚合 + namespace 承载这一生态位）。

**设计输入三层**（行业对标报告 §6）：

1. **直接继承**（原产品 10 万级验证过的资产）：心跳即注册幂等对账、四级摘除级联、两段式权重发布、逻辑实例、readiness 门控、三级地址容灾、错误码驱动容错、批量贯穿、状态 API 排障面（对照能力基线 §5 的 12 项资产）。另有**双轨分离**为原产品组件层结构实践（基线 §2.5/§2.7），随 D5 延续，不属 §5 资产条目。
2. **现代化改造**：传输 gRPC 化、复制报文增量化、自我保护显式化、可观测对齐 Prometheus/tracing 事实标准、管理面规则下发闭环化。其中对标报告建议的部分项**经评审明确偏离**：「数据版本/世代」「peer 定期对账」被否决（临时数据最终一致，见 D3/D6）；「Distro 负责制」后置为规模选项（D4）；「告警」「发现通道限流」列入[路线图后置项](../product/artemis-next-roadmap.md)。
3. **从零新建**：安全（认证/TLS/RBAC）、持久化与双端快照、客户端生态（starter/生命周期 API/多语言）、工程基线（测试/CI/容器化/滚动升级）。

## 2. 总体架构

### 2.1 三服务与依赖矩阵

产品拆为三个可独立部署的服务，同一代码库三种装配形态：

```
  服务提供者 SDK ──心跳/注册──► ┌──────────────────┐
  (Java 8 → Go/Rust)           │  registry 集群 A  │◄──对等复制(peer)──► registry 节点 ×N
                               └────────┬─────────┘
                                        │ ③
                                        ▼
  服务消费者 SDK              ┌───────────────────────┐      ③
  (Java 8 → Go/Rust)          │  discovery-service ×N │◄───────── registry 集群 B（可更多）
       ◄──查询/订阅·推送────── │  独立投影 · 多集群聚合  │
                               └────▲─────────────▲───┘
                                    │①            │②
                            ┌───────┴─────────────┴────────┐
                            │      management-service      │
                            │ 治理规则 · 账户 · 审计 · console│
                            │      外部 DB (MySQL/PG)      │
                            └──────────────────────────────┘

  边的方向（协议语义，以文字为准）：
  ① 规则流（版本 + ack）：management → discovery（下行）
  ② 订阅关系 / 生效水位：discovery → management（上行）
  ③ 数据分发：registry → discovery（订阅流 + 快照 bootstrap；每个 discovery 独立订阅 1..N 个集群）
  ④ 读注册表视图：management → registry（只读，registry 集群 0..N 配置化；图中未画，见依赖表）
```

| 服务 | 持有数据 | 上游依赖（配置化） |
|---|---|---|
| registry-service | 租约事实、注册表、实例级摘除记录（期 1 内存态 → 期 3 持久化） | **无**（零外部依赖） |
| discovery-service | 只读投影 | registry 集群 **1..N**（实例数据）；management **0..1**（启用治理时订阅规则） |
| management-service | 治理规则、账户、审计（DB） | 外部 DB；registry 集群 **0..N**；discovery **0..N** |

要点：

- **统一网络协议**：无论 all-in-one 还是分拆部署，三服务之间一律走网络协议（gRPC）通信，不做进程内直连优化。all-in-one 只是「同一进程内起三个 server、各自端口」的打包形态。
- **discovery 不组集群**：每个 discovery 节点是一个独立投影副本，实例间零协议。任一节点故障由消费方 SDK 地址容灾消化——discovery 无集群状态、不提供「存活节点列表」API，SDK 以**配置地址列表 + 历史成功连接缓存 + 熔断换址**容灾（registry 侧才保留服务端节点列表的完整三级容灾）；新节点冷启动独立 bootstrap。多集群聚合 = 单节点订阅多个 registry 集群后本地合并，聚合逻辑只发生在本地，无跨源污染。
- **discovery ↔ management 是双向数据流而非循环依赖**：规则下行（management → discovery，版本 + ack 生效确认）；状态上行（discovery → management，订阅关系与生效水位）。

### 2.2 端口与协议

| 通道 | 协议 | 说明 |
|---|---|---|
| SDK → registry（心跳/注册、实例管理） | gRPC（主）+ HTTP（辅） | 外部端口；gRPC client-stream 承载心跳 |
| SDK → discovery（查询/订阅） | gRPC（主）+ HTTP（辅） | 外部端口；gRPC server-stream 承载推送 |
| registry ↔ registry（复制/成员） | gRPC | 内部 peer 端口，与外部端口分离 |
| registry → discovery（数据分发） | gRPC | 内部端口；订阅流 + 快照 bootstrap |
| discovery ↔ management（规则流/状态上行） | gRPC | 内部端口 |
| console / Admin API | HTTP | management 外部端口 |

所有 proto 按服务与版本组织（`artemis.common.v1`（公共数据模型）/ `artemis.registry.v1` / `artemis.replication.v1` / `artemis.projection.v1` / `artemis.discovery.v1` / `artemis.management.v1`），作为语言中立的稳定契约，居多语言 monorepo 顶层 `proto/` 目录（组织与治理见[proto 契约规范](../dev/proto-contract.md)；Java 侧生成矩阵见[Java 技术选型](../dev/tech-stack-java.md) §2：各消费模块从契约目录各自生成）。

### 2.3 部署形态

v1.0 起提供三种部署形态：

1. **Docker 单机容器**：eval 最短路径，单容器起 all-in-one。
2. **虚拟机部署**：标准应用安装包（tar.gz），systemd/脚本维护。
3. **K8s/Helm 部署**：Helm chart，三服务可独立编排扩缩。

## 3. 技术栈与语言中立策略

| 项 | 决策 |
|---|---|
| 服务端先行实现 | Java 25 + Spring Boot 4.1（Spring 只在装配层，内核纯 Java——延续原产品内核零 Spring 的组件实践，能力基线 §1–§2） |
| 服务端后补 | Rust 实现 registry 数据面，**同一集群可混布 Java/Rust 节点** |
| 混合集群互通面 | 只需数据面协议互通（心跳/复制/分发/成员 proto）；management/discovery 无集群协议，Rust 无需实现 |
| 契约冻结策略 | 数据面 proto 经 v0.2–v1.0 稳定后再冻结，Rust 实现只做一次 |
| 客户端 SDK | 先 Java 8（照顾存量）+ Spring Boot starter；后 Go、Rust |
| 通信 | 内部 gRPC；外部 gRPC + HTTP 双协议；内部/外部端口分离 |

## 4. 数据面核心机制

### 4.1 registry-service

**心跳即注册（继承）**：SDK 本地实例集为唯一事实源；心跳 = 进程级全量实例集幂等上报（gRPC client-stream），断网重连自动对账，免显式注册状态机。心跳消息率 ∝ 进程数而非实例数（批量贯穿资产）。事实权判定：**心跳连接在哪个节点，该 service 的事实就在哪个节点**。

**不做版本管理（明确决策）**：注册数据是动态临时数据，最终一致即可。全对全无 leader 下任何全局单调版本都需要协调（等价于引入共识），复杂度不值得。正确性由自愈链承担（见下）。

**变更事件扇出**：心跳 diff 出变更才扇出（稳态心跳零复制流量）；增量小报文（service 级 diff）批量异步投递全部 peer；失败定向重试（指数退避 + 抖动）。全对全复制保留，负责制分片作为 v1.x 规模选项（20+ 节点）预留。

**丢失自愈链（无对账设计）**——「+实例 X」事件丢失后 peer 缺 X 的恢复路径：

1. X 的下一次真实变更 → 再扇出；
2. 客户端连接 TTL 轮换（默认 10min ± 随机抖动，带熔断换址）：客户端换到任意节点，全量心跳上报即修复该节点——**客户端轮换本身就是分布式对账**；
3. 新 peer 加入/重启 → bootstrap 全量拉。

registry 定期任务只有两个：租约过期清理、快照落盘。**没有 peer 间拉对账**。

**持久化（新建）**：

- 服务端快照：内存注册表定期落盘，重启先回放快照再向 peer 增量校验——消除「全集群重启依赖客户端风暴重注册」的结构性缺口。
- 客户端磁盘快照：SDK 持久化最后已知注册表，冷启动可回放。

**自我保护 2.0**：按服务粒度统计续约滑动窗口（替换全局单阈值）；保护态是一等显式状态——指标暴露、API 可查、经 discovery 下发数据带 `stale` 标记（消费方可感知降级）；阈值可配。

**readiness 门控（继承）**：快照回放 + peer 校验完成前不接客户端流量；修复原产品空集群启动死锁缺陷。

**集群成员（第一天约束）**：成员抽象接口 v0.1 进架构，静态配置实现先行；动态成员（K8s 成员源）后置版本。

**实例管理（治理期 1，内存态）**：Admin API 提供实例列表/详情、手动摘除/恢复——摘除是内存态记录（发现视图过滤，租约与心跳不受影响），随变更事件集群扇出保持一致；重启丢失，期 3 由 management/DB 持久化接管。摘除的可见出口：v0.1–v0.2 为 registry 管理查询 API 返回的**有效视图**（已应用摘除过滤的实例列表），v0.3 起为 discovery 查询/推送。状态 API 排障面（leases/config 类，继承资产）。

### 4.2 discovery-service

**投影构建**：启动/重连时向配置的 registry 集群 bootstrap 全量拉取，之后消费变更流；重连即重新 bootstrap（全量覆盖幂等，即自愈）。数据按 `(cluster, namespace, service, group)` 组织；**namespace 字段 v0.1 进数据模型**（多租户地基，后加代价大）。

**多集群聚合**：各源投影独立维护；查询/推送时刻按选择器合并（默认 union，可按 cluster/namespace 过滤）。

**推送模型（继承语义，协议升级）**：推送单元 = **service 实例集快照**（原子、幂等覆盖）；gRPC server-streaming 单流内天然有序；乱序/丢失窗口由兜底轮询收敛；订阅选择器 service/group/label，一流多目标。

**三层兜底轮询（继承资产）**：订阅推送失败或空服务 60s 主动拉、15min 全量刷新——at-most-once 推送的自愈底线。

**过滤器链 SPI（继承资产）**：治理启用时向 management 订阅规则流；规则本地缓存，management 不可用时用最后已知规则继续（逐 filter fail-open）。

**新鲜度标记**：查询响应携带状态元信息（上游断连中、上游自我保护态等），消费方可感知数据降级。

**哲学延续**：服务端不做主动健康探测，实例状态客户端自报；主动健康检查作为 v1.x 可选项。

## 5. 治理面设计

### 5.1 三期演进

| 期 | 载体 | 内容 | 持久化 |
|---|---|---|---|
| 期 1 | registry（v0.1–v0.2） | 实例管理 Admin API：列表/详情、摘除/恢复、状态 API | 内存态，重启丢失 |
| 期 2 | discovery（v0.3） | 发现管理：订阅关系查看、服务视图查询、新鲜度/上游状态 | 无状态（投影数据） |
| 期 3 | management + DB（v0.4–v0.5） | 四级摘除持久化、逻辑实例、分组路由、两段式发布、审计、账户 | MySQL/PG + Flyway |

期 3 接管后，期 1 的内存摘除升级为持久化：management 下发规则，执行点仍在 registry/discovery filter 链，重启后 management 重下发恢复摘除态。

### 5.2 API 与规则下发

**资源导向 API**（替换原产品 57 个表直透端点）：领域资源 `service` / `group` / `route-rule` / `release` / `ejection` / `logical-instance`；一个 API 完成一个运维意图（灰度发布 = 一个 release 调用）。

**规则版本 + ack 生效闭环**（替换原产品 sleep(2s) 轮询盲下发）：management 写 DB → 规则变更带版本推订阅流 → discovery 应用后回 ack → management 可见生效水位（哪个版本、多少节点已生效）。治理规则是持久数据，版本管理合理（与数据面临时数据不搞版本的决策对照）。

**继承的治理语义**：

- 四级摘除级联（instance → server → zone → group）+ 操作记录即状态（下线 = 可叠加、带原因、可审计的记录；恢复 = 反向记录）。
- 两段式权重发布（`weight / unreleased_weight` + 显式 `release`）；规则版本化 → diff 预览 + 一键回滚（回滚 = 指向历史版本的反向 release）。
- 逻辑实例：第三方/异构服务注册为静态实例，纳入统一发现视图。
- 分组路由：Group filter 语义延续。
- canary：自动创建专属规则组——route-rule + release 的预置派生用法，由 management 生成并下发，执行在 discovery 过滤器链。

### 5.3 console 与账户

- console：management 内嵌静态 SPA + REST，不引入独立前端部署单元；分页 + 模糊搜索（10 万实例可用性前提）、服务/实例详情、订阅关系视图（调用方视角）、操作审计流、自我保护态展示。
- 账户：认证 v0.1 起默认开启（各服务本地单账户）；账户体系（DB 持久化、统一认证）随 management v0.4 交付并接管各服务本地账户；RBAC（角色 × 资源 × 动作）v1.0；全部治理操作进审计表。
- management 可不部署：registry + discovery 独立可用（双轨分离资产延续），单机 eval 零 DB 依赖。

## 6. 安全与可观测

| 项 | 决策 |
|---|---|
| 认证 | v0.1 起默认开启（各服务本地单账户；v0.4 起由 management 统一账户接管），吸取 Nacos 3.0 才默认鉴权的教训；显式配置方可关闭（eval 场景） |
| TLS | v1.0 支持（内外通道均可启），内网部署可配明文 |
| RBAC | v1.0 |
| metrics | Micrometer + Prometheus 格式，v0.1 第一天起按版本递补：心跳/租约/保护态 v0.1、复制 v0.2、推送 v0.3 |
| 告警 | 走 Prometheus + Alertmanager 生态（不自建），关键告警规则（保护态触发、节点失联）随 v1.0 提供示例配置 |
| log | 结构化 JSON，v0.1 第一天 |
| trace | OpenTelemetry，v0.1 第一天 |

## 7. 关键决策记录

重大决策以 ADR 记录（[decisions/](../decisions/README.md)，一决策一文件：背景/决策/理由/否决方案/后果）。下表为总览索引：

| # | 决策 | ADR |
|---|---|---|
| D1 | 三服务拆分 + 统一网络协议（all-in-one 也是） | [adr-001](../decisions/adr-001-three-services-uniform-protocol.md) |
| D2 | discovery 不组集群 | [adr-002](../decisions/adr-002-discovery-no-cluster.md) |
| D3 | 数据面 AP + 无版本管理 | [adr-003](../decisions/adr-003-ap-no-versioning.md) |
| D4 | 全对全 + 增量报文，负责制后置 | [adr-004](../decisions/adr-004-full-mesh-replication.md) |
| D5 | 双轨 + 外部 DB（治理元数据） | [adr-005](../decisions/adr-005-dual-track-external-db.md) |
| D6 | peer 间无拉对账 | [adr-006](../decisions/adr-006-no-peer-reconciliation.md) |
| D7 | 推送单元 = service 实例集快照 | [adr-007](../decisions/adr-007-snapshot-push-unit.md) |
| D8 | Java 25 + Spring Boot 4.1 先行，Rust 后补混合集群 | [adr-008](../decisions/adr-008-java-first-rust-hybrid.md) |
| D9 | 安全内建（v0.1 默认认证） | [adr-009](../decisions/adr-009-security-by-default.md) |
| D10 | namespace 进 v0.1 数据模型 | [adr-010](../decisions/adr-010-namespace-day-one.md) |
| D11 | 多语言 monorepo：契约居仓库顶层 `proto/` 纯目录 | [adr-011](../decisions/adr-011-monorepo-top-level-proto.md) |

## 8. 与原产品的对照（资产继承与局限修复）

对照 [能力基线](../legacy/legacy-product-analysis.md) §5 资产 / §6 局限。

**§5 资产继承表**：

| 资产 | 处置 |
|---|---|
| #1 心跳即注册幂等对账 | 继承（gRPC client-stream） |
| #2 三级地址容灾 | 继承 + TTL 轮换承担分布式对账新职责 |
| #3 错误码驱动容错 | 继承（gRPC status 映射） |
| #4 四级摘除级联 + 操作记录即状态 | 继承（期 3 持久化） |
| #5 两段式权重发布 | 继承 + diff 预览/回滚 |
| #6 逻辑实例 | 继承 |
| #7 过滤器链 SPI | 继承（规则流 + ack 升级） |
| #8 三层兜底轮询 | 继承 |
| #9 readiness 门控 | 继承（修复空集群死锁） |
| #10 自我保护 | 继承语义，粒度/显式化升级（2.0） |
| #11 批量贯穿 | 继承 |
| #12 状态 API 排障面 | 继承 |

**§6 局限修复表**（23 条局限的处理归档）：

| 局限域 | 处置 |
|---|---|
| 传输与协议 #1–#3 | gRPC 长连接统一（心跳/推送/复制），消除 WS 截断与三套传输重复；delta 未用问题随「service 实例集快照推送 + 兜底轮询」语义消解；#3 后半「数据版本/世代暴露」被 D3 明确放弃（新鲜度标记替代） |
| 一致性与容量 #4–#10 | #4 增量扇出 + bootstrap（负责制 D4 后置）；#5 服务端快照；#6 客户端磁盘快照；#7 delta 窗口/version 单调性随 D3 零版本决策消解（无 delta 窗口与版本，兜底轮询承担）；#8 规则版本 + ack 生效水位（§5.2）；#9 自我保护 2.0；#10 成员抽象 v0.1 接口先行、动态成员后置 |
| 客户端工程 #11–#15 | #11 生命周期 API（close/优雅下线）；#12 单连接复用（gRPC stream 天然）；#13 回调有界队列、订阅快照引用（避免深克隆）为 SDK 设计约束；#14 starter 自动装配（v0.1 注册侧随 SDK 交付、v0.3 发现侧补全）；#15 新鲜度标记 |
| 管理面产品 #16–#18 | #16/#17 资源导向 API + console（分页/搜索/订阅视图/diff/回滚）；#18 鉴权→D9、审批流→后置项（[路线图](../product/artemis-next-roadmap.md) §4）、动态实例 metadata 由客户端事实源持有（心跳即注册语义内，设计性不可改）、逻辑实例（静态）metadata 可管理（期 3） |
| 安全 #19 | D9 内建 |
| 可观测与工程 #20–#23 | metrics/log/trace 第一天（告警走 Alertmanager 生态）、核心路径测试 + CI、容器化、滚动升级协议（proto 版本化 + v1.0 滚动升级验收）、全栈升级 Java 25/SB 4.1、proto 契约化消除字符串状态码 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.5 | 2026-10-10 | 启用 decisions/ ADR：D1–D11 迁移为 adr-001~011（背景/决策/理由/否决方案/后果），§7 改为总览索引（链接各 ADR） |
| 1.4 | 2026-10-10 | review 修复：§7 决策表补 D11（多语言 monorepo 与顶层 proto/ 契约目录的结构决策，含否决方案） |
| 1.3 | 2026-10-10 | proto 组织定案多语言 monorepo 落地形态：契约居多语言 monorepo 顶层 `proto/` 纯目录（`artemis-proto` Maven 模块取消），链接新增的 proto 契约规范（wire 兼容与变更流程跨语言单一来源） |
| 1.2 | 2026-10-10 | proto 组织补 `artemis.common.v1` 公共数据模型组，并链接 Java 技术选型的工程落地形态（随 dev 文档定案同步）；修正资产引用失真：「内核零 Spring」「双轨分离」标注为组件层实践的延续（基线 §1–§2 / §2.5–§2.7），不再冒称 §5 资产条目 |
| 1.1 | 2026-10-09 | review 修复：拓扑图边方向/依赖边修正（④ 补全）；discovery 容灾语义与 SDK 范围对齐；局限归档补 #7/#8/#13/#18 子项与滚动升级；§1 二层偏离显式标注；canary 补架构依据；账户/认证/指标/namespace 时间线对齐；生态位引用词项还原 |
| 1.0 | 2026-10-09 | 初版：三服务架构、数据面/治理面机制、关键决策、资产/局限对照 |
