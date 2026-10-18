# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支/worktree 新建（main 33931ad 起；phase 主题按 ADR-016 修订为 registry-query-api）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.1、架构 §2.2/§3.1/§4.1/§5.1/§5.2/§6、tech-java.md 全文、guidelines 全文、legacy §2.5/§2.6/§3.5/§3.6/§5 #12、phase-4 设计/测试文档、registry-core 领域与 application 层现状、registry-server 装配/认证/HTTP 入口、SCF 1.6.4 API（getProperties/source name）、现有单测与 IT 模式 |
| 设计 | done | 按 ADR-016 重写为 registry 只读查询 RPC；D1–D4 定案；ADR-016/架构 v1.22/roadmap v1.8/plan v1.12 同步 |
| 实现 | done | registry 只读查询 RPC：契约/领域/应用/接入/装配 + 单测与 IT |
| 测试 | done | dev-test.sh all BUILD SUCCESS：common 8 / core 87 / server 22 / IT 20 |
| code-review | done | 15 条 findings（2 High / 7 Medium / 6 Low） |
| fix | done | 15 条 findings 全部处置：敏感默认值脱敏、租约过滤/截断、age 钳制、权限边界文档、文档漂移修复 |
| 回归测试 | done | dev-test.sh all BUILD SUCCESS（common 8 / core 89 / server 22 / IT 20；删除冗余 LeaseTtlSource、契约迁 management.v1 后复跑仍绿） |
| 验证（人工） | done | 9 项清单用户执行确认通过 |
| 文档同步 | done | 产品 v1.5、架构 v1.23、ADR-016 v1.2、tech-stack v1.19、guidelines v1.22、新增 docs/api/registry-query-api.md 与索引 |
| 完工 | done | worktree 分支 4989eb8 已提交；squash merge 回 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-scaffold | done |
| phase-2-heartbeat | done |
| phase-3-protect-snapshot | done |
| phase-4-server-assembly | done |
| phase-5-registry-query-api | done |
| phase-6-management-service | planned |
| phase-7-client-sdk | planned |
| phase-8-starter-ci | planned |

状态枚举：planned | in-progress | done。
