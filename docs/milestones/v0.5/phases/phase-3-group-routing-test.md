# Phase 3 测试：分组路由（Group filter 全语义）

版本: 1.1    更新时间: 2026-10-27

## 测试方案

- **单测**（discovery-core）：
  - 判定语义：`RouteRules.isVisible`——selector 多键 AND 匹配、未命中归兜底、权重 0 摘出、命中 0 权重组不归兜底、多规则 AND、无规则全可见、逻辑实例（metadata 同源）同判定。
  - `GroupFilter`：整集替换后判定随新规则集生效。
  - 同步器扇出：快照 / 增量 → `runWithFanout` 内替换 + 对称差直投（DELETE/NEW）、受影响服务限定、幂等无扇出、断流不清缓存。
  - 结构债收口回归：#9 骨架抽取后 ejection 同步器既有单测（v0.3 phase-6 全组）必须原样通过——重构安全网，不改断言。
- **进程内多实例集群语义测试**（discovery-server）：`DiscoveryGroupRoutingIT`——进程内起真实 registry + discovery + management 三服务（`DiscoveryEjectionFilterIT` 形态），经 management HTTP API 建规则 / release / rollback 端到端驱动三出口。

形态均在既有 unit / integration 模式映射内，`dev-test.sh` 无扩展。

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见工作流 ⑤ 节，此处不复制）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_route_visibility_by_selector_and_weight | selector 多键 AND 命中；未命中归兜底；weight 0 目标摘出；命中 0 权重组不归兜底（兜底 >0 仍不可见） | 单测 | 通过 |
| should_combine_multiple_rules_with_and | 同服务多规则 AND；无规则服务全可见 | 单测 | 通过 |
| should_reflect_rule_updates_in_group_filter | replace 整集后 isVisible 随新规则集判定（新旧集视图切换） | 单测 | 通过 |
| should_fanout_view_changes_on_rule_change | 快照/增量应用 → 表锁内替换 + 对称差直投（退出 DELETE / 进入 NEW）；快照/增量/删除三事件序列断言 | 单测 | 通过 |
| should_limit_fanout_to_rule_scoped_services | 扇出与判定只作用规则覆盖服务（服务内资源），未覆盖服务视图与判定不受影响 | 单测 | 通过 |
| should_skip_fanout_when_rules_unchanged | 幂等事件（等值 upsert）→ 无替换无扇出 | 单测 | 通过 |
| should_keep_last_known_rules_when_disconnected | 断流后规则缓存不清、判定继续（fail-open，D-E3） | 单测 | 通过 |
| should_keep_ejection_semantics_after_skeleton_extraction | #9 骨架抽取回归：ejection 同步器既有单测全组（9 用例）+ DiscoveryEjectionFilterIT（4 用例）原样通过 | 单测 + IT（既有） | 通过 |
| should_filter_view_and_release_restores_across_three_exits | 三出口一致：建规则（生效列全 0，未 release）→ 查询空 + 订阅 DELETE 直投 + checksums 滤空缺项；release（跨 0）→ 三出口恢复 | IT | 通过 |
| should_drop_unmatched_instances_when_no_serving_target | 仅 beta 目标无兜底：未命中实例摘出、命中保留 | IT | 通过 |
| should_restore_view_on_rollback | release#1 全承接 → release#2 兜底归 0（i2 摘出）→ rollback 到 #1：视图还原 | IT | 通过 |
| should_apply_rule_to_logical_instances | 逻辑实例（management 静态实例带 metadata）与真实实例同判定（同摘出） | IT | 通过 |

注：⑤ 轮内合并了设计稿用例「release 跨 0 视图变化」进三出口用例（同一规则生命周期内断言未发布摘出 + release 回归）；「幂等无扇出」与「断流不清缓存」沿用既有用例形态（同 ejection 先例）。

## 验证记录（⑨ 填）

自动化验证（工作流执行，⑤）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（等价 `mvn -f java/pom.xml verify`，第 3 轮终态） | BUILD SUCCESS（11:36 min，12 模块，160 组测试 0 失败；含 DiscoveryGroupRoutingIT 4/4、DiscoveryEjectionFilterIT 4/4、EjectionRuleSynchronizerTest 9/9 回归） |

⑤ 首跑轮内修复（非 review findings）：① IT `release`/`rollback` 路径漏 `{name}` 段命中 PUT 路由 → 405（补段修正）；② `registry ReplicationFanoutIT` 首跑偶发负载超时（phase-2 已知先例），单模块复跑通过后全量轮全绿。

人工验证（用户执行）：所做检查 / 试用与结论，内容由用户决定，工作流不指定。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全量（review 修复后回归：判定纵深防御 + clusterIds 提升 + 测试锚点/gson/文档名） | 通过（BUILD SUCCESS 11:39 min，160 组测试 0 失败，含 RouteRulesTest 7/7 新防御用例、DiscoveryGroupRoutingIT 4/4） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-27 | 测试设计定稿（待 review）：单测判定语义与扇出 + ejection 骨架回归 + 三服务端到端 IT（三出口 / 两段联动 / 回滚 / 兜底 / 逻辑实例） |
| 1.0 | 2026-10-27 | 开工骨架 |
