# Phase 2：两段式权重发布与回滚

版本: 1.4    更新时间: 2026-10-26

## 目标

交付 release 资源与两段式权重发布语义（编辑写 `unreleasedWeight`，一个 release 调用完成一个运维意图：拷贝到 `weight` 生效）、diff 预览与一键回滚（指向历史版本的反向 release），规则经既有版本化规则流形态下发、生效水位经 v0.4 phase-5 出口可见——roadmap v0.5 验收要点「灰度发布端到端（一个 API 完成）、回滚生效」的交付主体。

## 范围

**做什么**：

- 两段状态机定稿：生效列（`weight`）唯一写入口 = release / rollback，编辑通道（create/update）收紧为只写编辑列与结构。
- release 记录资源（append-only 快照，回滚历史源）+ release / rollback / diff 预览 / releases 列表四个 API。
- route-rule 规则流下发：`RouteRuleService`（Watch + Ack，版本化 + ack 水位，复用 v0.4 phase-5 形态）；生效水位出口 `GET /admin/v1/status/rules` 增 routeRules 节。
- discovery 侧规则缓存消费（含本地已应用版本观测对照）——**不执行**（过滤器链归 phase-3）。
- V6 双方言 migration（`artemis_route_rule_release`）+ MyBatis store 与内存态 store。
- release / rollback 全量审计。

**不做什么**：

- selector 匹配执行语义与视图扇出（phase-3 Group filter：规则变化引起的视图变更直投归 phase-3 接执行点时补）。
- canary 派生用法（phase-4 生成专属规则组）。
- console UI（phase-5；本 phase API 先行）。
- 不做跨规则类型统一规则流重构（独立 `RouteRuleService` 决策见 §4；ejection 流零改动）。
- 不做跨表事务（两写一致性经写序 + 幂等重试自愈，见 §8）。
- INACTIVE 规则不下发（流内容 = ACTIVE 集；语义见 §4）。

## 涉及模块

| 模块 | 变更 |
|---|---|
| proto/management/v1 | 新增 RouteRuleService 组：`RouteRuleStatusValue` enum、`RouteRule`/`RouteRuleTarget`/`RouteRuleKey` 消息、`RouteRulesEvent`（snapshot/upserted/deleted + version envelope）、`WatchRouteRules`/`AckRouteRules` |
| artemis-management-core | domain 新增 `ReleaseType`/`RouteRuleRelease`/`RouteRuleReleaseStore`/`RouteRulePublisher`/`RouteRuleSubscriptions`/`RouteRuleSubscriber`；`EjectionAckRegistry` 泛化改名 `RuleAckRegistry`（ejection 引用随动）；`RouteRules` 校验增强；application `ManagementService` 增 release/rollback/diff/releases 用例；infrastructure `InMemoryRouteRuleReleaseStore`/`RouteRuleDispatcher` |
| artemis-management-server | `V6__route_rule_release.sql` 双方言；`RouteRuleReleaseEntity`/`Mapper`/`StoreImpl`；`AdminController` 四端点 + DTO 收紧；`StatusController` routeRules 水位节；`RouteRuleGrpcService`（watch + ack） |
| artemis-discovery-core | domain 新增 `RouteRule`/`RouteRules`/`RouteRuleSource`/`RouteRuleListener`；infrastructure `RouteRuleSynchronizer` |
| artemis-discovery-server | `GrpcRouteRuleSource`；`UpstreamsView`/`AdminController` management 节增 routeRules 对照 |

## 验收方式

对应测试文档：[phase-2-two-phase-release-test.md](phase-2-two-phase-release-test.md)

## 产品设计（③ 补）

### 1. 两段状态机（编辑通道收紧，plan 待定项定稿）

对照架构 §5.2（资源 `release`、两段式 + 版本化回滚）、legacy §2.5 / §5 资产 #5（两段式权重发布）、product-overview §6 新发现缺陷「SQLite 分支破坏两段式发布（编辑清空已发布权重）」。

**核心不变量：已存在目标的生效权重值（`weight`）只能经 release / rollback 变更**——编辑通道物理上碰不到生效值列，从根上消除 legacy「编辑清空已发布权重」缺陷模式：

