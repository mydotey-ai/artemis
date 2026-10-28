# Phase 6 测试：容量验证

版本: 1.6    更新时间: 2026-10-28

## 测试方案

**形态三分**：

1. **driver 编排逻辑单测**（surefire `*Test`，进 dev-test.sh unit/all）：CLI 参数解析与档位展开、分片计算、实例分配矩阵（S×I×P 完整性/无重复/确定性）、CSV 采样输出——逻辑正确性是压测数据可信的前提。
2. **冒烟压测**（bench.sh `--profile dev`，1 千实例矩阵）：验证编排脚本端到端可跑通（拓扑拉起 → driver 分片 → 采样 → 汇总 CSV 产出，含内存预检触发路径），作为 ④ 实现完成的门槛；不进 CI。
3. **压测出数**（bench.sh，**逐档递增** dev → small → large，见 phase 设计文档「规模档位与环境适配」；large = 10 万实例矩阵 + S1–S4 场景 × 单节点/3 节点拓扑）：长时间运行、出数到容量基线文档，瓶颈按预判表逐条对照；**独立入口不进 dev-test.sh 与 CI**（时长/资源不合 CI 口径）——dev-test.sh 形态↔模式映射不变，压测以 `java/scripts/bench.sh` 为独立入口（phase 设计文档「范围」节定案）。

执行入口：单测经 `java/scripts/dev-test.sh`；压测经 `java/scripts/bench.sh`。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_parse_and_validate_arguments_when_cli_given | CLI 参数解析与非法值拒绝（S/I/P 正整数、shard 范围、模式枚举） | 单测 | pass |
| should_expand_profile_when_profile_option_given | 档位展开：各档位展开为正确**全维度矩阵**（S×I、P、k、拓扑、Q、各进程 heap 预算与进程清单），custom 不展开；单参数覆盖优先于档位值 | 单测 | pass |
| should_shard_processes_evenly_when_shard_option_given | P 个 client 按 i/k 分片均摊且全覆盖无重叠 | 单测 | pass |
| should_assign_instances_deterministically_when_model_generated | S×I 实例分配到 P 进程：完整、无重复、两次生成一致 | 单测 | pass |
| should_emit_csv_rows_when_samples_collected | 采样器周期输出 CSV 行（时间戳 + 指标列） | 单测 | pass |
| should_pair_push_latency_when_events_matched | churn 注入事件与订阅 listener 事件按 id 配对出延迟 | 单测 | pass |
| should_complete_all_stages_when_smoke_bench | dev 档冒烟：bench.sh 端到端跑通、租约到位（1000 实例在册）、CSV 产出、jemalloc 检测注入；可用内存不足或 jemalloc 缺失拒绝路径经代码走查（显式触发留 ⑨ 补验） | 冒烟压测 | pass（s1/s4 跑通，S4 延迟出数 p50=11ms/p95≈1.5s） |
| should_run_incremental_profiles_when_benching | 逐档递增 dev → small 验证资源曲线无异常（large 前置门槛） | 压测（流程） | pending（出数阶段执行） |
| should_reach_target_when_register_storm_s1 | S1：目标实例数注册到位（租约总数达 S×I），到位时间出数 | 压测 | pending（出数阶段执行；dev 档冒烟已见 1000 到位） |
| should_hold_leases_when_steady_heartbeat_s2 | S2：稳态心跳持续期租约总数稳定（无丢失/无保护态误触发） | 压测 | pending（出数阶段执行） |
| should_absorb_changes_when_churn_s3 | S3：变更注入速率下服务端无积压（复制批次不持续增长）、heap 无持续上涨 | 压测 | pending（出数阶段执行） |
| should_propagate_to_subscribers_when_subscribe_push_s4 | S4：订阅 bootstrap 时延 + 变更推送延迟分布（P50/P95/P99/max）出数 | 压测 | pending（出数阶段执行） |
| should_verify_replica_consistency_when_three_nodes | 3 节点拓扑：churn 期后各节点租约/实例视图一致（复制放大系数出数） | 压测 | pending（出数阶段执行） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `dev-test.sh all`（⑤ 首跑 + ⑤ 补 + ⑧ 回归共 3 轮全绿） | BUILD SUCCESS，bench 8/8 → 9/9 测试，spotless/enforcer 过，proto 兼容 5/5 |
| `bench.sh --profile dev --scenarios s4`（修复后冒烟） | 1000 实例在册、count=250（per-subscriber 配对生效）、无 FAILED/WATCHDOG |
| `bench.sh --profile small --scenarios s1,s2,s4`（出数轮 I） | s1 632s / s2 632s 全绿；s4 因后台任务 30min 上限被砍，补跑单场景完成（633s） |
| `bench.sh --profile small --scenarios s4`（出数轮 I 补） | push-latency n=12590 p50=5ms p95=7ms p99=8286ms；慢消费断流 0 |
| `bench.sh --profile small --topologyNodes 3 --scenarios s4`（出数轮 II） | 三节点副本一致（各 10000 峰值）；发现订阅推送实时性退化（观察率 37%、p95≈500s 靠对账兜底）——表外新发现，全样本留存 |
| large 档预检 | 本机拒绝启动（预算 14.1G > 可用 ~11.3G，预检按设计生效）——10 万档以 small 外推出数，复现命令见基线文档 |

数据归档：`java/scripts/bench-report/20261028-183002`（s1/s2）、`20261028-190050`（s4 单拓扑）、`20261028-191246`（3 节点）。

人工验证（用户执行）：用户审查自动化验证汇总与产出物，追加交付诉求（/bench-java-test 会话命令 + dev-test 边界声明，已交付）后发起 ⑪ 完工提交——视为验证通过（2026-10-28）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤ 首跑） | all | 全 reactor | 失败：bench 模块 spotless 格式违规（7/7 测试绿） |
| 2（⑤） | all | 全 reactor | 通过：spotless:apply 后 BUILD SUCCESS、proto 兼容 5/5 |
| 3（⑤ 补） | all | 全 reactor（CsvAppender 抽取 + 单测） | 通过：bench 8/8 测试、BUILD SUCCESS、proto 兼容 5/5 |
| 4（⑧ 修复后回归） | all + 冒烟 | 全 reactor + S4 冒烟（13 findings 修复后） | 通过：BUILD SUCCESS、proto 5/5；冒烟 count=250（50 注入 × 5 订阅者 per-subscriber 配对）、1000 实例、无 FAILED/WATCHDOG |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.6 | 2026-10-28 | ⑨ 自动化验证记录（dev-test 3 轮 + 出数两轮 + large 预检拒绝）；⑩ 文档同步 |
| 1.5 | 2026-10-28 | ⑤ 执行：单测 6 条 pass、冒烟 pass（s1/s4 端到端 + 1000 实例 + 延迟出数）、回归 3 轮记录；压测出数用例留出数阶段 |
| 1.4 | 2026-10-28 | review 修订：冒烟用例补 jemalloc 强制预检断言 |
| 1.3 | 2026-10-28 | review 修订：档位展开用例断言扩展到全维度（物理布局 + 进程清单 + heap 预算） |
| 1.2 | 2026-10-28 | review 修订：档位展开用例、冒烟对齐 dev 档、逐档递增流程用例、内存预检路径（用例 13 条） |
| 1.1 | 2026-10-28 | 测试设计定稿：形态三分（单测进 dev-test / 冒烟门槛 / 全量出数独立入口）、用例 11 条 |
| 1.0 | 2026-10-28 | 测试骨架 |
