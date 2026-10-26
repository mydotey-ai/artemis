# Phase 5：规则下发闭环

版本: 1.0    更新时间: 2026-10-26

## 目标

将 v0.3 摘除规则流的盲下发（快照 + 增量、无版本/ack）升级为版本化规则流 + discovery ack 生效闭环，生效水位经 management 状态 API 可查。

## 范围

**做什么**：

- 规则版本化：摘除规则流（快照与增量）携带规则集状态版本；management 状态 API 暴露当前版本与各订阅方生效水位（哪个版本、多少节点已生效）。
- discovery ack：应用规则后上报已应用版本；断流重连重新快照 + 重新 ack 收敛。
- management ack 登记：per 订阅方水位记录 + 状态 API 出口。
- discovery 本地已应用版本可见（admin API 排障对照）。

**不做什么**：

- 不引入独立规则版本存储/序列表——版本由既有记录 sequence 派生（见设计）。
- 版本不做顺序协议：无增量缺口检测/严格 +1 连续假设（版本为水位语义，正确性骨干仍是 at-most-once + 快照自愈）。
- 不做 ack 重试/补偿任务（失败容忍，重连快照后重新 ack 兜底）；水位不持久化（观测态，重启经重连重建）。
- 不新增 filter：EjectionFilter 即规则执行点，filter 链成员扩展（Group filter）归 v0.5。
- 不做跨规则类型通用化（route-rule/canary 归 v0.5，届时再定统一规则流形态）。
- 逻辑实例流不版本化（数据语义快照自愈，phase-4 已定案）。

## 涉及模块

- `proto/management/v1/management.proto`（EjectionRuleService 版本字段 + AckEjections rpc）
- `java/server/artemis-management-core`（EjectionRecords 纯函数、EjectionRulePublisher/Subscriber/Dispatcher 版本携带、EjectionAckRegistry、application 状态用例）
- `java/server/artemis-management-server`（grpc ack 端点与注销联动、http StatusController 水位出口）
- `java/server/artemis-discovery-core`（EjectionRuleSource 端口 ack、EjectionRuleSynchronizer 应用后上报、ManagementStatus appliedVersion）
- `java/server/artemis-discovery-server`（GrpcEjectionRuleSource ack 实现、AdminController 对照出口）

## 验收方式

对应测试文档：[phase-5-rule-ack-loop-test.md](phase-5-rule-ack-loop-test.md)

## 产品设计（③ 补）

### 规则版本语义

- **版本 = 规则集状态标识，取值为触发生效态翻转的记录 sequence**（`EjectionRecord.sequence`：DB 态自增主键 / 内存态计数器，单调且随记录持久，无新增状态）。与推送语义严格对齐：叠加/对消无翻转不推、亦不产生版本（版本号跳号合法）。
- 快照 version = `EjectionRecords.lastFlipSequence(全部记录)`（新增纯函数：重放 per-target 对消计数，翻转点记录 sequence，返回最后一次；无记录 = 0）；增量 version = 触发翻转记录的 sequence。
- **版本是水位/观测语义，不是顺序协议**：正确性骨干不变——at-most-once、慢消费者断流注销、断流重连快照全量覆盖自愈；消费方对 ≤ 已应用版本的增量按幂等集语义安全重放（with/without 集合操作天然幂等）。
- 内存态 management 重启：记录清零 → 版本归零；快照全量替换 + 重新 ack，水位重新一致（无害）。DB 态：sequence 持久，版本跨重启单调延续。

### proto 契约变更（`proto/management/v1/management.proto`）

```protobuf
message EjectionRulesSnapshot {
  repeated EjectionRule rules = 1;
  int64 version = 2;            // 快照规则集状态版本（最后翻转记录 sequence）
}

message EjectionRulesEvent {
  EjectionRulesSnapshot snapshot = 1;
  EjectionRule ejected = 2;
  EjectionRecovered recovered = 3;
  int64 version = 4;            // envelope 级：快照与增量均携带
}

message AckEjectionsRequest {
  string subscriber_id = 1;     // 与 WatchEjections 同源标识
  int64 applied_version = 2;    // 已应用（已产生效果）的规则集版本
}

message AckEjectionsResponseData {}

service EjectionRuleService {
  rpc WatchEjections(EjectionRulesRequest) returns (stream EjectionRulesEvent);
  // 幂等、可重复上报（management 取 max）；未知 subscriber（无活订阅）忽略；
  // 失败由消费方容忍放弃（重连快照后重新 ack 收敛）
  rpc AckEjections(AckEjectionsRequest) returns (AckEjectionsResponse);
}
```

- AckEjectionsResponse 走 `status + data` 规范形式（data 空消息）。
- WatchEjections 相关注释由「无版本/ack」更新为版本化语义（at-most-once 与快照自愈表述保留）。
- ack 走独立 unary rpc（非 bidi 流改造）：幂等、重连后可重发、实现面复用 watch 的端点/channel 池；流形态不变，wire 变更全部为新增字段/新增方法。

### management 侧

- `EjectionRecords.lastFlipSequence(List<EjectionRecord>)` 纯函数（内存/DB 两态共享，单测一处覆盖）。
- `EjectionRuleSubscriber`：`onSnapshot(List<EjectionRecord>, long version)`、`onRecovered(EjectionTarget, long version)` 增版本参数；`onEjected(EjectionRecord)` 不变（version 即 `record.sequence()`）。`EjectionRulePublisher` 对应调整；`EjectionRuleDispatcher` 订阅快照同锁段计算版本，增量以触发记录 sequence 为版本。
- `EjectionAckRegistry`（domain 内存观测对象）：`register(subscriberId)`（订阅建立登记，水位未知）/ `evict(subscriberId)`（注销/断流清除）/ `record(subscriberId, appliedVersion, instant)`（仅更新已登记条目，取 max）。**水位只反映活订阅**——断连节点生效态未知，不冒充已生效，也免无界滞留。
- 状态 API：`GET /admin/v1/status/rules` → `RuleStatusView{currentVersion, subscribers: [{subscriberId, appliedVersion?, lastAckAt?, atLatest}], appliedLatestCount}`；currentVersion 查询时由 `lastFlipSequence(ejections.findAll())` 计算（管理操作级频率，不缓存）。
- 装配：`EjectionRuleGrpcService` 增 `ackEjections` 委派 AckRegistry；`watchEjections` 取消/注销路径同步 evict。

