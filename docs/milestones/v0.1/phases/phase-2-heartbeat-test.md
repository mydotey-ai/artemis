# Phase 2 测试：注册模型四操作 + 租约过期清理（registry-core + gRPC 装配）

版本: 2.2    更新时间: 2026-10-14

## 测试方案

两类形态并存（映射与执行入口单一来源：本 phase 设计文档「自动化测试入口」节）：

1. **单测**（surefire `*Test`，`dev-test.sh unit|all`）：内核语义与层间映射，不起服务。
2. **服务级集成测试**（failsafe `*IT`，`dev-test.sh integration|all`）：进程内起真实 registry 服务（Spring Boot 官方 gRPC starter 装配，进程内传输或随机端口），经真实调用链路 + 生成 stub 驱动端到端用例——覆盖 wire 序列化、mapper 往返、gRPC 绑定、租约调度；不依赖外部进程与容器（guidelines §7）。本 phase 起 failsafe 正式绑定（parent pom + registry-server），integration 模式首次真正有覆盖。

确定性设施（零 sleep）：

- **可控时钟**：`Clock` 以测试内可变实现注入（单测与 IT 通用），手动推进验证续约/TTL。
- **事件收集器**：`ChangeListener` 注入 `List<InstanceChange>` 收集，断言事件 `type`/`key`/`instance` 与时序。
- **直接触发清理**（单测）：`InstanceRegistry.evictExpired()` 与实现同包，测试直接调用，不依赖调度线程。
- **IT 的确定性**：服务与测试同 JVM，测试持有注入的 `Clock` 与事件收集器引用做确定性断言；调度触发的过期用例以生产默认 1s 清理周期 + **测试侧有界轮询（带超时）**观察结果（生产代码不含轮询；测试时钟经 registry-core test-jar 的 `MutableClock` 注入，与单测共用同一设施）。
- IT 依赖：registry-server 测试作用域的官方 gRPC 测试支持（`org.springframework.boot:spring-boot-grpc-test`）；stub 客户端经 registry-core 传递的 grpc-stub。

覆盖面：四操作语义与事件派生、心跳对账（续约/缺席补注册/内容变化覆盖/无集合 diff）、租约续约与过期清理、校验归一与部分失败、mapper 往返、响应字段（ack/明细/seq/时间）、端到端 gRPC 链路。

## 用例清单

