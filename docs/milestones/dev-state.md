# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | done | |
| v0.4 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

> v0.1/v0.2/v0.3 已收尾（验收对照见各 `plan.md`）。v0.4 于 2026-10-24 启动（治理面 I）。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 清理 15 个已完工 worktree/分支后新建 |
| 上下文加载 | done | 已读：roadmap §3 v0.4、架构 §2/§4.2/§5、tech-java.md、guidelines 全文、proto-contract、legacy §3.3/§5#7/§6#8；现状代码：EjectionRuleDispatcher/Synchronizer/GrpcService/GrpcEjectionRuleSource、EjectionRecords、V3 migration、DiscoveryEjectionFilterIT |
| 设计 | done | review 通过（2026-10-26） |
| 实现 | done | proto 版本字段 + AckEjections unary；management：lastFlipSequence/Dispatcher 版本/EjectionAckRegistry/StatusController /rules；discovery：listener/source 版本 + ack、ManagementStatus appliedVersion、admin API 对照；单测新增/修订全绿 |
| 测试 | done | dev-test.sh all 全绿（r4；r1–r3 修复：spotless 格式、EjectionRuleStreamIT 断言收窄、AckLoopIT classpath 隔离）；DB IT 后补验 20/20 全绿（Docker Desktop user 级服务启动） |
| code-review | done | 内置 code-review skill（max）：5 findings（2 确认 + 3 似合理） |
| fix | done | F1 代际令牌防重连误删、F2 删冗余 snapshot.version 字段、F3 ack 走 watch 端点、F4 接受限界（javadoc）、F5 收尾统一 |
| 回归测试 | done | dev-test.sh all（r5）全绿 |
| 验证 | done | 自动化全部通过（all 两轮 + DB IT 补验 20/20）；用户人工验证通过（2026-10-26） |
| 文档同步 | done | 产品文档 1.29（phase-5 交付段）、架构 1.43（§2.2 落点）、api 三份（ejection-rule-stream 1.2 / admin 1.10 / discovery-admin 1.1）；索引与 CLAUDE.md 无变更 |
| 完工 | done | squash merge 回 main（45ea383 基线）并 push | |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-db-foundation | done |
| phase-2-account-takeover | done |
| phase-3-ejection-persistence | done |
| phase-4-logical-instance | done |
| phase-5-rule-ack-loop | done |
| phase-6-console-readonly | planned |
| phase-7-all-in-one-full | planned |
