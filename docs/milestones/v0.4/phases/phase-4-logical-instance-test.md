# Phase 4 测试：逻辑实例（静态实例纳入统一发现视图）

版本: 1.3    更新时间: 2026-10-25

## 测试方案

形态与覆盖面（对齐 plan phase-4 验收方式：单测——逻辑实例模型与下发投影；IT——逻辑实例经 discovery 查询/订阅可见、与真实实例 union）：

- **单测**（surefire `*Test`）：存储语义（内存态）、分发器（快照/增量/at-most-once）、应用用例（归一/no-op/审计/推送时机）、proto 映射（快照分组、upsert→UPDATE、delete→DELETE）。
- **IT**（failsafe `*IT`，进程内多实例集群语义测试，guidelines §7）：
  - 流契约（management-server，装配同 `EjectionRuleStreamIT`）：快照 → 增量 → 断流重连快照收敛。
  - 端到端（discovery-server，装配同 `DiscoveryEjectionFilterIT` 三件套：进程内真实 registry + management + discovery）：union 可见性、订阅推送、摘除过滤、fail-open、0..1 未装配。
  - DB 持久化（双方言，Testcontainers，模式同 `EjectionPersistenceIT`）：跨重启保留 + 重下发。
- 执行入口 `java/scripts/dev-test.sh`（unit / integration / all 既有形态映射，本 phase 无新形态，脚本无改动）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_return_previous_on_upsert_and_removed_on_delete_when_store_mutates | 内存存储 upsert 返回 previous、delete 返回 removed、findAll 全量 | 单测 | pass |
| should_normalize_status_and_weight_when_upsert_logical_instance | UNSPECIFIED→UP、weight≤0→100 归一在应用层生效 | 单测 | pass |
| should_noop_and_skip_audit_and_push_when_upsert_identical_content | 内容相同的 upsert 不写库、不推送、不审计 | 单测 | pass |
| should_noop_when_delete_missing_logical_instance | 删除不存在的逻辑实例幂等 no-op | 单测 | pass |
| should_append_audit_summary_on_logical_instance_change | 变更时写 LOGICAL_INSTANCE_UPSERT/DELETE 摘要行 | 单测 | pass |
| should_push_snapshot_on_subscribe_and_increment_on_change | 登记即推快照（同锁段）、upsert/delete 增量推送 | 单测 | pass |
| should_disconnect_subscriber_when_push_fails | 推送失败断流注销（at-most-once，D-E10） | 单测 | pass |
| should_group_snapshot_by_service_and_map_events_to_changes | 快照按 ServiceKey 分组、upserted→UPDATE、deleted→DELETE 映射 | 单测 | pass |
| should_deliver_snapshot_then_increments_over_stream | 订阅流首条快照 → upsert/delete 增量按产生序（内存态） | IT | pass |
| should_reconnect_and_resnapshot_after_stream_break | 断流后重连收全量快照收敛（丢失窗口 = 断流期间变更） | IT | pass |
| should_see_logical_instance_in_query_and_union_with_real_instances | 同 service 真实实例（cluster-a）与逻辑实例（logical）并列可见；纯逻辑服务可见 | IT | pass |
| should_push_logical_changes_to_subscribers | 订阅基线含逻辑实例；upsert 推 UPDATE/NEW、delete 推 DELETE | IT | pass |
| should_filter_ejected_logical_instance | instance 级摘除逻辑实例 → 查询/订阅过滤；恢复收敛 | IT | pass |
| should_keep_last_known_when_management_down_and_converge_on_empty_snapshot_after_restart | management 不可用沿用最后已知逻辑实例；重启（内存态）空快照收敛 | IT | pass |
| should_not_create_logical_cluster_when_management_not_configured | 未配置 management：无 logical 源、查询无 logical cluster、零连接 | IT | pass |
| should_persist_logical_instances_across_management_restart_and_resend (PostgreSQL/MySQL) | DB 态 upsert → 重启 management → 逻辑实例保留且流重下发快照含之 | IT | pass |
| should_advance_schema_version_to_v4 (既有修订) | PersistenceDialectTestBase schema 版本断言 3→4（双方言基类随 V4 修订） | IT | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 检查 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑 6 轮内迭代修复 + ⑧ 回归终轮） | BUILD SUCCESS（13 模块全绿；单测 + 进程内 IT 全通过） |
| ⑧ 回归专项：LogicalInstanceStreamIT 5/5、DiscoveryLogicalInstanceIT 5/5、既有 Discovery*/Ejection* IT 全绿 | 通过（review 修复无回归） |
| 双方言 DB IT（Persistence/Account/Ejection/LogicalInstance ×PG/MySQL） | 无 Docker 环境整类跳过（phase-1 起基线行为；V4 DDL 键长经计算复核：(255+255+255)×4=3060 ≤ 3072） |
| 本地冒烟（内存态 exec.jar，⑤ 期）：PUT/GET/DELETE `/admin/v1/logical-instances` | 200/200/204；归一（缺省 weight→100、status→UP）与幂等删除符合设计（修复后同路径经 IT 复验） |

人工验证（用户执行）：用户 review 制品后确认通过（2026-10-25，方式与结论由用户自述「通过」）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑥ review 修复后） | all | 全 reactor（spotless + enforcer + 单测 + IT） | BUILD SUCCESS（2026-10-25） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-25 | ⑨ 人工验证通过（用户确认） |
| 1.2 | 2026-10-25 | ⑧ 回归全绿 + ⑨ 自动化验证记录（人工验证停点） |
| 1.1 | 2026-10-25 | ⑤ 首跑 `dev-test.sh all` 全绿（修复 3 轮：测试预期 UNSPECIFIED 对齐、Jackson3 null-primitive 拒绝改 boxed 入参、SCF 共享 key 收拢 ManagementConfig、lambda 断言误用）；双方言 DB IT 无 Docker 跳过（既有基线行为） |
| 1.0 | 2026-10-25 | 测试设计定稿 |
