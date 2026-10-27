# Phase 2 测试：RBAC

版本: 1.4    更新时间: 2026-10-27

## 测试方案

形态与覆盖面：

- **单测**（surefire `*Test`）：权限矩阵判定（角色 × 资源 × 动作全笛卡尔积）、HTTP 判权 filter 的路由登记表判定（含默认 deny）、多账户认证缓存（键控/失效路径）、用户管理应用服务校验与保护规则、Controller REST 语义。
- **IT**（failsafe `*IT`，进程内真实服务 + 真实 HTTP）：复用既有 `PersistenceDialectTestBase` 双方言（MySQL/PostgreSQL Testcontainers）基类形态——DB 态起服务（Flyway V7 迁移）→ seed admin → 以 admin 建 OPERATOR/VIEWER 用户 → 各角色 × 代表端点允许/拒绝 + 未授权分层（401/403）+ 审计记录；内存态 context 验证用户管理端点 409。
- 执行入口 `java/scripts/dev-test.sh`，⑤/⑧ 用 all 模式；无新增测试形态，dev-test.sh 不需扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_grant_only_matrix_permissions_when_role_fixed | 三角色 × 全部 Permission 笛卡尔积逐一断言 allow/deny 与设计矩阵一致 | 单测 | pass |
| should_map_admin_v1_routes_to_permissions_when_matched | 路由登记表：各 method+path（含 AntPath 通配）映射到预期 Permission | 单测 | pass |
| should_deny_when_admin_v1_route_not_registered | /admin/v1 下未登记路径默认 deny（403） | 单测 | pass |
| should_pass_through_when_path_outside_admin_v1 | /admin/v1 之外路径不经判权 | 单测 | pass |
| should_allow_when_authentication_disabled | auth.enabled=false（显式关闭）时判权放行 | 单测 | pass |
| should_verify_multi_accounts_when_db_mode | 多账户各自凭证 verify 返回对应 principal（username+role） | 单测 | pass |
| should_invalidate_cached_credential_when_password_or_role_changed | 改密/重置/改角色/删户后旧凭证立即失效（缓存槽清除，不等 TTL） | 单测 | pass |
| should_reject_when_username_or_password_or_role_invalid | 建户校验：username 非法（空白/超长/含 `/`）、密码 <8、role 非法 | 单测 | pass |
| should_reject_when_removing_last_admin_or_self | 改角色/删户使 ADMIN 归零或删自己 → 拒绝 | 单测 | pass |
| should_seed_admin_role_when_v7_migration | V7 迁移后 seed 账户与存量账户 role=ADMIN | IT | pass（MySQL/PostgreSQL 双方言 Docker 实跑） |
| should_enforce_role_matrix_on_admin_v1_endpoints | DB 态：viewer GET services 200 / POST ejections 403；operator 治理写 200 / GET users 403 / GET audit-logs 200；admin 全通（覆盖矩阵每行至少一个端点） | IT | pass（MySQL/PostgreSQL 双方言 Docker 实跑） |
| should_return_401_when_unauthenticated_or_wrong_credential | 无凭证/错凭证 401（认证层先行），认证后越权 403（分层） | IT | pass（MySQL/PostgreSQL 双方言 Docker 实跑） |
| should_record_audit_when_user_administered | 建户/改角色/重置密码/删户后 GET /audit-logs 可见对应 operation 行（operator 正确） | IT | pass（MySQL/PostgreSQL 双方言 Docker 实跑） |
| should_reject_user_administration_when_memory_mode | 内存态（无 DB）用户管理端点 409，自助改密 409（既有语义不回退） | IT | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

1. `java/scripts/dev-test.sh all`（⑤ 第 4 轮、⑧ 各一轮）：**BUILD SUCCESS**（全 reactor spotless + enforcer + 单测 + IT）。
2. Docker Desktop 启动后专项补跑双方言 IT：`mvn -f java/pom.xml -pl server/artemis-management-server,server/artemis-dist -am test-compile failsafe:integration-test failsafe:verify`（Testcontainers mysql:8.4 / postgres:16）：**BUILD SUCCESS**——MySQLRbacIT 4/4、PostgresRbacIT 4/4、双方言 AccountFlowIT、RouteRulePersistenceIT、AllInOnePersistenceIT 等全部通过。

人工验证（用户执行）：2026-10-27 用户结论 **通过**。

## 回归记录（⑤/⑧，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤-1 | all | 全 reactor | 失败：registry-core ProjectionDispatcherTest.should_disconnect_slow_subscriber_when_queue_full（未触碰模块，时序 flaky；单模块/子集 reactor 复跑均绿） |
| ⑤-2 | all | 全 reactor | 同上 flaky 复现；单模块 161 全绿、两模块 reactor verify 绿 |
| ⑤-3 | all | 全 reactor | registry 通过；暴露自身 UserAdminMemoryModeIT 400（@PathVariable 未命名，已修） |
| ⑤-4 | all | 全 reactor | **全绿**（management-core 133 + management-server 43 单测；内存态 IT 全执行；Testcontainers 双方言 IT 无 Docker skipped） |
| ⑧-1 | all | 全 reactor | 全绿（management IT 启动早于 Docker daemon，双方言 IT skipped，随后专项补跑） |
| ⑧-补-1 | integration | management-server + dist 全 IT（Docker） | 双方言 RbacIT 4/4 绿；RouteRulePersistenceIT 审计精确计数断言顺序依赖失败（既有潜在问题，改为按 target 过滤） |
| ⑧-补-2 | integration | management-server + dist 全 IT（Docker） | **全绿**（双方言 RbacIT/AccountFlowIT/RouteRulePersistenceIT、AllInOnePersistenceIT 等全部通过） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-27 | ⑨ 人工验证通过（用户结论） |
| 1.3 | 2026-10-27 | ⑧ 回归与 Docker 双方言 IT 补跑全绿；RouteRulePersistenceIT 审计断言改 target 过滤；⑨ 自动化验证记录 |
| 1.2 | 2026-10-27 | ⑤ 四轮记录与用例结果回填；修复 @PathVariable 未命名导致的 400 |
| 1.1 | 2026-10-27 | 测试设计定稿（单测矩阵 + 双方言 IT） |
| 1.0 | 2026-10-27 | 测试骨架 |
