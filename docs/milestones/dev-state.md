# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | done | |
| v0.4 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

> v0.1/v0.2/v0.3 已收尾（验收对照见各 `plan.md`）。v0.4 于 2026-10-24 启动（治理面 I）。

## 当前 phase 步骤进度（版本 done 后清空本节）

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | |
| 上下文加载 | done | 已读：roadmap v0.4、架构 §2/§4.1/§5/§6、tech-java.md、guidelines §1/§2/§7/§10、proto-contract、tech-stack §3/§6、ADR-005/ADR-009、legacy 局限 #19、phase-1 设计；代码现状（三服务 auth + PersistenceConfiguration） |
| 设计 | done | review 通过（2026-10-25）；定案同步：ADR-021 新增、架构 1.40、ADR-009 1.2、技术选型 #13 决断 1.27 |
| 实现 | done | core domain/application 5 类 + server repository/装配/过滤器/改密端点 + V2 双方言 + MyBatis-Plus boot4 接入（SB 4.1 实测兼容，回退未触发） |
| 测试 | done | dev-test.sh all 全绿（13 模块；PG/MySQL AccountFlowIT + 既有回归；中途修复：BasicAuthTest 编译、Spotless、SB4 WebServerApplicationContext 包名/取端口） |
| code-review | done | 内联替代 skill（Skill 调用通道不可用）；max 口径逐文件：2 findings（503 响应形态不一致/测试注释语义不符）+ 2 信息项 |
| fix | done | 2 findings 全修（手写 503 JSON、测试改为直测请求侧空白密码）；2 信息项登记实现结果 |
| 回归测试 | done | dev-test.sh all 全绿（13 模块，13:07） |
| 验证 | done | 自动化（两轮 all 全绿）+ 人工验证通过（2026-10-25） |
| 文档同步 | done | 产品文档 1.26、admin-api 1.7；架构/ADR/技术选型 ③ 时已同步；CLAUDE.md 无过时 |
| 完工 | done | squash 合入 main（5b669d7 → squash），分支保留本地 |

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-db-foundation | done |
| phase-2-account-takeover | done |
| phase-3-ejection-persistence | planned |
| phase-4-logical-instance | planned |
| phase-5-rule-ack-loop | planned |
| phase-6-console-readonly | planned |
| phase-7-all-in-one-full | planned |
