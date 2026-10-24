# discovery 静态节点目录 API

版本: 1.0    更新时间: 2026-10-24

> 本文定义 discovery-service 的**静态节点目录**端点（v0.3 phase-8，[ADR-020](../decisions/adr-020-discovery-static-node-directory.md)）：供消费方 SDK 做 HTTP 前置寻址，节点目录是**部署声明**而非存活探测。查询/订阅/对账数据面契约见 [discovery-query-api.md](discovery-query-api.md) / [discovery-subscription-api.md](discovery-subscription-api.md) / [discovery-reconciliation-api.md](discovery-reconciliation-api.md)。

## 1. 端点与部署

| 项 | 值 |
|---|---|
| 路径 | `GET /cluster/nodes` |
| 端口 | discovery **外部 HTTP** 端口（`server.port`，默认 8090） |
| 响应 | `application/x-protobuf`（`discovery.v1 DiscoveryNodesResponse`） |
| 认证 | 复用 discovery 外部账户（`artemis.discovery.auth.*`，**默认关闭**）；缺失/错误 → `401` |
| 就绪 | **readiness 门控**：首轮投影同步未完成 → `503`（未就绪节点不作寻址应答者）；与 `/admin/v1` 的免门控排障面区分 |

## 2. 契约

```protobuf
message DiscoveryNode {
  string host = 1;   // 对外 gRPC 端点；IPv6 带方括号
  int32 port = 2;
}
message DiscoveryNodesResponse {
  artemis.common.v1.ResponseStatus status = 1;
  repeated DiscoveryNode nodes = 2;
}
```

- **无节点 id、无状态字段**：地址即标识；所有条目均为候选，连通性由客户端失败上报 + 熔断兜底。
- `status.ack = ACK_SUCCESS`；列表可为空（服务端未配置 `artemis.discovery.nodes`）。
- 无对应 gRPC service；本端点只承载 HTTP 寻址目录。

## 3. 服务端配置（SCF，静态）

| 键 | 默认 | 说明 |
|---|---|---|
| `artemis.discovery.nodes` | 空 | 节点目录 `host:port` 逗号分隔（host 支持括号 IPv6）；单节点 = 只配一条。**未配置 = 空目录**；非法端点（端口不在 1..65535、host 空白、裸 IPv6 未加括号）/重复条目 → 启动失败（fail-fast） |

每个 discovery 节点配置**同一份**部署声明，`/cluster/nodes` 原样返回；节点集合启动定死（对齐静态成员哲学）。不组集群、无节点间协议、无探测。

## 4. 消费方用法（SDK）

`artemis-client` 发现侧 SDK 的地址管线与 registry 相同：

```text
artemis.client.discovery.addresses（HTTP 引导地址）
  → GET <url>/cluster/nodes（本端点）
  → gRPC 节点列表 → 历史成功优先 + 随机 + 熔断换址
```

配置、重试与指标见 [sdk-java-discovery.md](sdk-java-discovery.md) §4。

## 5. 示例

```bash
curl -s -u artemis:artemis -H 'Accept: application/x-protobuf' \
  http://discovery.example.com:8090/cluster/nodes -o nodes.pb
```

客户端解析后的候选端点示例：`discovery-1:9095`、`discovery-2:9095`。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-24 | v0.3 phase-8 初版：discovery 静态节点目录端点（ADR-020） |
