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
> v1.0 phase-1（TLS 传输安全）已交付（958379b）。phase-2（RBAC：三预置角色矩阵 + /admin/v1 判权 + 多账户用户管理 + V7 role 列；ADR-021 委托定案后置 v1.x）已交付。phase-3（部署形态 I：Docker all-in-one 镜像 + VM tar.gz/systemd + 部署冒烟 + all-in-one 成员自报补全）已交付（189ccd1 squash）。
>
> phase-4（部署形态 II：K8s/Helm chart 三服务独立编排 + Dockerfile.service 三单服务镜像 + 探针/DB 可选 + kind 冒烟与 CI 渲染断言）已交付（本 squash）。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支 v1.0-phase4-helm 自基线 ebbcd7c 起；骨架与索引已登记 |
| 上下文加载 | done | 已读：roadmap §3 v1.0、架构 §2.3、tech-java.md（全文）、guidelines §7、v1.0 plan、phase-3 设计（形态 I 交付物）、all-in-one.md、三单服务 application.properties、smoke-deploy.sh、Dockerfile×2、ci.yml、产品文档 §成员三段式 |
| 设计 | done | 制品落盘，用户 review 通过（2026-10-28） |
| 实现 | done | Dockerfile.service + deploy/helm/artemis/（9 模板）+ smoke-deploy.sh helm 模式 + CI（镜像×2/helm job）；④ 回写探针 /readyz 定案 |
| 测试 | done | dev-test.sh all BUILD SUCCESS（19min）；smoke-deploy.sh helm ALL PASS（kind 三服务就绪 + 探针 + console 401 + SDK 注册/订阅 instances=1 + uninstall）；⑤ 期修 smoke 脚本 sdk_classpath 捕获污染（mvn IT/JaCoCo 输出混入 $() 污染 cp：jar 缺失缓存误判 + -DskipTests 不跳 failsafe IT + 输出隔离 stderr 化） |
| code-review | done | 内联替代 skill（子 agent 输出完整无挂起，沿用内联执行 max 口径）：10 findings（management 端点 http:// 双前缀 / auth ENABLED 键缺失 / TLS 探针 scheme / 脚本×3 / CI 断言 / TLS 配对守卫 / 文档漂移×3 / 名长预算） |
| fix | done | 10/10 修；处置记录见 phase 文档「实现结果」 |
| 回归测试 | done | 渲染断言全绿 + kind 冒烟 ALL PASS（instances=1）；Java 零变更 dev-test all 不重跑（⑤ 结果未失效） |
| 验证 | done | 自动化（lint/渲染断言/冒烟×2/dev-test all）全过并记录；用户人工验证通过（2026-10-28） |
| 文档同步 | done | 产品文档 v1.40 交付节 + docs/deploy/k8s.md + 三索引；零 ADR（§2.3 即落地对象）；CLAUDE.md 未过时 |
| 完工 | done | 分支提交 a9f1456 → squash 合入 main（本提交） |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-tls | done |
| phase-2-rbac | done |
| phase-3-docker-vm | done |
| phase-4-helm | done |
| phase-5-rolling-upgrade | planned |
| phase-6-capacity | planned |
| phase-7-docs | planned |
| phase-8-release | planned |
