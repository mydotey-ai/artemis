# Phase 2 测试：变更事件扇出

版本: 3.3    更新时间: 2026-10-21

## 测试方案

两形态覆盖（guidelines §7）：

- **单测**（registry-core，`*Test`）：副本租约语义（`InstanceRegistry.applyReplicated` 覆盖/续约/补注册/删除幂等/零事件、纯续约发 RENEW、过期清理不发复制事件）与扇出编排（`ReplicationFanout` 同实例合并、聚合拆批、退避重试保序、peer 队列满丢弃）——用可编程 fake `PeerReplicator` 与直接注入的 listener 收集器验证，不起服务。
- **进程内多实例集群语义测试**（registry-server，`*IT`，failsafe）：复用 phase-1 harness 模式（`TestGrpcPorts.reservePorts` 预分配端口 + N 个 `SpringApplicationBuilder` context + 真实 gRPC 内部端口），3 节点成员互见基础上驱动端到端扇出/收敛/重试；查询面经内部端口 `RegistryQueryService`（management.v1 只读事实查询）轮询断言最终一致。

形态↔模式映射不变（unit = surefire `*Test`、integration = failsafe `*IT`），dev-test.sh 无扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_write_replica_without_change_event_when_replicate_applied | 复制写 NEW/UPDATE 覆盖建真实租约（renewalTime=now），listener 零调用（防回环） | 单测 | pass |
| should_renew_replica_when_replicated_renew_hits | 复制 RENEW 命中副本 → 续约（renewalTime 刷新） | 单测 | pass |
| should_upsert_replica_when_replicated_renew_absent | 复制 RENEW 缺席（副本已被本地清理）→ 补注册重建租约 | 单测 | pass |
| should_apply_replicated_delete_idempotently | 复制 DELETE 缺席 → no-op；命中 → 删除（含 no-op 空分组不留空 holder） | 单测 | pass |
| should_emit_renew_event_when_heartbeat_pure_renewal | 心跳内容相同纯续约 → 发 RENEW 事件（不再零事件） | 单测 | pass |
| should_remove_without_event_when_lease_expires | TTL 过期清理 → 本地删除、快照重建，但零复制事件（清理不扇出） | 单测 | pass |
| should_reject_batch_when_source_node_id_empty | 入站 batch source 空 / instanceId 空 → ACK_FAIL(INVALID_ARGUMENT) | 单测 | pass |
| should_apply_without_event_when_batch_valid | 入站合法批量零事件应用；proto 方向 wire RENEW 映射（facade 用例） | 单测 | pass |
| should_merge_same_instance_changes_in_batch | 组批时同 (key, instanceId) 后到覆盖（连续 RENEW 压缩、RENEW 被 UPDATE 覆盖） | 单测 | pass |
| should_split_batches_on_limit_in_emission_order | drain 积压按产生序拆批、同 key 保持序 | 单测 | pass |
| should_retry_same_batch_with_backoff_when_send_fails | 投递失败 → 重发同批（保序）且退避递增（fake replicator 前 N 次失败） | 单测 | pass |
| should_drop_oldest_and_count_when_peer_queue_full | peer 队列满 → 丢最旧 + per-peer 计数（全局队列满分支与之同构，fanout 未启动时 onChange 直接吞掉——生产装配 start 后无窗口） | 单测 | pass |
| should_drop_batch_and_continue_when_permanently_rejected | 确定性失败（整批 ACK_FAIL / UNIMPLEMENTED / 认证不匹配）→ 丢批计数续投，不队头阻塞（code-review 修复新增） | 单测 | pass |
| should_reject_batch_when_service_key_blank | 入站 ServiceChange key 缺失/blank → ACK_FAIL(INVALID_ARGUMENT)，防空键服务残留（code-review 修复新增） | 单测 | pass |
| should_converge_on_all_peers_when_instance_written_on_one_node | 节点 A 经外部端口 register 覆盖/deregister → B/C 查询面最终一致 | IT | pass |
| should_keep_replicas_alive_on_peers_when_client_heartbeats_steadily | A 上 SDK 稳态心跳多个 TTL 周期（ttl=2s）→ B/C 副本持续续约不消失（RENEW 复制维持） | IT | pass |
| should_clean_instance_on_all_nodes_when_client_stops_heartbeating | 停止心跳超过 TTL → 三节点各自过期清理，查询面最终全部消失（无残留） | IT | pass |
| should_rebuild_replica_when_renew_arrives_after_local_expiry | 全节点本地过期后源节点心跳 upsert → 扇出 → peer 副本重建（分区恢复路径） | IT | pass |
| should_converge_after_peer_joins_when_peer_temporarily_absent | 2 节点先行 + 变更发生（对缺席 peer 小退避持续重试）→ 第 3 节点后起 ready → 重试投递命中后收敛 | IT | pass |
| should_expose_replication_metrics_when_fanout_active | 扇出发生后 MeterRegistry 中 sent/received/applied 指标存在且增长 | IT | pass |