### discovery 侧

- `EjectionRuleSource` 端口增 `void ack(long appliedVersion)`；`GrpcEjectionRuleSource` 实现：unary AckEjections，与 watch 同端点轮换与 channel 池、短 deadline（约 3s）。
- `EjectionRuleSynchronizer`：每次应用成功（快照/增量）后 appliedVersion ← 事件 version，更新 `ManagementStatus`（增 appliedVersion，volatile 单写）并调 `source.ack(version)`——worker 线程内联同步调用（管理操作级频率，不引异步 executor），失败 WARN 容忍、不中断应用循环。
- discovery admin API：ManagementView 增 appliedVersion（排障对照：本节点应用版本 ↔ management 水位）。

### filter 链

零改动：EjectionFilter 即治理规则执行点（架构 §5.2；Group filter 归 v0.5），治理依赖保持 0..1（未配置 management 不过滤，既有行为）。

### 架构同步落点（review 通过后随 ⑩ 执行，非本节新增决策）

本 phase 为架构 §5.2「规则版本 + ack 生效闭环」已记载机制的落地；§2.2 v0.3 端口落点段「版本 + ack 生效水位归 v0.4 完整规则链」悬置句随 ⑩ 补 v0.4 phase-5 落点表述（同 phase-4 先例）。

## 实现结果（⑦ 补）

**实现要点**：

- proto：`EjectionRulesEvent` 增 envelope 级 `version = 4`（快照与增量单一来源）；新增 `AckEjections` unary（status + data 规范形式，data 空消息）。
- management：`EjectionRecords.lastFlipSequence` 纯函数（重放对消计数取最后翻转记录 sequence）；`EjectionRuleDispatcher` 快照/增量携带版本；`EjectionAckRegistry`（domain，代际令牌条件清除）；`ManagementService.ruleStatus()` + `GET /admin/v1/status/rules`；`recover` 传递翻转记录 sequence。
- discovery：`EjectionRuleListener`/`EjectionRuleSource` 增版本与 `ack(long)`；`EjectionRuleSynchronizer.applied(version)` 统一收尾（推进 `ManagementStatus.appliedVersion` + 内联短超时 ack，失败 WARN）；`GrpcEjectionRuleSource` 订阅方标识加进程级短随机后缀（同 host 多节点水位条目不互撞）；admin API `/upstreams` 增 appliedVersion 对照。
- filter 链零改动（EjectionFilter 即执行点）。

**code-review 修复（max 级，5 findings）**：

1. **重连 evict 竞态（确认）**：AckRegistry 条目改代际令牌（`register` 返回 `Subscription`，`evict` 仅清除当前代际）——同 id 新流先登记、旧流迟到 cancel/close 注销不再误删新条目；新增单测 `should_keep_new_generation_when_stale_subscription_evicts`。
2. **snapshot.version 子字段恒 0（确认）**：删除 `EjectionRulesSnapshot.version` wire 字段（未发布契约收敛），envelope 级 `version` 为单一来源，无代码引用该子字段。
3. **ack 端点轮换偏差**：`GrpcEjectionRuleSource` 记住当前 watch 端点，ack 优先走同端点 channel（订阅登记在该 management 进程，多端点并存换址窗口期不丢 ack）；无活 watch 防御性轮换兜底。
4. **旧流在途 ack 污染新条目（接受限界）**：ack 无代际凭证，迟到旧 ack 可短暂落入新条目（观测面失真、首次真实 ack 自愈）；javadoc 标注，不为此扩展协议。
5. **三分支重复收尾**：`apply()` 收敛为 `applied(version)` 单点。

## 文档同步（⑩ 填）

- `docs/product/artemis.md`（1.29）：新增 v0.4 phase-5 交付段（版本化规则流 / ack 上行 / 水位 API / 双态语义 / 本地对照 / 工程形态）；phase-6 节「无版本/ack」过时表述修正。
- `docs/arch/artemis-next-architecture.md`（1.43）：§2.2 v0.3 端口落点段补 phase-5 落点（envelope version + AckEjections + `GET /admin/v1/status/rules`，§5.2 已记载机制的落地，无机制变更）。
- `docs/api/ejection-rule-stream-api.md`（1.2）：头注更新；§2 补 version 事件语义；新增 §3 生效 ack 上行；§4 补本地对照。
- `docs/api/admin-api.md`（1.10）：新增 §6.5 `GET /admin/v1/status/rules`。
- `docs/api/discovery-admin-api.md`（1.1）：`management` 字段增 `appliedVersion`。
- `docs/milestones/v0.4/phases/phase-5-rule-ack-loop-test.md`：用例结果 / 验证记录 / 回归记录回填（1.3）。
- 各级索引与 CLAUDE.md 无需变更（无新增文档文件、模块结构与构建命令未变）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-26 | 开工骨架 |
| 1.1 | 2026-10-26 | 设计定稿：版本 = 翻转记录 sequence、AckEjections unary、水位只反映活订阅 |
| 1.2 | 2026-10-26 | 实现结果与 code-review 修复（5 findings）回填；文档同步清单 |