- **create**：请求体 targets 只接受 `selector + unreleasedWeight`（DTO 删除 weight 字段）；初始 `weight = 0`。创建 = 定义结构，release = 生效（原产品同姿势：`create-route-rule` 后需 release）。
- **update（PUT）**：全量替换 targets（`selector + unreleasedWeight`）与 status；**weight 按 selector 等值继承**——新 target 的 selector map 与旧 target 完全相等则继承其 weight，无匹配则 0。编辑新权重 → 旧生效值保留 → diff 可见；发布前生效面不受权重值影响。
- **结构边界（明示妥协）**：targets 集合（selector 结构）是单份直改的——删除/新增 target 即改变生效面（如删兜底目标即移除其生效承接）。结构整体双版本（`unreleased targets`/`released targets` 两套）复杂度不成比例（原产品亦单份），不做；产品文档明示「结构变更直接生效，权重值变更走两段」。
- **域校验增强**：同规则内 selector 等值重复 target → 400（phase-1「不要求互斥」指命中集合可重叠，不涉等值重复；等值重复拒绝为继承匹配提供唯一性保证）。

release 语义：targets 逐 target `weight ← unreleasedWeight`（全量拷贝）；两列已相等照常落记录（显式动作，审计需要）。INACTIVE 规则允许 release（状态机正交：停用规则的发布等 activate 后生效，不做状态耦合限制）。

rollback 语义（反向 release）：取源 release 记录快照 → **weight 与 unreleasedWeight 两列都写回**该快照（完全恢复该规则在源发布时刻的状态——「再发布一次」= 直接再 release，编辑草稿对齐历史不残留中间态）。

### 2. release 记录（回滚历史源，phase-1 版本化决策落地）

```java
public enum ReleaseType { RELEASE, ROLLBACK }

/** 发布记录：append-only；快照 = 发布/回滚后的完整 targets（两列）。 */
public record RouteRuleRelease(
        long id,                       // 全局单调（DB 自增 / 内存计数器）
        ServiceKey service,
        String name,                   // 规则名
        ReleaseType type,
        List<RouteRuleTarget> snapshot,
        Long rollbackTo,               // ROLLBACK：源记录 id；RELEASE 为 null
        String operator,
        Instant releasedAt) {}

public interface RouteRuleReleaseStore {
    /** append（id 由 store 分配）；返回带 id 记录。 */
    RouteRuleRelease append(RouteRuleRelease release);

    /** 单条查询（回滚源校验）。 */
    RouteRuleRelease find(ServiceKey service, String name, long id);

    /** (service, name) 内 id 降序（最新在前），limit 截断。 */
    List<RouteRuleRelease> list(ServiceKey service, String name, int limit);
}
```

- 回滚可指向任何历史 release/rollback 记录（ROLLBACK 记录亦合法历史状态）；指向不存在/异规则记录 → 404 `RELEASE_NOT_FOUND`。
- 规则删除后 release 记录保留（append-only 不删，审计语义；规则已删则无 API 可达）。
- 内存态：规则与记录同清（重启归零，与 route rules 一致）；DB 态：两者持久，重启后回滚仍可用。

### 3. diff 预览

selector 等值对齐 targets 并集，逐 target 输出基值与目标值：

```java
/** null = 该侧无此目标（新增/移除）。 */
public record TargetDiff(Map<String, String> selector, Integer currentWeight, Integer nextWeight) {}

public record ReleaseDiff(ServiceKey service, String name, Long baseReleaseId, List<TargetDiff> targets) {}
```

- **基（currentWeight 列）= 发布快照的生效列**：缺省取该规则最新 release 记录快照（从未发布 = 空基，全量新增）；`releaseId` 参数取该历史记录快照。next 列恒为当前编辑列（结构 + 权重全量变更）。
- 基取快照而非「当前生效列」的定案理由（实现期修正，两列对比看不见结构变更）：结构单份直改（§1 边界）后，被删除的目标已不在当前 targets 中，同一数组内两列对比无法表达「将从生效面移除」；以快照为基才能同时呈现新增（currentWeight null）/移除（nextWeight null）/权重变更，与「一键回滚」的快照对照对称。
- releaseId 不存在 / 异规则 → 404。

