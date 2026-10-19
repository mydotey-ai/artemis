# v0.1 版本规划

版本: 1.13    更新时间: 2026-10-19

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v0.1（registry 单服务），此处不复述条目原文——一句话概括：交付 registry 数据面完整内核（四操作注册 + 租约 + 自我保护基础版 + 快照回放）与 management-service 内存态管理面（实例管理/状态 API）、接入/安全/可观测/Java 8 SDK 注册侧/工程基线（monorepo + proto 契约 + CI）。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：单节点起停、SDK 注册/心跳、实例经状态/管理 API 可观测、摘除/恢复经管理查询 API 的**有效视图**（已应用摘除过滤）可验证、快照重启回放、认证生效、指标可抓取。（服务发现查询归 discovery，v0.3 起验收）

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-scaffold | Maven 脚手架 + proto 契约骨架 | 2 | `mvn -f java/pom.xml verify` 全绿；enforcer 依赖断言（core 禁 Spring、client 禁服务端模块）生效 |
| phase-2-heartbeat | 注册模型四操作 + 租约过期清理 + 模块内分层（domain/application/infrastructure，领域为核心）+ gRPC 装配 | 3 | 内核单测：四操作语义（覆盖/意图幂等/upsert 事件）、心跳续约 + 缺席补注册（无集合 diff，缺席等 TTL）、租约续约/过期清理语义、层间 mapper 往返；**集成测试**：进程内起 registry 服务（官方 gRPC starter），端到端四操作/心跳/租约过期（failsafe `*IT`） |
| phase-3-protect-snapshot | 自我保护基础版 + 服务端快照落盘回放 + readiness 门控 | 2 | 单测：续约窗口阈值与保护态、快照落盘/回放一致、门控时序 |
| phase-4-server-assembly | 配置规范化（SCF：source/manager/properties）+ registry-server 装配：gRPC+HTTP 双协议、内外端口分离、默认认证、metrics/log/trace、成员抽象接口 | 3 | 配置源优先级/默认值/校验单测；进程内起停测试：认证拒绝/放行、指标端点可抓取、trace 产出 |
| phase-5-registry-query-api | registry 只读事实查询 RPC（服务列表 / 实例快照 / 租约明细与保护统计 / 配置状态，敏感值脱敏） | 2 | 单测 + 服务级集成测试：认证后经 gRPC 查询真实注册表；敏感配置脱敏 |
| phase-6-management-service | management-service 骨架（独立服务 + 认证 + 配置）+ 实例管理 Admin API（列表/详情/摘除/恢复）+ 状态 API + 有效视图（内存态摘除记录，经 registry 只读查询组合） | 3 | 管理面集成测试：经 registry 查询组合有效视图；摘除/恢复可验证；状态 API 可观测 |
| phase-7-client-sdk | Java 8 SDK 注册侧：心跳客户端（含本地实例集管理）、生命周期 API、三级地址容灾骨架 | 2 | SDK 单测 + 对接 server 的进程内集成测试 |
| phase-8-starter-ci | Spring Boot starter（注册侧自动装配）+ CI + Dockerfile | 2 | starter 自动装配测试；CI 绿；镜像构建成功 |

phase-1 说明：建齐六组 proto 目录骨架（`proto/` 组织见 proto-contract.md），v0.1 仅定稿 `common.v1` + `registry.v1` 两组消息，其余组随各自版本填充；Maven reactor 按 tech-stack-java.md §2.1 全模块一次到位（边界后拆代价大）。

phase-3/4 分工说明：自我保护的指标暴露随 phase-4 metrics 内建交付（phase-3 交付保护态本体与窗口阈值语义）。

