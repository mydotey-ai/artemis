# Phase 1 测试：peer 协议与静态多节点成员

版本: 1.12    更新时间: 2026-10-20

## 测试方案

- **单测**：成员配置解析/校验（含自身识别）、节点状态用例（domain + proto 门面）、内部端口与内部认证配置（默认关闭、启用时凭证必填、非法值按 SCF 语义回落）、动态配置生效（认证凭证、保护窗口、调度周期、查询 timeout）、`replication.v1` 生成链路。
- **进程内服务级 IT**（`*IT`，真实 Spring context + 真实 gRPC）：
  - 单节点双监听：外部端口与内部端口各自可用、服务不串端口、认证矩阵（外部凭证 × 内部端口、内部凭证 × 外部端口、无凭证）；
  - 进程内 3 节点集群：预分配内部端口 + 独立 context，逐节点 `GetNodeStatus` 断言成员互见与自身识别；
  - 既有查询 IT / 管理面 IT 改连内部端口后回归（`RegistryQueryIT`、`ManagementAdminIT`）。
- 不新增测试形态，`dev-test.sh` 的 unit/integration/all 映射与脚本无需扩展。
- 多节点 harness 放 registry-server 测试源码，供 phase-2/3 复用。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_parse_members_when_node_id_and_peer_configured | `id@peer-host:peer-port` 逗号列表解析为成员，peer 端点正确 | 单测 | pass |
| should_identify_self_when_self_configured | `cluster.self` 命中成员表时 `self()` 返回对应成员 | 单测 | pass |
| should_return_empty_self_when_single_node | members 为空时单节点模式，`self()` 为空 | 单测 | pass |
| should_fail_fast_when_member_malformed_or_duplicate | 非法条目/重复 node-id/端口越界启动失败且带配置键 | 单测 | pass |
| should_fail_fast_when_self_missing_or_unknown | members 非空但 self 缺省或未命中 → 启动失败 | 单测 | pass |
| should_parse_bracketed_ipv6_member | peer 端点支持括号 IPv6，裸 IPv6 拒绝 | 单测 | pass |
| should_report_starting_then_up_when_readiness_changes | 未 ready → STARTING，markReady 后 → UP；node_id/started_at 正确 | 单测 | pass |
| should_map_members_and_self_when_node_status_queried | proto 门面输出 self + 全量成员（node_id/peer） | 单测 | pass |
| should_disable_internal_auth_by_default_when_not_configured | 内部认证默认 false、无默认凭证 | 单测 | pass |
| should_fail_fast_when_internal_auth_enabled_without_credentials | 启用内部认证但 username/password 空白 → 启动失败 | 单测 | pass |
| should_treat_non_true_value_as_disabled_per_scf_semantics | 认证开关非 "true" 按 SCF 语义视为关闭 | 单测 | pass |
| should_fall_back_to_default_when_port_invalid_or_out_of_range | 内部端口越界/非数字/0 按 SCF 语义回落 9093，不阻断启动（产品不支持动态端口） | 单测 | pass |
| should_have_no_default_endpoint_when_no_config | management 客户端端点无默认值（未配置=上游未就绪） | 单测 | pass |
| should_report_unavailable_when_endpoint_not_configured | 未配置 registry endpoint 时调用返回 REGISTRY_UNAVAILABLE，不阻断 management 启动 | 单测 | pass |
| should_use_latest_credentials_and_timeout_when_changed | management 客户端凭证与 timeout 动态生效 | 单测 | pass |
| should_use_latest_credentials_when_changed | gRPC/HTTP 认证配置与 management 客户端凭证运行期轮换即生效 | 单测 | pass |
| should_use_latest_window_when_changed | 自我保护窗口动态生效（分桶环固定、窗口长度运行期可调） | 单测 | pass |
| should_use_latest_interval_when_changed | 租约清理/快照调度周期运行期修改即时重排 | 单测 | pass |
| should_bracket_bare_ipv6_address_when_building_bind_address | 裸 IPv6 绑定地址自动补方括号 | 单测 | pass |
| should_fail_fast_when_member_host_invalid | 成员 host 含空白/非法 IPv6 → 启动失败 | 单测 | pass |
| should_bracket_ipv6_peer_host_when_mapping_to_wire | IPv6 peer_host 在 wire 上带方括号 | 单测 | pass |
| should_generate_replication_proto_when_building_registry_core | `replication.v1` 消息与 stub 生成链路可用 | 单测 | pass |
| should_start_peer_listener_when_context_starts | 内部端口可应答 `GetNodeStatus`，外部端口返回 UNIMPLEMENTED | 进程内 IT | pass |
| should_isolate_credentials_between_external_and_internal_ports | 内部认证启用时：四种凭证/端口组合按预期放行或 UNAUTHENTICATED | 进程内 IT | pass |
| should_allow_internal_call_without_credentials_when_auth_disabled | 内部认证默认关闭时无凭证可调用内部端口（复制 + 查询） | 进程内 IT | pass |
| should_require_internal_credentials_for_query_when_auth_enabled | 内部认证启用时查询同样要求内部凭证，外部凭证/无凭证拒绝 | 进程内 IT | pass |
| should_serve_query_only_on_internal_port_when_migrated | 内部凭证 + 内部端口查询成功；外部端口 UNIMPLEMENTED | 进程内 IT | pass |
| should_see_all_members_when_three_nodes_started | 3 节点各自 `GetNodeStatus`：3 成员、self 各自不同、UP | 进程内 IT | pass |
| should_reach_each_peer_when_cluster_members_configured | 成员表 peer 端点与配置一致，且每个端点均可应答（节点间实际复制调用随 phase-2） | 进程内 IT | pass |
| should_query_registry_when_management_uses_internal_port | management 改为内部端点后管理查询回归通过 | 进程内 IT | pass |
| should_attach_basic_auth_when_username_configured | management 客户端配置 username 时附加 Basic 头 | 单测 | pass |
| should_not_attach_auth_when_username_blank | 凭证默认空时不发送认证头 | 单测 | pass |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | `BUILD SUCCESS`，命令退出码 0 | 通过（用户确认 2026-10-20） |
| 2 | `mvn -f java/pom.xml -pl server/artemis-registry-server -am test-compile failsafe:integration-test failsafe:verify -Dit.test=PeerProtocolIT,PeerAuthIsolationIT,ClusterMembershipIT -Dfailsafe.failIfNoSpecifiedTests=false` | 双监听/认证隔离/三节点成员用例全绿（6 个用例，0 失败） | 通过（用户确认 2026-10-20） |
| 3 | 启动 registry（`mvn -f java/pom.xml -pl server/artemis-registry-server spring-boot:run`），另开终端 `ss -ltnp \| grep 9093` | 地址未配置时绑 `0.0.0.0:9093`（peer 跨节点可达）；启动日志有认证关闭的 WARN | 通过（用户确认 2026-10-20） |
| 4 | `mvn -f java/pom.xml -pl server/artemis-registry-server spring-boot:run -Dspring-boot.run.arguments=--artemis.registry.internal.port=70000`（或 `=0`） | 正常启动，内部端口回落默认 `9093`（SCF 记录非法/越界值日志），不阻断启动 | 通过（用户确认 2026-10-20） |
| 5 | `mvn -f java/pom.xml -pl server/artemis-management-server -am verify -Dit.test=ManagementAdminIT -Dfailsafe.failIfNoSpecifiedTests=false` | management 经 registry 内部端口查询的用例全绿 | 通过（用户确认 2026-10-20） |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全量 reactor（⑤ 首跑） | pass（EXIT=0） |
| 2 | all | 全量 reactor（⑦ code-review 修复后回归） | pass（EXIT=0） |
| 3 | all | 全量 reactor（review 修订：默认绑 0.0.0.0） | pass（EXIT=0） |
| 4 | all | 全量 reactor（review 修订：非法配置按 SCF 语义回落默认） | pass（EXIT=0） |
| 5 | all | 全量 reactor（review 修订：地址无默认值、端口不支持 0） | pass（EXIT=0） |
| 6 | all | 全量 reactor（配置动态性 review：窗口/deadline 改动态） | pass（EXIT=0） |
| 7 | all | 全量 reactor（调度周期/认证凭证动态化 + 单一 endpoint） | pass（EXIT=0） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.12 | 2026-10-20 | ⑨ 人工验证结论：全部通过 |
| 1.11 | 2026-10-20 | 调度周期/认证凭证动态化；management 单一 endpoint；回归记录第 7 轮 |
| 1.10 | 2026-10-20 | 配置动态性用例（窗口/deadline）；回归记录第 6 轮 |
| 1.9 | 2026-10-20 | 地址无默认值/固定端口语义；回归记录第 5 轮 |
| 1.8 | 2026-10-20 | 补管理客户端 host/port 用例；验证清单覆盖端口 0 回落 |
| 1.7 | 2026-10-20 | 配置语义回归 SCF 默认回落；验证清单第 4 项改为预期回落 9093 |
| 1.6 | 2026-10-20 | 验证清单第 3 项改为默认 `0.0.0.0`；回归记录第 3 轮 |
| 1.5 | 2026-10-20 | ⑦ 补 code-review 用例（严格配置/查询认证/客户端凭证）；⑧ 回归记录 |
| 1.4 | 2026-10-20 | ⑤ 首跑 `dev-test.sh all` 全绿，用例结果更新为 pass |
| 1.3 | 2026-10-20 | review 修订：去掉对外端点断言；补内部认证默认关闭/启用校验/无凭证调用用例 |
| 1.2 | 2026-10-20 | review 修订：成员配置简化后的解析用例；节点状态对外端点断言 |
| 1.1 | 2026-10-20 | ③ 测试设计定稿：单测 + 双监听/认证隔离/3 节点进程内 IT 用例清单 |
| 1.0 | 2026-10-20 | 开工骨架（① worktree 开工） |
