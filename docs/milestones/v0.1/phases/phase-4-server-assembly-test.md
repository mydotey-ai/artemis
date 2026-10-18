# Phase 4 测试：配置规范化（SCF）+ registry-server 装配

版本: 1.9    更新时间: 2026-10-18

## 测试方案

**形态**：

- **单测**（surefire `*Test`）：配置门面（默认值/优先级/校验/动静语义）、认证组件（gRPC interceptor / HTTP filter）、指标绑定、环境变量键映射、统计计数。
- **集成测试**（failsafe `*IT`）：进程内起真实 Spring Boot 服务（随机端口），经真实 gRPC/HTTP 链路验证认证拒绝/放行、Prometheus 抓取、readiness、trace 观测产出；既有 IT 全部挂默认凭证后回归。

**执行入口**：`java/scripts/dev-test.sh all`。本 phase 未引入既有形态↔模式映射之外的新形态，脚本无需扩展。

**配置测试设施**：单测用 `MemoryMapConfigurationSource` + `ConfigurationManagers` 构造 manager，不读写真实环境变量/文件；集成测试用 Spring 属性控制框架端口，业务配置走默认值或测试专用 bean 覆盖。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_use_defaults_when_properties_empty | 自我保护窗口/阈值在无配置时使用类内默认值 | 单测 | pass |
| should_fall_back_to_default_factor_when_out_of_range | factor=2.0 越界回落默认 0.85 | 单测 | pass |
| should_keep_initial_value_when_static_property_changed | 静态项（ScfProperties）源变更后仍为启动值 | 单测 | pass |
| should_fall_back_to_default_when_value_out_of_range | 静态项数值越界回落默认值 | 单测 | pass |
| should_parse_cluster_members_when_valid_comma_separated | `host:port,host:port` 解析为成员列表 | 单测 | pass |
| should_return_empty_when_members_blank | 成员配置为空/空白时返回空表 | 单测 | pass |
| should_fail_fast_when_cluster_member_malformed | 非法成员条目启动失败并给出键名 | 单测 | pass |
| should_fail_fast_when_cluster_member_port_out_of_range | 成员端口越界启动失败且消息含配置键 | 单测 | pass |
| should_use_default_account_when_no_source_value | 认证默认账户 artemis/artemis | 单测 | pass |
| should_read_explicit_credentials_when_configured | 显式配置的用户名/密码生效 | 单测 | pass |
| should_disable_auth_when_explicitly_false | `auth.enabled=false` 关闭认证 | 单测 | pass |
| should_create_boolean_property_with_builtin_converter | boolean 静态 Property 使用 SCF 内置转换器（true/FALSE） | 单测 | pass |
| should_create_custom_typed_property_via_generic_factory | 泛型 `staticProperty` 支持自定义类型/转换器/过滤 | 单测 | pass |
| should_use_builtin_boolean_semantics_when_value_not_true | `auth.enabled=yes` 按 `Boolean.parseBoolean` 语义为 false | 单测 | pass |
| should_fail_fast_when_enabled_with_blank_username | 认证开启且用户名为空白时启动失败 | 单测 | pass |
| should_map_property_key_to_environment_name | `artemis.registry.lease.ttlMs` → `ARTEMIS_REGISTRY_LEASE_TTL_MS` | 单测 | pass |
| should_reload_and_raise_change_event_when_file_changes | 配置文件变更后动态 property 自动更新 | 单测 | pass |
| should_expose_spring_environment_properties_to_scf | 命令行/Spring 属性经 Environment source 进入 SCF | 单测 | pass |
| should_parse_bracketed_ipv6_member | `[fd00::1]:9092` 正确解析 | 单测 | pass |
| should_reject_bare_ipv6_member_without_brackets | 裸 IPv6 成员条目 fail-fast | 单测 | pass |
| should_schedule_using_configured_interval | 调度器经生产 StringProperties 路径读取配置周期 | 单测 | pass |
| should_allow_health_service_without_credentials | gRPC 健康服务无凭证放行（K8s probe） | 单测 | pass |
| should_allow_readyz_under_context_path | context-path 下 `/readyz` 白名单生效 | 单测 | pass |
| should_use_latest_thresholds_when_changed | 自我保护 factor/minCount 动态变更后下一次判定生效 | 单测 | pass |
| should_record_metrics_when_operations_occur | 心跳/清理/注册后实现类就地注册的 counters/实例数 gauge 正确（无 stats 接口） | 单测 | pass |
| should_register_protection_gauges_when_constructed | `SelfProtection` 构造即注册保护态三项 gauges 且数值正确 | 单测 | pass |
| should_reject_grpc_when_credentials_missing | gRPC 无 `authorization` metadata 返回 UNAUTHENTICATED | 单测 | pass |
| should_reject_grpc_when_credentials_wrong | 错误用户名/密码返回 UNAUTHENTICATED | 单测 | pass |
| should_allow_grpc_when_credentials_valid | 正确 Basic 凭证放行 | 单测 | pass |
| should_skip_grpc_auth_when_disabled | 认证关闭时无凭证放行 | 单测 | pass |
| should_reject_http_when_credentials_missing | 外部 HTTP 非白名单路径无凭证返回 401 + WWW-Authenticate | 单测 | pass |
| should_allow_http_when_credentials_valid | 正确 Basic 凭证放行 | 单测 | pass |
| should_allow_http_when_scheme_case_differs | `basic` 小写 scheme 按 RFC 7617 放行 | 单测 | pass |
| should_expose_readiness_as_up_when_ready | 就绪门已开时健康指示器 UP，未开时 OUT_OF_SERVICE | 单测 | pass |
| should_reject_grpc_call_when_no_credentials | 端到端 gRPC 无凭证被拒绝、带默认凭证成功 | 集成 | pass |
| should_reject_external_http_when_no_credentials | 外部端口受保护路径无凭证 401、带凭证非 401 | 集成 | pass |
| should_serve_readyz_without_credentials | `GET /readyz` 无凭证返回 200 | 集成 | pass |
| should_scrape_prometheus_metrics | 管理端口 `/actuator/prometheus` 含 `artemis_registry_instances` 等指标 | 集成 | pass |
| should_expose_readiness_health_endpoint | `/actuator/health/readiness` 返回 UP | 集成 | pass |
| should_produce_observation_for_grpc_call | gRPC 调用经 ObservationHandler 捕获到 server 侧观测（trace 接线生效） | 集成 | pass |
| should_keep_existing_flows_when_auth_enabled | 既有四操作/心跳/租约/快照 IT 挂默认凭证后全部通过 | 集成 | pass |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | BUILD SUCCESS（core 单测 / server 单测 / IT 全绿） | 待用户执行 |
| 2 | `mvn -f java/pom.xml -pl server/artemis-registry-server -am package -DskipTests` 后 `java -jar java/server/artemis-registry-server/target/artemis-registry-server-0.1.0-SNAPSHOT-exec.jar` | 启动成功；gRPC 9090、HTTP 8080、管理 9091 监听；控制台日志为 JSON 行 | 待用户执行 |
| 3 | `curl -s http://127.0.0.1:9091/actuator/prometheus \| grep artemis_registry_`；`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9091/actuator/health/readiness` | 输出 `artemis_registry_instances` 等指标；readiness 返回 200 | 待用户执行 |
| 4 | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/anything`；同 URL 加 `-u artemis:artemis`；`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/readyz` | 依次 401 / 404 / 200（认证拒绝/放行 + 探针白名单） | 待用户执行 |
| 5 | `ARTEMIS_REGISTRY_AUTH_ENABLED=false java -jar ...` 后重复第 4 项首条；再以 `java -jar ... --artemis.registry.auth.enabled=false` 启动重复验证 | 环境变量与命令行开关均生效，认证关闭时返回 404（内置 boolean 语义：仅 `true` 开启） | 待用户执行 |
| 6 | 启动前创建 `artemis.properties`；运行中修改文件内容 | 日志出现 `reloaded properties file`（动态项即时生效；静态项如 `auth.enabled` 按设计重启生效，SCF 会记 WARN） | 待用户执行 |
| 7 | 配置 `management.tracing.export.otlp.enabled=false`（默认）后启动；trace 产出由 `AuthObservabilityIT#should_produce_observation_for_grpc_call` 断言 | 启动不尝试外发 OTLP；观测链路有 gRPC server observation（自动测试覆盖） | 待用户执行 |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | D2 重构后（配置定义分散到使用方） | BUILD SUCCESS（common 5 / core 72 / server 22 / IT 13） |
| 2 | all | ⑨ 回退后重走 ⑤：TTL 测试值适配值域校验 | BUILD SUCCESS（common 5 / core 72 / server 22 / IT 13） |
| 3 | all | D3 重构后（Property 字段化 / ScfProperties 返回 Property / 全仓配置审计） | BUILD SUCCESS（common 4 / core 72 / server 21 / IT 13） |
| 4 | all | ScfProperties 泛型化 + boolean Property（SCF 内置转换器） | BUILD SUCCESS（common 6 / core 72 / server 21 / IT 13） |
| 5 | all | 监控就地定义（删除集中 RegistryMetrics/RegistryStats） | BUILD SUCCESS（common 6 / core 73 / server 20 / IT 13） |
| 6 | all | `RelaxedEnvironmentConfigurationSource` 移至 artemis-common | BUILD SUCCESS（common 7 / core 73 / server 19 / IT 13） |
| 7 | all | 第二轮 review 15 条修复后回归 | BUILD SUCCESS（common 8 / core 77 / server 22 / IT 13） |
| 8 | all | 最终回归（认证关闭 WARN 补正） | BUILD SUCCESS（common 8 / core 77 / server 22 / IT 13） |

