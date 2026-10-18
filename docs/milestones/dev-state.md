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
| worktree 开工 | done | 分支/worktree 新建（非幂等续用）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.1、架构 §4.1/§7/§8、tech-java.md 全文、guidelines 全文、proto-contract.md、legacy §2.3/§2.7/§5 #9#10/§6 #5#9/§8（空集群死锁）、phase-2 设计/测试文档、registry-core/server 现有代码（InstanceRegistry/RegistryService/RegistryConfiguration 等） |
| 设计 | done | review 三轮修订（D5 三分支 / D10 时效 / D7 周期 5min 可配）人工复审通过；架构 §4.1 v1.19、ADR-015 v1.8 同步 |
| 实现 | done | 四层落地：domain（SelfProtection 分桶滑窗、快照端口、InstanceRegistry.snapshots/restore）、application（ReadinessGate、codec、编排）、infrastructure（文件 store 原子写、快照调度器）、server（bootstrap、UNAVAILABLE interceptor、装配）。实现期修正：SelfProtection 轮换改逐秒推进 + 滑出前采样（maxCount 峰值不依赖查询驱动） |
| 测试 | done | dev-test.sh all 全绿（core 单测 56 + server 单测 5 + IT 7） |
| code-review | done | max 级：主会话实证 + 10 角度并行审查，合并去重后终审 22 条（3 high / 6 medium），已核对核心代码事实与行号锚点 |
| fix | done | 22 条全部处置（含新增 9 用例）；治理类：CLAUDE.md 规则优先级条款、dev-java ① 分支状态 guard |
| 回归测试 | done | dev-test.sh all BUILD SUCCESS（含新增修复用例全绿） |
| 验证（人工） | done | 4 项清单用户执行确认 pass |
| 文档同步 | done | 产品文档 v1.2（phase-3 交付节）、架构 v1.20（时效回拨有界）、无新 ADR/API 变更 |
| 完工 | done | worktree 分支 c2fd6ae 已提交；squash merge 回 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-scaffold | done |
| phase-2-heartbeat | done |
| phase-3-protect-snapshot | done |
| phase-4-server-assembly | planned |
| phase-5-admin-api | planned |
| phase-6-client-sdk | planned |
| phase-7-starter-ci | planned |

状态枚举：planned | in-progress | done。
