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
| 分支 / worktree | v0.2-phase2-replication-fanout @ .claude/worktrees/v0.2-phase2-replication-fanout |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支/worktree 新建（main fa17c45 起）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap §3 v0.2、架构 §2.2/§4.1/§6/§7、tech-java.md、guidelines §1/§2/§4/§7/§10、proto-contract.md、legacy §3 复制协议/§5 资产 #1#11/§6 局限 #2#4、v0.2 plan、phase-1 设计文档、replication.proto、代码（ChangeListener/InstanceRegistry/Replication*/PeerServerConfiguration/RegistryConfiguration/ClusterMembershipIT） |
| 设计 | done | review 通过（v3.0：副本租约继承原产品）；架构 §4.1 v1.28 与 plan v1.1 已同步 |
| 实现 | done | |
| 测试 | done | ⑤ 首跑 dev-test.sh all 全绿（含修复：applyReplicated 空 holder 回收、RENEW 零快照重建、旧过期断言对齐） |
| code-review | done | 4 findings：入站漏 ServiceKey 校验、确定性失败无限重试队头阻塞、认证头 user:null、peer.events.dropped 按批计数语义错位 |
| fix | done | 全部修复：validate 补 key 校验；PermanentReplicationException 丢批续投（batches.rejected 指标）；headerValueOrNull；指标改名 batches.dropped |
| 回归测试 | done | ⑧ dev-test.sh all 全绿（含 review 新增用例） |
| 验证（人工） | done | 3 项全通过（用户确认 2026-10-21） |
| 文档同步 | done | 产品文档 v1.10（phase-2 交付节 + 过时表述修正）；架构/plan 已在 ③ 同步；proto 兼容追加；CLAUDE.md 无需变更 |
| 完工 | done | 分支 8dad0dd 提交，squash 回 main 并 push |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-peer-protocol | done |
| phase-2-replication-fanout | done |
| phase-3-peer-bootstrap | planned |
| phase-4-self-protection-2 | planned |
| phase-5-sdk-failover | planned |
| phase-6-graceful-shutdown | planned |

状态枚举：planned | in-progress | done。
