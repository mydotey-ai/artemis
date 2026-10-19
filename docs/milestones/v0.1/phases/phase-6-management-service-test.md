# Phase 6 测试：management-service 骨架与内存态管理面

版本: 2.0    更新时间: 2026-10-19

## 测试方案

**形态**：

- **单测**（surefire `*Test`）：management-core 的摘除存储语义、有效视图组合、映射；management-server 的认证配置/过滤器、gRPC client 异常翻译、HTTP 错误映射。
- **进程内服务级集成测试**（failsafe `*IT`）：同一测试 JVM 内起**真实 registry-server**（`SpringApplication`，gRPC/HTTP/管理端口随机）与**真实 management-server**（`@SpringBootTest(webEnvironment=RANDOM_PORT)`，registry 地址经 `@DynamicPropertySource` 注入）；registry 侧经真实 gRPC 注册实例，management 侧经真实 HTTP + Basic 认证驱动 Admin/状态 API。不依赖外部进程/容器（guidelines §7）。

**覆盖面**：

- 有效视图：摘除前/后/恢复后的实例集与计数；`includeEjected` 展开。
- 摘除不改 registry 事实：同一用例内经 registry 只读查询确认实例仍在。
- 幂等：重复摘除保留首条记录；未摘除时恢复成功。
- 认证：无凭证 401；管理面到 registry 的 Basic 凭证生效（IT 能查询即证明）。
- 状态 API：租约明细/保护统计透传；敏感配置脱敏。
- 错误语义：空白路径参数 400；registry 不可用 503（单测覆盖适配器 + 异常映射）。