实现期用例调整：删除「全局队列满丢当条」独立用例（与 peer 队列满丢弃同构，peer 队列用例已覆盖丢弃策略 + 计数语义；全局队列满仅在 onChange 风暴超聚合速度时发生，单测无法确定性构造且装配期 start 后无未启动窗口）。

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `bash java/scripts/dev-test.sh all` | `BUILD SUCCESS`，命令退出码 0 | 通过（用户确认 2026-10-21） |
| 2 | `mvn -f java/pom.xml verify -Dit.test=ReplicationFanoutIT -Dtest='NoUnitTestsForItRun' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` | 3 节点扇出 IT 6 用例全绿（最终一致 / RENEW 维持 / 猝死全清理 / 过期重建 / 缺席 peer 收敛 / 指标） | 通过（用户确认 2026-10-21） |
| 3 | 起 2 节点 registry（两个终端：`mvn -f java/pom.xml -pl server/artemis-registry-server spring-boot:run -Dspring-boot.run.arguments="--artemis.registry.cluster.members=node-a@127.0.0.1:9093,node-b@127.0.0.1:9094 --artemis.registry.cluster.self=node-a --artemis.registry.internal.port=9093 --server.port=18081"`，第二个换 self=node-b、port=9094、server.port=18082），节点 a 经 SDK/测试工具注册后 `curl -s localhost:18082/actuator/metrics/artemis.registry.replication.batches.sent | grep -o '"value":[0-9.]*'` | 复制 metrics 可抓取：节点 b 的 `batches.received` 计数 > 0（节点 a 的 `batches.sent{peer=node-b}` > 0） | 通过（用户确认 2026-10-21） |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑 | all | 全量（spotless + enforcer + 单测 + IT） | 全绿（修复 3 处后达成：applyReplicated no-op 空分组 holder 回收、commit RENEW 零快照重建、RegistryGrpcIT 过期断言对齐） |
| ⑧ 回归 | all | 全量（code-review 4 findings 修复后） | 全绿（BUILD SUCCESS，含 review 新增用例：permanent rejection 丢批续投、service key blank 拒绝） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.3 | 2026-10-21 | ⑨ 人工验证通过（3 项），验证清单结论回填 |
| 3.2 | 2026-10-21 | ⑦⑧⑨：review 修复用例（永久拒绝丢批 / key blank）与回归记录；验证清单就绪 |
| 3.1 | 2026-10-21 | ⑤ 首跑全绿回填；实现期用例调整登记（删全局队列满独立用例） |
| 3.0 | 2026-10-21 | review 修订：用例对齐原产品式副本租约语义（RENEW 续约/补注册、清理不扇出、稳态副本存活、猝死全清理、分区恢复重建）；删豁免/保护/转正用例 |
| 2.0 | 2026-10-21 | 测试设计定稿（单测 13 + IT 4） |
| 1.0 | 2026-10-21 | 开工骨架（① worktree 开工） |
