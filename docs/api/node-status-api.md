# 节点状态 API

版本: 1.0    更新时间: 2026-10-21

> 本文描述 registry 对外（SDK / 运维）的节点状态 gRPC API：本节点就绪态、静态成员列表、成员对外端点与后台探测观测态。契约本体见 `proto/replication/v1/replication.proto`；内部 peer 使用的 `ReplicationService.GetNodeStatus` 同构但只绑定内部端口，不在本文范围。

## 1. 传输与安全

| 项 | 说明 |
|---|---|
| 服务端 | registry 外部 gRPC 端口（默认 `9090`） |
| 方法 | `artemis.replication.v1.NodeStatusService/GetNodeStatus` |
| 认证 | 外部认证（`artemis.registry.auth.*`，默认开启）；未认证返回 `UNAUTHENTICATED` |
| readiness | **按方法名豁免**：节点未就绪时也可调用（返回 `self.state = STARTING`），用于排障与引导；其余外部写 rpc 仍被 readiness 拦截 |
| 单节点模式 | `self.node_id` 为空、`members` 为空；SDK 使用配置引导地址，不依赖本 API |

## 2. 契约

```proto
service NodeStatusService {
  rpc GetNodeStatus(GetNodeStatusRequest) returns (GetNodeStatusResponse);
}

message GetNodeStatusRequest {}   // 无参数

message GetNodeStatusResponse {
  artemis.common.v1.ResponseStatus status = 1;
  GetNodeStatusResponseData data = 2;
}

message GetNodeStatusResponseData {
  NodeStatus self = 1;
  repeated ClusterMember members = 2;
}

message NodeStatus {
  string node_id = 1;        // 单节点模式为空
  NodeState state = 2;       // STARTING / UP / DOWN（未知值统一映射 UNKNOWN）
  int64 started_at_ms = 3;   // epoch 毫秒
}

message ClusterMember {
  string node_id = 1;
  string peer_host = 2;      // 内部 peer 端点；IPv6 带方括号
  int32 peer_port = 3;
  string external_host = 4;  // 对外 SDK 端点；IPv6 带方括号
  int32 external_port = 5;
  NodeState state = 6;       // 本节点视角观测态
}
```

`NodeState` 取值：`NODE_STATE_STARTING`（未就绪）、`NODE_STATE_UP`（就绪）、`NODE_STATE_DOWN`（对端显式下线，优雅停机）、`NODE_STATE_UNKNOWN`（无法识别/探测失败）。

## 3. 语义

- **self**：本节点自报，`state` 直接映射 readiness 门（未 ready → STARTING，ready → UP）。
- **members**：静态成员表 + 本节点视角观测态。
  - self 成员状态 = self.state；
  - peer 成员状态来自**后台探测**：默认每 5s（`artemis.registry.cluster.probeIntervalMs`）用虚拟线程并发探测全部 peer，单 peer/整轮超时默认 1s（`artemis.registry.cluster.probeTimeoutMs`）；超时、异常、不可达、对端自报 nodeId 与配置不符一律记为 `UNKNOWN`。每轮整体重建，不保留陈旧 `UP`。
  - 单节点/尚未完成首轮探测时 peer 状态为 `UNKNOWN`。
- **探测不是存活保证**：探测有周期窗口；SDK 应以 `UP` 为首选、连接失败/熔断兜底（v0.2 phase-5 消费）。
- **不做服务端过滤**：API 返回全部静态成员（含 `UNKNOWN`），由消费方决定选址策略。
- 成员配置（集群模式三段必填）：`<node-id>@<peer-host>:<peer-port>@<external-host>:<external-port>`。

## 4. 示例

```json
{
  "status": {"ack": "ACK_SUCCESS"},
  "data": {
    "self": {"node_id": "node-a", "state": "NODE_STATE_UP", "started_at_ms": 1761000000000},
    "members": [
      {
        "node_id": "node-a",
        "peer_host": "10.0.0.1", "peer_port": 9093,
        "external_host": "10.0.0.1", "external_port": 9090,
        "state": "NODE_STATE_UP"
      },
      {
        "node_id": "node-b",
        "peer_host": "10.0.0.2", "peer_port": 9093,
        "external_host": "10.0.0.2", "external_port": 9090,
        "state": "NODE_STATE_UNKNOWN"
      }
    ]
  }
}
```

（JSON 仅示意字段；实际为 protobuf wire 格式。）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-21 | 初版：v0.2 phase-3 对外节点状态 API（成员/观测态、认证与 readiness 豁免、探测语义） |
