# 开发状态

> 工作流（/dev-java）自动维护，勿手工编辑。全流程唯一状态源。

## 版本总览（只追加，历史行保留）

| 版本 | 状态 | 备注 |
|---|---|---|
| v0.1 | done | |
| v0.2 | done | |
| v0.3 | in-progress | 当前版本 |

状态枚举：planned | in-progress | acceptance | done（planned = 启动产物已写、基线未提交）。

## 当前版本（版本 done 后清空本节）

| 项 | 值 |
|---|---|
| phase | — |
| 步骤 | — |
| 分支 / worktree | — |

> v0.1 已于 2026-10-19 收尾（验收对照见 `v0.1/plan.md`）；v0.2 已于 2026-10-23 收尾（验收对照见 `v0.2/plan.md`）。

## 当前 phase 步骤进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| worktree 开工 | done | main 61f86b7 起；分支/目录 v0.3-phase2-discovery-projection；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.3、架构 §2.1–§2.2/§3.1/§4.1–§4.2、tech-java.md、guidelines §2–§10、proto-contract、legacy §2.4/§5、ADR-007/014、phase-1 设计与测试文档、projection.proto、registry peer client/装配与 IT 模式（PeerChannels/GrpcPeerBootstrapSource/PeerServerConfiguration/PeerBootstrapIT）、discovery 骨架与 proto 生成矩阵 |
| 设计 | done | review 通过（v1.3：copy-on-write 修正 + 空即删条目 + 空条目规则收窄） |
| 实现 | done | discovery.v1 查询契约定稿；discovery-core（domain 12 类 + application 5 类 + ProjectionSynchronizer）；discovery-server（upstream/gRPC 出口/装配/配置）；pom（discovery-core grpc stub 生成、server gRPC/观测/IT 依赖） |
| 测试 | done | dev-test.sh all 全绿（BUILD SUCCESS，5:07）；18 用例全过（DiscoveryProjectionIT 4/4） |
| code-review | done | 内置 code-review skill（max）：11 findings（4 中高危：DELETE 未知实例误删条目、watch 无 keepalive、旧轮回调污染、DISCONNECTED 语义） |
| fix | done | 9 条修复 + 1 条 defer（三处重复上移 → v0.4 批次，注释与 phase 文档记录） |
| 回归测试 | done | dev-test.sh all 全绿（BUILD SUCCESS）；discovery 单测 16 + IT 4 全过 |
| 验证 | done | 自动化 + 人工验证均通过（2026-10-23） |
| 文档同步 | done | 产品文档 v1.17 phase-2 交付节；新建 api/discovery-query-api.md + 索引；CLAUDE.md/架构/ADR 无需变更 |
| 完工 | done | 分支 757bf0f 提交；squash 合入 main 并 push |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-projection-source | done |
| phase-2-discovery-projection | done |
| phase-3-subscription-push | planned |
| phase-4-fallback-freshness | planned |
| phase-5-multi-cluster | planned |
| phase-6-ejection-filter | planned |
| phase-7-discovery-admin-api | planned |
| phase-8-consumer-sdk | planned |
| phase-9-starter-all-in-one | planned |

状态枚举：planned | in-progress | done。
