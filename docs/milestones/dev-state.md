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
| 分支 / worktree | v0.3-phase1-projection-source @ .claude/worktrees/v0.3-phase1-projection-source |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）；v0.2 已于 2026-10-23 收尾（验收对照见 `v0.2/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main f8800ef 起；分支/目录 v0.3-phase1-projection-source；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.3、架构 §2.1/§2.2/§3.1/§4.1/§4.2、tech-java.md、guidelines §1/§2/§3/§7/§8、proto-contract.md、tech-stack §2.2、legacy §2.7/§5/§6、代码参照（registry-core domain/application/infrastructure、registry-server peer 装配与 ReplicationGrpcService、proto 三组契约） |
| 设计 | done | 两制品 v1.7/v1.5，用户 review 通过（5 轮修订：复用类型→common 上移→按消费方语义独立定义→契约注释消费方视角→删 ProtectionSnapshot→stale 完全移出）；架构 §4.1/§4.2、roadmap、v0.3 plan 已同步 |
| 实现 | done | proto 定稿；core：EVICTED 统一事件源 + ProjectionDispatcher（异步组批/订阅者队列/断流）+ ProjectionProtoFacade/Mapper + 端口；server：ProjectionGrpcService + peer 端口绑定 + 复合 ChangeListener 装配 |
| 测试 | done | dev-test.sh all 全绿（首跑） |
| code-review | done | 内置 code-review skill（max）返回 15 findings；逐条验证，13 条成立并修复、2 条为文档/测试问题已修正 |
| fix | done | 建立确认空批、取消竞态、subscribe/close 竞态、指标口径、分片/合并去重、EVICTED 防御分支、惰性分配、WatchEvent 更名、测试竞态与覆盖；详见 phase 文档「实现结果」 |
| 回归测试 | done | dev-test.sh all（轮次 1）BUILD SUCCESS |
| 验证 | done | 自动化通过（dev-test.sh all / 契约生成 / 内部端口 IT）；用户 2026-10-23 人工验证通过 |
| 文档同步 | done | 产品文档 v1.16；ADR-018 + decisions/README + 架构 §7（D18）；phase 文档文档同步清单 |
| 完工 | done | 分支 2696d28 已 squash 合入 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-projection-source | done |
| phase-2-discovery-projection | planned |
| phase-3-subscription-push | planned |
| phase-4-fallback-freshness | planned |
| phase-5-multi-cluster | planned |
| phase-6-ejection-filter | planned |
| phase-7-discovery-admin-api | planned |
| phase-8-consumer-sdk | planned |
| phase-9-starter-all-in-one | planned |

状态枚举：planned | in-progress | done。
