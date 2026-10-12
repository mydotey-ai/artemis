# ADR-014：cluster 不进 ServiceKey，来源标识在投影边界注入

版本: 1.0    更新时间: 2026-10-12

## 状态

Accepted（2026-10-11，v0.1 phase-1 设计 review 定案；2026-10-12 补 ADR 成文）

## 背景

多集群聚合（discovery 订阅多个 registry 集群）需要区分数据来源。cluster 是数据的外禀属性（哪个 registry 集群产出），与 namespace（租户，内禀）不同。

## 决策

cluster 不进 `ServiceKey`。v0.1–v0.2 registry 数据面（心跳/租约/复制）隐式同集群，集群名为服务端静态配置；v0.3 起作为来源标识进 projection/discovery 包装层（投影按 `(cluster, …)` 组织，维度在投影边界注入）；客户端心跳永不携带。

## 理由

- namespace 是服务的内禀属性（租户），cluster 是数据的外禀来源——绑定发生在消费边界，不该进身份模型。
- v0.1–v0.2 单集群，字段无消费者；v0.3 加包装字段向后兼容，推迟零代价。

## 否决方案

- cluster 进 `ServiceKey`——身份模型携带部署拓扑信息，单集群阶段全链路冗余。

## 后果

- discovery 投影按 `(cluster, namespace, service)` 组织（架构 §4.2），多集群聚合在查询/推送时刻按选择器合并。
- registry 数据面契约（common.v1/registry.v1）不出现 cluster 字段。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-12 | 初版：自 v0.1 phase-1 设计定案成文（补 ADR，code-review 发现遗漏） |
