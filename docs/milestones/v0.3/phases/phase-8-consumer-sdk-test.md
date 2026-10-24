# Phase 8 测试：消费方 SDK

版本: 1.6    更新时间: 2026-10-24

## 测试方案

形态：**单测**（surefire `*Test`，JDK 8 toolchain fork）+ **进程内真实协议集成测试**（failsafe `*IT`）。

- **SDK 侧 IT**：在 `artemis-client` 测试代码内起 **FakeDiscoveryServer**——真实 gRPC（`ServerBuilder.forPort(0)`，实现 `DiscoveryQueryService` / `DiscoveryReconciliationService` / `DiscoverySubscriptionService` 的 `ImplBase`，可编程数据、主动断流、失败/拒绝）+ **真实 HTTP `/cluster/nodes`**（JDK `com.sun.net.httpserver.HttpServer`，返回 `DiscoveryNodesResponse` protobuf bytes，可启停/改端口以驱动换址）。不依赖服务端模块（enforcer 边界）与外部进程；此形态与既有注册侧 `FakeRegistryServer` + `HttpNodeListFetcherTest` 一致。
- **服务端 IT**：discovery-server 既有 failsafe 绑定下新增节点目录 HTTP IT（进程内起真实 discovery context，验证 readiness/认证/protobuf 响应），与 phase-3–7 的 `Discovery*IT` 同形态。

**测试基础设施变更**：SDK 线此前无 `*IT`，failsafe 仅锁版本。本 phase 在 `artemis-client` 绑定 `maven-failsafe-plugin` 的 `integration-test`/`verify` 默认 executions，并在 `artemis-client-parent` pluginManagement 为 failsafe 加 `jdkToolchain 8`（与 surefire 同法）——`dev-test.sh all`（≡ `mvn verify`）自动纳入 IT，`integration` 模式直调 goal 亦可用；`dev-test.sh` 本身无需改。若后续 JUnit 5 在 JDK 8 fork 下的 failsafe 报告/参数化行为有差异，按实现期实测微调插件配置。

覆盖重点：服务端静态节点目录配置与 HTTP 端点、客户端 HTTP 前置寻址/节点列表刷新（与 registry 同一管线）、选择器 API（值对象不可变/多订阅隔离）、推送视图应用与零拷贝、断连续流自愈与全量替换收敛、地址熔断换址、快照随变更异步落盘/冷启动/全挂兜底/`clientId` 目录隔离、checksum 不一致触发刷新、canonical checksum 跨实现一致性（golden vector）、监听队列有界丢弃。

