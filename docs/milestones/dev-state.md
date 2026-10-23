# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）；v0.2 已于 2026-10-23 收尾（验收对照见 `v0.2/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main 7f57eff 起；分支/目录 v0.3-phase3-subscription-push；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.3、架构 §2.2/§4.2/§7、tech-java.md、guidelines §1/§2/§4/§7/§10、proto-contract.md、legacy §2.4/§5 资产 #8/§6 局限 #13、v0.3 plan、phase-2 设计文档、phase-1 projection.v1 契约、discovery-core/server 现有源码 |
| 设计 | done | 三轮 review 定案：推拉结合 + diff 续推（ADR-019 v1.1、架构 1.36、roadmap 1.12、plan 1.3 同步） |
| 实现 | done | core 4 类 + registry 读方法 + 同步器双路径编排 + server 出口/装配/IT |
| 测试 | done | dev-test.sh all 全绿（含全 reactor verify） |
| code-review | done | 内联兜底 + skill 双轨：skill 10 findings（约 16min 延迟返回）+ 内联 4 findings，逐条验证后 9 修 1 记录 | |
| fix | done | 表锁串行化（S1 核心竞态）+ 异常回滚 + 索引 memo + disconnect 删除 + 校验提取 + IT 断言强化 | |
| 回归测试 | done | dev-test.sh all 全绿（BUILD SUCCESS） | |
| 验证 | done | 自动化（dev-test all×2）+ 人工（2026-10-24 通过） | |
| 文档同步 | done | 产品文档 1.18、api/discovery-subscription-api.md 新建、索引登记；ADR/架构/roadmap/plan 于 ③ 已同步 | |
| 完工 | done | 分支 4c4dd20 提交，squash 合入 main | |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-projection-source | done |
| phase-2-discovery-projection | done |
| phase-3-subscription-push | done |
| phase-4-fallback-freshness | planned |
| phase-5-multi-cluster | planned |
| phase-6-ejection-filter | planned |
| phase-7-discovery-admin-api | planned |
| phase-8-consumer-sdk | planned |
| phase-9-starter-all-in-one | planned |

状态枚举：planned | in-progress | done。
