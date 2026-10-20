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
| worktree 开工 | done | 分支/worktree 新建（main e5d6eec 起）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap §3 v0.2、架构 §2.2/§3.1/§4.1/§6/§8、tech-java.md 全文、coding-guidelines §1/§2/§4/§7/§10、proto-contract §1–§3、legacy §2.7/§5 #2/#9/#11/§6 #4–#10、plan v0.2 与 phase-1 骨架、现有 registry/management 代码与 IT |
| 设计 | done | 设计/测试文档两轮 review 通过（v1.3） |
| 实现 | done | proto + 双监听 + 成员/节点状态 + management 查询迁移；架构/规范/选型同步 |
| 测试 | done | `dev-test.sh all` 全绿（EXIT=0） |
| code-review | done | max：14 findings（安全默认/配置严格性/观测拦截器/测试覆盖/文档），见 phase 文档实现结果 |
| fix | done | 全部 findings 已修复：地址无默认值、端口固定（不支持 0）、认证关闭 WARN、内部观测/异常拦截器、IPv6 括号、查询认证与客户端凭证用例；配置动态性 review（保护窗口/调度周期/查询 timeout/认证开关与凭证动态化，management 单一 endpoint）；文档同步 |
| 回归测试 | done | `dev-test.sh all` EXIT=0（第 2 轮） |
| 验证（人工） | done | 用户 2026-10-20 确认全部通过 |
| 文档同步 | done | 产品文档 v1.9、架构 v1.27、规范 v1.25、选型 v1.22、API 文档；索引与 CLAUDE.md 无需变更 |
| 完工 | done | 分支提交 24d332d，squash 合并入 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-peer-protocol | done |
| phase-2-replication-fanout | planned |
| phase-3-peer-bootstrap | planned |
| phase-4-self-protection-2 | planned |
| phase-5-sdk-failover | planned |
| phase-6-graceful-shutdown | planned |

状态枚举：planned | in-progress | done。
