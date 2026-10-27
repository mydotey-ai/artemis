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
| 分支 / worktree | v1.0-phase3-docker-vm @ .claude/worktrees/v1.0-phase3-docker-vm |

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
> v1.0 phase-1（TLS 传输安全）已交付（958379b）。phase-2（RBAC：三预置角色矩阵 + /admin/v1 判权 + 多账户用户管理 + V7 role 列；ADR-021 委托定案后置 v1.x）已交付。phase-3（部署形态 I：Docker all-in-one 镜像 + VM tar.gz/systemd + 部署冒烟 + all-in-one 成员自报补全）已交付（189ccd1 squash）。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支 v1.0-phase3-docker-vm 自基线 ebbcd7c 起；骨架与索引已登记 |
| 上下文加载 | done | 已读：roadmap v1.0、架构 §2.3、tech-java.md（全文）、guidelines §10、plan v1.0 phase-3 条目、AllInOne* 源码三件、all-in-one 三配置 + logback、docs/api/all-in-one.md v2.1、根 Dockerfile/.dockerignore/CI、mydotey-ai:build 技能（VM 包形态参照）、client API 面（ArtemisClients/RegistryClient）、RelaxedEnvironmentConfigurationSource |
| 设计 | done | review 通过（2026-10-28） |
| 实现 | done | 零 Java 变更；新增 Dockerfile.all-in-one、deploy/vm/（app.sh+unit+properties 模板）、build-dist.sh、smoke-deploy.sh+SmokeSdk.java、docs/deploy/ 三文档；改 CI（docker job 补 all-in-one + paths）、.gitignore（build/）、all-in-one.md 末行指向 deploy 文档 |
| 测试 | done | dev-test all 全绿（spotless 基线遗留一并修复）；smoke-deploy docker+vm 全过（8 用例，systemd 流程留 ⑨ 人工）；实现期发现并修复 all-in-one 缺成员自报（SDK 不可接入）缺口 |
| code-review | done | 内联替代 skill（本会话 Skill 工具不可用，按兜底条款主会话 max 级内联）：findings 中 1（冒烟 dead code）低 3（卷残留/classpath 缓存）信息若干，逐文件审查 |
| fix | done | 修 3（dead code / 卷清理 / 缓存失效），1 不修记录 |
| 回归测试 | done | 冒烟 all ALL PASS；⑧ 抓出随机端口注入缺陷（IT 7 errors）修复后 dist 单模块 + 全量 all + vm 冒烟复验全过 |
| 验证 | done | 自动化 6 项全过 + 用户人工验证通过（2026-10-28） |
| 文档同步 | done | 产品文档 v1.39 增 phase-3 节；docs/deploy/ 三文档 + 根索引登记；all-in-one.md v2.3；phase 文档同步节填齐；无 ADR/架构变更 |
| 完工 | done | squash 189ccd1 → main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-tls | done |
| phase-2-rbac | done |
| phase-3-docker-vm | done |
| phase-4-helm | planned |
| phase-5-rolling-upgrade | planned |
| phase-6-capacity | planned |
| phase-7-docs | planned |
| phase-8-release | planned |