（D2 重构前的实现/回归轮次见 dev-state 历史；重构后测试计数以此表为准。）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.9 | 2026-10-18 | 第二轮 review 修复用例（重载/Environment source/IPv6/健康服务/context-path/调度配置路径）；验证清单更新；回归第 7 轮 |
| 1.8 | 2026-10-18 | 环境变量 source 移至 common；回归第 6 轮 |
| 1.7 | 2026-10-18 | 监控就地定义：删除集中指标用例，新增 SelfProtection gauges/实现类 counters 用例；回归第 5 轮 |
| 1.6 | 2026-10-18 | ScfProperties 泛型化/boolean 用例；回归记录第 4 轮 |
| 1.5 | 2026-10-18 | D3 重构：Property 字段化与全仓配置审计；回归记录第 3 轮 |
| 1.4 | 2026-10-18 | D2 重构：配置用例随定义类分布（ScfProperties/StaticClusterMembers/AuthConfig/SelfProtection）；回归记录更新 |
| 1.3 | 2026-10-18 | 第三轮回归记录（smoke test 补正后）；可执行 jar 命令改 `-exec` classifier |
| 1.2 | 2026-10-18 | 结果列更新；⑧ 回归记录两轮；⑨ 验证清单（启动/端口/指标/认证/配置校验） |
| 1.1 | 2026-10-18 | 测试方案与用例清单定稿（配置/认证/可观测/回归，共 27 例） |
| 1.0 | 2026-10-18 | 开工骨架 |
