# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支/worktree 新建（main 90297a1 起）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.2、架构 §4.1/§6、tech-java.md、guidelines §1/§4/§7/§10、proto-contract.md、legacy 分析 §5#10/§6#9、v0.1 SelfProtection 实现与测试、原产品 LeaseUpdateSafeChecker/LeaseManager 源码、phase-2/3 设计文档、registry-query/admin/node-status API 文档 |
| 设计 | done | review 通过（v3.0）；ADR-017 + 架构 §4.1/§6/§7、plan v1.3 已同步 |
| 实现 | done | 双层窗口/状态机/指标/独立 API + 单测与 IT 落地 |
| 测试 | done | dev-test.sh all 全绿（2026-10-21，unit + IT + spotless + enforcer） |
| code-review | done | 内建 `/code-review max`：14 项 findings，逐项验证 |
| fix | done | 领域判定/并发/快照 + `node_fallback` + 测试与文档同步 |
| 回归测试 | done | dev-test.sh all：BUILD SUCCESS（registry-server 45 IT / management-server 13 IT） |
| 验证（人工） | done | 5 项全部通过（2026-10-21，记录于测试文档） |
| 文档同步 | done | 产品 v1.12、registry-query-api v1.3、admin-api v1.4、架构 v1.30、ADR-017、plan v1.3、索引已同步；CLAUDE.md 无变更 |
| 完工 | done | squash 合并并推送 main；worktree 分支保留 |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-peer-protocol | done |
| phase-2-replication-fanout | done |
| phase-3-peer-bootstrap | done |
| phase-4-self-protection-2 | done |
| phase-5-sdk-failover | planned |
| phase-6-graceful-shutdown | planned |

状态枚举：planned | in-progress | done。
