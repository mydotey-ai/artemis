# Phase 3 测试：peer 全量 bootstrap 与节点状态 API

版本: 3.2    更新时间: 2026-10-21

## 测试方案

形态与覆盖面：

- **单测**：成员配置三段式解析与 fail-fast、注册表整体替换语义、启动分支选择（单节点 / 无已就绪 peer / 已就绪 peer / 同步失败后重试成功）、proto 门面（成员对外端点映射、bootstrap 快照导出）、未就绪复制拒绝。
- **进程内多实例集群语义测试**（`*IT`，failsafe）：真实 gRPC 双监听 + 真实协议客户端；覆盖新节点加入全量同步、重启优先 peer 同步、无已就绪 peer 回退本地快照、已就绪 peer 同步失败保持 not ready 并重试恢复、对外节点状态 API。
- 执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见工作流 ⑤ 节，此处不复制）。本 phase 不引入既有映射之外的新形态，脚本无需扩展。
- 既有 IT 适配：`ClusterMembershipIT`、`ReplicationFanoutIT` 的成员配置改为三段式（预分配外部端口）；单节点 IT（`SnapshotRestoreIT`、`PeerProtocolIT` 等）无需成员配置变更。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_parse_external_endpoint_when_member_has_three_segments | `id@peer@external` 解析出两套端点，self 命中 | 单测 | 通过 |
| should_fail_fast_when_external_endpoint_missing_or_invalid | 集群模式缺第三段/外部端点非法/重复 id 启动失败 | 单测 | 通过 |
| should_parse_bracketed_ipv6_external_endpoint | 外部端点括号 IPv6 解析与 wire 括号映射 | 单测 | 通过 |
| should_replace_all_and_remove_absent_services_when_bootstrap_applied | `replaceAll` 覆盖旧服务、移除快照缺席 key、零事件、续约时刻重算 | 单测 | 通过 |
| should_be_ready_from_snapshot_when_single_node | 单节点仍走既有快照回放分支 | 单测 | 通过 |
| should_restore_snapshot_when_no_ready_peer_in_cluster | 集群模式 peer 均非 UP/不可达 → 回放本地快照并 ready | 单测 | 通过 |
| should_apply_peer_snapshot_and_skip_local_snapshot_when_ready_peer_exists | 存在 UP peer → 应用 peer 全量，本地快照不回放 | 单测 | 通过 |
| should_stay_not_ready_and_retry_when_peer_sync_fails | 首次同步失败 → not ready；退避重试成功 → ready，且不回退本地快照 | 单测 | 通过 |
| should_try_next_ready_peer_when_first_candidate_sync_fails | 首个 UP peer 同步失败时继续尝试其余 UP 候选，任一成功即 ready | 单测 | 通过 |
| should_allow_node_status_when_not_ready_but_reject_other_rpcs | readiness 拦截器放行节点状态 API，其余外部 rpc 仍 UNAVAILABLE | 单测 | 通过 |
| should_report_external_endpoints_and_states_in_node_status | 节点状态门面返回 self 就绪态 + 成员 peer/external 端点 + 观测态 | 单测 | 通过 |
| should_probe_all_peers_concurrently_when_round_runs | 一轮探测并发提交全部 peer（互不等待），整轮耗时接近单次 probe | 单测 | 通过 |
| should_mark_peer_unknown_when_probe_times_out_or_fails | 探测异常/超时/不可达 → UNKNOWN；整轮在 timeout 内结束不挂起 | 单测 | 通过 |
| should_mark_peer_unknown_when_probe_returns_mismatched_node_id | 应答自报 nodeId 与配置不符 → UNKNOWN（防配置串位） | 单测 | 通过 |
| should_recover_peer_state_on_next_round_when_probe_succeeds | 每轮整体重建：UNKNOWN 后探测成功恢复 UP | 单测 | 通过 |
| should_export_full_registry_snapshot_for_bootstrap | bootstrap 快照导出包含全部 service/实例，空表为空列表 | 单测 | 通过 |
| should_reject_replicate_with_unavailable_when_not_ready | 未就绪时复制入站返回 UNAVAILABLE（发送侧可重试） | 单测 | 通过 |
| should_reject_bootstrap_when_source_not_ready | 未就绪节点不接受 bootstrap 拉取（防止空/半量数据源） | 单测 | 通过 |
| should_sync_full_registry_from_ready_peer_when_new_node_joins | 2 节点就绪并写入后启动第 3 节点 → 全量同步、ready、数据可见 | 集群 IT | 通过 |
| should_prefer_peer_sync_over_stale_snapshot_when_node_restarts | 节点停机期间 peer 数据变化，重启后以 peer 为准、忽略本地旧快照 | 集群 IT | 通过 |
| should_fallback_to_local_snapshot_when_peers_unreachable | 成员表含未启动 peer + 本地有效快照 → 无 UP peer，回退快照并 ready | 集群 IT | 通过 |
| should_stay_not_ready_and_recover_when_bootstrap_attempt_fails_then_peer_recovers | peer 自报 UP 但 bootstrap 失败 → 保持 not ready 持续退避重试；peer 恢复后 ready | 集群 IT | 通过 |
| should_report_starting_and_skip_snapshot_write_when_not_ready | not ready 期间节点状态 API 返回 STARTING，且快照调度器不落盘（本地旧快照不被空表覆盖） | 集群 IT | 通过 |
| should_expose_self_up_and_member_external_endpoints_via_node_status_api | 3 节点对外 API：self UP、成员外部端点齐全、成员观测态均 UP、外部认证生效 | 集群 IT | 通过 |
| should_mark_stopped_peer_unknown_when_probe_round_runs | 停一个节点后，其余节点 API 在探测周期内把该成员标记 UNKNOWN | 集群 IT | 通过 |
| should_keep_member_visibility_when_external_endpoints_configured | 既有 3 节点成员互见用例适配三段式配置后仍全绿 | 集群 IT | 通过 |
| should_converge_on_peer_join_when_external_endpoints_configured | 既有缺席 peer 后起收敛用例适配三段式配置后仍全绿 | 集群 IT | 通过 |

