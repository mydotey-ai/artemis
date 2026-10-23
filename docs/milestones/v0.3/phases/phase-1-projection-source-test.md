# Phase 1 测试：projection.v1 契约 + registry 侧数据分发

版本: 1.9    更新时间: 2026-10-23

## 测试方案

形态与覆盖面：

- **单测**（`*Test`，surefire）：registry-core 各组件——`ProjectionDispatcher`（异步组批变更分发、RENEW 过滤、EVICTED→DELETE 映射、慢消费者断流、订阅注销）、统一事件源语义（`evictExpired` 派发 `EVICTED`、保护服务跳过、写路径与清理路径同锁保序、`ReplicationFanout` 过滤 `EVICTED`）、mapper 映射（枚举、DELETE 最小实例）。
- **服务级集成测试**（`*IT`，failsafe，经 `dev-test.sh integration`）：`ProjectionSourceIT` 进程内起真实 registry（外部端口 + 内部 peer 端口，随机端口），测试侧以 projection.v1 生成 stub 直接驱动（artemis-common 同源生成类，服务端模块内可用）：bootstrap 全量、watch 变更序列、TTL 清理 DELETE 端到端、readiness 拒绝。本 phase 是单节点分发语义，不涉多实例集群。

无既有形态↔模式之外的新形态，dev-test.sh 不需扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_send_empty_establishment_batch_before_any_change | 订阅建立后首条为空批（消费方据此开始 Bootstrap，先 Watch 后 Bootstrap 无丢失序可执行） | 单测 | 通过 |
| should_dispatch_changes_to_subscriber_and_filter_renew | 订阅后变更异步组批到达；RENEW 过滤、同实例后到覆盖先到、EVICTED 映射 DELETE | 单测 | 通过 |
| should_merge_and_group_changes_by_service | 同 (service, instance) 合并、按 service 聚合且保持产生序 | 单测 | 通过 |
| should_split_batches_on_change_limit | 按 maxBatchChanges 拆批，单服务超限时按序切分 | 单测 | 通过 |
| should_map_evicted_to_delete | 组批阶段清理事件映射为 DELETE（wire 不出现 EVICTED） | 单测 | 通过 |
| should_disconnect_slow_subscriber_when_queue_full | 慢消费者（订阅者队列满）被服务端断流注销；后续订阅者照常接收 | 单测 | 通过 |
| should_not_deliver_after_unsubscribe | 订阅注销后不再接收推送 | 单测 | 通过 |
| should_emit_evicted_when_lease_expired | TTL 过期清理经统一事件源派发 EVICTED（domain 事件类型） | 单测 | 通过 |
| should_emit_eviction_after_later_write_events | 写路径与清理路径事件同 Holder 锁保序（EVICTED 不越过并发写） | 单测 | 通过 |
| should_not_replicate_evicted_event | 复制扇出发送侧过滤 EVICTED（删除不跨节点传播，D4 不变量保持） | 单测 | 通过 |
| should_skip_eviction_when_protected / should_skip_only_protected_service_when_evicting | 保护态服务整组跳过清理，无 EVICTED 派发（既有用例覆盖） | 单测 | 通过 |
| should_map_three_change_types | 投影三值枚举映射；EVICTED 防御性映射 DELETE、RENEW 拒绝 | 单测 | 通过 |
| should_map_batch_with_minimal_delete_instance | ServiceInstances/ServiceChange 复用与 DELETE 最小实例字段映射 | 单测 | 通过 |
| should_reject_bootstrap_with_unavailable_when_not_ready | 未就绪时 Bootstrap 返回 UNAVAILABLE | 单测 | 通过 |
| should_reject_watch_with_unavailable_when_not_ready | 未就绪时 Watch 返回 UNAVAILABLE | 单测 | 通过 |
| should_bootstrap_empty_and_full_snapshot | 空表 bootstrap 流正常结束；注册后全量含实例 | 集成 | 通过 |
| should_split_bootstrap_into_multiple_shards | 注册表超分片预算时 bootstrap 拆为多片（片边界不丢服务、全量完整） | 集成 | 通过 |
| should_rebootstrap_after_watch_disconnect | watch 断流后重连（新订阅确认）+ 重新 bootstrap 全量覆盖，期间变更不丢 | 集成 | 通过 |
| should_receive_change_batch_on_watch_stream | watch 后 register/deregister → NEW/DELETE 按序到达 | 集成 | 通过 |
| should_not_receive_renew_on_watch_stream | 心跳纯续约不产生 watch 消息（以控制事件确认窗口） | 集成 | 通过 |
| should_receive_delete_on_watch_stream_when_lease_ttl_expired | TTL 到期清理后 watch 收到该实例 DELETE | 集成 | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 检查 | 命令 | 结果 |
|---|---|---|
| 全量构建（spotless:check + enforcer + 单测 + IT） | `java/scripts/dev-test.sh all` | BUILD SUCCESS |
| 契约生成落地 | `ls java/server/artemis-common/target/generated-sources/protobuf/.../projection/v1/` | Bootstrap*/WatchEvent*/ChangeType 等全部生成，`ProjectionServiceGrpc` 引用 `WatchEvent` |
| 内部端口绑定 | `ProjectionSourceIT`（内部 peer 端口真实调用） | 通过 |

人工验证（用户执行）：用户于 2026-10-23 复核设计、实现与测试，结论**通过**（含分片机制说明确认）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | ⑥ review 修复后全量（spotless + enforcer + 单测 + IT） | 通过（BUILD SUCCESS） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.9 | 2026-10-23 | ⑨ 自动化验证记录；人工验证待用户结论 |
| 1.8 | 2026-10-23 | ⑧ 回归测试记录（all 全绿）；新增用例结果回填 |
| 1.7 | 2026-10-23 | ⑥ code-review 修复：补订阅建立确认、分片、断流重连用例；异步断言改为末态判定（消除组批时序竞态） |
| 1.6 | 2026-10-23 | ⑤ 测试执行：用例清单按实际实现对齐并记录结果；readiness 用例落为单测（真服务未就绪不可控） |
| 1.5 | 2026-10-23 | review 修订：stale 完全移出分发契约（registry 内部概念）——删 stale 相关用例，10 单测 + 5 集成 |
| 1.4 | 2026-10-23 | review 修订：删 ProtectionSnapshot 相关用例——stale 仅随 bootstrap（IT 用例改为 bootstrap stale 断言），9 单测 + 6 集成 |
| 1.3 | 2026-10-23 | review 修订：EVICTED 不进 wire 契约——投影流清理映射 DELETE（单测 + IT 用例同步） |
| 1.2 | 2026-10-23 | review 修订：统一事件源用例（EVICTED 派发/扇出过滤/保序）、异步组批语义入用例名 |
| 1.1 | 2026-10-23 | 测试设计定稿（9 单测 + 6 集成用例） |
| 1.0 | 2026-10-23 | 测试文档骨架 |
