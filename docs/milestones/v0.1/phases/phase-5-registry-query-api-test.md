# Phase 5 测试：registry 只读事实查询 RPC

版本: 2.1    更新时间: 2026-10-18

## 测试方案

- **单测**（surefire `*Test`）：查询应用服务（服务列表/实例快照/租约明细/保护统计/配置状态与脱敏/排序）、`InstanceRegistry` 租约明细与 TTL 生产配置构造。
- **服务级集成测试**（failsafe `*IT`）：进程内起真实 registry 服务（随机端口），经 gRPC 注册真实实例后用真实客户端调用 `RegistryQueryService` 四个 RPC，覆盖认证、查询结果、脱敏与错误语义。
- 不涉及集群语义，无进程内多实例用例。
- 执行入口 `java/scripts/dev-test.sh all`；用例形态均为既有映射（单测 / `*IT`），**无需扩展 dev-test.sh**。

## 用例清单

### 单测：`RegistryQueryServiceImplTest`（application）

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_list_services_when_registered | 返回已注册服务及实例数，按 `(namespace, service)` 排序 | 单测 | passed |
| should_filter_by_namespace_when_listing_services | namespace 过滤生效；空白参数返回全部 | 单测 | passed |
| should_return_empty_when_service_not_registered | 未注册 service 返回空（列表/实例快照） | 单测 | passed |
| should_report_lease_age_and_remaining_ttl_when_lease_status | 单调钟推进后 age/remainingTtl 正确 | 单测 | passed |
| should_include_protection_stats_when_lease_status | leases 响应携带自我保护统计 | 单测 | passed |
| should_sort_leases_stably | 租约按 `(namespace, service, instanceId)` 排序 | 单测 | passed |
| should_report_source_static_flag_and_sort_when_config_status | 配置项含来源、静态标记、默认值、doc，按 key 升序 | 单测 | passed |
| should_mask_sensitive_config_when_config_status | 敏感键 value 与 default_value 均输出 `******`（含 accessKey 命名） | 单测 | passed |
| should_filter_leases_when_namespace_or_service_given | 租约查询按 namespace/service 过滤 | 单测 | passed |
| should_truncate_when_lease_details_exceed_limit | 超过 10000 条截断并标记 truncated | 单测 | passed |

### 单测：`InstanceRegistryTest` 增补

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_report_lease_details_when_registered | `leaseDetails()` 返回全部实例的 age/remainingTtl | 单测 | passed |
| should_apply_dynamic_ttl_when_configured | 生产构造经 SCF 属性读取 TTL，运行期改值生效 | 单测 | passed |

### 集成测试：`RegistryQueryIT`（server）

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_reject_query_when_unauthenticated | 无凭证调用查询 RPC 返回 UNAUTHENTICATED | 集成 | passed |
| should_list_services_when_instances_registered | gRPC 注册后 ListServices 返回服务与实例数 | 集成 | passed |
| should_return_instances_when_query_service | GetServiceInstances 返回注册实例事实 | 集成 | passed |
| should_return_empty_when_service_not_registered | 未注册 service 查询成功且为空 | 集成 | passed |
| should_report_lease_details_and_protection | GetLeaseStatus 返回租约明细与保护统计 | 集成 | passed |
| should_mask_password_when_query_config_status | GetConfigStatus 返回配置，密码 value 与默认值均脱敏 | 集成 | passed |
| should_return_invalid_argument_when_service_key_blank | 空白 namespace/service 返回 FAIL + INVALID_ARGUMENT | 集成 | passed |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | BUILD SUCCESS；common 8 / core 89 / server 22 / IT 20 | 通过 |
| 2 | `java -jar java/server/artemis-registry-server/target/artemis-registry-server-0.1.0-SNAPSHOT-exec.jar`，观察启动日志 | 出现 `Registered gRPC service: artemis.management.v1.RegistryQueryService` 与 gRPC 9090 监听 | 通过 |
| 3 | 另开终端（需 grpcurl；无则 `go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest`）：`grpcurl -plaintext -H 'authorization: Basic YXJ0ZW1pczphcnRlbWlz' -d '{}' localhost:9090 artemis.management.v1.RegistryQueryService/GetConfigStatus` | 返回配置项；`artemis.registry.auth.password` 的 `value` 与 `default_value` 均为 `******` | 通过 |
| 4 | 注册实例：`grpcurl -plaintext -H 'authorization: Basic YXJ0ZW1pczphcnRlbWlz' -d '{"services":[{"key":{"namespace":"default","service":"demo"},"instances":[{"instanceId":"i1","host":"10.0.0.1","port":8080,"weight":100}]}]}' localhost:9090 artemis.registry.v1.RegistryService/Register` | `status.ack` 为 `ACK_SUCCESS` | 通过 |
| 5 | `grpcurl -plaintext -H 'authorization: Basic YXJ0ZW1pczphcnRlbWlz' -d '{"namespace":"default"}' localhost:9090 artemis.management.v1.RegistryQueryService/ListServices` | 返回 `demo`，`instance_count=1` | 通过 |
| 6 | `grpcurl -plaintext -H 'authorization: Basic YXJ0ZW1pczphcnRlbWlz' -d '{"key":{"namespace":"default","service":"demo"}}' localhost:9090 artemis.management.v1.RegistryQueryService/GetServiceInstances` | 返回实例 `i1`（host/port 与注册一致） | 通过 |
| 7 | `grpcurl -plaintext -H 'authorization: Basic YXJ0ZW1pczphcnRlbWlz' -d '{}' localhost:9090 artemis.management.v1.RegistryQueryService/GetLeaseStatus` | `leases` 含 `default/demo/i1`，`age_ms`/`remaining_ttl_ms` 合理，`protection` 存在，`truncated=false` | 通过 |
| 8 | 无凭证调用：`grpcurl -plaintext -d '{}' localhost:9090 artemis.management.v1.RegistryQueryService/ListServices` | 返回 `UNAUTHENTICATED` | 通过 |
| 9 | Ctrl+C 停止服务 | 进程正常退出，无异常堆栈 | 通过 |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 4 | all | 全量（契约迁 management.v1 / client 不生成后回归） | BUILD SUCCESS（common 8 / core 89 / server 22 / IT 20） |
| 3 | all | 全量（删除冗余 LeaseTtlSource 后回归） | BUILD SUCCESS（common 8 / core 89 / server 22 / IT 20） |
| 2 | all | 全量（review 修复后回归） | BUILD SUCCESS（common 8 / core 89 / server 22 / IT 20） |
| 1 | all | 全量 | BUILD SUCCESS（common 8 / core 87 / server 22 / IT 20） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.1 | 2026-10-18 | review 修复：补租约过滤/截断与敏感默认值脱敏用例；用例名与实现对齐 |
| 2.0 | 2026-10-18 | 按 ADR-016 重写：phase-5 测试改为 registry 只读查询 RPC；管理面用例移交 phase-6 |
| 1.1 | 2026-10-18 | 原方案补摘除/恢复事件与同 key 定序用例 |
| 1.0 | 2026-10-18 | 原 Admin API 方案测试设计定稿 |