### 4. route-rule 规则流（下发通道，复用 phase-5 形态）

**独立 `RouteRuleService`，不并入 `EjectionRuleService`**（v0.4 phase-5「跨规则类型通用化届时再定」的裁决）：两规则结构差异大，混流需 oneof envelope 且已交付 ejection 流 wire 迁移复杂；管理操作级频率下省一条连接的收益可忽略；独立流 ejection 零改动、结构平行。proto 变更全部为新增消息/新增方法（management.v1 不受冻结约束，仍守 wire 兼容规则）。

```protobuf
enum RouteRuleStatusValue { ROUTE_RULE_STATUS_ACTIVE = 0; ROUTE_RULE_STATUS_INACTIVE = 1; }

message RouteRuleTarget {
  map<string, string> selector = 1;
  int32 weight = 2;
  int32 unreleased_weight = 3;
}

message RouteRule {
  artemis.common.v1.ServiceKey service = 1;
  string name = 2;
  RouteRuleStatusValue status = 3;
  repeated RouteRuleTarget targets = 4;
}

message RouteRuleKey { artemis.common.v1.ServiceKey service = 1; string name = 2; }

message RouteRulesSnapshot { repeated RouteRule rules = 1; }

// 流事件 envelope（message presence 区分快照与增量，对齐 EjectionRulesEvent 先例）：
message RouteRulesEvent {
  RouteRulesSnapshot snapshot = 1;
  RouteRule upserted = 2;        // upsert 语义（覆盖，upsert/delete 承载 CRUD+release/rollback）
  RouteRuleKey deleted = 3;
  int64 version = 4;             // envelope 级单一来源
}

service RouteRuleService {
  rpc WatchRouteRules(RouteRulesRequest) returns (stream RouteRulesEvent);
  rpc AckRouteRules(AckRouteRulesRequest) returns (AckRouteRulesResponse);
}
// Ack 消息同 AckEjections 形态（subscriber_id + applied_version，status + data 规范形式）
```

- **流内容 = ACTIVE 规则集**（对齐 ejection 流「有效规则集」）：INACTIVE 化 → deleted 事件、ACTIVE 化 → upserted 事件（应用层按变更后状态判定调 upsert/delete 通知）；phase-3 filter 直接消费无需再判 status。
- wire 视图含两列权重（数据模型完整性；执行只用 weight）。
- **版本 = dispatcher 内存 tick（AtomicLong）**，与 ejection「记录 sequence 派生」的差异及理由：route-rule 变更源含 CRUD（无记录表可派生，为版本引入 CRUD 记录表违背 phase-5「无新增状态」哲学）；tick 重启归零无害——management 重启必断流，discovery 重连收快照（新 tick 域）重新 ack，水位重新收敛（与 phase-5「内存态重启归零」论证同构）。跳号合法、等号安全（消费方事件应用不依赖版本序，版本纯水位观测）。
- at-most-once + 断流重连快照自愈、慢消费者断流注销——同 ejection 流全部骨干语义。

### 5. management 侧装配

- `RouteRuleDispatcher`（infrastructure，`EjectionRuleDispatcher` 同构）：implements `RouteRulePublisher` + `RouteRuleSubscriptions`；subscribe 快照现读与登记同锁段（`RouteRules.activeOf` = ACTIVE 过滤纯函数）；变更通知 `onUpserted(rule)` / `onDeleted(key)` → 锁内 tick.incrementAndGet + 推送；推送失败断流注销。
- `RuleAckRegistry`（`EjectionAckRegistry` 泛化改名，代际令牌语义不变）：route-rule 流独立实例；`EjectionRuleGrpcService` 引用随动。
- `RouteRuleGrpcService`（server）：watch 委派 dispatcher + registry register/evict；ack 委派 registry record（同 ejection 装配模式）。
- 水位出口：`GET /admin/v1/status/rules` 响应增 `routeRules` 节（同构 currentVersion + subscribers + appliedLatestCount；currentVersion = dispatcher 当前 tick，管理操作级频率现读）。

