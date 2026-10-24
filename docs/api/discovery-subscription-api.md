# discovery 订阅推送 API

版本: 1.1    更新时间: 2026-10-24

> `discovery.v1` 订阅契约（`DiscoverySubscriptionService.Subscribe`，v0.3 phase-3 交付）：SDK → discovery 外部 gRPC 端口的增量订阅推送。查询契约（`DiscoveryQueryService.Query`）见 [discovery-query-api.md](discovery-query-api.md)——两者共用同一选择器语义与端口/认证/readiness 行为，本文不复制。

## 推拉结合模型（ADR-019）

订阅通道为三件套之一，三者互补构成完整发现语义：

| 件 | 通道 | 交付 |
|---|---|---|
| 实时推增量 | `Subscribe` 流（本文） | v0.3 phase-3 |
| 定期拉全量 | `Query`（unary，兜底轮询用） | v0.3 phase-2 已交付 |
| hash 对账 | per-service checksum 表拉取（不一致 service 触发全量拉，增量丢失显式检测） | v0.3 phase-4 |

## 契约

```protobuf
message SubscribeRequest {
  repeated artemis.common.v1.ServiceKey services = 1;  // 空 = 全部服务（语义同 QueryRequest）
  map<string, string> labels = 2;                      // 实例级过滤（语义同 QueryRequest）
  repeated string clusters = 3;                        // 来源 cluster 过滤（语义同 QueryRequest，v0.3 phase-5）
}

message SubscriptionEvent {
  string cluster = 1;         // 来源 cluster（投影边界注入，ADR-014）
  ServiceChange service = 2;  // 单 service 视图语义变更
}

service DiscoverySubscriptionService {
  rpc Subscribe(SubscribeRequest) returns (stream SubscriptionEvent);
}
```

变更事件模型（`ChangeType`/`InstanceChange`/`ServiceChange`）与 `projection.v1` 同构（组间不横向 import）：`NEW`/`UPDATE`/`DELETE` 三值，`DELETE` 的 instance 至少携带 `instance_id`。

## 流语义

| 阶段 | 行为 |
|---|---|
| 建立 | 订阅登记后**首条空事件**（两字段均未设）= 建立确认；随后按选择器命中**逐 service 推送基线**（该服务当前实例集全 `NEW` 批，per-service 分条） |
| 稳态 | 上游变更以**视图语义增量**推送：`services` 过滤 service 级（空 = 全部），`labels` 由服务端翻译为**视图语义**——实例进出 labels 视图显式翻译为 `NEW`/`DELETE`，SDK 收到即 apply，无需自行判断匹配态 |
| 上游重连 | registry↔discovery 断连重连对订阅方**透明**：断连期间流静默（投影冻结，陈旧换可用），重连覆盖差异以 **diff 增量续推**（消失服务全 `DELETE`、新建全 `NEW`），流不断 |
| 慢消费者 | outbound 背压（`isReady` false）时服务端断流 `RESOURCE_EXHAUSTED`（at-most-once，防无界缓冲），消费方重订阅重新基线自愈 |
| 结束 | 流断开 = 订阅结束；增量丢失检测归 phase-4 hash 对账与 SDK 兜底轮询 |

单流内按产生序有序；一流多目标（一条流承载选择器命中的全部 service）；事件携带来源 `cluster`。

**多集群过滤**（v0.3 phase-5）：`clusters` 非空时只推送命中 cluster 的变更与基线（联合视图的子集）；空 = 全部 cluster（union）。事件 `cluster` 字段用于消费方归因；cluster 清单是服务端部署拓扑，未知名静默无命中。

## 错误与门控

| 场景 | 行为 |
|---|---|
| 未就绪（首轮投影同步未完成） | `UNAVAILABLE`（readiness 拦截器，语义同 Query） |
| 选择器校验失败（services/clusters 字段空白） | gRPC status `INVALID_ARGUMENT`（流未开，无 in-band 错误码） |
| 认证 | 与 Query 同端口同策略（BasicAuth，默认关闭） |

## 指标

`artemis.discovery.subscription.active`（gauge）/ `.push`（counter）/ `.closed.slow-consumer`（counter），经 discovery 管理端口 `/actuator/prometheus` 抓取。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-24 | phase-5：`SubscribeRequest.clusters` 来源过滤（空 = union）与 cluster 归因说明 |
| 1.0 | 2026-10-24 | v0.3 phase-3 交付：推拉结合增量订阅契约（ADR-019） |
