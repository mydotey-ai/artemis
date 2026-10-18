# Phase 3 测试：自我保护基础版 + 快照落盘回放 + readiness 门控

版本: 3.3    更新时间: 2026-10-18

## 测试方案

形态与覆盖面（guidelines §7——核心机制单测是 10 万实例正确性的根）：

- **单测**（surefire `*Test`，registry-core）：三机制的领域语义——保护窗口统计与阈值判定、保护态清理跳过与显式摘除不受保护、快照编码往返与损坏丢弃、回放零事件与 TTL 重置、readiness 时序、调度器生命周期（沿用 phase-2 调度器测试形态）。时间推进经注入 `MutableClock`/可控 `MonotonicClock`（零 sleep，phase-2 先例）。
- **集成测试**（failsafe `*IT`，registry-server，进程内起真实服务）：**重启回放端到端**——起 context 注册实例 → 手动触发快照落盘 → 关 context → 同快照目录起第二个 context → 经 gRPC 断言实例仍在且 TTL 从回放起算；readiness 正常路径（就绪后四操作可用）。

readiness 用例覆盖启动三分支的本地路径（设计 §4）：分支 ② 时效内快照回放 → ready、分支 ③ 无/损坏/超期快照 → 空表 ready；分支 ①（有已就绪 peer → 全量同步、失败定期重试不降级快照）在 v0.1 无集群成员概念、恒为假，v0.2 集群复制在位后由 bootstrap IT 补「有已就绪 peer 须同步成功才 ready（含 0 实例 peer）/ 同步失败定期重试不 ready」两向用例。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见 ⑤ 节，此处不复制）；本 phase 用例均为既有映射内形态（单测 + `*IT`）→ **无模式扩展**。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_not_protect_when_maxcount_below_min | 窗口续约量历史峰值未达 minCount（50），即使骤降也不进保护态 | 单测 | pass |
| should_enter_protection_when_window_renewals_below_threshold | maxCount 达标后窗口续约量跌破 85% 阈值进入保护态 | 单测 | pass |
| should_exit_protection_when_renewals_recover | 续约量恢复到阈值之上退出保护态 | 单测 | pass |
| should_slide_window_by_dropping_expired_buckets | 过期桶出窗后不计入当前窗口量（窗口滑动语义） | 单测 | pass |
| should_count_heartbeat_renewals_only | 心跳实例数计入窗口；register/update/deregister 不计 | 单测 | pass |
| should_skip_eviction_when_protected | 保护态下 evictExpired 整轮跳过（过期实例保留、返回 0） | 单测 | pass |
| should_allow_deregister_when_protected | 保护态下显式 deregister 照常摘除并发 DELETE 事件 | 单测 | pass |
| should_roundtrip_snapshot_bytes | domain 快照 → 编码（含落盘时间戳）→ 解码 → domain 等价（含 metadata/status/weight 归一后值） | 单测 | pass |
| should_discard_corrupt_snapshot_on_read | magic/长度/条目损坏的快照 read 返回 empty（不抛异常） | 单测 | pass |
| should_treat_stale_snapshot_as_invalid | 落盘时间戳距今超过 maxAge 的快照视为无效：不回放、返回 0、空表启动（WARN） | 单测 | pass |
| should_accept_future_timestamp_snapshot_within_clock_skew_tolerance | 时间戳超前 now ≤1min（墙钟回拨）宽容视为时效内、正常回放 | 单测 | pass |
| should_discard_far_future_timestamp_snapshot | 时间戳超前 now 超过回拨宽容（损坏/快进时钟）按超期丢弃、空表启动 | 单测 | pass |
| should_drop_invalid_entries_when_restoring_snapshot | 可解析但语义损坏的快照条目（空 instanceId、空 key）回放时丢弃、weight 归一，不直入注册表 | 单测 | pass |
| should_reject_corrupt_lengths_without_allocating | 损坏的条目数/条目长度在分配前被拒绝，抛 IAE 而非 OOME（D6） | 单测 | pass |
| should_not_catch_up_from_epoch_when_clock_origin_is_large | 单调钟原点为大值（长 uptime）时构造即对齐，首调不逐秒追赶（<500ms） | 单测 | pass |
| should_handle_negative_monotonic_seconds | 负单调秒（原点任意）下落桶/聚合正常，无负索引异常 | 单测 | pass |
| should_reset_statistics_for_test_isolation | 测试钩子 reset 清空窗口计数与历史峰值 | 单测 | pass |
| should_write_bare_relative_filename_without_parent | 裸文件名路径（无父目录）落盘不 NPE、可读回 | 单测 | pass |
| should_not_publish_torn_snapshot_under_concurrent_writes | 并发写同一快照文件时发布的始终是某个完整载荷，不撕裂 | 单测 | pass |
| should_write_atomically_and_read_back | FileRegistrySnapshotStore 写后读回一致；临时文件不残留 | 单测 | pass |
| should_restore_without_events | restore 重建租约与快照读，ChangeListener 零回调 | 单测 | pass |
| should_reset_lease_from_restore_time | 回放后 TTL 从回放时刻起算（回放前已「过期」的实例在回放后 TTL 内不摘） | 单测 | pass |
| should_expose_full_registry_for_snapshot | snapshots() 返回全部 service 的共享快照（含多 service） | 单测 | pass |
| should_reject_before_ready_and_allow_after | ReadinessGate 未 markReady 前 interceptor 语义拒绝（UNAVAILABLE），markReady 后放行 | 单测 | pass |
| should_be_ready_with_missing_or_corrupt_snapshot | 无快照/损坏/超期快照回放后仍 markReady（空表启动不死锁） | 单测 | pass |
| should_snapshot_on_schedule_and_close_cleanly | ScheduledSnapshotScheduler 周期触发 + close 释放（phase-2 调度器同形态） | 单测 | pass |
| should_restore_instances_after_server_restart | IT：注册 → 落盘 → 关 context → 同目录重起 → gRPC 查询/心跳断言实例在且租约有效 | 集成 | pass |
| should_serve_registry_after_ready | IT：就绪后四操作与心跳端到端可用（回归守护，含 readiness 正常路径） | 集成 | pass |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | BUILD SUCCESS；registry-core/server 单测与 IT 全绿（含本轮 9 个新增修复用例） | pass |
| 2 | `mvn -f java/pom.xml -pl server/artemis-registry-core -Dtest=RegistryServiceImplTest test` | 13 个用例通过（含未来时间戳双向时效、回放校验门） | pass |
| 3 | `grep -rn "@Value" java/server/*/src/main` | 无输出（生产装配不走 Spring 属性，配置纪律回归） | pass |
| 4 | `grep -rn "registry.v1.ServiceInstances" docs/milestones/v0.1/phases/phase-3-protect-snapshot.md` | 无输出（设计文档与实现同为 common.v1） | pass |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor（spotless + enforcer + 单测 + IT） | BUILD SUCCESS（2026-10-18；含 fix 后新增用例全绿） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.3 | 2026-10-18 | ⑨ 人工验证：4 项清单全部 pass |
| 3.2 | 2026-10-18 | ⑦ review 修复随附用例：未来时间戳双向时效（原用例改 ≤1min 宽容 + 新增远未来丢弃）、回放校验门、损坏长度不分配、长 uptime/负单调秒、reset 钩子、裸文件名路径、并发写不撕裂 |
| 3.1 | 2026-10-15 | ④⑤ 落地：用例清单结果全 pass（dev-test.sh all 首跑全绿，实现修正 SelfProtection 轮换逐秒采样）；补 update 的 gRPC 端到端（并入 SnapshotRestoreIT） |
| 3.0 | 2026-10-15 | 随设计 D5 二轮修订（三分支）：测试方案 readiness 段重写；新增时效用例（超期无效空表启动、未来时间戳宽容）；roundtrip 断言含落盘时间戳 |
| 2.1 | 2026-10-14 | 随设计 D5 修订：测试方案补 readiness peer 分支的 v0.1 恒空说明与 v0.2 用例预留 |
| 2.0 | 2026-10-14 | ③ 测试设计定稿（待人工 review） |
| 1.0 | 2026-10-14 | 测试骨架（开工） |
