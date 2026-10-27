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
>
> v1.0 phase-1（TLS 传输安全）已交付（958379b）。phase-2（RBAC：三预置角色矩阵 + /admin/v1 判权 + 多账户用户管理 + V7 role 列；ADR-021 委托定案后置 v1.x）已交付。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支 v1.0-phase2-rbac 自基线 958379b 起；骨架与索引已登记 |
| 上下文加载 | done | 已读：roadmap §3 v1.0、架构 §5.2/§5.3/§6/§8、ADR-021、tech-java.md、guidelines §7、proto-contract.md（结论：本 phase 无 proto 变更）、legacy 基线 §6 #18/#19、management-core/server 账户/审计/装配源码、Flyway V2、admin-api.md |
| 设计 | done | review 通过；ADR-021 v1.1 与架构 v1.47 已同步（委托继续后置 v1.x + RBAC 模型落稿） |
| 实现 | done | core 域模型/多账户认证/用户管理服务 + server 判权 filter/用户端点/V7 迁移；单测 133+43 绿 |
| 测试 | done | dev-test.sh all 全绿（第 4 轮；registry-core 既有 flaky 与本 phase 无关，单模块复跑绿；双方言 IT 无 Docker skipped） |
| code-review | done | 内联替代 skill（已知环境故障：审查子 agent 无内置工具会挂起，工作流既定兜底）；max 口径逐文件审查，findings 2 条 low |
| fix | done | 2 条 low 接受并 ponytail 注释（最后 ADMIN 竞态 / 缓存回填竞态）；实现结果节已记 |
| 回归测试 | done | ⑧ all 全绿 + Docker 双方言 IT 补跑全绿（RbacIT 4/4×2；RouteRulePersistenceIT 既有顺序依赖已修） |
| 验证 | done | 自动化全绿 + 用户人工验证通过（2026-10-27） |
| 文档同步 | done | 产品 v1.38、admin-api v1.16、ADR-021 v1.1 与架构 v1.47（③ 已同步）、phase 双文档回填 |
| 完工 | done | worktree 提交 7d4e80f，squash 回 main 并 push |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-tls | done |
| phase-2-rbac | done |
| phase-3-docker-vm | planned |
| phase-4-helm | planned |
| phase-5-rolling-upgrade | planned |
| phase-6-capacity | planned |
| phase-7-docs | planned |
| phase-8-release | planned |
