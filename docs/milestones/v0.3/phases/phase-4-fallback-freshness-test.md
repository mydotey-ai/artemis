# Phase 4 测试：discovery 兜底对账与新鲜度

版本: 1.3    更新时间: 2026-10-24

## 测试方案

两形态（guidelines §7）：

- **单测**（surefire `*Test`）：canonical 规则的确定性与抗序性（同内容异序同值、任一字段变化变值——SDK 侧须按同一规则独立实现，规则本身是正确性根）；投影条目 checksum 缓存一致性（apply/replaceAll 与重算对账）；labels 视图现算路径与缺项规则；`UpstreamStatuses` 快照；proto 映射（UNKNOWN 哨兵、uint32、freshness 装配）。
- **IT**（failsafe `*IT`，进程内真实 registry + discovery + 真实协议 stub，复用 phase-3 订阅 IT 装配模式）：IT 侧以 **SDK 角色**独立实现 canonical 算法（照 api 文档规则手写一份，不引服务端类）重算期望值——端到端验证「表拉取 ↔ 本地比对 ↔ 不一致触发全量拉」闭环与新鲜度标记可见性；registry context 关停/重启驱动上游断连（既有先例）。

形态↔模式映射沿用既有（unit/integration），dev-test.sh 无变更；用例形态未超出既有映射。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_compute_identical_checksum_when_instance_or_metadata_order_varies | 同一实例集打乱 instance 序 / metadata 迭代序，checksum 不变（抗序，D-F2） | 单测 | 通过 |
| should_change_checksum_when_any_instance_field_changes | instanceId/host/port/status/weight/metadata 任一变化均变值 | 单测 | 通过 |
| should_return_zero_for_empty_instance_set | 空实例集 checksum = 0（XOR 单位元，缺项语义的地基） | 单测 | 通过 |
| should_maintain_cached_checksum_on_apply_and_replace_all | NEW/UPDATE/DELETE/覆盖后条目缓存 = 独立重算值；空集删条目（D-F3） | 单测 | 通过 |
| should_omit_and_compute_view_checksum_by_labels_selector | labels 选择器：过滤视图现算、滤空缺项、显式集合命中语义（D-F1/D-F3） | 单测 | 通过 |
| should_snapshot_upstream_states_on_updates | update/snapshot 弱一致读写（D-F5） | 单测 | 通过 |
| should_report_state_transitions_to_upstream_statuses | 同步器状态转移上报（SYNCED→断流→DISCONNECTED，D-F5） | 单测 | 通过 |
| should_map_upstream_state_and_checksum_roundtrip | UpstreamState→proto 三值、uint32 位模式、Query/Checksum 响应 upstreams 装配 | 单测 | 通过 |
| should_expose_checksum_table_consistent_with_query_view | 注册/更新/注销后表项出现/变化/消失；SDK 角色独立重算比对一致 | IT | 通过 |
| should_detect_local_view_tamper_via_checksum_and_recover_by_query | SDK 角色漏记实例（模拟增量丢失）→ 比对不一致 → Query 全量拉收敛 | IT | 通过 |
| should_mark_upstream_disconnected_in_pulls_while_serving_stale_data | registry 关停：Query upstreams = DISCONNECTED 且数据继续服务；重启回 SYNCED、拉/表两面收敛（断连标记可见 + 轮询自愈） | IT | 通过 |
| should_compute_labels_view_checksum_end_to_end | labels 请求 = 过滤视图指纹（IT 侧独立重算，含缺项） | IT | 通过 |
| should_reject_checksum_before_first_sync | 未就绪 Checksum UNAVAILABLE（readiness 拦截器全局生效） | IT | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑） | BUILD SUCCESS（6:31min，全 reactor）——期间 2 次环境抖动重跑：SdkFailoverIT 超时（单跑通过确认 flaky）、ClientSdkIT 瞬时端口占用（残留释放后通过） |
| `java/scripts/dev-test.sh all`（⑧ 回归，fix 后） | BUILD SUCCESS（6:17min，全 reactor）；DiscoveryReconciliationIT 5/5 通过 |

人工验证（用户执行）：2026-10-24 通过——含 canonical 规则抗序机制质询（metadata XOR 结合交换律、排序依赖消除、碰撞天花板），确认实现与契约语义一致。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor（含 code-review 修复 FIX-1..3） | BUILD SUCCESS |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-24 | ⑤ 全绿（dev-test.sh all BUILD SUCCESS）：用例清单 13 条（8 单测 + 5 IT，含实现期补充的空集/转移上报两单测）结果登记 |
| 1.1 | 2026-10-24 | 测试设计定稿草案：两形态方案 + 11 用例（6 单测 + 5 IT） |
| 1.0 | 2026-10-24 | 开工骨架 |
