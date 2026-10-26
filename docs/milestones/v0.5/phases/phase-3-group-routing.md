# Phase 3：分组路由（Group filter 全语义）

版本: 1.3    更新时间: 2026-10-27

## 目标

交付 route-rule 的执行点：discovery 过滤器链按实例 metadata 选择器实现分组路由——查询 / 推送 / 对账三出口一致，规则变化视图扇出（复用 phase-2 规则缓存与规则流）；roadmap v0.5「分组路由（Group filter 全语义）」的交付主体。

## 范围

**做什么**：

- `GroupFilter`（discovery-core domain）：`RouteRules` 可见性判定（selector 等值匹配 + 生效权重承接语义，见 §1）+ volatile 整集替换。
- `RouteRules` 增执行判定与扇出索引方法（`isVisible` / `rules(ServiceKey)` / `services()`）。
- `RouteRuleSynchronizer` 接执行点：应用 = `runWithFanout` 表锁内「`filter.replace` + 新旧规则集对称差扇出直投」（ejection D-E6 同构）；补齐 `everSynced` 守卫语义统一。
- 装配：`GroupFilter` 进 `DiscoveryFilterChain`（management 启用时），三出口 + checksums 全走同一链。
- 结构债 #9 收口：抽同步器共享骨架（ejection + routeRule 两同步器，phase-2 review 触发条件在本 phase 成立）。
- 结构债 #10 收口：两规则流 gRPC source 抽共享 channel 池 / 认证头 / 端点轮换 / 订阅标识 helper（投影 / 逻辑实例 source 形态异构，不动）。

**不做什么**：

- proto / wire 变更（零——phase-2 流契约已含执行所需全部字段）；无新 HTTP API 端点；无新配置键。
- 非零权重的比例切分服务端执行（归调用方侧消费，定案理由见 §1；管理面 API 已可读取比例）。
- canary 派生规则组（phase-4）、console 管理版（phase-5）。
- management 侧任何变更（phase-2 已交付执行所需全部能力）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| artemis-discovery-core | domain 新增 `GroupFilter`；`RouteRules` 增 `isVisible`/`rules(ServiceKey)`/`services()`；infrastructure 抽 `ManagementRuleSynchronizer` 共享骨架，`EjectionRuleSynchronizer`/`RouteRuleSynchronizer` 收敛为子类（后者补扇出与执行接入） |
| artemis-discovery-server | `DiscoveryConfiguration`：`GroupFilter` bean + 进链 + synchronizer 新依赖装配；upstream 抽两规则流 source 共享 helper（channel/轮换/认证/订阅标识） |

## 验收方式

对应测试文档：[phase-3-group-routing-test.md](phase-3-group-routing-test.md)

## 产品设计（③ 补）

### 1. 执行语义（核心定案：可见性谓词 + 权重承接二值）

对照架构 §4.2（过滤器链 SPI，逐 filter fail-open）、§5.2（分组路由：filter 按 metadata 选择器过滤）、[ADR-013](../../../decisions/adr-013-group-as-instance-metadata.md)、phase-1 词汇定稿（等值选择器 / 权重动作 / 兜底目标 / 「权重分配是调用方侧消费语义」）、phase-2（执行只用生效列 `weight`；流内容 = ACTIVE 集）。

**规则定位**：规则按 `(ServiceKey, name)` 定位、按 service 生效——服务无 ACTIVE 规则 → 全可见（不过滤，无规则 = 无治理意图）；服务有多条规则 → **AND**（每条规则独立约束，与过滤器链多 filter AND 语义同构，顺序无关）。

**单规则判定**（纯函数，落 `RouteRules`，对称 `EjectionRules.isEjected`）：

```java
/** 目标分类：非空 selector 目标（多键 AND 等值匹配实例 metadata）命中；未命中任何
 * 非空 selector 的实例归兜底目标（空 selector，至多一条——等值重复校验已保证唯一）。 */
boolean isVisible(ServiceKey service, Map<String, String> instanceMetadata)
```

