# Phase 5：滚动升级协议验证

版本: 1.1    更新时间: 2026-10-28

## 目标

proto 前后兼容验证 + 3 节点集群滚动重启客户端容灾无感（滚动重启期间 SDK 注册心跳不断流不换数据）。

## 范围

**做什么**（三层验证组合）：

1. **契约层：proto wire 兼容纪律断言**——当前工作树 proto 相对各历史版本锚点无破坏性变更（类型/编号不变、删除必须 reserved），CI 门槛化。
2. **行为层：前向兼容运行时验证**——数据面服务端入口（心跳流、Replicate 投递）收到含未知字段的消息（模拟「未来版本节点/SDK 发来的消息」）正常处理。
3. **语义层：3 节点滚动重启 IT**——逐节点优雅停机 → 重启（含无排水变体），滚动全程存活节点注册表完整、无误摘、SDK 心跳持续换址续约、终态三节点一致。

**不做什么**：

- **不做真实「两版本二进制混跑」**（spawn 历史版本 jar 组混合集群）：仓库尚无 git tag 与历史发布物，锚点只能取版本收尾 commit；且混合互通的正确性由「契约纪律（层 1）+ protobuf 前向兼容默认行为（层 2 验证一次链路无破坏）」组合蕴含，真跑两份二进制的成本（构建历史 jar + 进程编排）不换新增保证。真实多版本部署的端到端留作用户人工验证（⑨）。
- 不做 discovery/management 侧滚动验证（phase 验收口径是 registry 集群 + 注册侧 SDK；discovery 上游断连重连语义已有既有 IT 覆盖）。
- 不新立升级编排机制（preStop/hook 等，归 Helm/K8s 形态 phase-4 交付物）。

## 涉及模块

- `java/scripts/proto-compat-check.sh`（新增契约检查脚本）+ `java/scripts/dev-test.sh`（all 模式接入，脚本同步义务）
- `java/server/artemis-registry-server` IT：`ProtoForwardCompatIT`（未知字段容忍）、`RollingRestartIT`（滚动重启）
- proto 组：只读校验数据面五组（common/registry/replication/projection/discovery），不改契约
- 既有设施复用：`SdkFailoverIT`/`GracefulShutdownIT` 的 3 节点起停与断言 helper 模式、`TestGrpcPorts`/`TestAwait`

## 验收方式

对应测试文档：[phase-5-rolling-upgrade-test.md](phase-5-rolling-upgrade-test.md)

IT：混合版本节点互操作（复制/bootstrap）+ 滚动重启期间 SDK 注册心跳不断流不换数据。

## 产品设计（③ 补）

### 验证口径总述

「集群滚动升级无感」（roadmap v1.0 验收要点）拆为三个可自动验证的正交前提：

| 层 | 前提 | 验证物 |
|---|---|---|
| 契约 | 升级前后节点互通的 wire 格式兼容（field number 只增不改、删除 reserved）——[proto 契约规范](../../../dev/proto-contract.md) §2 纪律 | `proto-compat-check.sh`（CI 门槛） |
| 行为 | 新旧消息互读：未知字段被忽略（新→旧方向）、新字段缺省语义安全（旧→新方向） | `ProtoForwardCompatIT` + 既有 IT 天然覆盖论证（见下） |
| 语义 | 节点逐个重启时复制/bootstrap/客户端容灾链路维持数据连续 | `RollingRestartIT` |

### 决策 1：proto 兼容检查器（契约层）

- **锚点**：各版本收尾 commit（v0.1 `53aada8`、v0.2 `e310b86`、v0.3 `0c12330`、v0.4 `1550374`、v0.5 `43fca55`）。仓库当前**无 git tag**（契约版本跟 tag 走的机制未启用），版本收尾 commit 是 tag 的等价锚点；将来打 tag 后检查器切 tag 列表（脚本内锚点表集中维护，v1.0 收尾后补本版锚点）。
- **范围**：数据面五组（common/registry/replication/projection/discovery/v1），与 proto-contract §2 冻结范围一致；management.v1 不在冻结列，不检查。
- **断言**（逐锚点 × 逐文件 × 逐 message）：
  1. 锚点已有的 `(message, field number)` 在当前版本**类型不变**（含 repeated/map 修饰一致）；
  2. 字段**不删除**；确需删除时该 message 的 `reserved` 编号必须覆盖之；
  3. 字段编号不被新字段复用（1+2 的推论，独立报错更可定位）。
