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
>
> phase-6（容量验证：artemis-bench + bench.sh + /bench-java-test + 容量基线 small 实测/外推；表外发现 3 节点订阅推送实时性退化）已交付（本 squash）。
>
> phase-5（滚动升级协议验证：proto 兼容契约门槛进 dev-test.sh all/CI + 未知字段前向兼容 IT + 3 节点滚动重启 IT；锚点 = 版本收尾 commit、ci fetch-depth 0）已交付（本 squash）。
>
> phase-7（文档站：快速开始/配置参考/运维手册 + README 门户 + 告警示例三件套 + check-docs.sh 守门与 docs.yml CI；修复 phase-3 smoke env 拼写空转假绿与 4 处存量断链）已交付（本 squash）。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支 v1.0-phase7-docs 自基线 ca5de53 起；骨架与索引已登记 | |
| 上下文加载 | done | 已读：roadmap §3 v1.0、架构 §2.2/§2.3/§6/§7、tech-java.md（全文）、guidelines §7/§10、plan v1.0、docs 目录现状盘点（deploy/api/product/README）、ci.yml、smoke-deploy.sh、代码指标名与 SCF 键枚举；proto/legacy 不涉及 |
| 设计 | done | review 通过（2026-10-28 用户确认） | |
| 实现 | done | 交付：quick-start/config-reference/operations 三文档、deploy/alerts 三件套、README 门户、check-docs.sh、docs.yml；附带修 4 处存量断链、smoke env 用例拼写与空转断言、docker.md 同款拼写 |
| 测试 | done | ⑤ 首跑四检查全绿：dev-test all（25:18 + proto 5/5）、check-docs all（204 文件）、smoke docker（ALL PASS，env 覆盖实证） |
| code-review | done | 内置 code-review skill（max）：15 findings（14 CONFIRMED / 1 PLAUSIBLE），chat 已呈现 |
| fix | done | 14 条修复 + 1 条 sudo 消除；记录入 phase 文档「实现结果」 |
| 回归测试 | done | ⑧ 两检查全绿：smoke docker（env HTTP 断言实证 401）+ dev-test all（20:48 + proto 5/5） |
| 验证 | done | 自动化四项 + 人工验证均通过（2026-10-29 用户确认），记入测试文档验证记录 |
| 文档同步 | done | 产品文档 artemis.md 1.43（交付节 + 快速开始指路）必含项已落；三索引 + project.md CI 行；清单入 phase 文档；CLAUDE.md/arch 无需更新；check-docs 终检绿 |
| 完工 | done | squash 收尾完成 |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-tls | done |
| phase-2-rbac | done |
| phase-3-docker-vm | done |
| phase-4-helm | done |
| phase-5-rolling-upgrade | done |
| phase-6-capacity | done |
| phase-7-docs | done |
| phase-8-release | planned |