### 6. discovery 侧消费

- domain：`RouteRule`（视图 record：service/name/targets）、`RouteRules`（不可变集，volatile 发布，`RouteRuleKey`=(ServiceKey,name) 定位，upsert/delete 集合操作幂等——`EjectionRules` 同构模式）；`RouteRuleSource` 端口（watch + ack）、`RouteRuleListener`（onSnapshot/onUpserted/onDeleted/onClosed）。
- `RouteRuleSynchronizer`（infrastructure，`EjectionRuleSynchronizer` 骨架复用：单 worker + per-round 有界队列 + 全抖动退避重连 + fail-open 断流不清缓存）：应用 = 集合替换 + `applied(version)`（推进本地已应用版本 + 内联短超时 ack）；**无扇出无 filter 替换**（执行点 phase-3 接入时按 ejection `runWithFanout` 模式补）。两同步器共享的队列/退避配置键抽出 `ManagementSyncProperties` 配置值对象统一定义（SCF 同 key 单一 PropertyConfig 校验——规范 §10.1「共享配置统一定义一次」的落实，实现期定案）。
- 状态：独立 `ManagementStatus` 实例（类复用不改名，management 上游状态语义通用）；admin API `/upstreams` management 节增 `routeRules: {state, appliedVersion}` 对照（排障：本节点应用版本 ↔ management 水位）。
- `GrpcRouteRuleSource`（discovery-server，`GrpcEjectionRuleSource` 同构：端点轮换、channel 池、订阅方标识进程级随机后缀、ack 优先同端点）。

### 7. HTTP API（`/admin/v1/route-rules` 增量）

```text
POST /admin/v1/route-rules/{ns}/{svc}/{name}/release
→ 200 发布后完整规则视图
→ 404 ROUTE_RULE_NOT_FOUND；503 ROUTE_RULE_STORE_UNAVAILABLE

POST /admin/v1/route-rules/{ns}/{svc}/{name}/rollback
{"releaseId": 5}
→ 200 回滚后完整规则视图
→ 404 ROUTE_RULE_NOT_FOUND / RELEASE_NOT_FOUND（含指向异规则记录）

GET /admin/v1/route-rules/{ns}/{svc}/{name}/diff?releaseId=
→ 200 {"baseReleaseId": null|5, "targets": [{selector, currentWeight, nextWeight}]}
→ 404 ROUTE_RULE_NOT_FOUND /（带 releaseId 时）RELEASE_NOT_FOUND

GET /admin/v1/route-rules/{ns}/{svc}/{name}/releases?limit=20
→ 200 {"releases": [{id, type, rollbackTo, snapshot, operator, releasedAt}]}   # id 降序
```

既有 CRUD 端点 DTO 收紧：`RouteRuleTargetRequest` 删 weight 字段（create/update 均不收生效值）；响应视图不变（含两列）。

### 8. 写序、并发与审计

- **两写无跨表事务（明示边界）**：release/rollback = 规则表 update → release 表 append → 推流。append 失败（DB 异常）→ 503：规则已生效但记录缺失，操作者重试 release 幂等补录（两列已相等照常落记录）。窗口极低概率（同库两连写第二次失败）；core 无 Spring 事务纪律不引入 @Transactional。
- **routeRuleLock（实现期修正，设计稿「零新增锁」不成立）**：CRUD / release / rollback 的「读-判-写（update 继承、release 拷贝）+ 推送」在应用层 `routeRuleLock` 内成对串行——否则并发操作交错会把旧视图后推、流与存储分歧（ejectionLock / logicalInstanceLock 同型先例，ejection review #1 同因）；读路径（get/list/diff/releases）无锁。
- dispatcher 推送锁沿用 ejection 形态（锁内串行短临界区）。
- 审计：`RELEASE_ROUTE_RULE` / `ROLLBACK_ROUTE_RULE` 摘要行（target = `route-rule:{ns}/{svc}/{name}`，detail = 发布后权重序列 + releaseId / rollbackTo 关联，审计可溯源回滚源）；审计失败不阻塞主操作（既有尽力而为语义）。
- operator 取认证用户名（既有机制）。

