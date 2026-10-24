# Phase 9 测试：发现侧 starter 与 all-in-one 打包

版本: 1.4    更新时间: 2026-10-24

## 测试方案

两类形态都落在既有映射内，`dev-test.sh` **无需扩展**：

| 形态 | 落点 | 模式 |
|---|---|---|
| starter 自动装配单测 | `spring-boot-starter-artemis-client` 的 `*Test`（`ApplicationContextRunner`，不起网络服务） | surefire（`unit`） |
| all-in-one 起停/端到端 IT | `artemis-dist` 的 `*IT`（同 JVM 起真实双上下文、真实 gRPC，端口随机） | failsafe（`integration`） |

- `artemis-dist` 显式声明 failsafe（同两 server 模块），`all=verify` 自动纳入；starter 已有 surefire + toolchains JDK 8 fork。
- starter 测试要验证真实生命周期与网络无关：地址用 `http://127.0.0.1:1`（不建连），discovery 侧通过 `subscribe` 后 close 的 `IllegalStateException` 断言关闭语义。
- all-in-one IT 复用既有 IT 的端口策略：gRPC/HTTP/管理端口用 `0` 随机，registry 内部 peer 端口预分配（产品内部端口不接受 0）；registry 默认认证开启，IT 带默认 `artemis/artemis` Basic 头。
- **v0.3 验收要点不重复造轮子**，由既有 IT + 本 phase 新增 AllInOneIT 覆盖：

| 验收要点 | 对应既有/新增测试 |
|---|---|
| 推送亚秒可见 | `DiscoverySubscriptionIT.should_push_increment_sub_second_on_stream`、`DiscoveryProjectionIT.should_serve_registry_writes_sub_second` |
| 断连自愈 | `DiscoveryClientIT.should_reconnect_after_stream_break_and_converge`、`should_fail_over_to_next_address_when_current_server_down`、`should_keep_last_known_instances_when_all_servers_down`、`DiscoveryProjectionIT.should_rebootstrap_and_cover_stale_after_registry_restart` |
| 多集群 union 查询 | `DiscoveryMultiClusterIT.should_union_query_across_clusters`（及 cluster 过滤/独立新鲜度用例） |
| all-in-one 单包起 | 本 phase `AllInOneIT`（双端口起停 + 注册→发现端到端） |

## 用例清单

### starter（`ArtemisClientAutoConfigurationTest`）

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_create_registry_client_when_registry_addresses_configured | 只配 registry 地址 → 仅 RegistryClient 创建且已 start | 单测 | pass |
| should_not_create_registry_client_when_registry_addresses_missing | 缺 registry 地址 → 不创建 RegistryClient（不再 fail-fast），上下文正常 | 单测 | pass |
| should_create_discovery_client_when_discovery_addresses_configured | 只配 discovery 地址 → 仅 DiscoveryClient 创建且已 start | 单测 | pass |
| should_create_both_clients_when_both_addresses_configured | 两侧地址都配 → 两 client 共存且互不影响 | 单测 | pass |
| should_not_create_any_client_when_no_addresses_configured | 两侧都未配 → 无任何 client bean，宿主正常启动 | 单测 | pass |
| should_not_create_clients_when_enabled_false | `artemis.client.enabled=false` → 两侧都不装配 | 单测 | pass |
| should_fail_fast_when_configured_address_invalid | 配了非法地址 → client 构造期 IllegalArgumentException（两侧同语义） | 单测 | pass |
| should_inject_boot_meter_registry_into_discovery_client | 宿主 MeterRegistry 存在 → 发现侧订阅指标写入该 registry 而非 global | 单测 | pass |
| should_inject_boot_meter_registry_into_registry_client | 宿主 MeterRegistry 存在 → 注册侧 gauge 写入该 registry 而非 global（code-review F8 恢复） | 单测 | pass |
| should_fallback_to_global_registry_when_no_meter_registry | 无宿主 MeterRegistry → 指标回落 globalRegistry（code-review F8 恢复） | 单测 | pass |
| should_close_both_clients_when_context_closed | `ContextClosedEvent` 后两 client 均不可用（register/subscribe 抛 closed） | 单测 | pass |
| should_allow_host_override_for_each_client | 宿主自定义 RegistryClient/DiscoveryClient → 对应自动装配让位，互不干扰 | 单测 | pass |
| should_allow_host_takeover_of_string_properties_bean | 宿主定义同名 `artemisClientStringProperties` → starter 桥让位，client 按名跟随接管 bean（⑨ 反馈新增） | 单测 | pass |
| should_bind_clients_to_starter_bridge_when_other_properties_bean_present | 宿主异名 StringProperties bean 共存 → client `@Qualifier` 绑 starter 桥不误注入（⑨ 反馈新增） | 单测 | pass |
| should_not_close_host_bean_with_same_bean_name | 宿主 bean 与 starter bean 同名（类型让位、名字归宿主）→ starter 关停监听不代关宿主 bean（第二轮 review R3 新增） | 单测 | pass |
| should_register_auto_configuration_in_both_boot_registries | `spring.factories` + `AutoConfiguration.imports` 双注册（既有） | 单测 | pass |
| should_resolve_relaxed_key_variants | starter 配置源键名归一化（既有） | 单测 | pass |

