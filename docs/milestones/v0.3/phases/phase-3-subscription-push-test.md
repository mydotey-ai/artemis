# Phase 3 测试：discovery 订阅契约与推送

版本: 1.4    更新时间: 2026-10-24

## 测试方案

两层形态，均落既有形态↔模式映射（dev-test.sh unit/integration），无模式扩展：

- **单测**（discovery-core `*Test`）：`SubscriptionManager` 的确认/基线/翻译/扇出/断流语义——纯内存驱动（stub `PushTarget` 收集推送 + 手动驱动 `ProjectionRegistry` 旧值），不起服务。labels 翻译矩阵（D-S3）逐象限覆盖。
- **IT**（discovery-server `*IT`，failsafe）：复用 phase-2 `DiscoveryProjectionIT` 模式——进程内真实 registry（外部 + peer 端口）+ 真实 discovery（上游投影 + 随机端口出口），discovery.v1 订阅 stub 直连驱动流式断言（确认、基线、增量、断流重订阅、亚秒可见）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_send_confirmation_then_new_baseline_on_subscribe | 订阅登记：首条空批确认 + 命中逐 service 全 NEW 基线（per-service 分条） | 单测 | pass |
| should_baseline_only_matching_explicit_services | 显式 services 订阅：命中条目才基线，未命中/不存在条目不基线 | 单测 | pass |
| should_translate_new_by_labels_match | NEW 实例匹配 labels 推 NEW；不匹配丢弃 | 单测 | pass |
| should_translate_update_all_four_match_states | UPDATE 四象限：双匹配 UPDATE、旧不匹配新匹配 NEW、旧匹配新不匹配 DELETE、双不匹配丢弃 | 单测 | pass |
| should_translate_delete_with_old_value_before_apply | DELETE 用投影旧值判匹配：旧匹配推 DELETE，旧不匹配不推（翻译先于 apply） | 单测 | pass |
| should_forward_changes_in_origin_order | 同 service 批内变更保持产生序转发 | 单测 | pass |
| should_diff_vanished_new_and_changed_services | ProjectionDiff：服务消失全 DELETE、新建全 NEW、共存实例按 equals 增删改（确定序） | 单测 | pass |
| should_push_diff_and_keep_stream_on_upstream_replace | 覆盖路径经同一翻译扇出推送 diff 增量、订阅流不断（无 disconnect） | 单测 | pass |
| should_not_push_after_subscription_closed | close 后不再接收推送 | 单测 | pass |
| should_stream_confirmation_and_baseline_on_subscribe | IT：真实订阅流首条空批确认 + 基线全量（一流多目标） | IT | pass |
| should_push_increment_sub_second_on_stream | IT：注册 → NEW 亚秒到流；update → UPDATE；deregister → DELETE | IT | pass |
| should_isolate_streams_by_selector | IT：并行两条订阅流（不同 services/labels）各自只收命中增量 | IT | pass |
| should_push_labels_view_semantics_on_stream | IT：labels 订阅只见子集；update 改 metadata 退出视图收到 DELETE 语义 | IT | pass |
| should_stay_connected_and_converge_via_diff_when_upstream_replaced | IT：registry 重启 → discovery 覆盖 → 订阅流不断、收到 diff 增量收敛（实例删除收 DELETE、新增收 NEW） | IT | pass |
| should_reject_subscribe_before_first_sync | IT：未就绪（首轮 bootstrap 未完成）订阅 UNAVAILABLE | IT | pass |
| should_clean_up_subscription_after_client_cancel | IT：客户端取消流后服务端移除订阅（不崩、后续变更不泄漏推送） | IT | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑） | BUILD SUCCESS（全 reactor：spotless + enforcer + 单测 + IT） |
| `java/scripts/dev-test.sh all`（⑧ fix 后回归） | BUILD SUCCESS |

人工验证（用户执行）：待

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤ 首跑） | all（全 reactor verify） | 全量：spotless + enforcer + 单测 + IT | 全绿（BUILD SUCCESS） |
| 2（⑧ 回归，fix 后） | all（全 reactor verify） | 全量（表锁/索引 memo/断言强化等 10 项修复后回归） | 全绿（BUILD SUCCESS） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-24 | 用例结果 pass + ⑤/⑧ 回归记录两轮 |
| 1.3 | 2026-10-23 | 二轮 review 定案：上游覆盖改 diff 续推——ProjectionDiff 单测 + 流不断收敛 IT，替换断流用例 |
| 1.2 | 2026-10-23 | review 定案：随推拉结合重写——labels 翻译矩阵逐象限、上游覆盖断流重订阅用例；快照类用例移除 |
| 1.1 | 2026-10-23 | 测试设计定稿（快照推送版：单测 + IT 两形态，用例 15 条） |
| 1.0 | 2026-10-23 | 开工骨架 |
