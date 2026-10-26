# Phase 6 测试：console 只读版

版本: 1.2    更新时间: 2026-10-26

## 测试方案

**单测**（surefire，`ManagementServiceImplTest` 增补）：分页/过滤语义为纯应用层逻辑，fake `RegistryQueryPort` 内存构造服务与实例集验证——过滤命中域、排序稳定性、切片与 total、缺省全量兼容、详情实例分页与全量计数分离；另以大列表（5000 服务）构造验证分页上界切片正确性（10 万实例语义 = 上界与切片正确性，非压测）。

**服务级集成测试**（failsafe，`ManagementConsoleIT`，复用 `ManagementAdminIT` 的同 JVM registry + management fixture 模式）：真实 registry 注册多服务多实例 → 真实 HTTP + Basic 认证驱动 console 页面可达性与分页/模糊 API 端到端。

形态均落在既有映射（单测 → unit、IT → integration），`dev-test.sh` 无需扩展。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_filter_services_when_keyword_matched_case_insensitive | keyword 大小写不敏感命中 namespace 或 service 任一 | 单测 | pass |
| should_keep_full_list_when_no_keyword_and_no_limit | 无 keyword 无 limit 时行为与既有全量一致（additive 兼容） | 单测 | pass |
| should_page_services_with_total_when_offset_limit_given | 排序后过滤切片，total 为过滤后总数，页间不重不漏 | 单测 | pass |
| should_return_empty_page_when_offset_beyond_total | offset 越界返回空页，total 不变 | 单测 | pass |
| should_bound_page_size_when_large_service_set_constructed | 5000 服务构造下 limit 切片与 total 正确（分页上界语义） | 单测 | pass |
| should_page_instance_views_when_detail_offset_limit_given | 详情 instances 切片，counts 始终全量（includeEjected 先过滤后切片） | 单测 | pass |
| should_serve_console_page_when_root_requested | GET / 返回 console HTML；无凭证 401 | 集群语义 | pass |
| should_filter_and_page_services_over_http_when_console_api_queried | 多服务注册后 keyword/offset/limit/total 经真实 HTTP 端到端 | 集群语义 | pass |
| should_page_instances_over_http_when_detail_queried | 详情实例分页参数经真实 HTTP 端到端 | 集群语义 | pass |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `./scripts/dev-test.sh all`（第 5 轮，review 修复后回归） | EXIT=0 全绿（spotless + enforcer + 全部单测/IT；ManagementServiceImplTest 35、ManagementConsoleIT 3、ManagementAdminIT 13） |
| `mvn -f server/pom.xml -pl artemis-discovery-server -am test-compile failsafe:integration-test failsafe:verify -Dit.test=DiscoveryAdminIT` | 6/6 通过（第 4 轮全量中该 IT 端口 bind 竞态失败的单独复验，环境级 flake，与本 phase 无关） |

人工验证（用户执行）：所做检查 / 试用与结论，内容由用户决定，工作流不指定。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑧ 第 4 轮 | all | 全量（review 修复后） | 失败：discovery DiscoveryAdminIT 端口 bind 竞态（环境级 flake，单独重跑 6/6 通过）；本 phase 相关测试全绿 |
| ⑧ 第 5 轮 | all | 全量 | 全绿（EXIT=0） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-26 | 测试设计定稿 |
| 1.1 | 2026-10-26 | ⑤ 首跑：用例全 pass（dev-test.sh all，第 3 轮全绿；修正 keyword 用例断言笔误——ORDER 不命中 ns/svc） |
| 1.2 | 2026-10-26 | ⑧ 回归（修复 4 findings 后第 5 轮全绿）+ ⑨ 自动化验证记录 |
