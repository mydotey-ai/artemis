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
| v1.0 | done | |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）


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
> phase-8（SDK 发布 Maven Central：client 线 release profile + Central 元数据 + release-java.md 流程 + release-dry-run.sh 21 断言演练；starter 更名 artemis-client-spring-boot-starter；两轮 code-review 27 findings 全处置）已交付（本 squash c808c5e）。
>
> **v1.0（GA 生产就绪）已收尾（2026-10-29）**：八个 phase 全部交付，验收对照五条全过（压测报告公开 / Docker+VM / K8s-Helm / 滚动升级无感 / 安全审计，见 [plan.md](v1.0/plan.md) 验收对照表）；文档终检完成（CLAUDE.md 当前状态更新）。下一版本按 roadmap 为 v1.x（生态与多语言，按需排期）。

## 当前 phase 步骤进度（版本 done 后清空本节）

（无进行中 phase。）

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

（无。）
| phase-2-rbac | done |
| phase-3-docker-vm | done |
| phase-4-helm | done |
| phase-5-rolling-upgrade | done |
| phase-6-capacity | done |
| phase-7-docs | done |
| phase-8-release | done |