- 实例命中目标集 = 全部非空 selector 且 selector ⊆ 实例 metadata 的目标；命中集为空 → 归兜底目标（存在时）。
- **可见 ⟺ 命中目标（或兜底）中任一生效列 `weight` > 0**；`weight = 0` 的目标不承接流量，其实例摘出发现视图。
- 边界语义：实例被非空 selector 目标命中（即使该目标 weight = 0）即**不归兜底**——兜底只承接「不匹配任何 metadata 约定」的实例（phase-1 兜底目标定义的严格落地）；命中集内全部 weight = 0 且兜底 weight > 0 → 仍不可见。
- 逻辑实例（保留 cluster `logical`）与真实实例同判定——同一投影内实例，metadata 同源，跨 cluster 生效对齐 ejection（D-E5）。

**权重切分的服务端执行面 = 0 / 非 0 二值承接**，定案理由（否决两条备选）：

1. **否决「按权重抽实例子集」**（weight 30 → 该组 30% 实例可见）：破坏组内冗余（摘掉活实例）、切分精度随实例数缩放（3 实例表达不了 30%）、无注册中心先例（Nacos/Dubbo 权重均为客户端消费）。
2. **否决「权重注回实例视图」**（发现响应携带 per-target 权重）：filter 链契约是纯可见性过滤（架构 §4.2 SPI，`DiscoveryFilter` 布尔谓词），Instance 是投影事实，注入派生数据破坏「投影 = 上游事实」边界；且客户端不内置 LB（legacy §2.4 哲学，phase-1 词汇定稿），无消费面。
3. 采用二值承接：注册中心只表达与执行「谁承接流量」（weight > 0），比例切分意图保留在规则数据中由调用方（宿主 RPC 框架）消费——与 phase-1「动作词汇 = 权重分配、就近由宿主执行」一致。

**两段发布联动**（phase-2 语义在执行点的落地）：release 使生效列 `weight` 跨越 0 → 视图变化 + 扇出直投（§2）；未跨 0 的权重调整 → 视图不动（水位照常推进）；rollback = 反向 release，同构。编辑列 `unreleasedWeight` 永不参与判定。

**与 ejection 组合**：`DiscoveryFilterChain.of(ejectionFilter, groupFilter)`——链上 AND，摘除与路由正交叠加；fail-open 语义继承链契约（单 filter 抛异常放行 + WARN）。

### 2. 规则变化扇出（视图直投，ejection D-E6 同构）

`RouteRuleSynchronizer` 应用事件时（快照 / 增量 upsert / delete）：

- 新旧规则集幂等相等（`with`/`without` 返回自身）→ 跳过（无扇出、无替换）。
- 否则 `subscriptionManager.runWithFanout(() -> { filter.replace(next); fanoutDiff(old, next); })`——表锁内「规则替换 + 扇出」成对（ejection review FIX-1 同型：与投影线程「translate + apply」互斥，防双路径事件丢失）。
- **受影响服务 = old ∪ new 规则集的 service 键集**（route-rule 是服务内资源，selector 不跨服务生效——无 ejection 的 selector 全局生效波及全投影分支，扇出面更窄）。
- 逐受影响服务 × 全部 cluster × 逐投影实例：`oldRules.isVisible` vs `newRules.isVisible` 对称差 → 退出视图发 `DELETE`、进入视图发 `NEW`，经 `SubscriptionManager.onViewChanges` 直投。判定函数与 filter 同源（§1 单一实现）。
- 扇出全投影扫描承袭 ejection `ponytail:` 标注（低频运维操作，规则 × 服务规模增长后按 metadata 反向索引）。
- ack / 水位 / fail-open / 断流不清缓存 / readiness 不受影响：全部沿用 phase-2 既有行为，零变更。

### 3. 三出口一致

同一 `GroupFilter` 实例进 `DiscoveryFilterChain`，查询（`DiscoveryQueryService`）、推送（`SubscriptionManager` 翻译与基线）、对账（`DiscoveryReconciliationService` checksums）、admin 视图与 checksum 缓存全走 `chain.filter`——volatile 整集替换即时生效，出口间天然一致（ejection 先例同构，v0.3 phase-6 三出口纪律对齐）。全可见返回原 List 引用（checksum 零计算路径，D-E8）保留。未配置 management（0..1）→ 链不含 groupFilter，空链直通零开销。

### 4. 结构债 #9 收口：同步器共享骨架

