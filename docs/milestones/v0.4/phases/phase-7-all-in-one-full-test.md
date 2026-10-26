# Phase 7 测试：all-in-one 完整形态与版本收尾

版本: 1.3    更新时间: 2026-10-26

## 测试方案

形态与覆盖面（执行入口 `java/scripts/dev-test.sh`，模式定义与形态↔模式映射见工作流 ⑤ 节，此处不复制）：

- **进程内服务级 IT**（`AllInOneIT` 扩展，failsafe `integration`/`all`）：`ArtemisAllInOneServer.start(options)` 单进程起三上下文（随机端口组 + `@TempDir` 隔离 registry 快照，既有模式），真实 gRPC 客户端（registry 注册 / discovery 查询订阅）+ management HTTP（TestClient）驱动端到端场景。
- **DB 态端到端**（Testcontainers PG + Docker 守卫 `@Testcontainers(disabledWithoutDocker = true)`，phase-1 既有模式）：`spring.datasource.url` 注入 management 上下文，跨 all-in-one 重启（close → 同 DB 同端口新 Node）验证摘除重下发；单方言 PG（D7-5，双方言语义由 phase-1–4 既有 IT 承载）。
- 单测无新增面（dist launcher 为装配代码，组合行为由 IT 端到端承载；`AllInOneConfig` 新端口键解析复用既有 `port()` 路径，既有单测模式不重复）。
- dev-test.sh 无扩展（D7-6）。

roadmap v0.4 验收要点对照：四要点全部在本 IT 以 all-in-one 形态端到端覆盖（摘除跨重启恢复 → DB 态用例；规则生效水位可见 / 逻辑实例可发现 / console 可用 → 内存态用例）；版本收尾填 plan 验收对照表时引用本表。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_start_all_three_services_on_distinct_ports | 三上下文单进程起停：start 后 registry/discovery/management 的 gRPC + HTTP + 管理端口全部就绪（management gRPC 必监听）、close 幂等且三上下文均停 | 进程内服务级 IT | pass |
| should_serve_registered_instance_through_discovery | 既有双上下文链路在完整形态下不回归：registry 注册 → discovery 查询/订阅可见 | 进程内服务级 IT | pass |
| should_filter_discovered_instance_after_ejection_and_expose_watermark | 治理链闭环 + 水位：注册两实例 → management `POST /admin/v1/ejections` 摘除其一 → discovery 查询被过滤（规则流闭环生效）→ `GET /admin/v1/status/rules` 水位可见（当前版本、生效节点 1）→ recover 恢复 | 进程内服务级 IT | pass |
| should_discover_logical_instance_union | 逻辑实例可发现：management `PUT /admin/v1/logical-instances` → discovery 查询/订阅 cluster=`logical` 实例与真实实例 union 可见 | 进程内服务级 IT | pass |
| should_serve_console_page_and_paged_services | console 可用：management HTTP 静态页 200 + `GET /admin/v1/services?keyword=&offset=&limit=` 分页/模糊语义 | 进程内服务级 IT | pass |
| should_restore_ejections_across_all_in_one_restart_with_db | DB 态跨重启恢复（roadmap 验收要点）：PG 容器 + all-in-one（DB 注入）→ 摘除 → close → 同 DB 同端口新 Node → discovery 订阅收到重下发摘除规则、查询过滤生效、水位重建 | 进程内服务级 IT（Testcontainers PG，Docker 守卫；独立类 `AllInOnePersistenceIT`——类级 `disabledWithoutDocker` 只守卫 DB 态，不殃及内存态用例） | pass |

既有 AllInOneIT 3 用例随三上下文改造（`should_start_both_services_on_distinct_ports` / `should_stop_both_contexts_when_closed` 更名扩展为三上下文断言；`should_serve_registered_instance_through_discovery` 保留）。

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `dev-test.sh all`（⑤ 第二轮，2026-10-26） | BUILD SUCCESS（14:53 min；AllInOneIT 6/6 + AllInOnePersistenceIT 1/1，PG 容器实测；首轮 DB 态 1 断言字段名错已修） |
| `/code-review` 内联 max 级 + findings 1–3 修复（⑥⑦） | 5 findings：3 修复、1 记录不改、1 归 ⑩ 文档 |
| `dev-test.sh all`（⑧ 第三轮，2026-10-26） | BUILD SUCCESS（14:58 min；AllInOneIT 6/6 含 union 排除断言 + AllInOnePersistenceIT 1/1） |

人工验证（用户执行）：通过（2026-10-26）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| 1（⑤） | all | 全 reactor（spotless + enforcer + 单测 + IT） | 第一轮 DB 态 1 断言字段名错（`product`→`databaseProduct`），修后第二轮 BUILD SUCCESS |
| 2（⑧） | all | 全 reactor（findings 1–3 修复后） | BUILD SUCCESS，14:58 min |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.3 | 2026-10-26 | ⑧ 回归全绿（BUILD SUCCESS 14:58 min）；⑨ 自动化验证记录（三轮） |
| 1.2 | 2026-10-26 | ⑤ 全绿（AllInOneIT 6/6 + AllInOnePersistenceIT 1/1，PG 容器实测；DB 态独立类说明） |
| 1.1 | 2026-10-26 | 测试设计定稿（待 review）：三上下文起停 + 四要点端到端（内存态 4 + PG 态跨重启 1）、Docker 守卫、dev-test.sh 无扩展 |
| 1.0 | 2026-10-26 | 开工骨架 |
