# Phase 5 测试：规则下发闭环

版本: 1.0    更新时间: 2026-10-26

## 测试方案

- **单测**（management-core / discovery-core）：版本派生纯函数、dispatcher 版本携带、AckRegistry 水位状态机、同步器应用后 ack 行为与失败容忍。fake source/记录调用，不起进程。
- **进程内多实例 IT**（`*IT`，failsafe）：真实 registry + discovery + management 三服务随机端口起停（模式同 `DiscoveryEjectionFilterIT`），驱动端到端：规则变更 → discovery 生效 → management 水位可见；management 重启 → 重连补齐；多 discovery 节点生效计数。
- **双方言 IT**：既有 Postgres/MySQL 持久化 IT 增版本连续性断言（DB 态 sequence 持久，management 重启后新变更版本延续）。
- 形态↔模式映射均在既有 unit/integration 范围内，无 dev-test.sh 扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_return_last_flip_sequence_when_replaying_records | 混合 EJECT/RECOVER 重放：翻转推进版本、叠加/对消无翻转不推进、多 target 各自判定、无记录 = 0 | 单测 | pass |
| should_not_advance_version_when_only_stacking_and_partial_cancel | 仅叠加与部分对消：版本停在首次翻转（EjectionRecordsTest） | 单测 | pass |
| should_return_zero_when_no_records | 空记录集版本 = 0（EjectionRecordsTest） | 单测 | pass |
| should_carry_flip_record_sequence_as_version_when_dispatching | 快照携带 lastFlipSequence、增量携带触发记录 sequence；叠加不产生版本（EjectionRuleDispatcherTest） | 单测 | pass |
| should_track_watermark_when_subscriber_acks | 登记后上报取 max；重复/乱序上报幂等；未 ack 前 null | 单测 | pass |
| should_evict_watermark_when_subscriber_gone | 注销/断流清除条目；未登记 subscriber 的 ack 被忽略；同 id 重连重置水位；条目按 id 排序 | 单测 | pass |
| should_compose_rule_status_when_queried | ruleStatus 组合：currentVersion + atLatest + appliedLatestCount（ManagementServiceImplTest） | 单测 | pass |
| should_evict_watermark_when_server_disconnects_subscriber | 服务端断流（背压）close 汇入点清除水位（EjectionRuleGrpcServiceTest） | 单测 | pass |
| should_ack_applied_version_when_snapshot_or_increment_applied | 同步器应用快照/增量后经 source 上报对应版本（fake source 断言调用） | 单测 | pass |
| should_tolerate_ack_failure_when_upstream_unavailable | ack 抛异常仅 WARN，应用循环继续、后续事件照常消费 | 单测 | pass |
| should_expose_rule_watermark_when_ejection_applied | 摘除 → discovery 查询过滤生效 → `GET /admin/v1/status/rules`：currentVersion = appliedVersion、atLatest = true、appliedLatestCount = 1 | IT | pass |
| should_carry_version_and_track_watermark_when_acked | 流事件携带版本 + AckEjections unary 水位登记/生效计数/取消清除（EjectionRuleStreamIT） | IT | pass |
| should_reack_after_reconnect_when_management_restarts | management 停（规则冻结 fail-open）→ 起：discovery 重连重新快照 + 重新 ack，水位恢复一致（版本归零对齐） | IT | pass |
| should_count_effective_nodes_when_multiple_discovery_subscribers | 两个 discovery 节点订阅同一 management：均应用后 appliedLatestCount = 2；再变更后维持 2 | IT | pass |
| should_continue_version_across_restart_when_db_backed | Postgres/MySQL 态 management 重启后新摘除的版本 > 重启前版本（sequence 持久延续，EjectionPersistenceIT 重启用例内断言） | IT（双方言） | pass（Docker Desktop 启动后补验，PG/MySQL 各 3 用例全绿） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

- `./java/scripts/dev-test.sh all`（r4，⑤ 首跑全绿）：BUILD SUCCESS——spotless + enforcer + 单测 + IT（`*IT`）一体；本 phase 相关：EjectionAckRegistryTest 5、EjectionRecordsTest 3、EjectionRuleDispatcherTest 7、EjectionRuleSynchronizerTest 10、ManagementServiceImplTest 29、EjectionRuleStreamIT 7（含版本 + ack 水位用例）、DiscoveryRuleAckLoopIT 3（三服务闭环）。
- `./java/scripts/dev-test.sh all`（r5，code-review 修复后回归）：BUILD SUCCESS，同上范围全绿；EjectionAckRegistryTest 增至 6（代际令牌用例）。
- 已知环境限制→补验：Docker Desktop（user 级服务，非系统服务）启动后补跑 `*PersistenceIT,*AccountFlowIT`：**20/20 全绿**（Postgres/MySQL 各类，含 DB 态版本跨重启延续断言）。
- 补跑过程中 `SelfProtectionClusterIT`（v0.2 registry，本 phase 未触及）一次超时失败，单独重跑全绿——判定为 Docker Desktop 冷启动负载下时序抖动，非回归。

人工验证（用户执行）：用户于 2026-10-26 验证通过。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑（r1/r2 合并） | all | 全 reactor | 失败：spotless 格式（DiscoveryAdminService）→ apply 修复；EjectionRuleStreamIT 断言过强（其它用例遗留流条目）→ 收窄断言 |
| ⑤ 首跑（r3） | all | 全 reactor | 失败：DiscoveryRuleAckLoopIT 起停缺 classpath 配置隔离（健康组键冲突）→ 补 `optional:` 空 config.location |
| ⑤ 首跑（r4） | all | 全 reactor | **全绿**（BUILD SUCCESS；DB IT 20 个因 Docker 未运行跳过） |
| ⑧ 回归（r5，⑥⑦ 修复后） | all | 全 reactor | **全绿**（BUILD SUCCESS；同上 DB IT 跳过） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-26 | 开工骨架 |
| 1.1 | 2026-10-26 | 测试设计定稿（单测 8 + IT 4） |
| 1.2 | 2026-10-26 | ⑤⑧ 结果回填（all 两轮全绿）、⑨ 自动化验证记录、review 修复补充用例（代际令牌） |
| 1.3 | 2026-10-26 | DB IT 补验全绿（Docker Desktop 启动）：版本连续性断言 pass |