- **形态**：纯 bash 脚本（`git show <anchor>:<file>` 取历史版 + 正则提取 message/field 结构对比），零新增工具依赖（引入 buf 等兼容检查工具须先评审进技术选型组件总表，不为单一用途破例）；退出码非 0 = 失败，输出逐条违规明细。
- **接入**：`dev-test.sh all` 模式在 `mvn verify` 成功后执行（新形态「契约脚本断言」进入 all 模式组成，脚本模式说明同步）；CI 经既有 `dev-test.sh all` 调用自动获得门槛。unit/integration 模式不含（契约检查与测试代码编译无关）。
- **现状基线**：v0.1 收尾 → HEAD 的 proto diff 全部为字段新增（删除仅注释行），检查器落地即绿。

### 决策 2：前向兼容运行时验证（行为层）

- **新→旧方向**（旧节点/旧 SDK 收到含未来字段的消息）：`ProtoForwardCompatIT` 以 `UnknownFieldSet` 注入构造「未来版本」心跳上报与 Replicate 批消息发给服务端入口，断言：rpc 正常 ack、实例数据效果正确（upsert/续约/复制落地）、无反序列化失败。验证整条接入链路（gRPC 解码 → application mapper → domain）无破坏 protobuf 前向兼容的行为（如字段严格校验）。
- **旧→新方向**（新节点收到历史消息 = 新字段缺省）：消息类只有当前一个版本，**既有全部 IT 即是「缺省值消息」的持续回归**，不另立测试；纪律依据 proto-contract §2「不依赖缺省值 = 未设置做语义」，数据面已加字段的缺省语义（labels/clusters 空 = 不过滤等）在各自 phase IT 已断言。
- 两个数据面入口覆盖选择：心跳流 + Replicate（复制），Bootstrap 出口消息（服务端发给旧 peer 的方向）以 Replicate 同构消息类覆盖（`UnknownFieldSet` 注入入口侧即可验证解码链，bootstrap 出口由契约层保证不加破坏字段）。

### 决策 3：滚动重启 IT（语义层）

3 节点静态集群（复用 `SdkFailoverIT` 起法：外部 gRPC/HTTP + 内部 peer 三端口、同端口重启、快照路径独立）+ 真实 SDK（HTTP 前置寻址 node-0 + `/cluster/nodes` 发现全集群，心跳 500ms）：

- **主用例（优雅停机滚动）**：注册单 service 多实例（跨 service 完整性由复制扇出 per-service 独立语义与既有 ReplicationFanoutIT 承担，不在此重复）后，逐节点 `context.close()`（drainMs 压短）→ 同配置同端口重启 → 等 ready（bootstrap 从存活 peer 全量同步）→ 下一节点；滚动全程：
  - 存活节点注册表完整（探针轮询断言，非仅终态）；
  - 误摘探针：滚动期间对存活节点周期 `evictExpired()` 后实例数不减（续约链未断、租约由复制维持）；
  - 重启节点 ready 后数据恢复（bootstrap 生效）；
  - 终态三节点一致 + 清理探针跑满超 TTL 窗口（心跳断则 TTL 内暴露——「不断流」的持续续约证明）。
- **变体用例（无排水滚动）**：同流程 drainMs=0（滚动升级中 Pod 被强终止的最近似进程内模拟——close 仍走 DOWN 自报与终刷快照，非真进程崩溃；其增量在无排水窗口，无快照恢复路径由 SnapshotRestoreIT 覆盖），断言口径同上。
- 配置口径：`lease.ttlMs` 与心跳间隔留足换址余量（滚动期间无实例因切换间隙过 TTL）；drainMs 压短使滚动总时长可控。

## 实现结果（⑦ 补）

### 实现要点