执行入口 `java/scripts/dev-test.sh all`（形态↔模式映射沿用既有定义，无需扩展脚本）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_eject_and_recover_store_record_when_operations | 存储层摘除写入、查询、恢复删除 | 单测 | 通过 |
| should_keep_first_record_when_ejecting_twice | 重复摘除幂等，保留首条原因与时间 | 单测 | 通过 |
| should_return_false_when_recovering_not_ejected | 恢复幂等，未摘除返回 false | 单测 | 通过 |
| should_list_records_by_service_when_multiple_instances | 按 service 查询记录并按 instanceId 排序；countByService 单次聚合 | 单测 | 通过 |
| should_filter_ejected_instances_when_get_service | 详情默认只返回有效实例 | 单测 | 通过 |
| should_include_ejected_instances_when_flag_set | `includeEjected=true` 返回全量并标注原因/时间 | 单测 | 通过 |
| should_return_zero_counts_when_service_not_registered | 未注册 service 返回 0 计数与空列表 | 单测 | 通过 |
| should_count_only_registered_ejections_when_get_service | 详情计数只统计当前注册实例 | 单测 | 通过 |
| should_list_services_with_fact_and_ejection_counts | 列表返回 registry 事实计数与摘除记录数 | 单测 | 通过 |
| should_not_touch_registry_when_ejecting_or_recovering | 摘除/恢复不调用 registry 写路径（端口仅查询） | 单测 | 通过 |
| should_passthrough_lease_status_when_requested | 状态 API 应用层透传租约/保护统计 | 单测 | 通过 |
| should_passthrough_config_status_when_requested | 状态 API 应用层透传配置状态 | 单测 | 通过 |
| should_map_instance_and_status_when_converting_proto | proto→domain：字段、metadata、UNSPECIFIED→UP、未知→UNKNOWN | 单测 | 通过 |
| should_map_lease_and_config_when_converting_proto | proto→domain：租约、保护统计、配置项 | 单测 | 通过 |
| should_map_service_key_to_proto_when_querying | domain→proto 请求键转换 | 单测 | 通过 |
| should_fall_back_to_default_when_credentials_blank | SCF 将空白凭证视为未设置并回落默认值 | 单测 | 通过 |
| should_default_auth_enabled_when_no_config | 默认启用、默认账户 | 单测 | 通过 |
| should_fall_back_to_default_when_endpoint_blank | SCF 空白端点视为未设置并回落默认值 | 单测 | 通过 |
| should_reject_request_when_no_credentials | HTTP 无凭证 401 | 单测 | 通过 |
| should_reject_request_when_credentials_wrong | HTTP 错误凭证 401 | 单测 | 通过 |
| should_pass_request_when_credentials_valid | 正确凭证放行 | 单测 | 通过 |
| should_pass_request_when_auth_disabled | 认证关闭放行 | 单测 | 通过 |
| should_map_unavailable_when_registry_down | gRPC 客户端把 UNAVAILABLE/DEADLINE_EXCEEDED 翻译为 REGISTRY_UNAVAILABLE | 单测 | 通过 |
| should_map_fail_status_when_registry_returns_fail | 响应 ACK_FAIL 翻译为 REGISTRY_ERROR | 单测 | 通过 |
| should_map_error_status_when_advice_handles | 异常→HTTP 400/502/503/500 映射 | 单测 | 通过 |
| should_require_auth_when_admin_api_called_without_credentials | IT：管理 API 无凭证 401、有凭证可访问 | 集成 | 通过 |
| should_list_services_from_registry_when_registered | IT：注册后服务列表可见（事实计数 + 摘除计数 0） | 集成 | 通过 |
| should_return_effective_view_when_instance_ejected | IT：摘除后详情默认视图 2→1，计数正确 | 集成 | 通过 |
| should_show_ejected_instance_when_include_ejected | IT：`includeEjected=true` 返回 2 条并标注被摘除者 | 集成 | 通过 |
| should_keep_registry_facts_when_instance_ejected | IT：摘除后 registry 查询仍返回 2 条（未改事实） | 集成 | 通过 |
| should_restore_effective_view_when_recovered | IT：恢复后详情回到 2 条 | 集成 | 通过 |
| should_be_idempotent_when_ejecting_twice_over_http | IT：重复摘除 200 且原因/时间不变 | 集成 | 通过 |
| should_return_400_when_path_params_blank | IT：空白 namespace/service/instanceId 拒绝 | 集成 | 通过 |
| should_keep_client_error_semantics_when_unknown_route_or_bad_param | IT：未知路径 404、参数类型错误 400（不被兜底 handler 吞成 500） | 集成 | 通过 |
| should_expose_lease_status_when_status_api_called | IT：状态 API 返回租约与保护统计 | 集成 | 通过 |
| should_mask_sensitive_config_when_config_status_api_called | IT：配置状态敏感项为 `******` | 集成 | 通过 |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | BUILD SUCCESS；management-core 19 单测、management-server 16 单测 + 11 IT 全绿 | 通过 |
| 2 | 构建并启动两服务：`mvn -f java/pom.xml -pl :artemis-registry-server,:artemis-management-server -am package -DskipTests`；终端 A：`java -jar java/server/artemis-registry-server/target/artemis-registry-server-0.1.0-SNAPSHOT-exec.jar`；终端 B：`java -jar java/server/artemis-management-server/target/artemis-management-server-0.1.0-SNAPSHOT-exec.jar` | 两进程分别起在 registry 9090/8080/9091、management 8081/9092；无启动异常 | 通过 |
| 3 | `curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/admin/v1/services`；再加 `-u artemis:artemis` | 无凭证 401；带凭证 200 | 通过 |
| 4 | `curl -s -u artemis:artemis 'localhost:8081/admin/v1/services'` | `{"services":[]}`（空注册表） | 通过 |
| 5 | 摘除幂等：`curl -s -u artemis:artemis -X POST -H 'Content-Type: application/json' -d '{"reason":"manual"}' localhost:8081/admin/v1/services/default/order-service/instances/i1/eject`，重复执行 | 均 200；两次 `ejectedAt` 相同、`reason` 保持 `manual` | 通过 |
| 6 | `curl -s -u artemis:artemis localhost:8081/admin/v1/status/config \| jq '.properties[] \| select(.key=="artemis.registry.auth.password")'` | `value` 与 `defaultValue` 均为 `******` | 通过 |
| 7 | `curl -s -u artemis:artemis localhost:8081/admin/v1/status/leases` | 含 `protection` 对象，`truncated=false`（空注册表 leases 为空） | 通过 |
| 8 | 停止 registry 进程后：`curl -s -o /dev/null -w '%{http_code}\n' -u artemis:artemis localhost:8081/admin/v1/status/leases` | 503；body `error=REGISTRY_UNAVAILABLE` | 通过 |
| 9 | 有效视图端到端证据：`mvn -f java/pom.xml -pl :artemis-management-server -am verify`，查看 `ManagementAdminIT` | 11 用例全绿（含摘除后有效视图 2→1、registry 事实不变、恢复回 2） | 通过 |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤ 首跑） | all | 全 reactor | 通过（BUILD SUCCESS，01:53 min） |
| 2（⑧ 回归） | all | 全 reactor（review 修复后） | 通过（BUILD SUCCESS，01:42 min） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.0 | 2026-10-19 | 测试方案与用例清单定稿（含真实 registry + management 进程内集成测试） |
| 1.0 | 2026-10-19 | 测试文档骨架 |
