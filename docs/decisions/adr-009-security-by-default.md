# ADR-009：安全内建（v0.1 默认认证）

版本: 1.2    更新时间: 2026-10-25

## 状态

Accepted（2026-10-10，架构设计定稿）

## 背景

开源注册中心的安全后补代价高：Nacos 到 3.0 才默认鉴权的教训（默认裸奔的部署大量存在于公网）。目标是「外部用户可 eval、可上生产」的开源产品。

## 决策

认证 v0.1 起默认开启（各服务本地单账户）；显式配置方可关闭（eval 场景）。TLS v1.0（内外通道均可启）、RBAC v1.0、统一账户 v0.4（management 接管）。

## 理由

开源产品可上生产的前提；后补代价高（协议面与 API 面都要返工）。

## 否决方案

v1.0 一次性补安全——存量 eval 部署升级时面临行为突变与裸奔窗口。

## 后果

- v0.1 起 eval 需处理认证（文档给出显式关闭配置）。
- 全部治理操作进审计表（期 3）。

## v0.1 实现语义（2026-10-18 补充）

- **默认账户**：`artemis`/`artemis`；使用默认密码启动时记 WARN，生产部署必须修改（`artemis.registry.auth.username/password`）。
- **关闭开关**：`artemis.registry.auth.enabled=false`；布尔项使用 SCF 内置 `StringToBooleanConverter`（`Boolean.parseBoolean` 语义：仅 `true` 开启）。
- **传输**：gRPC metadata `authorization: Basic <base64>` 与外部 HTTP 同名头；`MessageDigest.isEqual` 常量时间比较，不记录凭证。
- **放行面**：外部 HTTP `/readyz`、gRPC `grpc.health.v1.Health`（K8s/Envoy 探针无凭证）；管理端口（actuator）默认仅绑 `127.0.0.1` 且不施加认证。
- **空白凭证**：SCF 将空白值视为未设置，回落默认账户（文档明示；不在启动期 fail-fast）。
- 统一账户体系随 v0.4 management 接管。

## v0.4 落地形态（2026-10-25 补充）

统一账户 v0.4 落地为**管理访问面收敛**：management 单管理员 DB 账户（PBKDF2 哈希存储、DB 态首启将配置凭证一次性 seed 物化、改密端点）接管 Admin API 与 console 认证；各服务本地单账户保留为服务自身凭证，委托机制后置 RBAC v1.0。详见 [ADR-021](adr-021-management-account-takeover.md)。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-25 | 补 v0.4 落地形态节（ADR-021：管理访问面收敛 + 各服务账户保留分域） |
| 1.1 | 2026-10-18 | 补 v0.1 实现语义：默认账户/关闭开关/传输与放行面/空白凭证处理 |
| 1.0 | 2026-10-10 | 自架构文档 §7 决策表 D9 迁移成文 |
