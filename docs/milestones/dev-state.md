# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | done | |
| v0.4 | done | |
| v0.5 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | v0.5-phase2-two-phase-release @ .claude/worktrees/v0.5-phase2-two-phase-release |

> v0.1–v0.4 已收尾（验收对照见各 `plan.md`）。v0.5（治理面 II）于 2026-10-26 启动，基线 c87dc2a 已提交。phase-1（route-rule 资源模型与持久化）已交付（e961ebb）。phase-2（两段式权重发布与回滚）已交付。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main e961ebb 起点新建，骨架两文档 + 索引登记 |
| 上下文加载 | done | 已读：roadmap v0.5、架构 §2.1/§2.2/§4.2/§5.2/§5.3、tech-java.md 全文、guidelines §1–§3/§7/§10、proto-contract、legacy §2.5/§5-5/缺陷「SQLite 分支破坏两段式发布」、v0.5 plan + phase-1 设计、v0.4 phase-5 设计、management.proto、management-core/server 与 discovery-core/server 相关源码、IT 基建现状 |
| 设计 | done | review 通过（2026-10-26），定案登记见设计文档 §9 |
| 实现 | done | proto RouteRuleService 组；management-core release 领域 + dispatcher + AckRegistry 泛化改名；V6 双方言 + repository 三件套；四端点 + StatusController routeRules 节；discovery RouteRules/Synchronizer/GrpcSource + upstreams 对照；实现期定案修正回写设计 §3（diff 基=快照）§8（routeRuleLock） |
| 测试 | done | dev-test.sh all 全绿（多轮收敛：Qualifier 歧义 / NullNode 断言 / SCF 同 key 配置抽出 ManagementSyncProperties / diff 基语义修正，记录见测试文档 1.2） |
| code-review | done | 内置 skill max 级：11 findings（高 2 / 中 5 / 低 4） |
| fix | done | 修 7 / 接受 4；unit 回归绿 |
| 回归测试 | done | all 全绿（159 组测试 0 失败，24 skipped = 无 Docker DB IT） |
| fix | pending | |
| 回归测试 | pending | |
| 验证 | done | 自动化 + 人工验证均通过（2026-10-27） |
| 文档同步 | done | 产品文档 1.33 / admin-api 1.13 / route-rule-stream-api.md 新建 / discovery-admin-api 1.2 / 架构 1.44（§2.2 落点）/ 索引；清单见设计文档 1.4 |
| 完工 | done | 分支 78caa4c squash 入 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-route-rule | done |
| phase-2-two-phase-release | done |
