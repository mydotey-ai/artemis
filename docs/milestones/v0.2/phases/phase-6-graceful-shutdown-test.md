# Phase 6 测试：优雅停机与集群验收收尾

版本: 1.3    更新时间: 2026-10-23

## 测试方案

形态与覆盖面：

- **单测**（surefire，`*Test`）：`ReadinessGate` 三态状态机（core 纯 JDK 类）；停机钩子序列编排（markDown → drain → 终刷顺序、幂等、异常不阻断）；`nodeStatus()` self state DOWN 优先级；starter `ContextClosedEvent` 自动 close（事件触发、早于 bean destroy、幂等）。
- **进程内多实例集群语义测试**（failsafe，`*IT`）：复用 phase-5 `SdkFailoverIT` 设施模式（3 节点 `SpringApplicationBuilder` 随机端口集群 + 真实 SDK HTTP 前置寻址）；优雅停机以异步 `context.close()` 驱动，drain 窗口经 `drainMs` 调大取证。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见 ⑤ 节）；用例形态均在既有映射内（单测 / IT），无脚本扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_transitStates_when_markReadyAndMarkDown | ReadinessGate 三态流转：NOT_READY→READY→DOWN；NOT_READY→DOWN；markDown 幂等；DOWN 后 markReady 不复活 | 单测 | pass |
| should_runShutdownSequence_when_contextClosed | 停机钩子（drainMs=0 快速路径）：markDown 先于终刷、writeSnapshot 恰一次、重复事件只执行一次、终刷异常不抛出 | 单测 | pass |
| should_reportSelfDown_when_markedDown | nodeStatus() self state 判定：DOWN 优先于 ready（down+ready 历史 → DOWN） | 单测 | pass |
| should_refuseNewTrafficButServeEstablishedStreams_when_draining | 3 节点，停机节点 drainMs 调大异步 close：drain 窗口内 peer 观测其 = DOWN（经存活节点节点状态 API）、`/readyz` 503、已建心跳流仍续约 ack（客户端实例不过期）；新 rpc 拒绝经 isReady 联动，语义由 ReadinessInterceptor 既有单测覆盖 | IT | pass |
| should_failover_when_connectedNodeShutdownGracefully | 客户端连 node-X → 优雅停 node-X：无感换址（survivors 实例可见、evictExpired 后不过期）+ 心跳指标到达新节点（对齐 SdkFailoverIT 验收模式） | IT | pass |
| should_replayFinalSnapshot_when_restartedAfterShutdown | 单节点：注册 → 优雅停（终刷）→ 同 snapshot path 重启 → 回放恢复实例数并 ready | IT | pass |
| should_converge_when_nodeRestartedAfterShutdown | 3 节点停 node-1 → 存活节点数据完整（复制副本）→ 重启 node-1 → bootstrap 从 peer 同步 → node-1 ready 且全集群实例数一致（集群故障/恢复端到端闭环） | IT | pass |
| should_closeClient_when_contextClosedEvent | starter：容器 close 时 `ContextClosedEvent` 即触发 client `close()`（注销在 bean 销毁前、程序可用时发生）；destroy 阶段二次 close 幂等 no-op | 单测 | pass |
| should_skipFinalSnapshot_when_neverReady | 未就绪节点停机不终刷（空表不得覆盖可回退旧快照）——review #1 | 单测 | pass |
| should_skip_bootstrap_when_markedDown | DOWN 后 bootstrap 不再回放快照/不计尝试（与终刷并发替换注册表）——review #2 | 单测 | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（回归第 3 轮，最终态） | BUILD SUCCESS；全 reactor 测试 0 失败（registry-server 52、management-server 13、client/starter 9 等） |
| `mvn -f java/server/pom.xml -pl artemis-registry-server -am verify -Dtest=GracefulShutdownHookTest -Dit.test=GracefulShutdownIT` | 单测 4/4、IT 4/4 通过 |
| 日志取证（第 3 轮全量） | `graceful shutdown: node 'node-N' marked DOWN, draining for 5000ms` ×3（drain 窗口生效）；`final snapshot written` 70 次（凡曾就绪的关闭均终刷）；宽限期修复后停机线程在 20s 断言内结束 |
| 脚手架约束 | spotless / enforcer 随 verify 通过 |

人工验证（用户执行）：**通过**（2026-10-23）。用户复核停机序列与两条 review 修复语义（未就绪不终刷、DOWN 不 bootstrap）、gRPC 关停宽限 1s 取值与 `GracefulShutdownIT` 四场景覆盖面。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全量（⑦ 修复后首跑） | **失败**：`GracefulShutdownIT.should_refuse...draining` 新断言 isAlive=false 失败（停机线程 32.6s 未结束）→ 根因 spring-grpc 默认关停宽限 30s 与 drain 叠加；修 `spring.grpc.server.shutdown.grace-period=1s` |
| 2 | all | 全量（宽限期修复后） | **通过**：BUILD SUCCESS，73 个测试类；`GracefulShutdownIT` 4/4（83s → 40.7s，宽限期修复生效） |
| 3 | all | 全量（测试属性补齐后） | **通过**：BUILD SUCCESS；`GracefulShutdownIT` 4/4（31s）；`SnapshotRestoreIT`/`ManagementAdminIT` 不再等待排水 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-23 | ⑦ 补两条 review 修复用例（未就绪不终刷 / DOWN 不 bootstrap）；drain IT 补线程结束断言；⑧ 三轮回归与 ⑨ 验证记录（自动化 + 用户通过） |
| 1.2 | 2026-10-23 | review 修订：新增 starter ContextClosedEvent 自动 close 单测（4 单测 + 4 IT） |
| 1.1 | 2026-10-23 | 测试设计定稿：3 单测 + 4 IT（drain 窗口取证/无感换址/终刷回放/重启收敛） |
| 1.0 | 2026-10-23 | 开工骨架 |
