# v0.5 版本规划

版本: 1.1    更新时间: 2026-10-27

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v0.5（治理面 II），此处不复述条目原文——一句话概括：治理能力补完——分组路由（Group filter 全语义，分组以实例 metadata 表达）、两段式权重发布（`weight/unreleased_weight` + release）+ diff 预览 + 一键回滚、canary（自动专属规则组），console 升管理版（发布/摘除操作、订阅关系视图、操作审计流、自我保护态展示）。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：灰度发布端到端（一个 API 完成）、回滚生效、canary 流量切分。

治理语义基线见 [架构设计](../../arch/artemis-next-architecture.md) §5.2（资源导向 API：`route-rule` / `release`；两段式发布与版本化回滚；分组路由按 metadata 选择器，[ADR-013](../../decisions/adr-013-group-as-instance-metadata.md)；canary = route-rule + release 的预置派生用法，执行点在 discovery 过滤器链）与 §5.3（console 管理版范围）。下发通道复用 v0.4 版本化规则流 + ack 生效水位（phase-5 交付），不新立通道。设计不在本版展开，由各 phase ③ 设计定稿。

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-route-rule | route-rule 资源：领域模型（metadata 选择器 + 目标 + 动作）、V5 双方言 migration、CRUD API 与审计 | 2 | 单测：模型语义与 repository 双方言；IT：CRUD + 审计可查 + 跨重启保留 |
| phase-2-two-phase-release | 两段式权重发布：`weight/unreleased_weight` + release + diff 预览 + 一键回滚（指向历史版本的反向 release），经规则流下发 + ack 水位 | 3 | 单测：两段状态机与 diff 计算；IT：灰度端到端（一个 API 完成，roadmap 验收要点）、回滚生效（discovery 视图还原 + 水位可见） |
| phase-3-group-routing | Group filter 全语义：discovery 过滤器链按 metadata 选择器分组路由，查询/推送/对账三出口一致 | 2 | 单测：选择器匹配语义；IT：分组路由端到端（多组实例按选择器分流） |
| phase-4-canary | canary：自动专属规则组（route-rule + release 预置派生），management 生成并下发 | 2 | 单测：派生规则生成；IT：canary 流量切分端到端（roadmap 验收要点） |
| phase-5-console-management | console 管理版：发布/摘除操作、订阅关系视图（调用方视角）、操作审计流、自我保护态展示 | 2 | IT：console 操作闭环（页面操作驱动治理 API）、视图与审计可查 |

phase-1 说明：route-rule 是本版全部治理能力的资源底座（分组路由/canary 的规则载体、两段发布的对象）；模型语义（选择器表达、目标/动作词汇）由 ③ 对照架构 §5.2 与 ADR-013 定稿，不引入分组独立资源。

phase-2 说明：两段式发布语义（`weight/unreleased_weight` + 显式 release）与回滚（= 指向历史版本的反向 release）为继承治理语义（架构 §5.2）；一个 release 调用完成一个运维意图（资源导向 API）；生效水位复用 v0.4 phase-5 出口。

phase-3 说明：执行点在 discovery 过滤器链（架构 §4.2/§5.2），分组以实例 metadata 元素表达（ADR-013，分组不进数据模型）；三出口一致（查询/推送/对账）对齐 v0.3 phase-6 摘除过滤纪律。

phase-4 说明：canary = route-rule + release 的**预置派生用法**（架构 §5.2）：由 management 生成专属规则组并下发，不新增独立资源类型；③ 定稿派生规则的表达与切分语义。

phase-5 说明：console 管理版操作面全部走既有治理 REST API（不另立端点）；订阅关系视图（调用方视角）数据源为 discovery 发现管理面（v0.3 phase-7）+ management 组合，③ 定稿视图口径。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|
| 灰度发布端到端（一个 API 完成） | `RouteRuleReleaseIT.should_expose_release_api_contract_over_http`（phase-2：一个 release 调用拷贝编辑列生效 + append-only 发布记录） | 通过 |
| 回滚生效 | `RouteRuleReleaseIT`（rollback 指向历史记录反向 release，视图还原）+ `DiscoveryGroupRoutingIT`（phase-3：回滚后 discovery 视图还原，三出口一致） | 通过 |
| canary 流量切分 | `DiscoveryCanaryIT.should_cut_canary_traffic_end_to_end`（phase-4：端到端视图切分，weight 0/部分/10000 三态） | 通过 |
| （范围项）console 管理版 | `ManagementConsoleOpsIT`（phase-5：操作闭环 5 用例）+ `DiscoveryConsoleSubscriptionsIT`（订阅组合端到端 2 用例） | 通过 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-27 | 版本收尾：验收对照表填齐（三验收要点 + console 范围项，全部通过） |
| 1.0 | 2026-10-26 | 版本启动，初始 phase 拆分（5 phase，滚动细化） |
