# Phase 2 测试：账户体系与统一认证接管

版本: 1.1    更新时间: 2026-10-25

## 测试方案

- **单测**（surefire `*Test`）：哈希器、裁决服务（内存态配置实现 / DB 态 mock `AccountStore`）、`BasicAuth.decode`、HTTP 过滤器三态路径、改密端点校验链。DB 访问不 mock SQL——mapper/SQL 语义归 IT 真实 DB 验证。
- **IT**（failsafe `*IT`，`dev-test.sh integration`）：进程内起 management（随机端口）+ Testcontainers 双方言（`AccountFlowIT` 基类 + PG / MySQL 各一子类，无 Docker 整类跳过）驱动端到端：seed → 登录 → 改密 → 旧凭证失效 → **跨重启（同 DB 新 `SpringApplicationBuilder` context）账户保留 + 配置凭证失效**（@Order 定序，改密用例有状态依赖）。
- **回归面**：内存态行为不变——既有 `ManagementAuthConfigTest` / `ApplicationContextTest` / `ManagementAdminIT` 全绿即内存态零回归；双方言 `schemaVersion` 断言随 V2 更新（1 → 2）。
- dev-test.sh 无模式扩展（既有 unit / integration / all 映射覆盖全部用例形态）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_hash_and_match_when_password_correct / should_not_match_when_password_wrong | PasswordHasher 往返 / 错密码 false | 单测 | pass |
| should_return_false_when_stored_hash_malformed | 坏格式哈希串（缺段/坏 b64/未知算法/非正 iterations）false 不抛 | 单测 | pass |
| should_produce_distinct_hashes_when_same_password_twice + should_embed_parameters_in_hash_format | 随机 salt 两次哈希不同串均可匹配；格式含 600000 参数 | 单测 | pass |
| should_verify_when_db_account_credentials_match / should_not_verify_when_username_mismatch_or_account_absent | DB 态凭证裁决 / 用户名不匹配 / 表空 false | 单测 | pass |
| should_use_cache_when_same_credentials_within_ttl / should_bypass_cache_when_cache_expired | 单槽缓存命中（store 零触达）/ TTL 过期再触达 | 单测 | pass |
| should_invalidate_cache_when_password_changed | 改密后旧缓存凭证立即 false | 单测 | pass |
| should_reject_change_when_old_password_wrong / should_reject_change_when_new_password_too_short | 改密校验链，不写 store | 单测 | pass |
| should_propagate_store_failure_as_unavailable | store 异常传播（接入层 503 判据） | 单测 | pass |
| should_seed_when_store_empty / should_not_seed_when_store_has_account | seed 物化自定义配置值 / 表非空不覆盖 | 单测 | pass |
| should_detect_password_in_use | 默认密码启动告警判据 | 单测 | pass |
| should_match_configured_credentials_when_memory_mode（ConfigAccountAuthServiceTest 4 用例） | 内存态配置凭证语义零变化（含运行期轮换、空白拒绝、改密不支持） | 单测 | pass |
| should_decode_basic_header_roundtrip / should_decode_when_scheme_case_and_spaces_vary / should_return_null_when_header_malformed / should_match_when_scheme_case_and_spaces_vary（BasicAuthTest） | decode 往返与坏输入 null | 单测 | pass |
| should_authorize_when_authenticator_accepts / should_reject_request_when_authenticator_rejects / should_return_503_when_authenticator_unavailable（HttpBasicAuthFilterTest） | 过滤器三态路径 + 关认证放行 + 无凭证 401 | 单测 | pass |
| should_change_password_when_old_valid / should_return_409_when_memory_mode_change_password / should_return_400_when_change_request_invalid / should_propagate_service_validation_when_new_password_too_short（AccountControllerTest） | 改密端点语义 | 单测 | pass |
| should_map_account_failures_when_advice_handles（ManagementExceptionHandlerTest） | 503 / 409 映射 | 单测 | pass |
| should_seed_default_account_and_login_when_database_mode | DB 态默认配置首启 seed → 默认凭证 200 | IT（双方言） | pass |
| should_honor_password_change_when_database_mode | 改密 → 旧凭证 401、新凭证 200（接管生效） | IT（双方言） | pass |
| should_retain_account_across_restart | 同 DB 新 context：改后凭证 200、默认/自定义配置凭证 401（表唯一源 + 不 re-seed） | IT（双方言） | pass |

「配置自定义凭证 seed」以单测 `should_seed_when_store_empty`（自定义值物化断言）+ IT restart 用例（配置凭证失效）联合覆盖，未单列 IT 用例。

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

- `dev-test.sh all`（⑤ 首跑，2026-10-25 12:45–13:14）：13 模块全 SUCCESS（含 dist 组合 classpath）；PG/MySQL `AccountFlowIT` 6 用例全过。
- `dev-test.sh integration`（中程确认，2026-10-25 12:47–12:57）：全 reactor SUCCESS。
- `dev-test.sh all`（⑧ 回归，review 修复后，2026-10-25 13:21–13:34）：13 模块全 SUCCESS——修复（503 响应形态、测试语义修正）无回归。

人工验证（用户执行）：待用户验证。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（review 修复后） | all | 全 reactor（spotless + enforcer + 单测 + IT 双方言） | 全绿（13 模块，13:07） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-25 | 用例清单对齐实现并登记结果（dev-test.sh all 全绿）；seed 自定义凭证改为单测 + IT 联合覆盖 |
| 1.0 | 2026-10-25 | 测试设计定稿（待 review） |