### 单测（surefire `*Test`，registry-core）

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_emit_new_and_create_lease_when_register_absent | register 全新实例：写入 + 建租约 + NEW 事件 | 单测 | pass |
| should_emit_new_when_register_existing_instance | register 已存在实例：无条件覆盖且仍发 NEW | 单测 | pass |
| should_emit_delete_and_remove_when_deregister_present | deregister 命中：摘除 + DELETE 事件 | 单测 | pass |
| should_return_empty_when_deregister_absent | deregister 缺席（聚合）：返回空列表、无事件、无副作用 | 单测 | pass |
| should_ack_success_when_deregister_absent | deregister 缺席（应用服务）：意图幂等 ack=SUCCESS | 单测 | pass |
| should_emit_update_when_update_hit | update 命中：全量覆盖 + UPDATE 事件 | 单测 | pass |
| should_emit_new_and_create_lease_when_update_absent | update 缺席：自动注册 + 建租约 + NEW 事件 | 单测 | pass |
| should_renew_without_event_when_heartbeat_content_unchanged | 心跳内容不变：仅续约、零事件（稳态零复制流量前提） | 单测 | pass |
| should_emit_new_when_heartbeat_instance_absent | 心跳含本地未注册实例：缺席补注册 + NEW | 单测 | pass |
| should_emit_update_and_overwrite_when_heartbeat_content_changed | 心跳上报内容与存储不同：覆盖 + UPDATE（update 丢失自愈路径） | 单测 | pass |
| should_not_remove_absent_instances_when_heartbeat_partial | 心跳只报部分实例：不做集合 diff，未上报实例仍在（等 TTL） | 单测 | pass |
| should_defer_expiry_when_lease_renewed | 续约推后过期时刻：TTL 内多次心跳后仍在，超过续约点 + TTL 才过期 | 单测 | pass |
| should_emit_delete_and_remove_when_lease_expired | 过期清理：超 TTL 实例被摘 + DELETE 事件 | 单测 | pass |
| should_not_evict_when_within_ttl | TTL 边界内不摘除（now - renewal < ttl） | 单测 | pass |
| should_use_latest_ttl_when_config_changes | 动态 TTL：清理时读取最新值（缩短 TTL 后立即生效） | 单测 | pass |
| should_remove_service_when_last_instance_evicted | 最后一个实例被摘后空 service 回收（无残留） | 单测 | pass |
| should_fail_group_and_skip_when_service_key_invalid | namespace/service 为空：该分组整体拒绝、ack=FAIL、不写入 | 单测 | pass |
| should_report_instance_error_when_instance_id_empty | instanceId 为空：逐实例 INVALID_ARGUMENT 明细、ack=FAIL、其余实例照常生效 | 单测 | pass |
| should_normalize_weight_when_not_positive | weight ≤ 0 归一 100（存储与快照为归一值，不判失败） | 单测 | pass |
| should_echo_seq_and_server_time_when_heartbeat | 心跳结果回填 seq + 应用层 `serverTimeMs` 取注入时钟 | 单测 | pass |
| should_return_shared_snapshot_when_get_instances | 读快照：多次读同一不可变引用；未注册 key 返回 empty | 单测 | pass |
| should_not_rebuild_snapshot_when_pure_renewal | 纯续约不重建快照（读到的引用不变）——稳态零分配 | 单测 | pass |
| should_map_proto_to_domain_when_mapper_round_trip | mapper 往返：proto 请求 → 领域请求 → 应用服务 → 领域响应 → proto 响应字段一致（单一 mapper 双向覆盖） | 单测 | pass |
| should_map_same_name_classes_across_packages_when_mapping | 同名类按包转换：`proto.Instance` → `domain.Instance` 字段全量一致（dto 只出现在 server 接入层与 application 转换边界） | 单测 | pass |
| should_map_unrecognized_status_to_unknown_when_mapper_maps_enum | 未知枚举值（`UNRECOGNIZED`）→ 领域 `UNKNOWN` 哨兵 | 单测 | pass |
| should_keep_proto_types_out_of_domain_when_mapping | 结构断言：domain 不含 proto 类型（dto 只出现在 server 接入层与 application 转换边界；分层纪律，编译期即可，另以测试守护边界） | 单测 | pass |
| should_map_seq_when_heartbeat_request_mapped | mapper：HeartbeatRequest 的 seq 原样映射 | 单测 | pass |
| should_map_response_fields_when_mapping_to_proto | mapper toProto：领域响应字段全量映射到 proto 响应 | 单测 | pass |
| should_omit_error_when_ack_success | mapper：ACK_SUCCESS 响应不携带 error 字段 | 单测 | pass |
| should_evict_when_ttl_source_changes_and_clock_advances | 端口注入：`LeaseTtlSource` 实现变更（动态 TTL）经注入端口即时生效于清理判定 | 单测 | pass |
| should_reject_duplicate_start_when_started_twice | 调度器生命周期：重复 `start()` 快速失败（防双份周期任务静默叠加） | 单测 | pass |
| should_continue_scheduling_when_round_throws_error | 调度器兜底：单轮抛 Error 后调度继续（scheduleWithFixedDelay 语义防静默死亡） | 单测 | pass |
| should_keep_last_event_consistent_with_final_state_when_concurrent_ops_on_same_key | 并发定序（D13）：同 key 并发 register/deregister 各 200 轮，最后一事件类型与存储终态一致（在场 ≠ DELETE；缺席 = DELETE），无幻影终态事件 | 单测 | pass |
| should_keep_storage_invariants_when_listener_throws | listener 异常隔离（D13 提交尾）：首事件抛异常不逃逸，批内剩余事件照常派发、写入/快照/空 holder 回收不受影响 | 单测 | pass |
| should_expose_consistent_snapshot_in_listener_when_last_instance_removed | 锁内派发一致性：DELETE 派发期间（锁内回调）回读快照已不含被摘实例 | 单测 | pass |
| should_expire_by_monotonic_clock_when_wall_clock_steps_back | 两域时钟区分（D14 回归守护）：壁挂后跳 60s 租约仍按单调钟过期；serverTimeMs 取壁挂值且异于单调值 | 单测 | pass |

