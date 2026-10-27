# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | done | |
| v0.4 | done | |
| v0.5 | done | |
| v1.0 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

> v0.1–v0.4 已收尾（验收对照见各 `plan.md`）。v0.5（治理面 II）于 2026-10-26 启动，基线 c87dc2a 已提交。phase-1（route-rule 资源模型与持久化）已交付（e961ebb）。phase-2（两段式权重发布与回滚）已交付。
>
> phase-3（分组路由执行：Group filter 权重二值承接 + 视图直投 + 三出口一致 + 结构债 #9/#10 收口）已交付（b72f67e）。
>
> phase-4（canary：route-rule + release 预置派生一键用法）已交付（5f340c1）。
>
> v0.5（治理面 II）已收尾（2026-10-27）：五个 phase 全部交付，验收对照见 [plan.md](v0.5/plan.md)。
>
> v1.0（GA 生产就绪）于 2026-10-27 启动：plan 已写（8 phase：TLS / RBAC / Docker+VM / Helm / 滚动升级 / 容量压测 / 文档站 / SDK 发布），基线提交后转 in-progress。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支 v1.0-phase1-tls 自基线 c6c2212 起；骨架与索引已登记 |
| 上下文加载 | done | 已读：roadmap §3 v1.0、架构 §2.2/§2.3/§6/§7（D9/D21）、tech-java.md 全文、guidelines §7/§10、plan v1.0；代码：PeerServerConfiguration/PeerChannels/GrpcProjectionSource/ManagementRuleChannels/GrpcLogicalInstanceSource/RegistryQueryGrpcClient/GrpcRegistryClient/GrpcDiscoveryClient/HttpNodeListFetcher/ArtemisClients、application.properties×3、spring-boot-grpc-server 4.1.1 Ssl 属性。proto/legacy 不涉（传输层新增能力） |
| 设计 | done | review 通过（2026-10-27），两份制品定稿 |
| 实现 | done | common TLS 设施 + peer/discovery/management/SDK 全通道接线 + 双 IT |
| 测试 | done | dev-test.sh all 全绿（BUILD SUCCESS，12 模块 0 失败） |
| code-review | done | max 级 7 findings（1 崩溃级 + 1 行为不一致 + 1 NPE + 3 简洁/卫生 + 1 文档） |
| fix | done | 6/7 修复（1 崩溃/1 fail-fast/1 NPE/2 简洁/1 卫生），7 归 ⑩ 文档 |
| 回归测试 | done | 修复后 all 全量重跑 BUILD SUCCESS（13 模块 0 失败） |
| 验证 | done | 自动化 4 项全过；用户人工验证通过（2026-10-27） |
| 文档同步 | done | 产品/API×2/架构/技术选型 + 设计文档 ⑩ 节登记 |
| 完工 | done | squash 入 main（2026-10-27） |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-tls | done |
| phase-2-rbac | planned |
| phase-3-docker-vm | planned |
| phase-4-helm | planned |
| phase-5-rolling-upgrade | planned |
| phase-6-capacity | planned |
| phase-7-docs | planned |
| phase-8-release | planned |
