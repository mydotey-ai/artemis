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
| phase | phase-6-ejection-filter |
| 步骤 | 实现 |
| 分支 / worktree | v0.3-phase6-ejection-filter @ .claude/worktrees/v0.3-phase6-ejection-filter |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）；v0.2 已于 2026-10-23 收尾（验收对照见 `v0.2/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main e74cd66 起；分支/目录 v0.3-phase6-ejection-filter；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.3、架构 §2.1/§2.2/§4.1/§4.2/§5/§7、tech-java.md、guidelines §1/§2/§4/§7/§10、proto-contract 全文、legacy §2.6/§5 资产 #4/#7/#8、tech-stack §2.2、proto 三组、discovery-core（Registry/SubscriptionManager/Synchronizer/Query/Reconciliation）、discovery-server（UpstreamConfig/Synchronizers/GrpcProjectionSource）、management-core（EjectionStore/ManagementServiceImpl）、management-server（AdminController）、registry 分发模板（ProjectionDispatcher/ProjectionGrpcService） |
| 设计 | done | review 通过（2026-10-24），定案 D-E1–E11 |
| 实现 | done | proto 契约 + 两 core + 两 server + 23 用例（15 单测 + 8 IT）；实现期修复 deliverViewChanges 误判 chain（D-E6 违例）
| 测试 | done | dev-test.sh all 全绿（BUILD SUCCESS，含 DiscoveryEjectionFilterIT 3 用例 + EjectionRuleStreamIT 4 用例）；迭代 6 轮修编译/断言/IT classpath 隔离/GrpcEjectionRuleSource channel 泄漏
| code-review | done | 内置 skill max 完成：5 findings（1 high 竞态丢 DELETE 事件、2 medium：规则流无 isReady 断流/停机 30s 宽限、2 low：快照超限重连循环无区分日志/DNS 每轮查找） |
| fix | done | 5 findings 全修：① replace+扇出移入表锁（丢 DELETE 竞态）② 规则流 isReady 慢消费者断流 + dispatcher 断流补 close ③ management 停机宽限 1s ④ 快照上限 16 MiB + onError 日志 ⑤ subscriberId 缓存；补 EjectionRuleGrpcServiceTest |
| 回归测试 | done | dev-test.sh all 全绿（BUILD SUCCESS；一轮 registry IT 端口绑定 flaky 与本 phase 无关，重跑过） |
| 验证 | done | 自动化（all 两轮全绿）+ 用户人工验证通过（2026-10-24） |
| 文档同步 | done | 产品文档 v1.21 + 规则流 API 新建 + 4 份 API 文档同步 + 架构 §2.2 v0.3 落点（D-E11）；ADR/CLAUDE.md 无变更（理由见 phase 文档） |
| 完工 | done | 分支 8e44356 已 squash 合并 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-projection-source | done |
| phase-2-discovery-projection | done |
| phase-3-subscription-push | done |
| phase-4-fallback-freshness | done |
| phase-5-multi-cluster | done |
| phase-6-ejection-filter | done |
| phase-7-discovery-admin-api | planned |
| phase-8-consumer-sdk | planned |
| phase-9-starter-all-in-one | planned |

状态枚举：planned | in-progress | done。
