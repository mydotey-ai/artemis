# Phase 5：discovery 多集群聚合

版本: 1.5    更新时间: 2026-10-24

## 目标

单 discovery 节点独立订阅 1..N 个 registry 集群：各源投影独立维护、上游状态按集群独立维护，查询/推送时刻本地合并（默认 union，可按 cluster/namespace 过滤）——聚合只发生在本地、无跨源污染（ADR-002）。

## 范围

做什么：

- discovery-core：多上游同步引擎编排（每 cluster 独立 `ProjectionSynchronizer` + 独立上游状态）、查询/订阅/对账的选择器 cluster 过滤维度。
- discovery-server：多上游配置（endpoints 按 cluster 分组）与装配。

不做什么：

- 跨源冲突消解（同 ServiceKey 多源并存即 union 并列，语义随 ③ 设计定稿）。
- 摘除过滤（phase-6）、发现管理 API（phase-7）、SDK 侧多集群感知（phase-8）。

## 涉及模块

| 模块 | 内容 |
|---|---|
| `java/server/artemis-discovery-core` | 多同步器编排、选择器 cluster 维度 |
| `java/server/artemis-discovery-server` | 多上游配置与装配 |

proto：`discovery.v1` 选择器是否扩展 cluster 过滤字段随 ③ 设计定稿（wire 兼容只增）。

## 验收方式

对应测试文档：[phase-5-multi-cluster-test.md](phase-5-multi-cluster-test.md)

## 产品设计（③ 补）

### 现状与缺口

phase-2–4 已将领域层按 per-cluster 形态建成：`ProjectionRegistry` 按 cluster 分表（union 查询天然成立）、`UpstreamStatuses` per-cluster 上报、`ClusterServiceInstances`/`SubscriptionEvent` 携带来源 cluster（ADR-014 投影边界注入）。缺口四处：**装配只建单同步器**（`UpstreamConfig` 单 clusterId + 平铺 endpoints，`DiscoveryConfiguration` 单 `ProjectionSynchronizer` bean）、**选择器无 cluster 维度**（`DiscoverySelector` 仅 services/labels，proto 三 request 同）、**readiness 单上游语义**（任一首轮 SYNCED 即 ready）、**查询/订阅/对账无 cluster 过滤能力**。本 phase 补齐四处，同步器内核（`ProjectionSynchronizer`）与订阅扇出内核（`SubscriptionManager` 表锁串行）不动。

### 多上游配置（discovery-server）

- 新配置键 `artemis.discovery.registry.clusters`（静态），值为 JSON map：`{"clusterA":"host:port,host:port","clusterB":"host:port"}`——键 = clusterId，值 = 逗号分隔端点列表。
- **SCF property 形态**（TypeConverter 管道，机制核实于 scf 1.6.4 源码）：
  - 自定义 `TypeConverter<String, Map<String, List<String>>>`（Gson `TypeToken` 反序列化到嵌套泛型；泛型擦除经 `(Class) Map.class` 传递，与 SCF 内建 converter 同法）。Gson 版本由服务端 parent dependencyManagement 管理（随 grpc 线对齐），discovery-server 显式声明该依赖——既有受管组件的首次直接使用，随本次 review 定案。
  - 经 `ScfProperties.staticProperty` 通用入口（converter 注入）取得 `Property<String, Map<String, List<String>>>`（`setStatic(true)`，字段保存）。
  - **converter 非法值返回 null、不抛异常**（关键语义）：SCF `DefaultConfigurationManager` 对 converter 抛异常的行为是 catch 后「ignore the source」打 ERROR 日志并跳过该 source（异常被吞、日志误导）；返回 null 则 `AbstractConfigurationSource.convert` 视为未命中落默认值——null 路径让 fail-fast 收敛到使用方构造器，出带配置键与期望格式的清晰消息。valueFilter 抛异常同样被 manager 吞掉，不能承担校验。
  - 内建 `StringToMapConverter`/`StringToListConverter` 组合不可用：其按逗号/冒号切分、`split(":")` 长度≠2 即静默丢项，端点 `host:port` 含冒号无法表达，且静默丢坏项违背 fail-fast。
- **替换** `artemis.discovery.upstream.clusterId` + `.endpoints` 两键（v0.3 未发布，无兼容负担；其余 `artemis.discovery.upstream.*` 调优键保留）；`UpstreamConfig` 消费 property 产出 `List<Upstream>`（record `Upstream(String clusterId, List<Endpoint>)`）。
- 校验 fail-fast（构造器内，对齐既有 UpstreamConfig 校验模式；未配置与 JSON 语法错经 converter null 语义收敛到同一条）：值 null/空 map / clusterId 空白 / 端点列表空或非法（沿用现有 host:port 解析规则）。JSON 对象键天然唯一（同键重复由解析器后值覆盖），不设重复校验。
- 上游认证**共享**：`UpstreamAuthConfig` 不变、同一凭证作用于全部集群（v0.3 评测场景单账户；per-cluster 凭证待真实部署需求，实现处以 `ponytail:` 注记天花板）。

