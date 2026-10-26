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
| 分支 / worktree | v0.5-phase1-route-rule @ .claude/worktrees/v0.5-phase1-route-rule |

> v0.1–v0.4 已收尾（验收对照见各 `plan.md`）。v0.5（治理面 II）于 2026-10-26 启动，基线 c87dc2a 已提交。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main fed8655 起点新建，骨架两文档 + 索引登记 |
| 上下文加载 | done | 已读：roadmap §3 v0.5、架构 §5.1–§5.3、ADR-013、tech-java.md、guidelines §7、legacy §2.5/§5/§6、v0.4 phase-3/phase-5 先例、management 现有代码与 V1–V4 migration |
| 设计 | done | review 通过 2026-10-26，定案登记见设计文档 §7 |
| 实现 | done | V5 双方言 + domain/application/infrastructure/repository/HTTP 五层 + 单测与 IT |
| 测试 | done | dev-test.sh all 全量 BUILD SUCCESS（⑤ 首跑全绿；调试轮内修复测试 body 构造 bug、audit 前缀、IT 键隔离） |
| code-review | done | 内置 skill max 级：6 findings（3 高/1 中/2 低），chat 已呈现 |
| fix | done | 6 findings 全修 + PUT 缺 name 行为锁定断言；登记见设计文档「实现结果」 |
| 回归测试 | done | dev-test.sh all 全量 BUILD SUCCESS（⑧ 轮 1） |
| 验证 | done | 自动化两轮 all 全绿；人工验证通过（用户 2026-10-26） |
| 文档同步 | done | 产品文档 1.32 + admin-api 1.12；架构/ADR/proto 无变更（review 定案既有结论） |
| 完工 | done | 0c2a40d 已 squash merge 回 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-route-rule | done |
