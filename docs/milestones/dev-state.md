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
| worktree 开工 | done | worktree 用户预建（main 83bebf6 起、无分叉干净）续用确认；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.2、架构 §2.2/§4.1、tech-java.md、guidelines §4/§5/§7、legacy §2.7（无优雅停机资产）、phase-5 设计文档、replication.proto NodeState（DOWN 预留）、RegistryBootstrap/RegistryConfiguration/ReadinessGate/ReplicationServiceImpl.nodeStatus/ClusterNodeStatusMonitor/ReadinessInterceptor/ReplicationGrpcService/SdkFailoverIT、guidelines §7 |
| 设计 | done | review 通过（1 轮修订，设计文档 v1.2 / 测试文档 v1.2：starter ContextClosedEvent 自动 close 时序修正） |
| 实现 | done | core（ReadinessGate 三态 + nodeStatus/clusterNodes DOWN 优先）+ server（GracefulShutdownHook + 装配）+ starter（ContextClosedEvent 自动 close）+ pom（surefire/failsafe 测试 JVM drainMs=0）+ 测试（3 单测类新增/扩充 + GracefulShutdownIT 4 用例 + starter 事件用例） |
| 测试 | done | ⑤ dev-test.sh all 全绿（首轮 spotless 违规，apply 后重跑 BUILD SUCCESS）；单跑确认 GracefulShutdownIT 4/4、GracefulShutdownHookTest 3/3、starter 事件用例过 |
| code-review | done | 内联 max 级 review（按 dev-java ⑥「已知环境故障与兜底」：skill 子 agent 无内置工具、吊死 playwright 约 1h）：5 findings（1 high 空快照覆盖、2 medium bootstrap 竞态/IT 假绿、2 low） |
| fix | done | 5/5 修复：未就绪不终刷 + bootstrap DOWN 早退（各补用例）、IT isAlive 断言、新端口、AssertJ 收敛 |
| 回归测试 | done | ⑧ 三轮：① 新断言暴露 gRPC 关停宽限 30s 与 drain 叠加（失败）；② 宽限 1s 后全绿；③ 测试属性补齐后全绿（GracefulShutdownIT 4/4，31s） |
| 验证 | done | 自动化验证通过（dev-test.sh all BUILD SUCCESS + 日志取证）；用户人工验证通过（2026-10-23） |
| 文档同步 | done | 架构 v1.32、产品 v1.14、sdk-java v1.3、phase 设计 v1.4 / 测试 v1.3、dev-java ⑥ 兜底条款；索引无新增 |
| 完工 | done | 分支 a490ba4 提交；squash 回 main 并 push |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-peer-protocol | done |
| phase-2-replication-fanout | done |
| phase-3-peer-bootstrap | done |
| phase-4-self-protection-2 | done |
| phase-5-sdk-failover | done |
| phase-6-graceful-shutdown | done |

状态枚举：planned | in-progress | done。
