# Phase 4 测试：自我保护 2.0（按服务粒度）

版本: 3.4    更新时间: 2026-10-21

## 测试方案

**形态**：单测覆盖双层窗口、状态机、回落策略、指标与查询；服务级 IT 验证真实 gRPC/状态 API；3 节点进程内集群 IT 验证各节点独立判定、指标标签与超时退出。均为既有 `dev-test.sh` 形态（`*Test` → unit，`*IT` → integration），**不新增测试形态，脚本无需扩展**。

**覆盖面**：

1. **双层窗口（单测）**：节点级/服务级独立峰值；服务级未达 `minCount` 时回落节点级兜底；服务级达门槛后权威判定（节点级保护中仍可按服务清理）；显式状态迁移与 `protectingForMs`。
2. **回落策略（单测）**：节点健康时服务窗口 `maxCountResetMs` 后回落并退出保护；节点级保护中不回落；节点级自身安全态回落；回落后续约恢复/再次骤降可重新进入保护。
3. **指标（单测）**：服务级 gauge 仅在 PROTECTING 期间存在、退出即移除；保护中服务数、迁移计数、节点级三个 gauge；窗口回收同步移除 gauge。
4. **编排（单测）**：多服务心跳分别计数；显式操作/复制/回放不计；清理先评估后摘除；`deregister` 不受保护影响；服务消失后窗口回收。
5. **查询与映射（单测）**：默认仅保护中服务、`include_normal`、namespace/service 过滤、排序、上限截断、节点级统计；registry/management 两侧 proto 映射与 management 透传。
6. **服务级 IT**：真实 registry 进程内起服，经 `GetSelfProtectionStatus` 验证服务级保护态、节点级统计与过滤。
7. **集群语义 IT**：3 节点进程内集群——A 的心跳只落 node-0、B 只落 node-1，另以高续约服务 C 保持各节点级健康；续约停止后 node-0 只保护 A、node-1 只保护 B、node-2 均不保护且副本正常过期；指标只含本地服务标签；开启短 `maxCountResetMs` 时 node-0 的 A 保护超时退出、陈旧租约被清理。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_isolate_service_windows_when_one_service_renewals_drop | A 续约骤降、B 持续续约：仅 A 进入服务级保护 | 单测 | pass |
| should_keep_max_count_per_service_when_windows_differ | 各服务峰值独立（A=100、B=50），窗口滑出后各自判定 | 单测 | pass |
| should_fall_back_to_node_protection_when_service_below_min_count | 服务峰值未达 `minCount`：节点级保护中时仍受保护，`node_fallback=true` | 单测 | pass |
| should_fall_back_to_node_protection_when_service_has_no_window | 无本地心跳窗口（副本）：节点级保护中时受保护 | 单测 | pass |
| should_use_service_state_when_node_protects_but_service_healthy | 节点级保护中、服务级续约正常：服务级权威，`node_fallback=false` | 单测 | pass |
| should_enter_and_exit_protecting_state_with_since_duration | 显式状态迁移、`protectingForMs` 与续约恢复退出保护 | 单测 | pass |
| should_reset_service_max_count_when_node_healthy_after_reset_interval | 节点健康、服务持续低续约：超时回落并退出保护 | 单测 | pass |
| should_not_reset_service_max_count_when_node_protecting | 节点级保护中：服务窗口不回落，保护持续 | 单测 | pass |
| should_reset_node_max_count_only_when_safe | 节点级窗口沿用安全态回落语义 | 单测 | pass |
| should_not_reset_node_peak_before_protection_when_drop_at_reset_boundary | 回落边界骤降：先判定保护，不改写峰值（对齐原产品顺序） | 单测 | pass |
| should_report_current_window_renewals_between_evaluations | 查询只读当前窗口求和，不依赖评估轮 | 单测 | pass |
| should_keep_window_when_renewal_arrives_after_absent_mark | 缺席标记后并发续约：按最后续约重新计时，不丢窗口 | 单测 | pass |
| should_reenter_protection_when_renewals_drop_again_after_recovery | 回落/恢复后再次骤降可重新进入保护 | 单测 | pass |
| should_slide_window_per_service_when_renewals_age_out | 分桶按秒滑出、峰值只增不减（每服务） | 单测 | pass |
| should_not_protect_when_maxcount_below_min | 峰值未达 `minCount` 且节点级不保护时不保护 | 单测 | pass |
| should_use_latest_thresholds_when_changed | 动态修改 factor/minCount 对节点级与服务级立即生效 | 单测 | pass |
| should_rebase_peak_when_window_changed | 窗口缩短/增长时以新口径当前量重建基线，不误触发保护 | 单测 | pass |
| should_register_service_protection_gauge_only_while_protecting | 进入保护注册 `service.protection{namespace,service}`，退出移除；保护中计数与迁移计数正确 | 单测 | pass |
| should_report_node_gauges_with_v01_names | 节点级 `protection`/`renewals`/`max` 三个 gauge 数值正确 | 单测 | pass |
| should_remove_gauge_when_service_window_reclaimed | 服务消失后窗口与 gauge 一并回收 | 单测 | pass |
| should_keep_window_when_service_still_live | 存活服务窗口与统计不被回收 | 单测 | pass |
| should_report_statuses_sorted_and_filtered | 服务状态按 `(namespace, service)` 排序并支持过滤 | 单测 | pass |
| should_return_synthetic_status_when_filtered_service_has_no_window | 指定 namespace+service 无本地窗口时返回零计数兜底条目；仅 service 时返回空 | 单测 | pass |
| should_reset_all_windows_for_test_isolation | `reset()` 清空全部窗口/状态/gauge | 单测 | pass |
| should_handle_negative_monotonic_seconds / should_not_catch_up_from_epoch_when_clock_origin_is_large | 单调钟负值与超大原点下的窗口/推进边界 | 单测 | pass |
| should_use_defaults_when_properties_empty / should_fall_back_to_default_factor_when_out_of_range | 配置默认值与越界回落 | 单测 | pass |
| should_count_heartbeat_renewals_only | register/update/deregister 不计续约（复制入站不经过心跳入口，由 `InstanceRegistryTest` 覆盖） | 单测 | pass |
| should_count_heartbeat_renewals_per_service_when_multi_service_heartbeat | 单次心跳含多服务，按服务分别累计实例数、节点级累计总量 | 单测 | pass |
| should_skip_eviction_when_protected | 节点级保护中跳过过期清理，退出后照常清理 | 单测 | pass |
| should_skip_only_protected_service_when_evicting | A 服务级保护、B 低于门槛且节点健康：只保留 A 的租约，B 过期 | 单测 | pass |
| should_allow_deregister_when_protected | 保护态不拦截显式 deregister | 单测 | pass |
| should_return_only_protecting_services_by_default | 查询默认只返回 PROTECTING 服务 | 单测 | pass |
| should_include_normal_when_requested | `include_normal=true` 返回未保护服务 | 单测 | pass |
| should_return_service_status_when_filtered_even_if_normal | 指定 service 时无论状态均返回该服务 | 单测 | pass |
| should_truncate_protection_status_when_limit_exceeded | 超过上限标记 `truncated` | 单测 | pass |
| should_include_node_protection_stats_when_lease_status | `GetLeaseStatus` 携带节点级窗口统计 | 单测 | pass |
| should_filter_leases_when_namespace_or_service_given | 租约明细按 namespace/service 过滤 | 单测 | pass |
| should_map_self_protection_status_to_proto | registry 侧 mapper 映射新 RPC（含 `node_fallback`） | 单测 | pass |
| should_map_self_protection_status_when_converting_proto | management 侧 mapper 反向映射（含 `node_fallback`） | 单测 | pass |
| should_passthrough_self_protection_status_when_requested | management 服务透传保护状态查询 | 单测 | pass |
| should_send_filters_and_map_self_protection_status_when_requested | management gRPC client 请求组装（namespace/service/includeNormal）与响应映射 | 单测 | pass |
| should_report_per_service_protection_when_querying | 真实 gRPC 查询返回服务级保护态/节点统计/过滤 | 集成 | pass |
| should_expose_self_protection_status_when_status_api_called | 真实 Admin HTTP 端点：`includeNormal` 参数、默认仅保护中、namespace+service 过滤 | 集成 | pass |
| should_judge_protection_independently_per_node_when_renewals_drop | 3 节点：node-0 只保护 A、node-1 只保护 B、node-2 均不保护且副本过期；各节点 gauge 只含本地服务标签 | 集成 | pass |
| should_exit_protection_and_expire_stale_leases_when_reset_interval_elapsed | 3 节点：短 `maxCountResetMs` 下 node-0 的 A 保护超时退出、陈旧租约清理 | 集成 | pass |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | `BUILD SUCCESS`；registry-server 45 IT、management-server 13 IT，失败 0 | 通过 |
| 2 | `cat java/server/artemis-registry-server/target/failsafe-reports/org.mydotey.ai.artemis.registry.server.RegistryQueryIT.txt java/server/artemis-registry-server/target/failsafe-reports/org.mydotey.ai.artemis.registry.server.SelfProtectionClusterIT.txt java/server/artemis-management-server/target/failsafe-reports/org.mydotey.ai.artemis.management.server.ManagementAdminIT.txt` | `RegistryQueryIT` 7、`SelfProtectionClusterIT` 2、`ManagementAdminIT` 13，Failures/Errors 均 0 | 通过 |
| 3 | 起 registry（单节点，`--artemis.registry.selfProtection.minCount=2 --artemis.registry.selfProtection.windowSeconds=1 --artemis.registry.lease.evictionIntervalMs=1000`）与 management（`--artemis.management.registry.endpoint=localhost:{registry 内部端口}`），产生心跳后停止续约；`curl -u artemis:artemis 'localhost:8081/admin/v1/status/protection?namespace={ns}&includeNormal=true'` | 响应含 `trackedServices/protectingServices/services/node`；保护中服务 `protecting=true` 或 `nodeFallback=true`；`/admin/v1/status/leases` 中该服务租约未被摘除 | 通过 |
| 4 | `curl -s -u artemis:artemis localhost:9092/actuator/prometheus \| grep artemis.registry.self` | 节点级 `protection/renewals/max` 三个 gauge；保护中服务 `service.protection{namespace,service}`；`service.protection.count` 与 `transitions{to=protecting\|normal}` 可见 | 通过 |
| 5 | 查看 registry JSON 日志中 `self-protection entered/exited` | 日志含 `namespace/service/windowRenewals/maxCount`，迁移方向与 API/指标一致，无异常堆栈 | 通过 |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤-1 | all | 全量 verify（spotless + enforcer + unit + IT） | 全绿（registry-server 45 IT 含本 phase 3 个保护 IT） |
| ⑧-1 | all | 全量 verify（review 修复后回归） | BUILD SUCCESS；registry-server 45 IT、management-server 13 IT，失败 0 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.4 | 2026-10-21 | ⑨ 人工验证完成：5 项全部通过 |
| 3.3 | 2026-10-21 | ⑧ 回归记录 + ⑨ 人工验证清单（自动化结果复核、API/指标/日志点检） |
| 3.2 | 2026-10-21 | ⑦ 回填：用例名与实际测试对齐；新增回落边界、动态窗口、只读快照、回收竞态、多服务心跳、client/Admin 端点用例 |
| 3.1 | 2026-10-21 | ⑤ 回填：用例结果与首轮 dev-test.sh all 记录 |
| 3.0 | 2026-10-21 | review 修订：双层/状态机/回落/有界指标/独立 API/集群独立与超时退出用例 |
| 2.0 | 2026-10-21 | ③ 测试设计：按服务窗口/编排/查询/集群语义用例清单 |
| 1.0 | 2026-10-21 | 测试设计定稿 |