## 验证清单（人工，⑨ 填）

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `bash java/scripts/dev-test.sh all` | BUILD SUCCESS；spotless/enforcer/checkstyle 通过；单测 + 42 个 IT 全绿 | 通过 |
| 2 | `mvn -f java/pom.xml -pl server/artemis-registry-server -am test-compile failsafe:integration-test failsafe:verify -Dit.test=PeerBootstrapIT,NodeStatusApiIT,ClusterMembershipIT -Dfailsafe.failIfNoSpecifiedTests=false` | 7 个用例全绿（join/重启优先/快照回退/失败重试恢复、节点状态 API、成员互见） | 通过 |
| 3 | 3 节点手动起停（见下方命令）：先起 node-a/node-b，3s 后起 node-c | node-c 日志出现 `bootstrap from peer node-* complete`；三节点 `/readyz` 均 200 | 通过 |
| 4 | `curl -s -u artemis:artemis http://127.0.0.1:19091/actuator/prometheus \| grep '^artemis_registry_cluster_peers_up'` | 值为 `2.0`（每个节点探测到另外两个 UP peer） | 通过 |
| 5 | 停掉 node-c，等待 >1s 后重查 #4 | 值变为 `1.0`；node-a 日志出现 `peer node-c changed from UP to UNKNOWN` | 通过 |
| 6 | 重新启动 node-c，等待 ready 后重查 #4 | 值恢复 `2.0`；node-c 日志出现 `bootstrap from peer ... complete`；node-c `/readyz` 200 | 通过 |

手动起停命令（在 worktree 根目录执行；jar 由 verify 产出）：

```bash
JAR=java/server/artemis-registry-server/target/artemis-registry-server-0.1.0-SNAPSHOT-exec.jar
D=$(mktemp -d)
MEMBERS='node-a@127.0.0.1:19093@127.0.0.1:19090,node-b@127.0.0.1:19193@127.0.0.1:19190,node-c@127.0.0.1:19293@127.0.0.1:19290'
start() { # $1=node letter $2=grpc $3=http $4=mgmt $5=peer
  java -jar "$JAR" --spring.application.name=artemis-registry \
    --spring.grpc.server.port=$2 --server.port=$3 --management.server.port=$4 \
    --management.server.address=127.0.0.1 --artemis.registry.internal.port=$5 \
    --artemis.registry.internal.address=127.0.0.1 \
    --artemis.registry.cluster.members="$MEMBERS" --artemis.registry.cluster.self=node-$1 \
    --artemis.registry.snapshot.path="$D/node-$1.bin" > "$D/node-$1.log" 2>&1 &
}
start a 19090 18080 19091 19093
start b 19190 18180 19191 19193
sleep 3
start c 19290 18280 19291 19293
sleep 5
curl -sf -u artemis:artemis http://127.0.0.1:18080/readyz && echo " node-a ready"
curl -sf -u artemis:artemis http://127.0.0.1:19280/readyz && echo " node-c ready"
grep 'bootstrap from peer' "$D/node-c.log"
# 停 node-c（kill %3 或按 PID），观察 metrics 与日志后重启 node-c；验证完毕后：
pkill -f 'artemis-registry-server-0.1.0-SNAPSHOT-exec.jar' || true
rm -rf "$D"
```

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤ 首跑） | all | `dev-test.sh all` 全量 verify | 全绿（core 122 + server 41 + client 24 单测；server 42 集成测试；spotless/enforcer/checkstyle） |
| 2（⑧ 回归） | all | `dev-test.sh all` 全量 verify（code-review 9 条修复后） | 全绿 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 3.2 | 2026-10-21 | ⑧ 回归全绿（第 2 轮）；⑨ 人工验证清单落盘 |
| 3.1 | 2026-10-21 | ⑤ 全量 verify 全绿：用例结果回填、回归记录第 1 轮 |
| 3.0 | 2026-10-21 | 后台 peer 状态探测用例（并发、超时/失败 UNKNOWN、nodeId 校验、状态恢复、停节点 IT）；节点状态 API 断言观测态 |
| 2.2 | 2026-10-21 | 补未就绪 bootstrap 源守卫用例；与设计 v2.2 对齐 |
| 2.1 | 2026-10-21 | review 输入：补多候选尝试、退避重试、节点状态 API 未就绪可观测与 not-ready 不落盘用例 |
| 2.0 | 2026-10-21 | ③ 测试设计定稿：单测 + 集群 IT 用例清单，既有 IT 适配说明 |
| 1.0 | 2026-10-21 | 开工骨架 |
