# Phase 2 测试：两段式权重发布与回滚

版本: 1.3    更新时间: 2026-10-26

## 测试方案

形态与既有映射一致，dev-test.sh 无扩展：单测（surefire `*Test`，management-core / discovery-core 纯 JVM）+ 进程内集成（failsafe `*IT`，进程内起真实服务 + 真实协议驱动，不起容器；Testcontainers 仅 MySQL/PG 双方言持久化用例沿既有基建）。

覆盖面：

- **两段状态机与 diff 计算**（单测，management-core application/domain）：编辑通道收紧（create 初始 0 / update selector 等值继承）、release 拷贝、rollback 两列写回、diff 并集对齐。
- **release 记录存储**（单测：内存态 store；IT：MyBatis 双方言 + 跨重启）。
- **规则流**（单测：dispatcher 快照/增量/版本/断流；IT：management 侧 gRPC 流 + ack 登记）。
- **discovery 消费**（单测：RouteRules 集合幂等、Synchronizer 应用/ack；IT：端到端灰度 + 回滚 + 水位）。

discovery IT 断言通道：本地规则集经 `RouteRuleSynchronizer` 发布的不可变集 bean 注入直读（不为 IT 新增产品 API；ejection 侧先例以过滤效果断言，本 phase 无执行点故用 bean 断言），水位经 `GET /admin/v1/status/rules`（management）与 `/upstreams` routeRules 对照（discovery）双端核。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_initialize_weight_zero_when_create_route_rule | create 不收 weight，初始生效列全 0、编辑列按请求 | 单测 | pass |
| should_inherit_weight_by_selector_equality_when_update | update 替换 targets：selector 等值继承旧 weight，新 target weight 0 | 单测 | pass |
| should_reject_duplicate_selector_targets_when_validate | 同规则内 selector 等值重复 target → 域校验拒绝 | 单测 | pass |
| should_copy_unreleased_to_weight_when_release | release 全量拷贝编辑列到生效列，两列已相等照常生效 | 单测 | pass |
| should_append_release_record_with_post_snapshot_when_release | release 落 append-only 记录：type=RELEASE、快照=发布后 targets、rollbackTo 空 | 单测 | pass |
| should_restore_both_columns_from_source_snapshot_when_rollback | rollback 将 weight 与 unreleasedWeight 两列写回源快照，落 type=ROLLBACK 记录且 rollbackTo 指向源 | 单测 | pass |
| should_return_not_found_when_rollback_source_missing_or_foreign | 回滚源记录不存在 / 属于其它规则 → 404 语义域异常 | 单测 | pass |
| should_diff_added_removed_changed_targets_when_preview | 编辑态 diff：selector 并集对齐，新增（currentWeight null）/移除（nextWeight null）/变更/无差异 | 单测 | pass |
| should_diff_against_release_snapshot_when_release_id_specified | 指定 releaseId：next 列取记录快照 weight 列（回滚预览） | 单测 | pass |
| should_snapshot_active_rules_with_version_when_subscribe | dispatcher 订阅快照 = ACTIVE 集过滤 + 当前 tick 版本 | 单测 | pass |
| should_push_increment_and_advance_tick_on_change | CRUD/release/rollback 通知推 upserted/deleted 增量且 tick 单调递增；INACTIVE 化推 deleted | 单测 | pass |
| should_disconnect_subscriber_when_push_fails | 推送失败断流注销，重连经快照自愈（at-most-once） | 单测 | pass |
| should_upsert_and_delete_idempotently_in_route_rules | discovery RouteRules 集 upsert 覆盖 / delete 幂等 | 单测 | pass |
| should_apply_and_ack_after_each_event | Synchronizer 应用后推进本地 appliedVersion 并 ack；断连不清缓存（fail-open） | 单测 | pass |
| should_release_end_to_end_via_single_api | 端到端灰度：create + 编辑 → 一次 release 调用 → discovery 规则视图 weight 更新 + 双端水位对齐（roadmap 验收要点） | IT | pass |
| should_rollback_end_to_end_with_view_restored | 端到端回滚：release×2 → rollback(releaseId) → discovery 视图还原源快照权重 + 水位推进 | IT | pass |
| should_flow_rules_via_watch_stream | management 流 IT：watch 快照（ACTIVE 集）→ release 推 upserted → INACTIVE 推 deleted → Ack 登记（EjectionRuleStreamIT 模式） | IT | pass |
| should_expose_release_api_contract_over_http | HTTP IT：release/rollback/diff/releases 四端点契约（视图、错误码 404/503、审计行可查） | IT | pass |
| should_keep_release_records_across_restart_in_db | DB 态：release 记录跨重启保留，重启后按历史 releaseId 回滚仍生效（双方言；schemaVersion 断言 5→6） | IT | pass（本机无 Docker 整类 skip，CI 有 Docker 补跑；schemaVersion 断言随 PersistenceDialectTestBase 已改 6） |

