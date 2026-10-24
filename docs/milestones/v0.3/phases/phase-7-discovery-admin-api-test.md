# Phase 7 测试：discovery 发现管理 API

版本: 1.3    更新时间: 2026-10-24

## 测试方案

形态沿用既有映射，**无需扩展 `dev-test.sh`**：核心模型/用例逻辑用单测（`*Test`，unit 模式）；管理面端到端用进程内真实 registry + discovery 的 `DiscoveryAdminIT`（failsafe，integration 模式），经真实 gRPC 注册/订阅与真实 HTTP + Basic 认证驱动。

覆盖面：

- **订阅关系**：登记分配 id、远端地址/时刻记录、快照排序、关闭即移除（单测）；真实订阅流出现在管理 API、流关闭后消失（IT）。
- **服务视图**：列表聚合与 namespace/cluster 过滤、详情逐 cluster 实例、过滤链生效（未过滤事实不出现在视图）、未注册服务返回空（单测 + IT）。
- **上游状态/规则源状态**：多 cluster 快照、management 未配置 = `DISABLED`（单测 + IT）；`SYNCING → SYNCED → DISCONNECTED` 转移与断连不清规则（同步器单测扩展）。
- **HTTP 契约面**：Basic 认证 401/200、参数空白 400、未知路由 404、输出排序稳定（IT）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_snapshot_active_subscription_with_caller_identity | 登记订阅后快照含单调 id、远端地址、登记时刻与选择器；close 后消失 | 单测 | 通过 |
| should_order_subscriptions_by_id_and_default_unknown_subscriber_when_snapshot | 多条订阅按 id 升序；未传标识归一 unknown | 单测 | 通过 |
| should_list_effective_service_view_when_instances_registered | 列表按 (cluster, namespace, service) 聚合实例数并稳定排序 | 单测 | 通过 |
| should_filter_service_list_by_namespace_and_cluster | namespace/cluster 过滤只返回命中来源；空白 = 全部 | 单测 | 通过 |
| should_hide_ejected_instance_from_admin_views | 过滤链摘除的实例不出现在列表计数与详情；全被过滤时详情无 cluster 条目 | 单测 | 通过 |
| should_return_sorted_detail_when_service_exists | 详情 cluster 按名、实例按 instanceId 稳定排序 | 单测 | 通过 |
| should_return_empty_clusters_when_service_unknown | 未注册服务详情返回空 cluster 列表（非异常） | 单测 | 通过 |
| should_reject_blank_service_path_when_querying_detail | 详情路径 namespace/service 空白抛 IllegalArgumentException（HTTP 400） | 单测 | 通过 |
| should_expose_sorted_upstreams_and_management_state | upstreams 按 cluster 排序；management DISABLED/SYNCED 状态映射 | 单测 | 通过 |
| should_report_syncing_then_synced_when_snapshot_applied | 同步轮开始＝SYNCING、首快照应用＝SYNCED | 单测 | 通过 |
| should_report_disconnected_when_stream_lost_after_sync | 曾同步后断流＝DISCONNECTED | 单测 | 通过 |
| should_report_disabled_when_management_not_configured | 未配置 management＝DISABLED | 单测 | 通过 |
| should_require_auth_when_admin_api_called_without_credentials | 启用认证时无凭证 401、带凭证 200 | IT | 通过 |
| should_list_active_subscription_when_subscribed | 真实 gRPC 订阅后在 `/admin/v1/subscriptions` 可见选择器与远端地址 | IT | 通过 |
| should_remove_subscription_when_stream_cancelled | 客户端取消订阅流后管理面快照移除该订阅（release → 登记表注销链路） | IT | 通过 |
| should_list_service_view_and_detail_when_registered | 注册后列表出现对应 cluster/service 与实例数；详情逐 cluster 可见实例稳定排序；未注册服务空 clusters | IT | 通过 |
| should_expose_upstream_freshness_and_management_disabled | `/admin/v1/upstreams` registry 来源 SYNCED、management DISABLED | IT | 通过 |
| should_return_client_error_when_blank_path_or_unknown_route | 空白路径参数 400、未知路由 404 | IT | 通过 |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 检查 | 命令 | 结果 |
|---|---|---|
| 全量构建 + 测试 + 格式/静态检查 | `java/scripts/dev-test.sh all` | BUILD SUCCESS（7:50，spotless/checkstyle/enforcer/单测/IT 全通过） |
| 管理面端到端 | `DiscoveryAdminIT`（包含在上一项） | 6/6 通过（认证、订阅出现/取消、视图/详情、上游与规则源状态、客户端错误） |

人工验证（用户执行）：用户验证通过（2026-10-24，方式与范围由用户自行决定）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1 | all | 全 reactor（spotless + enforcer + 单测 + IT） | BUILD SUCCESS（7:50） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-24 | ⑧⑨：回归全绿、自动化验证记录（待人工验证结论） |
| 1.2 | 2026-10-24 | ⑦ review 修复：补订阅流取消后管理面移除用例（单测 12 + IT 6） |
| 1.1 | 2026-10-24 | ③ 测试设计初稿：覆盖订阅关系/服务视图/上游与规则源状态/HTTP 契约面（待 review） |
| 1.0 | 2026-10-24 | 开工骨架 |
