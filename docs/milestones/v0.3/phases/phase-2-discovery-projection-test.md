# Phase 2 测试：discovery 投影内核与查询

版本: 1.5    更新时间: 2026-10-23

## 测试方案

形态与覆盖面：

- **单测**（`*Test`，surefire，discovery-core）：投影存储语义（全量覆盖/变更应用/选择器查询/空集规则）、synchronizer 消费安全序（建立确认门控、bootstrap 期间事件缓冲后应用——D-D3 关键窗口、断流重连覆盖、退避、队列满重连、readiness 升降）、mapper 同构与 UNKNOWN 哨兵。synchronizer 用 fake `ProjectionSource` 驱动（协议无关，纯时序语义）。
- **服务级集成测试**（`*IT`，failsafe，经 `dev-test.sh integration`，discovery-server）：`DiscoveryProjectionIT` 进程内起真实 registry context（外部 + 内部 peer 端口随机，借 test 依赖 `artemis-registry-server` + `artemis-registry-core` test-jar 测试设施 `TestGrpcPorts`/`TestAwait`）+ 真实 discovery context（上游指向 registry peer 端口、查询端口随机），discovery.v1 stub 直连驱动查询。「亚秒可见」在稳态（首轮 SYNCED 完成后）测量注册→查询可见延迟。

无既有形态↔模式之外的新形态，dev-test.sh 不需扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_cover_all_services_and_drop_missing_when_replace_all | bootstrap 全量覆盖：新表原子替换、快照中消失的服务删除、重复覆盖幂等 | 单测 | 通过 |
| should_apply_new_update_delete_changes | 变更批次应用：NEW 插入、UPDATE 覆盖、DELETE 移除实例、未知服务 DELETE 忽略 | 单测 | 通过 |
| should_remove_service_entry_when_last_instance_deleted | 最后一个实例 DELETE 后 service 条目删除（空即删，D-D2）；与 bootstrap 覆盖路径收敛 | 单测 | 通过 |
| should_keep_siblings_when_delete_unknown_instance_id | 陈旧 DELETE（instanceId 不在实例集）no-op，不误删服务条目与兄弟实例（code-review #1） | 单测 | 通过 |
| should_query_by_services_and_labels | 选择器：空 services 全量、精确集合命中、labels 实例级过滤 | 单测 | 通过 |
| should_return_empty_entry_only_when_labels_filter_out_all_instances | 空条目规则：labels 过滤后为空且服务显式命中 → 返回空实例条目；服务零实例（条目已删）与未命中同形不返回；全量查询不返回过滤后空条目 | 单测 | 通过 |
| should_reject_blank_service_key_with_invalid_argument | 指定 services 含空白 namespace/service → INVALID_ARGUMENT | 单测 | 通过 |
| should_map_proto_and_domain_with_unknown_sentinels | discovery.v1 ↔ domain 同构映射；未知枚举映射 UNKNOWN | 单测 | 通过 |
| should_bootstrap_only_after_establishment_confirmation | 未收到建立确认不发 bootstrap；确认后发出（先 Watch 后 Bootstrap 序） | 单测 | 通过 |
| should_apply_buffered_events_after_bootstrap_cover | **D-D3 关键窗口**：bootstrap 期间到达的事件在覆盖后按序应用——产生于快照前者被幂等吸收、产生于后者不丢 | 单测 | 通过 |
| should_rebootstrap_and_cover_stale_data_on_disconnect | 流断开后整轮重来：重新 bootstrap、全量覆盖清除陈旧数据 | 单测 | 通过 |
| should_retry_with_backoff_when_upstream_fails | 上游失败按全抖动指数退避重试，恢复后完成同步 | 单测 | 通过 |
| should_reconnect_when_event_queue_full | 事件队列满触发断流与整轮重连 | 单测 | 通过 |
| should_not_open_readiness_until_first_sync | 首轮 SYNCED 前 readiness gate 关闭 | 单测 | 通过 |
| should_keep_readiness_after_upstream_disconnect | SYNCED 后上游断连 readiness 不降（继续服务投影） | 单测 | 通过 |
| should_serve_registry_writes_sub_second | 稳态下 registry 注册 → discovery 查询可见延迟 < 1s | 集成 | 通过 |
| should_reflect_update_and_delete_on_query | update/deregister 后查询反映新实例集/移除 | 集成 | 通过 |
| should_rebootstrap_and_cover_stale_after_registry_restart | registry 重启（空表）→ discovery re-bootstrap：旧实例清除、新写入可见 | 集成 | 通过 |
| should_reject_query_with_unavailable_before_first_sync | 上游未就绪时查询 UNAVAILABLE；registry 就绪注册后查询转正常 | 集成 | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 检查 | 命令 | 结果 |
|---|---|---|
| 全量构建（spotless:check + enforcer + 单测 + IT） | `java/scripts/dev-test.sh all` | BUILD SUCCESS（⑤ 与 ⑧ 两轮） |
| discovery.v1 生成落地 | `ls java/server/artemis-discovery-core/target/generated-sources/.../discovery/v1/` | QueryRequest/ClusterServiceInstances/QueryResponse 与 `DiscoveryQueryServiceGrpc` stub 全部生成 |
| 端到端投影闭环 | `DiscoveryProjectionIT`（真实 registry + discovery 双 context，projection 流 + 查询出口真实调用） | 4/4 通过（含稳态亚秒可见 <1s 断言） |
| readiness 门控 | `should_reject_query_with_unavailable_before_first_sync`（IT）+ synchronizer 单测 | 通过（首轮 SYNCED 前查询 UNAVAILABLE） |

人工验证（用户执行）：用户于 2026-10-23 复核设计、实现与测试，结论**通过**。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | ⑤ 实现后首跑全量（spotless + enforcer + 全单测 + 全 IT，5:07） | 通过（BUILD SUCCESS） |
| 2 | all | ⑧ code-review 修复后全量回归（spotless + enforcer + 全单测 + 全 IT） | 通过（BUILD SUCCESS） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.5 | 2026-10-23 | ⑨ 自动化验证记录；停待人工验证 |
| 1.4 | 2026-10-23 | ⑦/⑧ code-review 修复：补 DELETE 未知实例 no-op 用例；回归轮次 2 全绿 |
| 1.3 | 2026-10-23 | ⑤ 测试执行：18 用例全部通过（15 单测 + 4 IT 中 mapper 单测含 3 断言组）；回归轮次 1 记录 |
| 1.2 | 2026-10-23 | review 修正：补空即删条目用例；空条目规则用例改为仅产生于 labels 过滤（14 单测） |
| 1.1 | 2026-10-23 | 测试设计定稿（13 单测 + 4 集成用例） |
| 1.0 | 2026-10-23 | 开工骨架 |
