# Phase 5 测试：console 管理版

版本: 2.0    更新时间: 2026-10-27

## 测试方案

**形态**：单测（组合语义 fake 端口）+ 服务级 IT（进程内真实服务、随机端口、真实 HTTP/协议驱动）。

- **单测**（management-core，surefire）：`ManagementServiceImplTest` 增订阅组合用例——fake `SubscriptionQueryPort` 驱动多源合并、逐源错误隔离、稳定排序；无其余新内核逻辑（操作面为既有用例的 HTTP/SPA 驱动）。
- **IT-1 console 操作闭环**（management-server，failsafe）：同 JVM 真实 registry（RegistryFixture）+ 真实 management（随机端口），沿用 `ManagementConsoleIT`/`ManagementHttpIT` 基建——管理版页面可达（导航含新视图）、页面所驱动 API 的操作闭环（摘除/恢复、canary、发布两段）与视图/审计可查。
- **IT-2 订阅关系端到端**（discovery-server，failsafe）：三服务同 JVM（`DiscoveryCanaryIT` 形态：registry + management + discovery，端口预选注入），discovery 开 HTTP；真实订阅流（gRPC）建立后经 management 组合出口断言订阅可见、断流移除、坏源隔离。落 discovery-server 模块因 Maven reactor 依赖方向（discovery-server → management-server 既有，反向 test 依赖成环）。

**dev-test.sh**：无新形态（单测 + `*IT` 均为既有 unit/integration 模式映射），脚本零变更。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_isolate_failing_source_and_keep_stable_order_when_subscriptions_queried | 双源一坏：坏源 error 承载且订阅空、好源订阅完整、排序（源序→id）稳定 | 单测 | pass |
| should_serve_management_console_with_management_views_when_root_requested | 管理版页面可达：根路径 200 text/html 且含新视图导航（订阅/审计/保护态/规则/摘除） | IT-1 | pass |
| should_eject_and_recover_instance_over_http_in_console_loop | 详情页驱动闭环：eject（instance 级 + reason）→ ejections?activeOnly 可见 → recover → active 集 conceal；审计行（EJECT/RECOVER）可查 | IT-1 | pass |
| should_deploy_and_finish_canary_over_http_in_console_loop | 服务详情页 canary 闭环：deploy（selector+weight）→ 规则可查（保留名 canary、两列一致）→ DELETE 幂等 → 审计（DEPLOY_CANARY）可查 | IT-1 | pass |
| should_walk_two_phase_release_over_http_in_console_loop | 规则页两段闭环：create（编辑列）→ diff（current 空/next 编辑列）→ release → diff（两列一致）→ releases 记录 + 审计（RELEASE_ROUTE_RULE）可查 | IT-1 | pass |
| should_return_protection_and_audit_views_over_http | 视图可查：status/protection 结构（node 统计 + services 数组）与 audit-logs operation 过滤均 200 且字段完整 | IT-1 | pass |
| should_expose_live_subscription_over_management_composite_when_subscriber_connected | 真实订阅流建立后，management `GET /admin/v1/subscriptions` 见该订阅（subscriber 地址 + source endpoint + selector 拍平字段） | IT-2 | pass |
| should_remove_subscription_from_composite_view_when_stream_closed | 订阅流取消后，组合视图订阅移除（活跃快照语义，无历史残留） | IT-2 | pass |
| should_isolate_unreachable_discovery_source_in_composite_view | endpoints 含不可达地址：该源 error 非空、其余源订阅照常返回（per-source fail-open，整体 200） | IT-2 | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑） | BUILD SUCCESS（reactor 全模块；本 phase 用例 9/9 pass） |
| `java/scripts/dev-test.sh all`（⑧ review 修复后回归） | BUILD SUCCESS（8 findings 修复后全量重跑，用例 9/9 pass） |

人工验证（用户执行）：所做检查 / 试用与结论，内容由用户决定，工作流不指定。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑（2026-10-27） | all（全量 verify） | reactor 全模块 | BUILD SUCCESS（修复 4 轮：ScfProperties 便捷方法签名、Jackson 3 JsonNode API、IT 跨用例全局态断言过滤、discovery-server 测试 classpath 误载 application.properties） |
| ⑧ 回归（2026-10-27，review 修复后） | all（全量 verify） | reactor 全模块 | BUILD SUCCESS（#1–#8 全修：PUT 回传 status / 恢复按钮挂 EJECT 行 / all-in-one 端口 0 守卫 / hash 与选择器与路径编码 / canary 行禁 CRUD 编辑 / 摘除提交后重渲染 / 共享命名 executor） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.0 | 2026-10-27 | 测试设计定稿（③）：1 单测 + 2 组 IT（console 闭环 / 订阅组合端到端），9 用例 |
| 1.0 | 2026-10-27 | 开工骨架 |
