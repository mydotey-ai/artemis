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
| 分支 / worktree | v0.2-phase5-sdk-failover @ .claude/worktrees/v0.2-phase5-sdk-failover |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支/worktree 新建（main 8a6706c 起）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.2、架构 §2.1/§2.2/§4.1、ADR-006、legacy §2.8/§5#2/§8-5、guidelines §7/§9/§10、tech-stack §2.2 矩阵、client internal 全组件、服务端 NodeStatusGrpcService/StaticClusterMembers/ClusterNodeStatusMonitor/ReplicationServiceImpl、NodeStatusApiIT/ClientSdkIT、replication.proto |
| 设计 | done | review 通过（4 轮修订，设计文档 v1.4 / 测试文档 v1.3） |
| 实现 | done | 回退重入完成：/cluster/nodes readiness 门控（503）+ FakeRegistryServer 开流拒绝 + 3 新用例；架构 §2.2/§4.1 与设计/测试文档已同步 |
| 测试 | done | 回退重入 ⑤ all 全绿（12 模块 BUILD SUCCESS，新用例 DefaultArtemisClientTest 13/HttpNodeListFetcherTest 5/ClusterNodesControllerTest 1 全过） |
| code-review | done | 回退重入 ⑥：9 findings（1 单链破坏、2 markBroken 双计、3 阈值语义、5 轮换双跳为行为级） |
| fix | done | 9/9 全修复 + 单链保持用例补充；实现结果已记（设计文档 v1.7） |
| 回归测试 | done | ⑧（回退重入）all 全绿；中途 2 失败已处置（starter 断言对齐、ReplicationFanoutIT 判 flaky 单跑+末轮过） |
| 验证 | done | 自动化验证重验通过（all 全绿 + TTL 取证 + serverAddresses 无残留）；用户人工验证通过（2026-10-23，含两条裁决：寻址端点 readiness 门控、UNAVAILABLE 收敛用例） |
| 文档同步 | done | 架构（④ 期已同步 v1.31）、sdk-java v1.2、node-status-api v1.1、产品文档 v1.13；phase 文档「文档同步」节已填 |
| 完工 | done | 分支 2b48004 提交（用户发起 commit）；squash 回 main 并 push |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-peer-protocol | done |
| phase-2-replication-fanout | done |
| phase-3-peer-bootstrap | done |
| phase-4-self-protection-2 | done |
| phase-5-sdk-failover | done |
| phase-6-graceful-shutdown | planned |

状态枚举：planned | in-progress | done。