### 9. review 定案登记（2026-10-26 通过）

- §1–§8 机制落点为架构 §5.2 已记载机制（资源 `release`、两段式权重发布 + 显式 release、diff 预览 + 一键回滚、规则版本 + ack 闭环）的落地细化，无机制变更。
- 独立 `RouteRuleService` 流与 tick 版本为 v0.4 phase-5「跨规则类型通用化届时再定」悬置项的定案；架构 §2.2 端口落点段随 ⑩ 补 v0.5 phase-2 落点（同 phase-4/5 先例），无新 ADR 级决策。
- 编辑通道收紧（create 不收 weight / update selector 等值继承）修订 phase-1 已交付 CRUD API 语义（v0.5 内未发布可改）；`docs/api/admin-api.md` §4.10 随 ⑩ 同步。

## 实现结果（⑦ 补）

**实现要点**：

- proto：`RouteRuleService` 组（Watch + Ack、envelope version、流内容 = ACTIVE 集——快照 `activeOf` 过滤与增量 ACTIVE 判定均收敛在 dispatcher 单点，见 review #4 修复）。
- management-core：release 领域（`ReleaseType`/`RouteRuleRelease`/`RouteRuleReleaseStore`）+ `RouteRulePublisher/Subscriptions/Subscriber` 端口 + `RouteRuleDispatcher`（tick 版本、锁内快照/增量串行）+ `EjectionAckRegistry` 泛化改名 `RuleAckRegistry`（两流各一实例）；`RouteRules` 增等值重复校验与 `activeOf`；应用层 release/rollback/diff/listReleases 用例 + 编辑通道收紧（create 归零 / update selector 等值继承 / `routeRuleLock` 写推成对串行）。
- management-server：V6 双方言（`artemis_route_rule_release`，索引 (namespace, service, rule_name, id)）+ repository 三件套；release/rollback/diff/releases 四端点 + DTO 收紧（targets 删 weight）+ `ROUTE_RULE_RELEASE_NOT_FOUND` 映射；`RouteRuleGrpcService` + 装配（两 AckRegistry bean `@Qualifier` 消歧——无 `-parameters` 编译下按名匹配失效）；`status/rules` 增 routeRules 节。
- discovery：`RouteRule`/`RouteRules`（volatile 不可变集）/`RouteRuleSource`/`RouteRuleSynchronizer`（无扇出缓存，fail-open）+ `GrpcRouteRuleSource` + `/upstreams` routeRules 对照（两 `ManagementStatus` bean `@Qualifier`）；共享配置抽 `ManagementSyncProperties`（SCF 同 key 单一 PropertyConfig 校验的落实）。

**⑤ 首跑轮内修复（非 review findings）**：`RouteRules.with` 键不存在 NPE（单测抓到）；diff 基改发布快照（结构变更可见性，§3 已回写）；Spring 两 bean 歧义 `@Qualifier`；Jackson NullNode 断言（`JsonNode.isNull()`）；IT 用例键隔离；registry `ReplicationFanoutIT` 一次负载性超时复跑通过（与本 phase 无关）。

**code-review 修复（max 级 11 findings：修 7、接受 4）**：

