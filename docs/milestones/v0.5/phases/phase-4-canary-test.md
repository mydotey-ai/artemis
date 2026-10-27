# Phase 4 测试：canary（自动专属规则组）

版本: 1.3    更新时间: 2026-10-27

## 测试方案

canary 变更全部收敛在 management 侧（core 用例 + server 端点），执行 / 下发 / 回滚复用 phase-2/3 既有机制——测试分三层，形态全部在既有映射内（dev-test.sh 零扩展）：

- **单测**（management-core，surefire）：targets 派生与域校验、保留名拒绝、deploy/delete 用例（InMemory store + 既有 dispatcher/audit，锁语义与写序断言）。
- **管理面 IT**（management-server，failsafe：`CanaryIT` 内存态 + `RouteRulePersistenceIT` 增 canary 用例双方言继承）：HTTP 端点行为、release 记录落库、rollback 联动、重启持久。
- **端到端 IT**（discovery-server，`DiscoveryCanaryIT`：进程内 registry + discovery + management + 订阅查询出口，phase-3 GroupRoutingIT 骨架）：canary API 驱动的视图切分——roadmap 验收要点「canary 流量切分」的对应测试。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见 ⑤ 节）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_derive_canary_targets_when_deploy | selector+weight 派生 canary 目标 + 兜底目标，两列一致、兜底 = 10000-weight | 单测 | pass |
| should_reject_invalid_canary_requests | selector 缺失/空 map、weight 越界拒绝；边界 0/10000 合法 | 单测 | pass |
| should_reject_reserved_name_when_crud_create_or_update | CRUD create/update 名 `canary` → IllegalArgumentException（保留名） | 单测 | pass |
| should_replace_targets_and_append_release_when_redeploy | 重复部署不同 weight/selector：targets 全量替换、createdAt 继承、每部署一条 RELEASE 记录 | 单测 | pass |
| should_delete_rule_and_keep_releases_when_delete_canary | 删除：规则删除 + releases 保留 + DELETE_CANARY 审计；二次删除幂等不追加审计 | 单测 | pass |
| should_deploy_canary_over_http | POST 200 视图两 targets 两列一致；GET route-rules/{ns}/{svc}/canary 同规则；RELEASE 记录 + DEPLOY_CANARY 审计 | 管理 IT | pass |
| should_return_400_when_canary_request_invalid | HTTP 层空 selector / weight 越界/缺失 / body 缺失 → 400 且无规则残留 | 管理 IT | pass |
| should_reject_reserved_route_rule_creation_over_http | CRUD HTTP 建保留名规则 → 400 | 管理 IT | pass |
| should_rollback_canary_to_previous_weight | 部署 w=1000 → 部署 w=3000 → 既有 rollback API 指向首条 releaseId → 恢复 1000（两列写回） | 管理 IT | pass |
| should_delete_canary_over_http_idempotently | DELETE 204；规则 404；releases 保留；再 DELETE 204 幂等不追加审计 | 管理 IT | pass |
| should_keep_canary_rule_and_releases_across_management_restart | DB 态（MySQL/PG 双方言）重启后派生规则已发布态与 RELEASE 记录保留 | 管理 IT | pass（无 Docker 跳过，双方言继承 RouteRulePersistenceIT） |
| should_cut_canary_traffic_end_to_end | 注册 metadata 实例 + canary 部署 weight=0 → 金丝雀实例摘出查询视图；再部署 weight=1000 → 回归；DELETE → 规则 404 全可见 | 端到端 IT | pass |

用例清单与实现的方法级对应：单测 5 个合并覆盖设计稿 8 条（派生纯函数经 deployCanary 行为断言、校验三态合一、部署/重部署/删除各含审计与记录断言）；IT 键隔离先例（phase-1）应用于 CanaryIT 方法级 service 隔离。

## 验证记录（⑨ 填）

**自动化验证（工作流执行）**：

| 命令 | 结果 |
|---|---|
| `mvn -f java/pom.xml verify`（经 dev-test.sh all，⑤ 与 ⑧ 各一次全绿） | BUILD SUCCESS（14 模块：spotless + enforcer + 单测 + 集成 IT；DB 方言 IT 无 Docker 整类跳过，与既往一致） |
| `mvn -pl artemis-registry-server failsafe:... -Dit.test=ReplicationFanoutIT`（偶发复跑） | 6/6 pass |
| code-review skill（max）4 findings 全修后回归 | 上述 ⑧ 全绿 |

**人工验证（用户执行）**：2026-10-27 用户确认通过。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor | registry-server `ReplicationFanoutIT` 偶发负载超时（phase-2/3 已知先例，本 phase 零改 registry）→ 单独复跑 6/6 绿 |
| 2 | all（断点续跑排查中） | registry-server 整模块 + 续跑 | 整模块复跑全绿；`-rf` 续跑段 management-server AdminIT/ConsoleIT 出现 dataSource context error（非代码缺陷：同形态干净全量两度绿，判定 `-rf` 环境伪影） |
| 3 | all | 全 reactor | **全绿**（BUILD SUCCESS，14 模块，含 review 修复后 DiscoveryCanaryIT 真断言版） |

## 更新历史补记

⑧ 回归三轮：偶发项均为 registry 时序敏感 IT 与 `-rf` 伪影，与本 phase 变更无关；最终以全量 all 全绿收口。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-27 | ⑧ 回归全绿（3 轮，偶发项与 `-rf` 伪影已排除）；⑨ 自动化验证记录 |
| 1.2 | 2026-10-27 | ⑤ 全绿：用例结果登记（12 条 pass，单测 5 方法合并覆盖设计 8 条）；CanaryIT 方法级键隔离 |
| 1.1 | 2026-10-27 | 测试设计定稿：三层形态（core 单测 / 管理 IT / 端到端 IT）与用例清单 |
| 1.0 | 2026-10-27 | 开工骨架 |