## ⑤ 首跑轮内修复（非 review findings）

- 单测抓到 `RouteRules.with` 在键不存在时 NPE（`Map.get` 未判空）——改 `Objects.equals`。
- diff 语义缺陷：同 targets 两列对比看不见结构变更（编辑删除的目标不可达）——基改为发布快照（设计 §3 已回写）。
- Spring 装配：两 `RuleAckRegistry`/两 `ManagementStatus` bean 在无 `-parameters` 编译下按名匹配失效——消费方显式 `@Qualifier`。
- 共享 SCF 配置键两处声明触发「同 key 单一 PropertyConfig（含 doc）」校验失败——抽出 `ManagementSyncProperties` 配置值对象（规范 §10.1 落实，设计 §6 已回写）。
- IT 断言：Jackson 显式 null 字段解析为 NullNode 非 Java null（`isNull()` 改 `JsonNode.isNull()`）；同 context 用例键隔离（规则名独立）；registry `ReplicationFanoutIT` 一次负载性超时复跑通过（与本 phase 无关）。

## 验证记录（⑨ 填）

自动化验证（工作流执行，2026-10-26/27）：

| 命令 / 检查 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑，多轮收敛后全绿） | BUILD SUCCESS（详 ⑤ 首跑轮内修复节） |
| `java/scripts/dev-test.sh unit`（⑦ 修复后快速回归） | BUILD SUCCESS，全模块单测 0 失败 |
| `mvn -f java/pom.xml verify`（⑧ 回归，all 同口径：spotless + enforcer + 159 组测试含全部 IT） | EXIT=0，BUILD SUCCESS，Failures/Errors 全 0（24 skipped = 无 Docker 双方言 DB IT 整类跳过，CI 有 Docker 补跑） |
| proto 生成物取证：artemis-common `target/generated-sources` 含 `RouteRuleServiceGrpc` / `RouteRulesEvent` / `RouteRule` / `RouteRuleKey` / `AckRouteRules*` | ✓（management.v1 同源生成纪律） |
| V6 双方言 migration 落位：mysql / postgresql `V6__route_rule_release.sql` 均在 | ✓ |

人工验证（用户执行）：待记。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all（verify） | 全 reactor：code-review 修复 7 项（写序 / 坏行 / ACTIVE 单点 / everSynced / 水位组装 + admin-api 与 discovery-admin-api 两文档） | BUILD SUCCESS（159 组测试 0 失败） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-27 | ⑧ 回归记录 + ⑨ 自动化验证记录（verify EXIT=0 / proto 生成物 / V6 双方言取证）；待人工验证 |
| 1.2 | 2026-10-26 | ⑤ all 全绿（spotless + enforcer + 单测 + IT；24 skipped = 无 Docker 双方言 DB IT 整类跳过）；轮内修复五项记录 |
| 1.1 | 2026-10-26 | 测试设计定稿（待 review）：14 单测 + 5 IT，形态复用既有映射（dev-test.sh 无扩展） |
| 1.0 | 2026-10-26 | 开工骨架 |