测试按 `subscribe(selector, listener)` 建立独立订阅；同一 client 多订阅共享连接/线程/RPC，用例需验证视图与快照互不串扰。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见工作流 ⑤ 节，此处不复制）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_match_golden_vector_when_computing_checksum | canonical FNV-1a-32 + XOR 规则对齐 API 文档 golden vector（3584519012） | 单测 | pass |
| should_be_order_independent_when_computing_checksum | 实例集与 metadata 迭代序不同，checksum 不变 | 单测 | pass |
| should_apply_new_update_delete_across_clusters | NEW/UPDATE/DELETE 按 instanceId upsert/移除，union 并列多 cluster | 单测 | pass |
| should_return_immutable_reference_without_clone | getInstances 返回缓存不可变列表引用（读路径零拷贝） | 单测 | pass |
| should_not_notify_when_view_unchanged | 幂等重放/同值 UPDATE 不触发监听通知 | 单测 | pass |
| should_replace_all_and_drop_missing_services | 全量替换删除响应外服务，逐 service 通知变化 | 单测 | pass |
| should_round_trip_snapshot_and_reject_selector_mismatch | 快照写读回放；选择器指纹不符/损坏文件忽略且不失败订阅启动 | 单测 | pass |
| should_write_snapshot_asynchronously_on_view_change | 视图变更触发异步落盘（合并且不阻塞 applier）；close 终写最新视图 | 单测 | pass |
| should_prefer_last_successful_then_circuit_address | 历史成功地址优先；连续失败熔断跳过；全熔断回退全量随机（与注册侧共用熔断） | 单测 | pass |
| should_build_immutable_selector_and_reject_invalid | DiscoverySelector 防御性拷贝/不可变；空白 service/cluster/label 键值 fail-fast | 单测 | pass |
| should_drop_oldest_and_count_when_listener_queue_full | 监听队列有界、满丢最旧 + dropped 计数，慢监听不阻塞推送 | 单测 | pass |
| should_isolate_snapshot_dirs_by_client_id | 不同 `clientId` 快照目录隔离；同 id + 同选择器共享文件 | 单测 | pass |
| should_reject_path_traversal_client_id | `clientId` 拒绝 `.`/`..` 与非安全文件名字符（快照路径防穿越） | 单测 | pass |
| should_be_order_independent_and_unambiguous_when_fingerprinting | 选择器指纹 64 位、集合序无关、key/value 长度前缀无歧义 | 单测 | pass |
| should_update_cluster_attribution_when_union_unchanged | union 不变但实例 cluster 归属变化时写回视图（checksum 收敛） | 单测 | pass |
| should_ignore_unknown_change_type_without_constructing_instance | 未知变更类型前向兼容：不构造可能非法的完整实例，缓存忽略 | 单测 | pass |
| should_decode_discovery_nodes_response_into_address_pool | HTTP 节点目录 protobuf → 地址池（全部 UP 优先级），坏响应失败不影响旧列表 | 单测 | pass |
| should_parse_static_node_directory_and_fail_fast | discovery-server：`nodes` 端点解析、非法端口/未加括号 IPv6/重复条目 fail-fast；未配置 = 空目录 | 单测 | pass |
| should_serve_discovery_nodes_over_http | discovery-server：`GET /cluster/nodes` 返回 protobuf 节点目录；未就绪 503；Basic 认证生效 | IT | pass |
| should_isolate_views_and_snapshots_between_subscriptions | 同 client 两订阅不同选择器：视图/通知/快照文件互不串扰 | IT | pass |
| should_seed_and_apply_subscription_push | 确认后的基线经 bootstrap Query 落地；增量推送亚秒进本地视图并通知；close 后 status/getInstances 抛 IllegalStateException | IT | pass |
| should_reconnect_after_stream_break_and_converge | 服务端断流后客户端退避重连；断连期间消失的服务经全量替换从视图移除 | IT | pass |
| should_fail_over_to_next_address_when_current_server_down | 双 fake discovery（HTTP 节点目录均列出双方端点），停当前 gRPC 地址 → 熔断换址到另一地址并恢复订阅 | IT | pass |
| should_replay_snapshot_on_cold_start_when_server_down | client 同步数据后 close（终写快照）→ 关 server → 新 client 订阅从快照回放 | IT | pass |
| should_keep_last_known_instances_when_all_servers_down | 已同步后全部 server 停止：getInstances 继续返回最后视图，连接态为 false | IT | pass |
| should_full_refresh_when_checksum_mismatch | fake 端视图被篡改使 checksum 不一致 → 下一对账周期触发全量 Query 收敛 | IT | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（2026-10-24 18:16） | BUILD SUCCESS（12 模块；surefire + failsafe + spotless + checkstyle + enforcer 全绿） |
| client 统计 | 63 单测 + 7 IT |
| discovery-server 统计 | 9 单测（含节点目录 1）+ 2 节点目录 IT |
| starter 统计 | 9 单测 |

人工验证（用户执行）：待用户自行验证并记录结论（方式与范围由用户决定，工作流不指定）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor（java/pom.xml）——含 ⑥ code-review 修复与既有 flaky 测试稳健性修复 | pass（BUILD SUCCESS，2026-10-24 18:16） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.6 | 2026-10-24 | ⑥/⑦/⑧ 完成：15 findings 全修（共享 channel 去重、刷新收敛、任务取消/关闭屏障、指纹 64 位、归属写回等）+ 新增 4 用例；全量回归 pass |
| 1.5 | 2026-10-24 | ⑤ 测试完成：client 60 单测 + 7 IT、server 9 单测（含节点目录 1）+ 2 节点目录 IT、starter 9 单测；全部通过 |
| 1.4 | 2026-10-24 | review 三次修订：节点目录去 id/self（host:port），服务端配置用例同步简化 |
| 1.3 | 2026-10-24 | review 二次修订：discovery 服务端节点目录（配置/HTTP IT）、客户端 HTTP 前置寻址/节点列表解码、`clientId` 快照隔离；用例 14 单测 + 8 IT |
| 1.2 | 2026-10-24 | review 修订：选择器改为 API 参数（多订阅隔离）、快照随变更异步落盘、寻址与注册侧同构 |
| 1.1 | 2026-10-24 | ③ 测试设计定稿：单测 + 进程内 fake discovery IT；failsafe/JDK8 toolchain 接入 |
| 1.0 | 2026-10-24 | 开工骨架 |