### 集成测试（failsafe `*IT`，registry-server）

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_register_then_observe_when_serving_grpc | 起服务后经真实 stub `register`：响应 ack=SUCCESS，服务侧快照含该实例 | 集成 | pass |
| should_heartbeat_renew_and_upsert_when_grpc_bidi_stream | bidi 流上发多条心跳（含缺席实例）：seq 回填、缺席补注册、续约使租约推后 | 集成 | pass |
| should_deregister_idempotently_over_grpc | 经真实 stub 对缺席目标 `deregister`：幂等成功、无事件 | 集成 | pass |
| should_report_invalid_argument_over_grpc_when_instance_id_empty | 经真实 stub 上报空 instanceId：ack=FAIL + INVALID_ARGUMENT 明细 | 集成 | pass |
| should_evict_and_observe_when_ttl_elapses | 注册后推进时钟超 TTL（短清理周期）：实例被摘 + DELETE 事件 + 快照更新 | 集成 | pass |
| should_expose_server_time_when_heartbeat_over_grpc | 心跳响应 `server_time_ms` = 注入时钟（服务端时间基准经 wire 传递） | 集成 | pass |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `bash java/scripts/dev-test.sh all` | 全 reactor BUILD SUCCESS：registry-core 37 单测（聚合 21 + 应用服务 6 + mapper 7 + 调度器 2 + proto 1）+ registry-server 1 单测 + 6 IT（failsafe） | ✅ 通过（2026-10-14，用户执行） |
| 2 | `bash java/scripts/dev-test.sh integration` | failsafe 6 IT 单独跑通（integration 模式独立可用） | ✅ 通过（2026-10-14） |
| 3 | `bash java/scripts/dev-test.sh unit` | surefire 单测快速通过 | ✅ 通过（2026-10-14） |
| 4 | （可选）`mvn -f java/pom.xml -pl artemis-registry-server -am spring-boot:run` 后 `grpcurl -plaintext -import-path proto -proto registry/v1/registry.proto -proto common/v1/model.proto -d '{"services":[{"key":{"namespace":"default","service":"manual-check"},"instances":[{"instanceId":"m1","host":"127.0.0.1","port":8080}]}]}' localhost:9090 artemis.registry.v1.RegistryService/Register` | 返回 `status` 对象且不含 `ACK_FAIL` 与错误明细（服务端未启用 reflection，grpcurl 须带 `-proto`；`ACK_SUCCESS=0` 为零值枚举，protojson 省略 `ack` 字段）；Ctrl+C 停服 | 跳过（可选项，自动化 1–3 已覆盖同链路） |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all（dev-test.sh） | 全 reactor：spotless + enforcer + checkstyle + surefire 31 单测（registry-core）+ failsafe 6 IT（registry-server）+ 既有模块 | ✅ BUILD SUCCESS（2026-10-13） |
| 2 | all（dev-test.sh） | ⑦ fix 后回归：同上全量（含修复后的续约验证 IT） | ✅ BUILD SUCCESS（2026-10-13） |
| 3 | all（dev-test.sh） | ⑨ 设计反馈（D12 领域端口）回退重实现后 ⑤ 首跑：全量（31 单测 + 6 IT） | ✅ BUILD SUCCESS（2026-10-14） |
| 4 | all（dev-test.sh） | 第 2 轮 ⑦ fix（15 findings）后 ⑧ 回归：全量（33 单测〔+调度器 2〕+ 6 IT，spotless apply 后） | ✅ BUILD SUCCESS（2026-10-14，failsafe 报告 08:40 tests=6 failures=0） |
| 5 | all（dev-test.sh） | D13+D14 实现后 ⑤ 首跑：全量（34 单测 + 6 IT） | ✅ BUILD SUCCESS（2026-10-14） |
| 6 | all（dev-test.sh） | 第 3 轮 ⑦ fix（15 findings）后 ⑧ 回归：全量（37 单测〔+隔离/一致/两域时钟 3〕+ 6 IT） | ✅ BUILD SUCCESS（2026-10-14，failsafe tests=6 failures=0） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.2 | 2026-10-14 | 第 3 轮 ⑦ fix 用例：listener 异常隔离、锁内快照一致、两域时钟区分（D14 守护）；计数 37 |
| 2.1 | 2026-10-14 | ⑤/⑧ 回归记录补第 3/4 轮（D12 回退重实现首跑 + 第 2 轮 fix 回归，均全绿） |
| 2.0 | 2026-10-14 | ⑨ 回退后第 2 轮 ⑦ fix：用例清单与代码对齐（幽灵用例更正为缺席 deregister 两用例、补 mapper 3 用例、补调度器 2 用例）；IT 时钟改用 test-jar MutableClock；「短 cleanupInterval」措辞更正为生产默认 1s |
| 1.9 | 2026-10-14 | ⑨ 验证清单就绪：第 4 条修正——服务端无 reflection，grpcurl 补 `-import-path`/`-proto` 参数；预期结果按零值枚举省略改写 |
| 1.8 | 2026-10-13 | ⑦ fix 后回归第 2 轮全绿；续约验证用例改为真实语义（t=60 续约 → t=110 仍在 → t=161 摘） |
| 1.7 | 2026-10-13 | ⑤ 测试完成：31 单测 + 6 IT 全绿（dev-test.sh all 首次真正覆盖 integration 模式），用例结果与回归记录回填 |
| 1.6 | 2026-10-13 | ④ 实现期坐标定案：IT 依赖更正为 `org.springframework.boot:spring-boot-grpc-test` |
| 1.5 | 2026-10-13 | code-review 修复：标题「最小 gRPC 装配」统一为「gRPC 装配」（随 plan v1.7 定案）；IT 依赖坐标更正为 `org.springframework.grpc:spring-grpc-test` |
| 1.4 | 2026-10-13 | 四次 review 修订：随 DDD 四层对齐——分层守护用例改为「domain 不含 proto」（dto 边界 = server 接入层 + application 转换边界），mapper 归属措辞同步 |
| 1.3 | 2026-10-12 | ③ 设计 review 三次修订：mapper 收敛为单一 proto↔domain（同名类按包转换）；补领域端口注入（LeaseTtlSource 动态生效）用例 |
| 1.2 | 2026-10-12 | ③ 设计 review 二次修订：IT 改用 Spring Boot 官方 gRPC（测试支持 starter）；单测补「三层同名类按包转换」用例；mapper 往返用例改为两段 mapper |
| 1.1 | 2026-10-12 | ③ 设计 review 修订：补服务级集成测试（6 用例，failsafe *IT，进程内起真实 gRPC 服务）；单测补 mapper 往返与分层纪律用例；测试方案补两类形态与确定性设施 |
| 1.0 | 2026-10-12 | ③ 测试设计定稿（21 用例，全单测；可控时钟 + 事件收集器） |
