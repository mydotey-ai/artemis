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
>
> phase-8（SDK 发布 Maven Central：client 线 release profile + Central 元数据 + release-java.md 流程 + release-dry-run.sh 21 断言演练；starter 更名 artemis-client-spring-boot-starter；两轮 code-review 27 findings 全处置）已交付（本 squash）。v1.0 八个 phase 全部交付，下次 /dev-java 进入版本收尾（验收对照）。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | 分支 v1.0-phase8-release 自基线 0e2790a 起；骨架与索引已登记 |
| 上下文加载 | done | 已读：roadmap §3 v1.0、plan v1.0 phase-8 说明、tech-java.md、tech-stack #28/#36/#31、guidelines §7/§9/§11、架构（无 SDK 发布章节，工程面）、client 线三 pom、Central Portal 插件文档与 repo1 版本核实、本机 GPG/Maven 环境核查 |
| 设计 | done | review 通过（2026-10-29），定案 D1–D6 |
| 实现 | done | D7：starter 更名 artemis-client-spring-boot-starter（git mv 目录 + artifactId + modules + 现行文档 9 文件替换，历史里程碑文档不改；包名/装配注册不涉名） |
| 测试 | done | 改名后 dry-run 21 断言全绿（新坐标）+ dev-test.sh all 全绿（verify + proto 兼容 5/5） |
| code-review | done | 第二轮 max 15 findings：12 修复 / 2 驳回 / 1 转 ⑩（明细见 phase doc 实现结果第二轮记录） |
| fix | done | 含恢复核验顺序缺陷当场修正（dryrun11） |
| 回归测试 | done | dry-run 21 断言 + 恢复核验 + dev-test.sh all 全绿（verify + proto 兼容 5/5 + check-docs ok） |
| 验证 | done | 人工验证通过（2026-10-29，用户确认） |
| 文档同步 | done | 产品文档 1.44 + release-java.md 新建 + tech-stack 1.31 + guidelines 1.27 + tech-java.md 规则 + 改名触及四文档版本头补登 + plan 1.1；无 ADR、CLAUDE.md 无过时 |
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
| phase-8-release | done |