phase-3 接入后 `RouteRuleSynchronizer` 与 `EjectionRuleSynchronizer` 结构全同（filter + registry + subscriptionManager + 扇出），差异仅事件/规则类型——phase-2 review「phase-3 接 filter 时抽共享 round 引擎」触发条件成立。抽 infrastructure 包抽象基类：

```java
/** 管理规则流同步骨架：单 worker 循环 + per-round 有界事件队列 + 全抖动退避重连 + fail-open。 */
abstract class ManagementRuleSynchronizer implements AutoCloseable {
    /** 打开 watch 流（本轮事件经 sink 转发；返回流句柄由骨架 try-with-resources 关闭）。 */
    protected abstract AutoCloseable watch(EventSink sink);
    /** 应用单事件（子类含 filter 替换 + 扇出 + ack）。 */
    protected abstract void apply(Object event);
    // 骨架持有：run/syncRound/drain、RoundEvents、backoff、disable/start/close、everSynced、
    // managementStatus 状态转移（SYNCING/SYNCED/DISCONNECTED 统一：SYNCED = 首次成功 apply 起）
}
```

- 两子类收敛为：事件 record + listener 适配（offer 进 sink）+ `apply`（含扇出）+ ack——骨架约 120 行 × 2 去重，退避 / 关闭 / 断连判定语义单点。
- 语义统一点（实现期落实）：SYNCED 状态转移统一为「首次成功 apply 起」（现 ejection 仅快照分支置位、route-rule 每事件置位，等价偏差收拢）。
- `ProjectionSynchronizer` 不并入（bootstrap 两段序异构，v0.3 已注明「简化」关系）；不为假想第三流预留扩展点。

### 5. 结构债 #10 收口：规则流 source 共享 helper

`GrpcEjectionRuleSource` / `GrpcRouteRuleSource`（各约 180 行）重复部分 = subscriberId 生成、channel 池 + 认证头拦截、端点轮换、ack 端点追踪、close 释放（约 100 行 × 2）。抽 upstream 包共享 helper（channel 池 + 认证 + 轮换 + 订阅标识 + watch 端点记忆），两 source 委派；watch 流适配与 stub 调用保留在各 source（规则类型差异所在）。投影 / 逻辑实例 source 形态异构（per-cluster 源、bootstrap 协议），不动。

### 6. review 定案登记（2026-10-27 通过）

- §1–§3 机制落点为架构 §4.2（过滤器链 SPI）与 §5.2（分组路由按 metadata 选择器过滤）已记载设计的落地细化，无机制变更；执行语义细化（权重二值承接 + 否决备选理由）登记产品文档随 ⑩，无原始架构文档修订、无新 ADR 级决策。
- 零 proto / wire / API / 配置变更——架构 §2.2 端口落点段无新增端口，不动。
- §4/#9、§5/#10 收口为 phase-2 review 登记债务的触发条件兑现（「phase-3 接 filter 时收口」），工程内部重构、无对外语义变化。
- 触及的原始文档：产品文档（执行语义，⑩ 同步落点）。

## 实现结果（⑦ 补）

**实现要点**：

- `RouteRules`（discovery-core）：内部结构改 per-service 分组索引（`Map<ServiceKey, Map<String, RouteRule>>`，判定与扇出 O(1) 定位）；新增 `rules(ServiceKey)` / `services()` / `isVisible`（判定纯函数单点，filter 与扇出同源）。
- `GroupFilter`（domain）：volatile 整集替换 + 委派 `RouteRules.isVisible`，形态对称 `EjectionFilter`；进链 `DiscoveryFilterChain.of(ejectionFilter, groupFilter)`（AND 叠加，三出口 + checksums 全走同一链）。
- `RouteRuleSynchronizer` 接执行点：`runWithFanout` 表锁内「`filter.replace` + 对称差扇出」成对（ejection FIX-1 同型）；受影响服务 = 新旧规则集 service 键集并（无 selector 全局波及分支）；`rules()` 委派 filter 同源视图。
- **#9 收口**：抽 `ManagementRuleSynchronizer` 包级抽象骨架（worker 循环 / per-round 队列 / 全抖动退避 / `EventSink` 转发 / close / disable / `fanoutVisibilityDiff` 模板 + `applied` 收尾）；两子类收敛为「watch 适配 + apply（含扇出）+ ack」，ejection 行为不变（`EjectionRuleSynchronizerTest` 9 用例 + `DiscoveryEjectionFilterIT` 4 用例原样通过）；SYNCED 状态转移统一为「首次成功 apply 起」（等价收拢，ejection 增量后幂等置位）。
- **#10 收口**：抽 `ManagementRuleChannels`（channel 池 + 认证头 + 端点轮换 + 订阅标识 + ack 端点记忆），两规则流 source 各持一份（独立池独立轮换）委派；投影 / 逻辑实例 source 不动。
- 装配：`DiscoveryConfiguration` 增 `GroupFilter` bean + 进链 + synchronizer 六参构造；零 proto / API / 配置变更。

