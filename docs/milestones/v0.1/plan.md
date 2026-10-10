# v0.1 版本规划

版本: 1.1    更新时间: 2026-10-10

## 目标

引 roadmap v0.1（registry 单服务）范围：

- **内核**：心跳即注册（gRPC client-stream，进程级聚合全量幂等上报）、租约/过期清理、自我保护基础版（窗口阈值 + 指标暴露）、服务端快照落盘 + 重启回放、readiness 门控、成员抽象接口（静态实现，单成员起步）。
- **接入**：gRPC + HTTP 双协议；内部/外部端口分离。
- **治理期 1 前半**：实例管理 Admin API（列表/详情/摘除/恢复，内存态）+ 状态 API 排障面（leases/config 类）。
- **安全**：默认开启的单账户认证。
- **可观测**：metrics（Micrometer/Prometheus）+ 结构化 log + OpenTelemetry trace，第一天内建。
- **客户端**：Java 8 SDK 注册侧——心跳客户端、实例管理、生命周期 API（close/优雅下线）、磁盘快照、三级地址容灾骨架；随附 Spring Boot starter（注册侧自动装配）。
- **工程**：monorepo 目录布局与 Maven 多模块、proto 契约（六组 proto：common 公共数据模型 + 五组 service proto，数据模型含 namespace 字段——架构 D10）、CI、Dockerfile。

**验收要点**：单节点起停、SDK 注册/心跳、实例经状态/管理 API 可观测、摘除/恢复经管理查询 API 的**有效视图**（已应用摘除过滤）可验证、快照重启回放、认证生效、指标可抓取。（服务发现查询归 discovery，v0.3 起验收）

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-scaffold | Maven 脚手架 + proto 契约骨架 | 2 | `mvn -f java/pom.xml verify` 全绿；enforcer 依赖断言（core 禁 Spring、client 禁服务端模块）生效 |
| phase-2-heartbeat | 心跳即注册 + 租约过期清理（registry-core） | 2 | 内核单测：全量幂等上报 diff、租约续约/过期清理语义 |
| phase-3-protect-snapshot | 自我保护基础版 + 服务端快照落盘回放 + readiness 门控 | 2 | 单测：续约窗口阈值与保护态、快照落盘/回放一致、门控时序 |
| phase-4-server-assembly | registry-server 装配：gRPC+HTTP 双协议、内外端口分离、默认认证、metrics/log/trace、成员抽象接口 | 2 | 进程内起停测试：认证拒绝/放行、指标端点可抓取、trace 产出 |
| phase-5-admin-api | 实例管理 Admin API（列表/详情/摘除/恢复，内存态）+ 状态 API + 有效视图 | 2 | API 集成测试：摘除/恢复经有效视图可验证 |
| phase-6-client-sdk | Java 8 SDK 注册侧：心跳客户端（含本地实例集管理）、生命周期 API、磁盘快照、三级地址容灾骨架 | 2 | SDK 单测 + 对接 server 的进程内集成测试 |
| phase-7-starter-ci | Spring Boot starter（注册侧自动装配）+ CI + Dockerfile | 2 | starter 自动装配测试；CI 绿；镜像构建成功 |

phase-1 说明：建齐六组 proto 目录骨架（`proto/` 组织见 proto-contract.md），v0.1 仅定稿 `common.v1` + `registry.v1` 两组消息，其余组随各自版本填充；Maven reactor 按 tech-stack-java.md §2.1 全模块一次到位（边界后拆代价大）。

phase-3/4 分工说明：自我保护的指标暴露随 phase-4 metrics 内建交付（phase-3 交付保护态本体与窗口阈值语义）。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-10 | review 修复：phase-6 显式含 SDK 实例管理；补 phase-3/4 保护态指标暴露分工说明 |
| 1.0 | 2026-10-10 | 版本启动，初始 phase 拆分（7 phase） |
