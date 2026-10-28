# Phase 5 测试：滚动升级协议验证

版本: 1.0    更新时间: 2026-10-28

## 测试方案

三层形态（与设计文档「验证口径总述」一一对应）：

| 层 | 形态 | 执行入口 |
|---|---|---|
| 契约 | **契约脚本断言**（新形态）：`java/scripts/proto-compat-check.sh` 逐版本锚点比对 proto wire 兼容纪律 | `dev-test.sh all`（mvn verify 成功后内嵌调用；unit/integration 模式不含） |
| 行为 | 服务级 IT（`*IT`，failsafe）：进程内起真实服务，stub 客户端发注入 `UnknownFieldSet` 的「未来版本」消息 | `dev-test.sh integration` / `all` |
| 语义 | 集群语义 IT（进程内 3 实例集群 + 真实 SDK），复用 v0.2 集群 IT 设施（`TestGrpcPorts`/`TestAwait`/3 节点起停模式） | 同上 |

新形态「契约脚本断言」进入 `dev-test.sh all` 组成（模式说明行同步），脚本与测试制品不漂移。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_passWireCompatibility_when_comparedWithEachVersionAnchor | 数据面五组 proto 相对 v0.1–v0.5 各版本收尾锚点：field 类型/编号不变、删除已 reserved、编号不复用 | 契约脚本 | 通过（负向自检：三类破坏注入均被抓出，合法演进静默过） |
| should_processFutureVersionPayload_when_heartbeatCarriesUnknownFields | 心跳流消息注入未知字段（模拟新版本 SDK 发旧节点）：逐条 ack、实例续约/补注册效果正确 | 服务级 IT | 通过 |
| should_processFutureVersionPayload_when_replicationCarriesUnknownFields | Replicate 批消息注入未知字段（模拟新版本节点发旧节点）：复制入站正常应用、副本租约维持 | 服务级 IT | 通过 |
| should_keepRegistryServingAndSdkAlive_when_rollingGracefulRestart | 3 节点逐节点优雅停机→同端口重启（bootstrap 恢复）：滚动全程存活节点注册表完整、evictExpired 探针无误摘、终态三节点一致且 SDK 实例仍注册 | 集群语义 IT | 通过 |
| should_keepDataConsistent_when_rollingRestartUndrained | 同滚动流程但节点以无排水停机（drainMs=0，Pod 强终止的进程内最近似——close 仍走 DOWN/终刷，无快照恢复归 SnapshotRestoreIT）：断流换址 + peer 副本 + 重启 bootstrap，终态一致无误摘 | 集群语义 IT | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：命令与结果逐条记。人工验证（用户执行）：所做检查 / 试用与结论，内容由用户决定，工作流不指定。

**自动化验证（工作流执行）**：

- `java/scripts/dev-test.sh all`（⑤ 首轮 + ⑧ 回归两轮）：BUILD SUCCESS，全 reactor 1616 测试 failures=0 errors=0，proto 兼容检查「5/5 锚点 × 5 文件无破坏性变更」。
- 检查器负向自检（注入-还原，工作树零残留）：类型变更 ✓ 抓出、删除未 reserved ✓ 抓出、reserved 编号复用 ✓ 抓出、编号 reserved 名字未保留 ✓ 抓出、合法演进（编号+名字 reserved）✓ 静默、`[deprecated = true]` 选项 ✓ 静默、`reserved N to max` ✓ 20s 内正常返回（不挂死）、注释含花括号 ✓ 静默、伪锚点 ✓ 显式红。
- 三个新 IT 单独执行（`failsafe -Dit.test=ProtoForwardCompatIT,RollingRestartIT`）：4/4 通过（2+2）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首轮 | all | 全 reactor（1616 测试）+ proto 兼容检查 | 全绿（failures=0 errors=0；脚本锚点 ×5 ×文件 ×5 通过） |
| ⑧ 回归 | all | 同上（review 修复后：脚本重写 + IT bean 缓存 + 文档措辞） | 全绿（1616 测试 failures=0 errors=0 + 契约检查通过） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-28 | 测试设计定稿（契约脚本断言新形态 + 前向兼容 IT + 滚动重启 IT，5 用例） |
