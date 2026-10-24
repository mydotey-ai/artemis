# Phase 6 测试：management 摘除规则订阅与 discovery 过滤

版本: 1.3    更新时间: 2026-10-24

## 测试方案

- **单测**：规则模型（快照替换/增量叠加）、过滤器链（fail-open/全可见原引用）、订阅扇出过滤（投影变更 × 摘除态组合翻译、规则扇出直投）、投影出口过滤（query/checksums 口径与零计算路径）、规则同步引擎（断流不清缓存、扇出）、management 分发（快照 + 增量全序、推送失败断流）、存储 findAll、配置（条件装配/校验 fail-fast）、proto 映射。
- **进程内多实例集群语义测试**：
  - `EjectionRuleStreamIT`（management-server）：进程内起真实 management（gRPC 规则流端口），测试 stub 直连驱动：连接收快照、eject/recover 增量、断流。
  - `DiscoveryEjectionFilterIT`（discovery-server）：进程内起真实 registry + discovery + management 三服务，端到端驱动：摘除后查询与推送视图被过滤、恢复收敛、management 重启空快照收敛。

形态均在既有 unit / integration 模式映射内，`dev-test.sh` 无扩展。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见工作流 ⑤ 节，此处不复制）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_apply_snapshot_and_delta_to_rules | 快照替换 + with/without 增量叠加 + EMPTY 判定 | 单测 | 通过 |
| should_fail_open_when_filter_throws | 单 filter 抛异常 → 放行；全可见 → 返回原 List 引用 | 单测 | 通过 |
| should_reflect_rule_updates_in_ejection_filter | replace 后 isVisible 随新规则集判定（跨 cluster 同键命中） | 单测 | 通过 |
| should_translate_projection_changes_with_ejection_filter | 投影 NEW/UPDATE/DELETE × 摘除态组合的视图语义翻译（NEW 已摘除无事件、UPDATE 转摘除发 DELETE(previous)、DELETE 已摘除无事件） | 单测 | 通过 |
| should_filter_baseline_with_chain | 订阅基线为过滤后视图（摘除实例不进基线） | 单测 | 通过 |
| should_deliver_view_changes_on_rules_change | 规则扇出直投：eject → DELETE、recover → NEW；labels/cluster/services 不命中不投递；投影无此实例无推送 | 单测 | 通过 |
| should_filter_query_and_checksums_with_chain | query/checksums 出口过滤；显式命中过滤后可空；空链/空规则返回原引用读缓存（checksum 零计算） | 单测 | 通过 |
| should_keep_last_known_rules_when_stream_closed | 同步器断流（source 关闭）后规则缓存不清空、继续过滤 | 单测 | 通过 |
| should_fanout_view_changes_on_rules_change | 同步器收到快照/增量后经表锁扇出视图变更 | 单测 | 通过 |
| should_find_all_sorted | EjectionStore.findAll 按 (namespace, service, instanceId) 升序 | 单测 | 通过 |
| should_push_snapshot_then_deltas_in_order | 订阅登记即收快照（含空集）；eject/recover 增量按序；订阅期间快照读与增量无竞态 | 单测 | 通过 |
| should_disconnect_subscriber_on_push_failure | 推送抛异常 → 断流注销、订阅计数归零 | 单测 | 通过 |
| should_notify_dispatcher_on_eject_and_recover | 应用服务 eject/recover 后通知分发（分发异常不影响操作） | 单测 | 通过 |
| should_parse_management_config_or_fail_fast | endpoints 非法（空白项/无端口）拒绝启动；空 = 不启用（空链 + 不建同步器装配断言） | 单测 | 通过 |
| should_map_ejection_rule_events | proto 快照/增量（presence 分派）↔ domain 映射 | 单测 | 通过 |
| should_send_snapshot_on_connect | 预置摘除 2 条 → watch 首条快照含 2 | IT | 通过 |
| should_send_empty_snapshot_when_no_ejections | 无摘除 → 首条空快照（snapshot 已设、rules 空） | IT | 通过 |
| should_push_delta_on_eject_and_recover | watch 后经 Admin API eject → 增量 ejected；recover → 增量 recovered | IT | 通过 |
| should_disconnect_stream_on_client_cancel | 客户端取消 → 服务端注销订阅 | IT | 通过 |
| should_filter_query_and_push_after_ejection | 三服务：注册 2 实例 → 查询/订阅基线见 2 → eject A → 查询只见 B、订阅流收 DELETE(A) | IT | 通过 |
| should_recover_instance_back_to_view | recover A → 查询复见 A、订阅流收 NEW(A) | IT | 通过 |
| should_keep_filtering_when_management_down | 停 management 规则流端口 → discovery 沿用最后已知规则继续过滤 | IT | 通过 |
| should_converge_to_empty_snapshot_after_management_restart | management 重启（内存态清零）→ discovery 重连空快照 → A 恢复可见 | IT | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑） | BUILD SUCCESS（含 DiscoveryEjectionFilterIT 3 用例 + EjectionRuleStreamIT 4 用例 + 既有全部回归） |
| `java/scripts/dev-test.sh all`（⑧ review 修复后回归） | BUILD SUCCESS（12 模块全绿；spotless + enforcer + 单测 + IT 一体） |

人工验证（用户执行）：通过（2026-10-24，用户确认）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全量（⑤ 首跑，经 6 轮迭代修复：mapper 嵌套 builder、两测试断言、IT management context classpath 隔离与 gRPC 端口显式钉定、GrpcEjectionRuleSource channel 泄漏改 bean 生命周期） | 通过 |
| 2 | all | 全量（资源修复后回归） | 通过 |
| 3 | all | 全量（⑧ review 修复后回归；一轮 registry SdkFailoverIT 端口绑定 flaky 与本 phase 无关，重跑通过） | 通过 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-24 | ⑧ 回归轮次 3 + ⑨ 自动化验证记录 |
| 1.2 | 2026-10-24 | ⑤ 首跑全绿：用例结果与回归记录填写（含实现期测试修正：UPDATE 摘除实例语义、显式命中断言、watch 建立竞态） |
| 1.1 | 2026-10-24 | ③ 测试设计定稿：23 用例（15 单测 + 8 IT，两 IT 新形态为三服务/单服务进程内集群） |
| 1.0 | 2026-10-24 | 开工骨架 |
