# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | done | |
| v0.4 | done | |
| v0.5 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |

> v0.1–v0.4 已收尾（验收对照见各 `plan.md`）。v0.5（治理面 II）于 2026-10-26 启动，基线 c87dc2a 已提交。phase-1（route-rule 资源模型与持久化）已交付（e961ebb）。phase-2（两段式权重发布与回滚）已交付。
>
> phase-3（分组路由执行：Group filter 权重二值承接 + 视图直投 + 三出口一致 + 结构债 #9/#10 收口）已交付。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main 6d06c3d 起点新建，骨架两文档 + 索引登记 |
| 上下文加载 | done | 已读：roadmap v0.5、架构 §2.2/§4.2/§5.2、ADR-013、tech-java.md、guidelines §7/§10、legacy §2.4/§2.5/§5-#7、phase-1/2 设计文档、route-rule-stream-api.md、discovery-core 过滤器链/两同步器/RouteRules/EjectionRules、DiscoveryConfiguration、DiscoveryEjectionFilterIT |
| 设计 | done | review 通过（2026-10-27，无修改意见，§6 登记） |
| 实现 | done | RouteRules 判定 + GroupFilter + 同步器扇出 + 骨架 #9/source helper #10 收口 + 装配 |
| 测试 | done | dev-test.sh all 全绿（11:36 min；GroupRoutingIT 4/4 + ejection 回归 9+4；轮内修 IT 405 路径 + registry 偶发复跑） |
| code-review | done | 内置 code-review skill（max）正常返回，7 findings |
| fix | done | 7 findings 全修（判定纵深防御 ×2 / state 重复行 / clusterIds 提升 / 测试锚点 / gson 解析 / 文档用例名） |
| 回归测试 | done | dev-test.sh all 全绿（11:39 min，160 组 0 失败，review 修复含新防御用例） |
| 验证 | done | 自动化两轮 all 全绿；人工验证通过（2026-10-27） |
| 文档同步 | done | 产品文档 1.34（phase-3 交付节 + phase-2 状态句）+ route-rule-stream-api 1.1；admin/架构/索引无变更 |
| 完工 | done | squash 99d73f4 |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-route-rule | done |
| phase-2-two-phase-release | done |
| phase-3-group-routing | done |
