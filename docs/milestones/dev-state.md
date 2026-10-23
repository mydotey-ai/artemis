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
| worktree 开工 | done | main b7ae133 起；分支/目录 v0.3-phase4-fallback-freshness；设计/测试文档骨架落盘，phases/README 登记 |
| 上下文加载 | done | 已读：roadmap v0.3、架构 §2.2/§3.1/§4.1/§4.2/§6/§7、tech-java.md、guidelines §1/§2/§7/§8/§9/§10、proto-contract.md、legacy §2.4/§3.3/§5 资产 #8/§6 局限 #3/#15、ADR-019、v0.3 plan、phase-3 设计文档、discovery.v1/common.v1 契约、discovery-core/server 现有源码、订阅 IT 装配模式 |
| 设计 | done | 一轮 review 定案：视图 checksum + 拉取面新鲜度（ADR-019 v1.2、plan 1.4 同步） |
| 实现 | done | proto 契约 + core 6 新类/4 改造（Entry 缓存/checksums/UpstreamStatuses/setState 上报）+ server 出口/装配 + 单测 4 文件 + IT 1 文件 |
| 测试 | done | dev-test.sh all 全绿（BUILD SUCCESS，6:31min；期间 2 次环境抖动重跑：SdkFailoverIT 超时单跑通过、ClientSdkIT 瞬时端口占用已释放） |
| code-review | done | skill max 正常返回（16.5min，5 findings）+ 内联 1 finding，逐条验证：3 修 + 2 归 ⑩ + 1 记录 |
| fix | done | FIX-1 快照先于读表（query/checksum 双处）+ FIX-2 断连断言改持久 events + FIX-3 迭代序无关化 + 文档头版本对齐 |
| 回归测试 | done | dev-test.sh all 全绿（BUILD SUCCESS，6:17min） |
| 验证 | done | 自动化（⑤⑧ 两轮 all 全绿）+ 人工（2026-10-24 通过，含 canonical 抗序机制质询） |
| 文档同步 | done | 产品文档 1.19（phase-4 交付节）、discovery-reconciliation-api.md 新建（canonical 单一来源 + golden vector）、discovery-query-api.md 1.1（upstreams 字段）、api 索引登记；ADR/plan 于 ③ 已同步；CLAUDE.md 无需变更 |
| 完工 | done | 分支 6acd71c 提交，squash 合入 main |

步骤状态枚举：pending | in-progress | done | skipped。

## phase 总览（当前版本，版本 done 后清空；只记状态，主题/工作量以 plan 为准）

| phase | 状态 |
|---|---|
| phase-1-projection-source | done |
| phase-2-discovery-projection | done |
| phase-3-subscription-push | done |
| phase-4-fallback-freshness | done |
| phase-5-multi-cluster | planned |
| phase-6-ejection-filter | planned |
| phase-7-discovery-admin-api | planned |
| phase-8-consumer-sdk | planned |
| phase-9-starter-all-in-one | planned |

状态枚举：planned | in-progress | done。
