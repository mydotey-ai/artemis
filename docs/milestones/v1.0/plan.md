# v1.0 版本规划

版本: 1.1    更新时间: 2026-10-29

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v1.0（GA：生产就绪），此处不复述条目原文——一句话概括：v0.1–v0.5 已交付全部核心能力（注册发现闭环 + 集群 + 治理面完整），v1.0 补齐**生产就绪面**：安全完整（TLS 内外通道可启、RBAC 角色 × 资源 × 动作）、容量验证（10 万实例压测 + 容量基线文档）、三种部署形态（Docker 单机容器 / VM 标准安装包 / K8s Helm chart）、文档站（快速开始/配置参考/API 参考/运维手册）、SDK 与 starter 发布 Maven Central、滚动升级协议验证（proto 前后兼容 + 集群滚动重启客户端容灾无感）。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：压测报告公开、三种形态各自一条命令/一套流程拉起、集群滚动升级无感、安全审计通过。

架构基线：部署形态见架构 [§2.3](../../arch/artemis-next-architecture.md)（三形态定义）；安全见 §6（TLS/认证/RBAC 时间线、告警走 Alertmanager 生态随 v1.0 提供示例配置）；账户接管形态见 [ADR-021](../../decisions/adr-021-management-account-takeover.md)（各服务管理面委托机制后置 v1.0 RBAC）。既有底座：认证 v0.1 默认开（各服务本地单账户）+ v0.4 management 单管理员 DB 账户；Dockerfile（registry 单服务镜像）与 CI 已有；artemis-dist（all-in-one 可执行 fat jar，三服务完整形态）已有。设计不在本版展开，由各 phase ③ 设计定稿。

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-tls | TLS 传输安全：各服务外部 gRPC/HTTP 与内部通道（peer/规则流）TLS 可启（证书配置加载），SDK TLS 支持；内网明文可配共存 | 2 | 单测：证书/配置解析与通道装配；IT：TLS 端到端（SDK over TLS 注册/心跳/订阅 + 内部通道 TLS） |
| phase-2-rbac | RBAC：角色 × 资源 × 动作模型（DB 持久化）、Admin API/console 权限 enforcement、账户管理、ADR-021 委托机制定案 | 2 | 单测：权限矩阵判定；IT：角色 × 资源 × 动作允许/拒绝 + 未授权访问拒绝 + 审计记录 |
| phase-3-docker-vm | 部署形态 I：Docker 单机容器（all-in-one 一条命令起全功能，eval 最短路径）+ VM 标准安装包（tar.gz + systemd unit + bin 脚本） | 2 | 冒烟：`docker run` 单容器全功能（三服务端口就绪 + SDK 走通）；tar.gz 解包 systemd 流程冒烟 |
| phase-4-helm | 部署形态 II：K8s/Helm chart，三服务独立编排扩缩（registry/discovery/management + DB 依赖与探针） | 2 | 冒烟：helm lint + kind 集群 `helm install` 三服务起 + 就绪探针通过 + SDK 走通 |
| phase-5-rolling-upgrade | 滚动升级协议验证：proto 前后兼容验证 + 3 节点集群滚动重启客户端容灾无感 | 2 | IT：混合版本节点互操作（复制/bootstrap）+ 滚动重启期间 SDK 注册心跳不断流不换数据 |
| phase-6-capacity | 容量验证：压测工具与场景矩阵（注册/心跳/查询/订阅推送 × 10 万实例），发布容量基线文档（内存/带宽/QPS/推送延迟） | 3 | 压测报告（基线数据 + 瓶颈结论），文档入 docs/deploy/ |
| phase-7-docs | 文档站：快速开始/配置参考/API 参考/运维手册 + 告警示例配置（Prometheus + Alertmanager）+ README 门户 | 2 | 文档四类覆盖齐 + 告警示例规则可加载 + 快速开始按文档操作可复现 |
| phase-8-release | SDK 发布 Maven Central：client 线（SDK + starter）发布配置（源码/Javadoc/签名）、发布流程文档 | 2 | 发布配置就绪 + 本地 dry-run（bundle/sign 校验）通过；实际发布由用户执行（凭账号） |

phase-1 说明：TLS 为「内外通道均可启、内网可配明文」（架构 §6），非强制默认——与默认认证（D9）哲学一致：显式配置启用，eval 场景保持零依赖。③ 定稿证书供给形态（PEM 路径配置等）与各通道开关矩阵。

phase-2 说明：RBAC 模型（角色 × 资源 × 动作）与资源词汇表由 ③ 对照架构 §5.2 资源导向 API 定稿；enforcement 落点在 management 管理访问面（ADR-021 唯一认证源）；「各服务管理面委托」是否随本版交付或继续后置由 ③ 定案（ADR-021 预留）。

phase-3 说明：Docker 形态基于既有 artemis-dist fat jar 组 all-in-one 镜像（现有 Dockerfile 为 registry 单服务，保留并补全）；VM 安装包复用同一 fat jar + 外置配置/数据目录 + systemd unit。③ 定稿镜像/包结构与配置外置约定。

phase-4 说明：三服务独立编排（registry 有状态副本、discovery/management 无状态）；探针接 `/readyz`；DB 依赖为 management 可选组件。③ 定稿 chart 结构与 values 口径（含 TLS/RBAC 配置透出）。

phase-5 说明：「proto 前后兼容」的验证口径（历史版本 proto 与当前实现的互操作范围）由 ③ 对照 [proto 契约规范](../../dev/proto-contract.md) 定案；滚动重启 IT 复用 v0.2 优雅停机 + SDK 容灾既有设施，不新立机制。

phase-6 说明：压测工具形态（独立压测模块 vs 脚本化 SDK 集群）与场景矩阵由 ③ 定案；容量基线为公开文档（成功标准 1/3 的「容量基线公开」）；10 万实例机制基础（批量/聚合心跳）v0.1 起内建，本 phase 是验证与出数，非机制补建。

phase-7 说明：文档站为仓库内 Markdown 文档集（快速开始/配置参考/运维手册入 docs/deploy/ 与 docs/product/，API 参考聚合 docs/api/），不引入独立前端站点（与 console 同哲学：不新增部署单元）；③ 定稿目录结构。告警示例配置归档 §6「随 v1.0 提供」承诺。

phase-8 说明：client 线独立 parent/版本线（tech-java.md）本就为独立发布设计；发布走 OSSRH/Central Portal 由用户账号操作，工作流交付物为发布配置 + 流程文档 + dry-run 验证。服务端线是否同发 Central 由 ③ 定案（默认本版只发 client 线——roadmap 仅列 SDK 与 starter）。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|
| 压测报告公开 | | |
| 三种形态各自一条命令/一套流程拉起（Docker / VM） | | |
| 三种形态各自一条命令/一套流程拉起（K8s/Helm） | | |
| 集群滚动升级无感 | | |
| 安全审计通过 | | |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-29 | phase-8 验收方式口径修正（bundle/sign） |
| 1.0 | 2026-10-27 | 版本启动，初始 phase 拆分（8 phase，滚动细化） |
