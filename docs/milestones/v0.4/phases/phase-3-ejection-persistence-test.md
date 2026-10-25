# Phase 3 测试：四级摘除持久化与审计

版本: 1.1    更新时间: 2026-10-25

## 测试方案

形态与覆盖面：

- **单测**（management-core / discovery-core）：四级目标模型与对消纯函数（`EjectionRecords`）、内存 store、四级级联命中与视图组合、规则集四级判定与增量幂等、selector 扇出语义。
- **进程内服务级 IT**（management-server）：既有 `EjectionRuleStreamIT` 扩展四级事件；新增 `EjectionPersistenceIT`（双方言 Testcontainers 子类，模式同 phase-2 `AccountFlowIT`）——V3 migration、四级摘除/恢复/叠加 HTTP 语义、**跨重启规则流快照恢复**（roadmap v0.4 验收要点）、审计与改密审计查询。
- discovery 侧规则流四级消费经 management-server IT 的 gRPC 真实端口覆盖（快照/增量含 scope）；`EjectionRuleSynchronizer` 扇出逻辑经 discovery-core 单测覆盖（既有单测扩展）。
- 执行入口 `java/scripts/dev-test.sh`（unit / integration / all），无新形态——既有模式映射已覆盖，脚本零改动。

## 用例清单

### management-core 单测

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_append_and_count_active_per_target | 同目标两条 EJECT 一条 RECOVER 仍 active；再一条 RECOVER 转 inactive；多余 RECOVER 判定 no-op | 单测 | pass |
| should_order_records_by_sequence_and_compute_active | 全记录按 sequence 升序；activeOf 只含未对消集且确定性排序 | 单测 | pass |
| should_match_instance_level_first_then_selectors | 同实例同时命中 instance 级与 zone 级记录时标注 instance 级（优先序 INSTANCE > SERVER > ZONE > GROUP） | 单测 | pass |
| should_match_selectors_by_instance_metadata | metadata[server/zone/group] 命中对应 selector 记录；不带键不命中；跨 namespace 命中（selector 全局） | 单测 | pass |
| should_count_active_instance_records_by_service | 服务列表计数只含 INSTANCE 级有效记录（selector 级不折算） | 单测 | pass |
| should_eject_and_recover_four_scopes_in_memory_store | 内存 store 四级 append/findAll 序与幂等 no-op 语义 | 单测 | pass |
| should_compose_detail_view_with_four_level_annotations | 详情视图 includeEjected 逐实例四级标注；默认过滤全部命中实例 | 单测 | pass |
| should_push_rule_delta_only_on_state_flip | 叠加第二条 EJECT 不推增量；RECOVER 后仍 active 不推；翻转时推（内存 publisher 收集） | 单测 | pass |
| should_append_audit_log_and_query_by_operation | 审计 append + operation 过滤 + 时间倒序 + limit | 单测 | pass |
| should_record_operator_from_call_context | 摘除/恢复记录与审计行携带认证用户名 | 单测 | pass |

### discovery-core 单测

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_evaluate_four_scopes_with_metadata | EjectionRules.isEjected 四级判定：instance 键等值 + selector metadata 匹配 | 单测 | pass |
| should_apply_delta_idempotently_across_scopes | with/without 各 scope 幂等（已存在/未存在返回自身） | 单测 | pass |
| should_fanout_instance_scope_by_service | instance 级规则变更只扇出该 service（现状回归） | 单测 | pass |
| should_fanout_selector_scope_to_all_services | selector 值集变化扇出投影中全部命中服务（DELETE/NEW 视图变更） | 单测 | pass |
| should_not_fanout_when_rules_unchanged | 幂等增量（无变化）零扇出 | 单测 | pass |

### management-server IT

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_run_v3_migration_and_report_schema_version | DB 态 V3 applied，状态 API schemaVersion=3（双方言断言基类更新） | 进程内服务级 IT | pass |
| should_eject_and_recover_via_unified_api_four_scopes | POST /admin/v1/ejections 四 scope 参数校验与 200/204/400 语义；GET 记录列表 activeOnly | 进程内服务级 IT | pass |
| should_recover_no_op_when_no_active_record | 无可对消记录时 recover 204 且零新增记录 | 进程内服务级 IT | pass |
| should_stack_ejections_and_require_matching_recovers | 两条 EJECT 需两次 RECOVER 才恢复（规则流快照验证） | 进程内服务级 IT | pass |
| should_send_scope_rules_in_stream_snapshot_and_delta | gRPC 规则流快照含四级规则；scope 增量推送（EjectionRuleStreamIT 扩展） | 进程内服务级 IT | pass |
| should_restore_ejections_across_management_restart | DB 态摘除（instance + selector）→ 同 DB 新 context 重启 → 新订阅快照含摘除（roadmap 验收要点） | 进程内服务级 IT（双方言） | pass |
| should_query_audit_logs_including_password_change | 摘除/恢复/改密进审计且可查（operation 过滤 + 倒序 + limit） | 进程内服务级 IT（双方言） | pass |
| should_keep_memory_mode_behavior_unchanged | 内存态（无 datasource）四级摘除照常可用（重启丢失为既有语义，不在本用例断言） | 进程内服务级 IT | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：
- `java/scripts/dev-test.sh all`（≡ `mvn -f java/pom.xml verify`）两轮全绿：spotless + enforcer + 单测 + failsafe IT（双方言 Testcontainers、三服务端到端），13 模块 BUILD SUCCESS（⑤ 终轮 + ⑧ 回归，见回归记录）。
- 验收要点覆盖对照：摘除跨重启恢复 → `EjectionPersistenceIT.should_restore_ejections_across_management_restart`（双方言）；审计可查 → 同 IT `should_persist_four_scopes_and_audit` / `should_audit_password_change`；四级级联 → 单测 + `DiscoveryEjectionFilterIT.should_filter_by_zone_selector_across_instances`（端到端）。

人工验证（用户执行）：2026-10-25 用户确认通过。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑 | all | 全 reactor | pass（首轮暴露：EjectionRules 空集 EnumMap/Map.copyOf 构造异常、activeOf 目标去重语义、recover 部分对消误推增量——修复后绿） |
| ⑤ 终轮 | all | 全 reactor（13 模块） | pass（含双方言 EjectionPersistenceIT、EjectionRuleStreamIT 四级事件、DiscoveryEjectionFilterIT selector 端到端） |
| ⑧ 回归 | all | 全 reactor（13 模块） | pass（⑦ 修复后：#1 推送入锁、#2 长度校验、#5 列表 limit + 倒序、#8 删重载；首轮单测倒序断言适配后全绿） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-25 | ⑤ 全量（all）两轮全绿；用例结果列 pass；首跑修复项登记 |
| 1.0 | 2026-10-25 | 测试设计定稿 |
