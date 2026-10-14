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
| 分支 / worktree | v0.1-phase2-heartbeat @ .claude/worktrees/v0.1-phase2-heartbeat |

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支/worktree 新建（非幂等续用）；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.1、架构 §4.1、tech-java.md 全文、guidelines 全文、proto-contract.md、legacy §2.1–2.3/§5–§6、ADR-012、tech-stack §2、phase-1 设计/测试文档、parent 与 registry-core pom |
| 设计 | done | review 四次修订：对齐标准 DDD 四层（ADR-015 v1.4）；/code-review + /fix 修复 10 findings；决策表全项 ✅；人工 review 终审通过（371f12d） |
| 实现 | done | D13 同 key 事件定序（锁内全同步 + 回收后置 + 竞态重试）+ D14 时长判定单调钟化（MonotonicClock 端口，serverTimeMs 留壁挂）；文档即时同步（phase v2.2、ADR-015 v1.7）；修复 IT 装配 bean 名冲突/双 primary |
| 测试 | done | dev-test.sh all 全 reactor BUILD SUCCESS：registry-core 34 单测（聚合 19 含并发定序守护）+ 6 IT（failsafe tests=6 failures=0） |
| code-review | done | D13+D14 增量 max effort：15 findings——锁内派发正确性 3（listener 异常跳过回收尾/空摘路径快照陈旧/apply 无隔离）、bidi 死流双 onError、锁内延迟权衡未记、时钟两域测试零区分、javadoc 矛盾 2、changeTimeMs 语义无标记、jar-plugin 未锁版、三处内联协议重复、@GrpcAdvice 高度、await 重复、evict 双循环；2 项验证驳回（unary onError-after-cancel、serverTimeMs 移门面） |
| fix | done | 13 项修复：fire 逐事件隔离 + commit 统一提交尾（空摘重建快照）、bidi dead 标记（grpc 1.84.2 验证）、两域时钟区分测试、TestAwait/maven-jar-plugin 锁版、javadoc/契约 4 处；@GrpcAdvice 登记缓期（phase-5）；详见 phase 文档第 3 轮 fix 记录 |
| 回归测试 | done | dev-test.sh all 全 reactor BUILD SUCCESS（第 6 轮：37 单测 + 6 IT） |
| 验证（人工） | done | 清单 1–3 通过、4（可选）跳过（测试文档回填，2026-10-14） |
| 文档同步 | done | 产品文档 v1.1（phase-2 交付 + D6 默认值落地）；ADR-015 端口枚举（D12/D14）；phase 文档 v2.4「文档同步」节登记清单；无 API/架构/CLAUDE.md 变更（理由见该节） |
| 完工 | done | 分支提交 1eb006e；squash merge 回 main，worktree/分支随收尾删除 |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-scaffold | done |
| phase-2-heartbeat | done |
| phase-3-protect-snapshot | planned |
| phase-4-server-assembly | planned |
| phase-5-admin-api | planned |
| phase-6-client-sdk | planned |
| phase-7-starter-ci | planned |

状态枚举：planned | in-progress | done。