- `java/scripts/proto-compat-check.sh`：正则级 proto 解析器（注释剥离、块深度跟踪 message 栈、reserved 编号区间/名字）+ 单 awk 对比器；锚点不可解析（浅克隆）显式红不静默跳过。
- `java/scripts/dev-test.sh`：all 模式在 `mvn verify` 后执行契约检查（usage 同步）。
- `.github/workflows/ci.yml`：checkout `fetch-depth: 0`（锚点可达性前提）。
- `ProtoForwardCompatIT`（2 用例）：`UnknownFieldSet` 注入心跳流/Replicate 消息，service 名隔离防 context 级注册表跨用例污染。
- `RollingRestartIT`（2 用例）：3 节点滚动（优雅/无排水）；bean 于 await 循环外缓存（Spring 单例锁阻塞风险，同 SdkFailoverIT caution）；终态「不断流」证明 = 清理探针跑满超 TTL（3s）窗口。

### code-review 修复记录（10 findings）

| # | finding | 处置 |
|---|---|---|
| 1 | CI 浅克隆下检查器静默 no-op | ci.yml `fetch-depth: 0` + 锚点不可解析计 FAIL（不静默跳过） |
| 2 | `reserved N to max` 物化枚举挂死 | RN 行改区间表示（上界 536870911），区间包含判断不物化；已验证 20s 内正常返回 |
| 3 | 字段选项 `[deprecated = true]` 致解析不可见（假 FAIL + 漏检） | field 正则行尾放开容忍选项；已验证 deprecated 行静默通过 |
| 4 | kill 变体未真 kill（close 走优雅序列） | 承认模拟边界：改名 `Undrained` + 注释/文档明确增量在无排水窗口，无快照恢复归 SnapshotRestoreIT |
| 5 | 删除字段未检查 reserved 名字（契约 §2 编号与名字都保留） | RM 行收集 + 删除合法性双条件（编号区间 + 名字）；已验证编号-only 报「名字未保留」 |
| 6 | 注释内花括号破坏 message 栈 | 解析前剥 `//` 与 `/* */`（含跨行状态）；已验证注释花括号静默 |
| 7 | 范围声称覆盖 Bootstrap 但无测试 | 范围措辞改为「心跳流、Replicate」（决策 2 narrowing 理由既有） |
| 8 | 3 节点 scaffolding 第 5 份复制（~70 行 × 5 IT） | **不修**：重构共享 TestCluster fixture 需动 4 个已绿的既有 IT，超出本 phase scope；留待后续独立清理 |
| 9 | 文档「多 service」与实现（单 service 多实例）不符 | 文档改准确（跨 service 完整性由 per-service 复制语义 + ReplicationFanoutIT 承担） |
| 10 | await 循环内 getBean 单例锁阻塞风险 | RegistryService/ReadinessGate 于循环外缓存（cacheBeans） |
| — | 修复中负向自检另发现三处解析器 bug：① `NR==FNR` 对空 old 文件失效（v0.1 占位文件触发，改 FILENAME 判别）；② `$2 FS ++rn_n[$2]` 被 gawk 解析为算术加（一元加歧义，拆开自增）；③ 多词 type（repeated/map）行 name 提取错位（改按 type 定长截取）。三处均有注入复现与修复验证 |

## 文档同步（⑩ 填）

| 文档 | 同步内容 |
|---|---|
| `docs/product/artemis.md`（1.41） | 交付状态新增 v1.0 phase-5 节（验证口径/契约门槛/两 IT/工程形态）；「构建与测试」dev-test.sh 注释补 all 语义 |
| `docs/dev/proto-contract.md`（1.6） | §1 补契约版本锚点落地口径（git tag 启用前 = 版本收尾 commit，proto-compat-check.sh 强制）——③ 定案的原始文档同步落点 |
| `CLAUDE.md` | 构建与测试节 dev-test.sh 注释同步（all = verify + proto 兼容检查） |
| `.github/workflows/ci.yml` | checkout `fetch-depth: 0`（锚点可达前提，随实现落地） |
| `docs/milestones/v1.0/phases/README.md` | phase-5 两文档登记（① 补漏） |
| 架构文档 | 无变更（无新机制/决策：验证复用既有优雅停机/bootstrap/容灾设施，roadmap 承诺由 plan 验收对照承接） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-28 | 实现结果与 code-review 修复记录（脚本 5 项硬伤 + CI 浅克隆 + IT bean 缓存 + 文档措辞）；范围/决策 3 措辞对齐实现 |
| 1.0 | 2026-10-28 | 开工骨架 + 设计定稿（三层验证口径：契约断言脚本 + 前向兼容 IT + 滚动重启 IT；锚点 = 版本收尾 commit） |