1. **[高｜修] release 两写失败后流通知缺口**：append 抛 STORE_UNAVAILABLE 时 update 已成但流未推——生效列与流无限期分叉。写序改为 update → notify（流与存储一致优先）→ append（失败 503，重试幂等补录；重试再推 upserted 覆盖语义幂等无害）；rollback 同型。
2. **[高｜修] RouteRuleReleaseStoreImpl 坏行放大**：list 的 toDomain 映射移进异常边界，坏行（枚举名非法 / snapshot 损坏 / null）逐行跳过 + WARN（排障列表不被单行毒化）；find 坏行翻译 STORE_UNAVAILABLE（回滚源损坏显式 503 而非误导性 404）。
3. **[中｜接受] 等值重复校验追溯 phase-1 存量**：v0.5 未发布、无部署存量（phase-1 数据仅存于开发环境），同版本内 API 演进不受升级兼容约束；GA 前有真实存量场景再补迁移/宽容路径。
4. **[中｜修] ACTIVE 集不变量分属两处**：dispatcher `onUpserted` 内判 INACTIVE 转推 deleted（快照/增量口径单点一致），应用层四调用点退化为无条件通知。
5. **[中｜修] admin-api.md 未随 API 收紧同步**：§4.10 请求体删 weight、等值重复 400、新增 §4.11 四端点、§6.5 routeRules 节、§7 错误码、§8 边界（1.13）。
6. **[中｜修] RouteRuleSynchronizer 丢 everSynced 守卫**：补齐（对齐 ejection）——从未同步成功保持 SYNCING，不谎报 DISCONNECTED。
7. **[中｜修] discovery-admin-api.md management 节形状**：§5 补嵌套 routeRules 对照（1.2）。
8. **[中｜接受] diff 空基对 phase-1 存量误导**：与 #3 同根（无存量）；phase-2 语义下 create 归零、空基 = 真实「未发布」。
9. **[低｜接受] synchronizer 骨架第 3 份拷贝**：结构性债务非缺陷；phase-3 接 filter/新流时抽共享 round 引擎统一收。
10. **[低｜接受] GrpcRouteRuleSource channel helper 第 4 份拷贝**：同上，phase-3 统一抽 channel-pool/auth helper。
11. **[低｜修] 水位组装重复**：`watermarks()` / `appliedLatestCount()` 提私有静态方法两处复用（RuleStatus 形状不变）。

## 文档同步（⑩ 填）

- [产品文档](../../../product/artemis.md)（1.33）：新增 v0.5 phase-2 交付节（两段状态机与编辑通道收紧、release/rollback/diff/releases、RouteRuleService 流与 routeRules 水位、V6 发布记录持久化、工程形态与验收要点落地）。
- [admin-api.md](../../../api/admin-api.md)（1.13，⑦ 内完成）：§4.10 请求体删 weight + 等值重复 400；新增 §4.11 两段式发布四端点；§6.5 routeRules 水位节；§7 补 `ROUTE_RULE_RELEASE_NOT_FOUND`；§8 边界更新。
- [route-rule-stream-api.md](../../../api/route-rule-stream-api.md)（1.0，新建）：路由规则流契约（ACTIVE 集语义 / upsert-deleted 增量 / version = tick / AckRouteRules / 只缓存不执行边界），索引已登记。
- [discovery-admin-api.md](../../../api/discovery-admin-api.md)（1.2，⑦ 内完成）：§5 management 节补 routeRules 对照。
- [架构](../../../arch/artemis-next-architecture.md)（1.44）：§2.2 端口落点段补 v0.5 phase-2 落点（RouteRuleService 独立流 + AckRouteRules + status/rules routeRules 节、版本 = dispatcher tick、执行归 phase-3）——review §9 已预告的同步落点。
- proto 契约规范 / ADR：无变更——机制均为架构 §5.2 已记载设计的落地（§9 定案），无新 ADR 级决策。
- 索引 / CLAUDE.md：docs/api/README.md 已登记新文档；构建命令、模块结构、当前状态无过时项（proto 与 V6 均在既有模块内演进）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-27 | ⑨ 人工验证通过；⑩ 文档同步登记（产品文档 1.33 / admin-api 1.13 / route-rule-stream-api 1.0 新建 / discovery-admin-api 1.2 / 架构 1.44） |
| 1.3 | 2026-10-26 | 实现结果 + code-review 修复登记（11 findings：修 7 / 接受 4，理由逐条） |
| 1.2 | 2026-10-26 | 实现期定案修正回写：§3 diff 基改为发布快照（结构变更可见性）、§8 routeRuleLock（写推成对串行，防流上乱序——原「零新增锁」不成立）、§6 共享配置抽出 ManagementSyncProperties（SCF 同 key 单一 config 校验） |
| 1.1 | 2026-10-26 | 产品设计定稿（review 通过，§9 登记）：两段状态机与编辑通道收紧、release 记录与回滚、diff、独立 RouteRuleService 流 + tick 版本、discovery 消费、V6、审计与写序边界 |
| 1.0 | 2026-10-26 | 开工骨架 |
