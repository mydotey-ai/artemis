# v0.4 版本规划

版本: 1.1    更新时间: 2026-10-26

## 目标

范围引 [roadmap](../../product/artemis-next-roadmap.md) §3 v0.4（治理面 I），此处不复述条目原文——一句话概括：management-service 接入 DB（MySQL/PG 双方言 + Flyway）并交付期 3 治理面基础：账户体系（单管理员统一认证）、四级摘除持久化（操作记录即状态 + 审计）、逻辑实例（静态实例纳入统一发现视图）、规则下发闭环（版本 + 订阅流 + discovery ack + 生效水位）、console 只读版与三服务 all-in-one 完整形态。逐条范围与验收要点以 roadmap 为单一来源。

**验收要点**：摘除跨重启恢复（management 重下发）、规则生效水位可见、逻辑实例可发现、console 可用。

架构基线见 [架构设计](../../arch/artemis-next-architecture.md) §5（治理面三期演进、API 与规则下发、console 与账户）与 §2.3（management 可不部署、单机 eval 零 DB 依赖的双轨约束）；DB 组件选型见[技术选型](../../dev/tech-stack-java.md) #14（Flyway，仅 management，双方言脚本）。设计不在本版展开，由各 phase ③ 设计定稿。

## phase 清单

（phase 状态以 dev-state.md 为准，此处不设状态列；滚动细化——后续 phase 随进度可调整拆分）

| phase | 主题 | 工作量（天） | 验收方式 |
|---|---|---|---|
| phase-1-db-foundation | management DB 接入基座：MySQL/PG 双方言 + Flyway migration + 连接装配（可选依赖，未配置零 DB 运行）+ 测试基建 | 2 | 单测：migration 脚本与 repository 双方言语义；IT：双方言（PG/MySQL）真实 schema 起停；未配置 DB 时 management 内存态照常起 |
| phase-2-account-takeover | 账户体系：单管理员 DB 持久化 + 统一认证接管（management 认证源 + 各服务账户对接） | 2 | 单测：账户装配与认证裁决；IT：DB 账户登录、接管生效、跨重启账户保留 |
| phase-3-ejection-persistence | 四级摘除持久化（instance → server → zone → group）+ 操作记录即状态 + 审计表 | 3 | 单测：级联计算与记录叠加；IT：摘除跨 management 重启重下发恢复（v0.1 内存态升级）、审计可查 |
| phase-4-logical-instance | 逻辑实例：第三方/异构服务注册为静态实例，纳入统一发现视图 | 2 | 单测：逻辑实例模型与下发投影；IT：逻辑实例经 discovery 查询/订阅可见、与真实实例 union |
| phase-5-rule-ack-loop | 规则下发闭环：规则版本 + 订阅流（版本化升级 v0.3 规则流）+ discovery ack + 生效水位 + filter 链扩展 | 3 | 单测：版本/水位计算与 ack 状态机；IT：规则变更 → discovery 生效 → 水位可见、discovery 重连补齐、生效节点数可查 |
| phase-6-console-readonly | console 只读版：management 内嵌静态 SPA + REST（服务列表/详情/搜索分页+模糊、实例视图） | 2 | 单测：REST 分页/模糊查询语义；IT：console 页面与 API 可用、10 万实例语义（分页上界） |
| phase-7-all-in-one-full | 三服务 all-in-one 完整形态（dist 扩 management）+ 版本验收场景收尾 | 2 | IT：三服务单进程起停与端到端；验收四要点端到端场景全绿 |

phase-1 说明：DB 为 management **可选依赖**（架构 §5.3 双轨资产：registry + discovery 独立可用、单机 eval 零 DB）——未配置 DB 时 management 保持内存态运行（期 1 行为），配置后启用持久化；连接装配、双方言方言层与 Flyway 脚本组织由 ③ 设计定稿（含测试基建：双方言验证策略）。

phase-2 说明：v0.4 为单管理员账户（RBAC v1.0）；统一认证接管范围（management 自身 + 各服务账户对接方式）由 ③ 对照架构 §5.3/§6 定稿，注意各服务本地单账户默认开启的既有行为兼容。

phase-3 说明：四级级联与「操作记录即状态」（下线 = 可叠加、带原因、可审计的记录；恢复 = 反向记录）为继承治理语义（架构 §5.2）；v0.1 内存摘除升级为 DB 持久化，discovery 经既有规则流收到重下发。摘除目标中 server/zone/group 的取值来源（实例 metadata）与级联展开语义由 ③ 定稿。

phase-4 说明：逻辑实例入统一发现视图的链路（management 持久化 → 下发通道 → discovery 投影合并）由 ③ 对照架构 §5.2 定稿，不引入新数据面契约除非 ③ 显式登记。

phase-5 说明：替换 v0.3 摘除规则流的盲下发（快照 + 增量、无版本/ack）为版本化规则流 + ack 生效闭环（架构 §5.2）；生效水位（哪个版本、多少节点已生效）出口在 management 状态 API。discovery filter 链为执行点，治理依赖保持 0..1。

phase-6 说明：console 为 management 内嵌静态 SPA + REST，不引入独立前端部署单元（架构 §5.3）；只读范围 = 服务列表/详情/搜索（分页 + 模糊）+ 实例视图，操作审计流与订阅关系视图展示随实现裁剪。前端形态（原生/轻量构建）由 ③ 定稿。

phase-7 说明：`artemis-dist` 扩为三服务同 JVM 组合（v0.3 已落 registry + discovery 双上下文形态，management 并入沿用同一机制）；收口 roadmap v0.4 验收要点的端到端场景，验收对照表在版本收尾时填写。

## 验收对照（收尾时填）

| roadmap 验收要点 | 对应测试 | 结果 |
|---|---|---|
| 摘除跨重启恢复（management 重下发） | `EjectionPersistenceIT.should_restore_ejections_across_management_restart`（phase-3，双方言）；`AllInOnePersistenceIT.should_restore_ejections_across_all_in_one_restart_with_db`（phase-7，all-in-one 形态：DB 摘除重下发 → discovery 过滤恢复 + 水位重建，PG） | 通过 |
| 规则生效水位可见 | `EjectionRuleStreamIT.should_carry_version_and_track_watermark_when_acked`（phase-5）；`DiscoveryRuleAckLoopIT`（phase-5，三服务端到端：水位可见/重启重 ack/双节点生效计数）；`AllInOneIT.should_filter_discovered_instance_after_ejection_and_expose_watermark`（phase-7，all-in-one 形态） | 通过 |
| 逻辑实例可发现 | `DiscoveryLogicalInstanceIT`（phase-4，查询/订阅/union/摘除生效/fail-open）；`LogicalInstanceStreamIT`（phase-4）；`AllInOneIT.should_discover_logical_instance_union`（phase-7，all-in-one 形态，cluster 正交断言） | 通过 |
| console 可用 | `ManagementConsoleIT`（phase-6，页面可达/401、搜索分页、实例分页）；`AllInOneIT.should_serve_console_page_and_paged_services`（phase-7，all-in-one 形态） | 通过 |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-26 | 版本收尾：验收对照表填齐（四要点全过，7/7 phase done） |
| 1.0 | 2026-10-24 | 版本启动，初始 phase 拆分（7 phase，滚动细化） |