### 选择器 cluster 维度（core + proto）

- proto（wire 兼容只增，field number 3）：`QueryRequest` / `SubscribeRequest` / `ChecksumRequest` 各加 `repeated string clusters = 3`。语义：空 = 全部 cluster（union 默认）；非空 = 精确集合；**未知 cluster 名静默无命中**（不报 INVALID_ARGUMENT——cluster 清单是服务端部署拓扑，不耦合 SDK）；集合内空白 cluster 名 = INVALID_ARGUMENT（对齐 services 显式字段空白校验先例）。
- domain：`DiscoverySelector` 加 `Set<String> clusters` + `matchesCluster(String)`（空集恒真）；`QueryRequest` record 加 `Set<String> clusters`（契约原样入参，归一在应用服务，与 services 同）。
- 消费点过滤落点：
  - `ProjectionRegistry.query` / `checksums`：外层 cluster 迭代处 `matchesCluster` 跳过（`ClusterProjection` 内部不动）。
  - `SubscriptionManager.SubscriptionImpl.deliver`：开头 `matchesCluster(cluster)` 不命中即返回；基线经 `registry.query(selector)` 自动过滤。
  - 新鲜度：`DiscoveryQueryService` / `DiscoveryReconciliationService` 的 `upstreams` 快照在选择器 clusters 非空时过滤——消费方只关心所查 cluster 的新鲜度（过滤与未过滤一致性无关紧要，取正确的一侧）。
- mapper：`DiscoveryProtoMapper` 三 request → selector 补 clusters 映射。

### readiness 全量同步门（application）

`DiscoveryReadinessGate` 改期望计数构造：`new DiscoveryReadinessGate(expectedUpstreams)`，`markSynced(String cluster)` 按 cluster 去重累计，**全部到达才 ready**。语义定案：任一配置上游未完成首轮 bootstrap，其服务缺席于 union——查询即以不完整数据冒充真相，违反「空数据不作真相」（资产 #9），故全量门。断连不降级不变（D-D5）；某上游永久不可达 → 不 ready + 日志排障（上游状态出口 phase-7）。

### 多同步器编排（discovery-server）

- `GrpcProjectionSource` 构造改收单 `Upstream`（端点轮换在该 cluster 的端点列表内），每 cluster 一个实例，channel 池 per 实例、close 释放。
- 新组合类 `UpstreamSynchronizers`（server `upstream` 包）：per cluster 建 (source, synchronizer) 对，`start()`/`close()` 全体生命周期；单 bean `@Bean(initMethod="start", destroyMethod="close")` 替换原单同步器 bean。`ProjectionSynchronizer` 逐参不变（本已 per-cluster）。
- 并发语义：N 同步线程共享 `SubscriptionManager.runWithFanout` 表锁——per-cluster 变更序保持（per-cluster 单 worker），跨 cluster 无顺序约束（数据独立），锁串行代价 = 批处理纯内存（既有 FIX-1 论证不变）；`UpstreamStatuses` CHM per-cluster 独立上报（phase-2 已预留多集群形态）。

### 语义定案汇总

- **union 并列**：同 ServiceKey 多 cluster 并存 → 查询/基线/推送按 cluster 并列多条（`ClusterServiceInstances` 一条/cluster），无冲突消解（phase 范围外，本 phase 不引入）。
- **跨源隔离**：每 cluster 独立投影表、同步器、退避重连；某 cluster 断连只冻结该 cluster 投影 + 其新鲜度 DISCONNECTED，其余 cluster 不受影响。
- **cluster 集合静态**：配置驱动、启动定死（对齐 registry 静态成员哲学）；运行期增减上游属动态成员范畴，后置。

### 架构同步声明

本 phase 全部决策落在架构文档已载设计的实现细化（架构 §4.2「多集群聚合：各源投影独立维护；查询/推送时刻按选择器合并（默认 union，可按 cluster/namespace 过滤）」、roadmap v0.3「多集群聚合：单节点订阅多个 registry 集群，本地合并（union + 选择器过滤）」、ADR-002/014），无架构文档/ADR/roadmap/proto 契约规范修订；proto 变更仅 discovery.v1 三 request 加 optional repeated 字段（wire 兼容规则内，无契约规则变更）。产品文档配置说明随 ⑩ 同步。

## 实现结果（⑦ 补）

### 实现要点

