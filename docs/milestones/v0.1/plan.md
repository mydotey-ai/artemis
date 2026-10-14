# v0.1 版本规划

版本: 1.10    更新时间: 2026-10-13

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v0.1（registry 单服务），此处不复述条目原文——一句话概括：交付 registry 单服务完整内核（四操作注册 + 租约 + 自我保护基础版 + 快照回放）与接入/治理期 1 前半/安全/可观测/Java 8 SDK 注册侧/工程基线（monorepo + proto 契约 + CI）。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：单节点起停、SDK 注册/心跳、实例经状态/管理 API 可观测、摘除/恢复经管理查询 API 的**有效视图**（已应用摘除过滤）可验证、快照重启回放、认证生效、指标可抓取。（服务发现查询归 discovery，v0.3 起验收）

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-scaffold | Maven 脚手架 + proto 契约骨架 | 2 | `mvn -f java/pom.xml verify` 全绿；enforcer 依赖断言（core 禁 Spring、client 禁服务端模块）生效 |
| phase-2-heartbeat | 注册模型四操作 + 租约过期清理 + 模块内分层（domain/application/infrastructure，领域为核心）+ gRPC 装配 | 3 | 内核单测：四操作语义（覆盖/意图幂等/upsert 事件）、心跳续约 + 缺席补注册（无集合 diff，缺席等 TTL）、租约续约/过期清理语义、层间 mapper 往返；**集成测试**：进程内起 registry 服务（官方 gRPC starter），端到端四操作/心跳/租约过期（failsafe `*IT`） |
| phase-3-protect-snapshot | 自我保护基础版 + 服务端快照落盘回放 + readiness 门控 | 2 | 单测：续约窗口阈值与保护态、快照落盘/回放一致、门控时序 |
| phase-4-server-assembly | registry-server 装配：gRPC+HTTP 双协议、内外端口分离、默认认证、metrics/log/trace、成员抽象接口 | 2 | 进程内起停测试：认证拒绝/放行、指标端点可抓取、trace 产出 |
| phase-5-admin-api | 实例管理 Admin API（列表/详情/摘除/恢复，内存态）+ 状态 API + 有效视图 | 2 | API 集成测试：摘除/恢复经有效视图可验证 |
| phase-6-client-sdk | Java 8 SDK 注册侧：心跳客户端（含本地实例集管理）、生命周期 API、磁盘快照、三级地址容灾骨架 | 2 | SDK 单测 + 对接 server 的进程内集成测试 |
| phase-7-starter-ci | Spring Boot starter（注册侧自动装配）+ CI + Dockerfile | 2 | starter 自动装配测试；CI 绿；镜像构建成功 |

phase-1 说明：建齐六组 proto 目录骨架（`proto/` 组织见 proto-contract.md），v0.1 仅定稿 `common.v1` + `registry.v1` 两组消息，其余组随各自版本填充；Maven reactor 按 tech-stack-java.md §2.1 全模块一次到位（边界后拆代价大）。

phase-3/4 分工说明：自我保护的指标暴露随 phase-4 metrics 内建交付（phase-3 交付保护态本体与窗口阈值语义）。

phase-2/4 分工说明（2026-10-12 review 定案）：phase-2 内扩含 **Spring Boot 官方 gRPC 装配**（`org.springframework.boot:spring-boot-starter-grpc-server` + `@GrpcService`，仅 registry 四 rpc）与端到端集成测试（`*IT`，failsafe 本 phase 起正式绑定）；phase-4 承接 HTTP 辅助通道、内外端口分离、默认认证、metrics/log/trace、成员抽象接口与完整装配。分层决策见 [ADR-015](../../decisions/adr-015-layered-model-inside-core.md)。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
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
