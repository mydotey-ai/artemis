# Phase 1 测试：route-rule 资源模型与持久化

版本: 1.3    更新时间: 2026-10-26

## 测试方案

- **单测**（`*Test`，surefire）：`RouteRules` 校验纯函数语义（约束矩阵逐项）+ `ManagementService` CRUD 用例（内存态，含审计写入断言）。
- **IT**（`*IT`，failsafe）：进程内起 management（Testcontainers MySQL / PG 双方言，`PersistenceDialectTestBase` 模式）+ HTTP 驱动：CRUD 闭环、审计查询、跨重启保留（停库重启进程重新断言）。
- 形态↔模式映射沿用既有（单测→unit、IT→integration/all），无 dev-test.sh 扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_create_route_rule_when_valid | 合法规则创建，返回完整视图（operator/时间戳/缺省 ACTIVE） | 单测 | pass |
| should_reject_create_when_duplicate_name | 同 `(ns, svc, name)` 重复创建 → 409 语义 | 单测 | pass |
| should_replace_whole_rule_when_put | PUT 全量替换 targets/权重/状态；缺失 → 404 | 单测 | pass |
| should_delete_idempotently_when_missing | 删除缺失规则幂等 no-op | 单测 | pass |
| should_reject_rule_when_constraint_violated | 目标数超界 / 权重越界 / selector 键值超长或空 / name 非法字符 / 超长 → 400 | 单测 | pass |
| should_allow_empty_selector_when_fallback_target | 空 selector 目标合法（兜底语义） | 单测 | pass |
| should_write_audit_log_when_crud | CREATE/UPDATE/DELETE 各写审计摘要行（operation/target/operator） | 单测 | pass |
| should_crud_route_rules_when_http | HTTP CRUD 闭环：201/200/204/404/409/400 状态码与视图字段 | IT | pass |
| should_list_route_rules_when_filter | 列表 (ns, svc) 过滤 + (ns, svc, name) 升序 + limit 截断 | IT | pass |
| should_query_audit_logs_when_route_rule_crud | HTTP CRUD 后审计查询可查（operation 过滤） | IT | pass |
| should_persist_route_rules_across_restart | DB 态重启 management 后规则保留且字段完整（MySQL + PG 双方言各一） | IT | pass |

## 验证记录（⑨ 填）

**自动化验证（工作流执行）**：

| 命令 | 结果 |
|---|---|
| `bash java/scripts/dev-test.sh all`（⑤ 首跑） | BUILD SUCCESS，全模块测试 0 失败 |
| `bash java/scripts/dev-test.sh all`（⑧ 回归，review 修复后） | BUILD SUCCESS，14 模块（common/registry/discovery/management/client）测试 0 失败；含 spotless:check 与 enforcer 通过 |

**人工验证（用户执行）**：待用户记录。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑧，review 修复后） | all | 全模块（spotless + enforcer + 单测 + IT） | BUILD SUCCESS，14 模块测试 0 失败（含修复后 ManagementAdminIT 15 用例与双方言 route-rule IT） |

（⑤ 首跑记录：dev-test.sh all 全量 BUILD SUCCESS，全模块测试 0 失败；实现期调试轮次——测试代码 body 构造 bug（拼接 + formatted 优先级）、audit target 前缀、IT 用例键隔离与审计包含性断言——均已在首跑全绿前修复，详见设计文档「实现结果」。）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-26 | ⑧ 回归轮登记（all 全绿）；⑨ 自动化验证记录 |
| 1.2 | 2026-10-26 | 用例清单全部 pass（dev-test.sh all 全绿） |
| 1.1 | 2026-10-26 | 测试设计定稿：7 单测 + 4 IT（双方言重启用例 ×2 方言） |
| 1.0 | 2026-10-26 | 开工骨架 |