phase-2/4 分工说明（2026-10-12 review 定案）：phase-2 内扩含 **Spring Boot 官方 gRPC 装配**（`org.springframework.boot:spring-boot-starter-grpc-server` + `@GrpcService`，仅 registry 四 rpc）与端到端集成测试（`*IT`，failsafe 本 phase 起正式绑定）；phase-4 承接 HTTP 辅助通道、内外端口分离、默认认证、metrics/log/trace、成员抽象接口与完整装配。分层决策见 [ADR-015](../../decisions/adr-015-layered-model-inside-core.md)。

phase-5/6 分工说明（2026-10-18 定案，ADR-016）：管理面从 v0.1 独立交付。phase-5 只做 registry 侧只读事实查询 RPC（注册表事实/租约/配置状态，registry 不感知摘除）；phase-6 交付 management-service（摘除记录归 management，有效视图由管理面组合 registry 事实 + 摘除记录），原 phase-5-admin-api 不再在 registry 内实现。管理查询 v0.1 复用 registry 外部 gRPC 端口，内部端口分离随 v0.2。

phase-4 配置规范化说明（2026-10-18 定案）：phase-4 先落地 SCF 配置体系，再叠加服务装配。各服务独立定义 manager/properties，放各自 `infrastructure` 包；领域层与应用服务层使用 `Property` 抽象，基础设施层定义全部配置项（键、默认值、动静类型、校验），server 包提供具体装配（source 列表 → manager → properties）。manager 原生支持多 source，不使用 `PipelineConfigurationSource`。phase-3 遗留的配置常量统一迁入服务配置门面。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.13 | 2026-10-19 | phase-7 主题修订（设计 review 定案）：注册侧 SDK 去磁盘快照（归发现侧 v0.3；注册事实源是业务代码，重启重注册 + 心跳幂等收敛自愈） |
| 1.12 | 2026-10-18 | ADR-016：管理面从 v0.1 独立交付——phase-5 改为 registry 只读查询 RPC，新增 phase-6-management-service，原 SDK/starter 顺延 |
| 1.11 | 2026-10-18 | phase-4 范围调整：先做 SCF 配置规范化（source/manager/properties，各服务独立门面），工作量 2→3 天、主题与验收方式同步 |
| 1.10 | 2026-10-13 | ④ 实现期坐标定案：phase-2 分工说明 gRPC starter 坐标更正为 `org.springframework.boot:spring-boot-starter-grpc-server` |
| 1.9 | 2026-10-13 | code-review 修复：phase-2/4 分工说明 Spring gRPC starter 坐标更正为 `org.springframework.grpc:spring-grpc-server-spring-boot-starter` |
| 1.8 | 2026-10-12 | phase-2 三次 review 定案：分层改为「领域为核心」（domain/application/infrastructure，依赖倒置）；phase-2 主题措辞同步 |
| 1.7 | 2026-10-12 | phase-2 二次 review 定案：gRPC 装配改用 Spring Boot 官方组件（不自写 Server）；措辞「最小 gRPC 装配」统一为「gRPC 装配」 |
| 1.6 | 2026-10-12 | phase-2 设计 review 定案：phase-2 扩含模块内分层（ADR-015）与最小 gRPC 装配 + 端到端集成测试，工作量 2→3 天、验收方式补集成测试；补 phase-2/4 分工说明 |
| 1.5 | 2026-10-12 | code-review 修复：目标节改指针（verbatim 复制 roadmap 双编辑漂移已兑现两次，roadmap 为单一来源） |
| 1.4 | 2026-10-12 | D12 心跳语义精化同步（目标节与 phase-2 验收方式） |
| 1.3 | 2026-10-12 | 目标节内核 bullet 同步 D12 新文案（消除与 phase 表矛盾） |
| 1.2 | 2026-10-11 | D12 同步：phase-2 主题与验收方式更新（四操作语义 + 心跳对账） |
| 1.1 | 2026-10-10 | review 修复：phase-6 显式含 SDK 实例管理；补 phase-3/4 保护态指标暴露分工说明 |
| 1.0 | 2026-10-10 | 版本启动，初始 phase 拆分（7 phase） |