### all-in-one（`AllInOneIT`）

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_start_both_services_on_distinct_ports | 启动后两 gRPC 与两 HTTP 端口各自可用且互不相同 | IT | pass |
| should_serve_registered_instance_through_discovery | 经 registry 外部 gRPC 注册实例 → discovery 查询在超时内可见（证明 discovery 经网络消费同进程 registry） | IT | pass |
| should_stop_both_contexts_when_closed | `Node.close()` 后两上下文均 inactive，重复 close 幂等 | IT | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

- `./java/scripts/dev-test.sh all`（⑤ 首跑）：BUILD FAILURE → starter 1 例失败（meter 注入用例 clientId 键名误用 `artemis.client.id`，实际键 `artemis.client.clientId`），修正后 client 线单独 verify 全绿。
- `./java/scripts/dev-test.sh all`（⑤ 复跑）：BUILD SUCCESS——AllInOneIT 3/3、starter 12/12、全 reactor 回归绿；`artemis-dist` 产物 `target/artemis-dist-0.1.0-SNAPSHOT-exec.jar` 存在。
- `./java/scripts/dev-test.sh all`（⑧ 回归，code-review 修复后）：BUILD SUCCESS——starter 14/14（恢复 2 例 meter 用例）、AllInOneIT 3/3、全 reactor 回归绿。
- 单包冒烟（⑨ 初次记录修正）：干净目录 `java -jar artemis-dist-0.1.0-SNAPSHOT-exec.jar`——默认 7 端口全监听（registry grpc 9090 / http 8080 / mgmt 9091 / peer 9093，discovery grpc 9095 / http 8090 / mgmt 9096，mgmt/peer 绑 loopback 符合配置），两 actuator health 均 200，日志 0 ERROR；SIGTERM 后 **11 秒**优雅退出（JVM hook 按 discovery → registry 关停），端口全释放、进程无残留。初版记录的「即时退出」为误杀 nohup wrapper 所致假象，已按真实进程复测修正。
- 环境事件记录（⑨）：初版冒烟误杀 wrapper 导致 exec jar 残留进程占用 9090-9096 端口，污染了下一轮全量跑（registry-server GracefulShutdownIT EADDRINUSE 3 例失败）；清理进程后重跑该模块恢复全绿，确认为环境性失败而非代码缺陷。
- ⑨ 反馈修订后 ⑤ 重走：starter 新增 2 例（同名接管 / 异名共存按名绑定），SDK 线 16/16；全 reactor all 结果见回归记录。

人工验证（用户执行）：

- 第一轮（2026-10-24）：对 starter 装配语义提出修订意见——`artemisClientStringProperties` 应可让位（`@ConditionalOnMissingBean`）、client 注入应指定 bean 名避免多 bean 误注入。按回退规则 ④–⑨ 重走（修订记录见 phase 设计文档 1.3 版与第二轮 findings 处置）。
- 第二轮（2026-10-24）：修订后制品 review 通过。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor（code-review 修复后） | BUILD SUCCESS（starter 14/14、AllInOneIT 3/3） |
| 2 | all | 全 reactor（⑨ 反馈 StringProperties 让位语义修订后） | BUILD SUCCESS（starter 16/16、AllInOneIT 3/3；中途一轮 artemis-client 1 例失败为环境抖动，复跑恢复） |
| 3 | all | 全 reactor（第二轮 review R3/R4/R5 修复后） | BUILD SUCCESS（starter 17/17、AllInOneIT 3/3） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.4 | 2026-10-24 | ⑨ 反馈重走：+3 例（同名接管/异名共存/同名撞车不代关）共 17 例；第二轮 review 修复后回归轮次 3 全绿 |
| 1.3 | 2026-10-24 | ⑥⑧⑨：code-review 恢复 2 例 meter 用例（14 例）+ 回归全绿 + 单包冒烟（7 端口/健康 200/SIGTERM 优雅退出）记入验证记录 |
| 1.2 | 2026-10-24 | ⑤ 实现 + dev-test.sh all 全绿：starter 12/12、AllInOneIT 3/3，结果列更新 |
| 1.1 | 2026-10-24 | ③ 测试设计定稿：starter 12 例单测 + AllInOneIT 3 例；验收要点映射既有 IT，dev-test.sh 无需扩展 |
| 1.0 | 2026-10-24 | 开工骨架 |