- **proto**：discovery.v1 三 request 各加 `repeated string clusters = 3`（wire 兼容只增），顶部注释补选择器三维度描述。
- **core**：`DiscoverySelector` 加 clusters 分量（2 参便捷构造保留既有调用点）+ `matchesCluster`（空集恒真）+ `requireValid`（原 `requireExplicitServicesValid` 扩展空白 cluster 校验后更名）；`ProjectionRegistry.query/checksums` 外层按 matchesCluster 过滤；`SubscriptionManager.deliver` 开头 cluster 守卫；`UpstreamStatuses.snapshot(selector)` 过滤重载；`DiscoveryReadinessGate` 改期望计数构造 + `markSynced(cluster)` 去重累计（全量门）。
- **server**：`UpstreamConfig` 重写为 JSON map 解析（`TypeConverter<String, Map<String, List<String>>>` + Gson，先 Map<String,String> 反序列化再按逗号切分——wire 值形态是逗号分隔串非 JSON 数组）；`GrpcProjectionSource` 构造改收单 `Upstream`（端点轮换限于本 cluster）；新 `UpstreamSynchronizers` 组合 per-cluster (source, synchronizer) 对（start/close 全体生命周期，close 先停线程再关 channel）；装配单 bean 替换原 source+synchronizer 两 bean；gson 进 discovery-server 依赖（parent 钉 error_prone_annotations 2.50.0 消 requireUpperBoundDeps 冲突）。
- **converter null 语义（SCF 机制约束）**：非法 JSON 返回 null 不抛——manager 对 converter 异常的行为是吞掉并跳过 source；null 落默认值使 fail-fast 收敛到 UpstreamConfig 构造器出带配置键的清晰消息。
- **测试**：单测 5 处（selector/registry/subscription/gate/mapper）+ 新 `UpstreamConfigTest` + 新 `DiscoveryMultiClusterIT`（6 用例：union/过滤/订阅归因/对账过滤/新鲜度独立/readiness 全量门，含 registry 后起端口预留机制）；三个既有 IT 配置键切换。

### review 修复记录（⑥ findings）

| # | severity | 位置 | 问题 | 处理 |
|---|---|---|---|---|
| 1 | medium | discovery-server `application.properties` | 模板残留旧键（`upstream.clusterId=default` + 注释 `upstream.endpoints`），与新配置键漂移 | 改为 `artemis.discovery.registry.clusters` JSON 注释示例 |
| 2 | low | `UpstreamConfig` javadoc | `// ponytail:` 写进 javadoc 文本、挂点错位（应在共享 auth 使用处） | javadoc 净化；注释放 `UpstreamSynchronizers` per-cluster source 构造处 |
| 3 | low | `DiscoveryMultiClusterIT` | union 用例对 cluster 内两服务有序断言，依赖 CHM 迭代序（违背 FIX-3 纪律） | `containsExactlyInAnyOrder` |
| 4 | info | `ProjectionSynchronizerTest`（预存，非本 phase 引入） | `failNextOpens.set` 晚于 worker 起，首轮 open 可抢先成功（⑤ 首跑暴露） | 移到 `startSynchronizer` 之前 |

## 文档同步（⑩ 填）

| 文档 | 变更 |
|---|---|
| `docs/product/artemis.md` | v1.20：核心概念补「多集群聚合」；新增 v0.3 phase-5 交付节（聚合/union/选择器/配置/readiness 全量门/工程形态）；phase-2 节的旧配置键标注为已被 phase-5 替换 |
| `docs/api/discovery-query-api.md` | v1.2：选择器加 `clusters` 行（空 = union、未知静默、空白 INVALID_ARGUMENT）；配置表 `upstream.clusterId`/`upstream.endpoints` 行替换为 `registry.clusters` JSON map；查询描述更新为多集群 |
| `docs/api/discovery-subscription-api.md` | v1.1：proto 片段与订阅过滤语义补 `clusters`；错误表校验项含 clusters |
| `docs/api/discovery-reconciliation-api.md` | v1.1：选择器语义补 `clusters`；新鲜度列表同源过滤 |
| `docs/arch/artemis-next-architecture.md` | 无变更——本 phase 为 §4.2 已载设计的实现细化（各源投影独立维护、查询/推送时刻按选择器合并、默认 union）。注：§4.2「可按 cluster/namespace 过滤」中的 namespace 过滤经显式 `ServiceKey`（namespace 为 key 内禀分量，ADR-010）表达，未新增独立维度 |
| `docs/decisions/` | 无新增 ADR——决策沿用 ADR-002（discovery 不组集群、本地聚合）与 ADR-014（cluster 来源标识于投影边界注入） |
| 索引 | 无新增文档（两份 phase 制品在 ① 已登记 `phases/README.md`），索引无需变更 |

## 更新历史

| 1.5 | 2026-10-24 | ⑩ 文档同步清单（产品文档 + 三份 API 文档；架构/ADR/索引无变更及理由） |
| 1.4 | 2026-10-24 | ④⑦ 实现与 review 修复记录（4 findings） |
| 1.3 | 2026-10-24 | review 二轮修订：property 形态定案 `TypeConverter<String, Map<String, List<String>>>`（Gson TypeToken）；converter 非法值返回 null 不抛（SCF 异常吞 source 语义核实） |
| 1.2 | 2026-10-24 | review 修订：配置键改 `artemis.discovery.registry.clusters`、值改 JSON map 形态（SCF TypeConverter + Gson） |
| 1.1 | 2026-10-24 | ③ 设计定稿：多上游配置/选择器 cluster 维度/readiness 全量门/多同步器编排 |
| 1.0 | 2026-10-24 | 开工骨架 |