**实现期语义定案（§1 边角，落地时确认）**：创建规则未 release 时生效列全 0 = 全不承接（phase-2「初始 weight = 0」+ 本 phase 二值承接的组合后果，phase-2 不执行故未显现）——「创建 = 定义结构、release = 生效」的严格执行语义；安全姿势 = INACTIVE 创建（流不下发）→ release → activate。产品文档随 ⑩ 明示。

**⑤ 首跑轮内修复（非 review findings）**：IT `release`/`rollback` 路径漏 `{name}` 段命中 PUT 路由 → 405；`registry ReplicationFanoutIT` 偶发负载超时（phase-2 已知先例）复跑通过。

**code-review 修复（max 级 7 findings：全修）**：

1. **[中高] 空 targets 规则全摘出**：`visibleUnder` 对空 targets（management 校验 1–10 条但 wire 不设防）视为无治理意图全可见——规则数据损坏不得静默摘除整个服务（纵深防御，fail-open 精神）；补 `should_tolerate_malformed_rule_as_no_governance` 锁定。
2. **[中] 多兜底目标静默取最后**：违反「至多一条」等值约束时改为取首个生效（targets 保序，确定性降级）；同用例锁定。
3. **[低] dev-state.md 重复 fix 行**（本工作流编辑事故）：删除。
4. **[低] `fanoutVisibilityDiff` per-service 重取 `clusterIds()`**：提到服务循环外（每扇出一次，免 |affected| 次 Set 拷贝——自 ejection 复制来的模式，提取时顺手修正）。
5. **[中] 幂等无扇出用例 sleep(200) 弱锚点**：改 `await(appliedVersion == 2)` 确定性锚点后断言。
6. **[低] `latestReleaseId` 字符串 split 解析 JSON**：改 gson `JsonParser`（phase-2 IT 同法）。
7. **[低] 测试文档用例名与实际方法名不一致**：对齐 `should_keep_last_known_rules_when_disconnected`。

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.34）：新增 v0.5 phase-3 交付节（执行语义与权重二值承接、兜底与多规则 AND、视图直投扇出、三出口一致、「创建未 release 全不承接」运维须知与安全姿势、纵深防御、#9/#10 收口工程形态）；phase-2 节「执行归 phase-3」状态句更新为指向本节。
- [route-rule-stream-api.md](../../../api/route-rule-stream-api.md)（1.1）：§4 「phase-2 只缓存不执行」边界更新为执行已接入（Group filter + 视图直投，摘除流同型）。
- admin-api.md / discovery-admin-api.md / 架构 / ADR / proto 契约：无变更——零 API/端口/机制变更（§6 定案），执行语义细化按定案落产品文档。
- 索引 / CLAUDE.md：无过时项（无新文档、无新模块、构建命令不变）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-27 | ⑨ 人工验证通过；⑩ 文档同步登记（产品文档 1.34 / route-rule-stream-api 1.1） |
| 1.2 | 2026-10-27 | 实现结果 + code-review 修复登记（7 findings 全修）；实现期语义定案（未 release 全不承接边界） |
| 1.1 | 2026-10-27 | 产品设计定稿（review 通过，§6 登记）：执行语义（可见性谓词 + 权重二值承接 + 否决备选）、扇出、三出口一致、结构债 #9/#10 收口方案 |
| 1.0 | 2026-10-27 | 开工骨架 |
