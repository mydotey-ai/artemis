# ADR-020：discovery 静态节点目录与客户端前置寻址

版本: 1.1    更新时间: 2026-10-24

## 状态

Accepted（2026-10-24，v0.3 phase-8 设计定案）

## 背景

[ADR-002](adr-002-discovery-no-cluster.md) 定案 discovery 不组集群：节点是独立投影副本、实例间零协议、不提供存活节点列表 API，消费方 SDK 仅以配置 gRPC 地址列表 + 历史成功连接缓存 + 熔断换址容灾。

该形态与 registry 的三级寻址管线不对称：registry 客户端可用 HTTP 引导地址拉取服务端节点列表（`GET /cluster/nodes`），运维只需暴露稳定的 HTTP 入口；discovery 客户端却必须硬编码/逐一下发 gRPC 端点，节点扩缩容与端点变更成本高。引入静态节点目录可在**不改变「不组集群」核心决策**的前提下补齐这一点。

## 决策

1. **discovery 仍不组集群**：无节点间协议、无跨节点状态同步、无存活探测；节点目录是**静态部署声明**。
2. 服务端新增静态配置 `artemis.discovery.nodes`：`<host>:<port>` 逗号分隔（host 支持 `[ipv6]`；单节点 = 只配一条；未配置 = 目录为空）。**无节点 id、无 self 概念**——地址即标识；每个节点配置同一份部署声明、原样返回（registry 侧需要 id/self 是复制与探测归因所需，discovery 无节点间协议、无从归因）。非法条目（语法错/重复）启动失败。
3. discovery 外部 HTTP 端口（`server.port`，默认 8090）新增 `GET /cluster/nodes`：返回 `discovery.v1 DiscoveryNodesResponse` protobuf bytes（对外 gRPC `host/port` 列表），复用外部 Basic 认证；**readiness 门控**（未就绪 503，同 registry 寻址端点；与 `/admin/v1` 的免门控排障面区分）。
4. 消费方 SDK 走**与 registry 相同的三级管线**：HTTP 引导地址（`artemis.client.discovery.addresses`）→ `GET /cluster/nodes` 节点列表 → gRPC 选址（历史最近成功优先 + 随机）+ 熔断换址 + 列表周期刷新。
5. 节点目录**无状态字段**：所有条目为候选地址，连通性由客户端失败上报 + 熔断兜底，不做服务端探测。

## 理由

- 与 registry 寻址管线同构：同一套抓取/刷新/选址/熔断实现，降低 SDK 复杂度与运维心智。
- 部署形态更友好：入口只需 HTTP 域名/LB，节点端点变更由服务端配置发布，客户端无需逐一下发。
- 不违反 ADR-002 的核心（无集群协议、无状态同步）；静态目录不是「存活列表」。

## 否决方案

- **仅配置 gRPC 端点列表**（ADR-002 原形态）：客户端扩缩容需全量下发，与 registry 不对称；否决。
- **带探测的存活节点列表**：需要节点间状态或服务端探测，违反 ADR-002 与「服务端不做主动健康探测」哲学；否决。
- **带节点 id / self 的成员表**（registry `StaticClusterMembers` 形态）：id 与 self 的价值在复制归因与探测归因，discovery 无节点间协议，多出来的概念徒增配置负担；否决，地址即标识。
- **复用 `replication.v1 GetNodeStatusResponse`**：语义绑定 registry 复制/探测态（node_id/观测态），discovery 无此概念；改为 discovery.v1 独立消息（仅 host/port），避免契约语义漂移。

## 后果

- ADR-002 的「不提供『存活节点列表』API」修订为：**不提供存活列表，但提供静态节点目录**（部署声明，无探测态）；其余决策不变。
- `discovery.v1` 增 `DiscoveryNode` / `DiscoveryNodesResponse` 消息（wire 兼容只增，无新 service）。
- discovery-server 新增节点目录配置、HTTP 端点与测试；SDK 泛化复用 `HttpNodeListFetcher` / `NodeListRefresher` / `FailoverAddressProvider`。
- API 文档新增 discovery 节点目录端点说明。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-24 | review 定案：节点目录去 id/self（`host:port` 地址即标识），配置与消息简化 |
| 1.0 | 2026-10-24 | v0.3 phase-8 设计定案：静态节点目录 + 与 registry 相同的客户端前置寻址管线 |
