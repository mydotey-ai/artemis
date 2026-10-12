# ADR-013：分组退出数据模型，以实例 metadata 元素表达

版本: 1.0    更新时间: 2026-10-12

## 状态

Accepted（2026-10-12，v0.1 phase-1 设计 review 定案）

## 背景

原产品 group 为实例属性（`Instance._groupId`，blank → 默认组）且治理面另有 Group 实体（DB 持久化）。v0.1 phase-1 数据模型设计时面临选择：group 进 `ServiceKey`/`Instance` 字段（原产品形态）还是以 metadata 表达。

## 决策

分组退出数据模型：`ServiceKey` = (namespace, service)，`Instance` 不设 group 字段——需要时以 metadata 元素表达（如 `metadata["group"]`）；治理分组路由（v0.5 Group filter）与四级摘除的 group 级按 metadata 选择器过滤。

## 理由

- 身份稳定：ServiceKey/InstanceKey 不随分组变化，重命名/迁移分组零身份 churn。
- 推送单元不碎片化：service 实例集快照（ADR-007）按 service 组织，分组维度不拆分推送。
- 不用分组的服务零成本：无字段占用、无默认组语义。

## 否决方案

- group 进 `ServiceKey`（原产品形态）——身份随分组变化，跨 group 聚合需拼 key。
- group 为 `Instance` 独立字段——与 metadata 元素表达无本质差异，多一个专用字段与校验面。

## 后果

- 治理面不设 Group 资源（资源导向 API 领域资源不含 group，架构 §5.2）；分组路由/摘除规则以 metadata 选择器表达。
- 分组语义依赖 metadata 约定（key 命名由治理文档约定，如 `group`）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-12 | 初版：自 v0.1 phase-1 设计定案成文（补 ADR，code-review 发现遗漏） |
