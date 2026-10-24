# Phase 5 测试：discovery 多集群聚合

版本: 1.0    更新时间: 2026-10-24

## 测试方案

- **单测**（discovery-core / discovery-server）：配置解析（多集群格式与 fail-fast）、选择器 cluster 过滤（query/checksums/订阅投递与基线）、readiness 全量门、proto↔domain clusters 字段映射与空白校验。
- **进程内多实例集群语义测试**（`DiscoveryMultiClusterIT`，failsafe `*IT`）：进程内起 2 个真实 registry（各自 external + peer 端口、独立 cluster 名）+ 1 个真实 discovery（配两上游），discovery.v1 stub 直连驱动 union 查询、cluster 过滤、订阅归因、对账过滤、新鲜度独立性与 readiness 全量门。既有三个 IT（Projection/Subscription/Reconciliation）随配置键变更改用新格式，回归单集群语义。

形态均在既有 unit / integration 模式映射内，`dev-test.sh` 无扩展。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见工作流 ⑤ 节，此处不复制）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_parse_multi_cluster_groups | JSON map 合法配置解析出 N 组 clusterId+端点 | 单测 | 通过 |
| should_fail_fast_when_clusters_missing_or_malformed | 未配置 / JSON 语法错 / clusterId 空白 / 端点列表空或非法均拒绝启动 | 单测 | 通过 |
| should_filter_query_by_clusters | 两 cluster 写入，clusters 非空只返回命中 cluster、空集全量 union 并列 | 单测 | 通过 |
| should_filter_checksums_by_clusters | checksums 按选择器 clusters 过滤，未命中 cluster 不出条目 | 单测 | 通过 |
| should_skip_delivery_for_non_matching_clusters | 订阅 clusters 非空时非命中 cluster 的变更不投递 | 单测 | 通过 |
| should_baseline_only_matching_clusters | 订阅基线只含命中 cluster 的条目 | 单测 | 通过 |
| should_be_ready_only_after_all_upstreams_synced | N-1 个上游 markSynced 不 ready，全部到齐 ready，重复 markSynced 幂等 | 单测 | 通过 |
| should_map_clusters_and_reject_blank_entry | proto↔domain clusters 往返映射；空白 cluster 名转 INVALID_ARGUMENT | 单测 | 通过 |
| should_union_query_across_clusters | 两 cluster 各注册（含同 ServiceKey 并列 + 各自独有 service），clusters 空查询返回全部并列条目 | IT | 通过 |
| should_filter_query_by_cluster_selector | clusters=[A] 只见 A；未知 cluster 名返回空成功非错误 | IT | 通过 |
| should_push_only_matching_clusters_to_subscription | 订阅 clusters=[A]：B 注册不推、A 注册推（cluster 归因正确）；无过滤订阅两边推 | IT | 通过 |
| should_filter_checksums_and_upstreams_by_cluster | 对账 clusters=[A] 只含 A 的 checksum 条目与 A 的新鲜度 | IT | 通过 |
| should_keep_cluster_freshness_independent | 停 registry A：A 转 DISCONNECTED、B 保持 SYNCED、A 投影冻结仍可查；重启 A 恢复 SYNCED | IT | 通过 |
| should_gate_readiness_on_all_upstreams | 只起 registry B 时 discovery 查询 UNAVAILABLE；补起 A 后 ready 且两 cluster 数据可见 | IT | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首轮） | BUILD SUCCESS（7:43；含全部单测 + 4 组 discovery IT；期间暴露并修复 ProjectionSynchronizerTest 预存竞态） |
| `java/scripts/dev-test.sh all`（⑧ review 修复后回归） | BUILD SUCCESS（7:37，exit 0） |

人工验证（用户执行）：待用户自行验证并记录。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全量（spotless + enforcer + 单测 + IT） | 通过 |
| 2 | all | 全量（review 修复后回归） | 通过 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-24 | 测试设计定稿 |
