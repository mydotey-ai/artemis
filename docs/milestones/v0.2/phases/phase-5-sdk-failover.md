# Phase 5：SDK 三级地址容灾与连接 TTL 轮换

版本: 1.8    更新时间: 2026-10-23

## 目标

补全 SDK 三级地址容灾（配置引导地址 → 服务端节点列表 → 随机选址 + 熔断换址），并引入连接 TTL 轮换（默认 10min ± 抖动），以「客户端轮换即分布式对账」修复丢失事件在单节点上的残留缺口（[ADR-006](../../../decisions/adr-006-no-peer-reconciliation.md)）。

## 范围

- **做**：三级地址容灾管线（HTTP 前置寻址发现节点 → 随机选 UP 节点 → 不可用熔断换址）；服务端外部 HTTP 辅助端口新增 `GET /cluster/nodes`（节点列表发现端点，proto bytes 响应）；连接 TTL 轮换（默认 10min ± 20% 抖动，承担 ADR-006 分布式对账职责）；client 生成矩阵扩 `replication.v1`（消费 `GetNodeStatusResponse` 消息类）；SDK 指标（熔断/列表刷新/轮换）。
- **不做**：discovery 侧容灾（v0.3——无服务端存活列表，走配置地址 + 历史成功连接缓存 + 熔断）；v0.1 服务端兼容（见兼容口径）；服务端优雅停机与 DOWN 标记（phase-6，本 phase 只消费 `NodeState` 优先级）；熔断半开主动探测（全熔断时随机回退即天然探测，不建状态机）；连接迁移（架构定案不做，客户端凭容灾换址）；配置 url 背后的 DNS 多 A 记录轮询（域名容灾归 DNS/LB，SDK 依次试配置 url 即可）。

## 涉及模块

- `java/client/artemis-client`：`internal/`（`ServerAddressProvider` 扩展、新增 `FailoverAddressProvider`/`HttpNodeListFetcher`、`GrpcRegistryClient`/`HeartbeatChannel` 扩展、`ConfigAddressProvider` 值语义改 http url）+ pom proto includes 扩组。
- `java/server/artemis-registry-server`：外部 HTTP 辅助端口 `ClusterNodesController`（`/cluster/nodes`，委派 core 应用层）+ 测试（`SdkFailoverIT`，进程内 3 节点集群 + 真实 SDK）。
- `java/server/artemis-registry-core`：无（复用 phase-3 `ReplicationService.nodeStatus()/clusterNodes()` 与 proto 映射）。
- `proto/`：契约零变更（`replication.v1` phase-3 已定稿，SDK 开始生成消费）。

## 验收方式

对应测试文档：[phase-5-sdk-failover-test.md](phase-5-sdk-failover-test.md)

SDK 单测：熔断阈值/换址/恢复、TTL 轮换抖动；3 节点 IT：杀节点客户端无感换址并续约，TTL 轮换修复新节点数据。

## 产品设计（③ 补）

### 三级地址容灾管线（资产 #2 继承，形态对齐原产品）

发现通道与数据通道分离：**HTTP 前置寻址**发现实际可用节点与端口，**gRPC 直连**节点承载数据面。

1. **前置寻址（HTTP 发现）**：配置 `artemis.client.registry.addresses` = http url 列表（`http(s)://host[:port]`，逗号分隔；生产实际配一个**域名地址**——域名/LB/DNS 天然多活，SDK 不感知其背后拓扑）。SDK 调 `GET <url>/cluster/nodes`（外部 HTTP 辅助端口，Basic 认证复用 gRPC 凭证，响应体 = `replication.v1.GetNodeStatusResponse` protobuf bytes）取得节点列表（`members[].external_host:port` + `state`）。冷启动与周期刷新（默认 5min）同一路径，多个配置 url 依次尝试；**拉取失败保留旧列表**（不清空、不回退——勘误 §8-5 修正）；列表不可得时持续重试寻址（下一周期/下一 url），不构造「配置地址直连 gRPC」的回退——配置是 http 入口，gRPC 端点只从节点列表来。
2. **随机选 UP 节点**：选址池 = 节点列表。`state` 优先级 `UP` > `UNKNOWN`/`STARTING` > `DOWN`（组内随机、高优非空不落低优；探测有周期窗口，架构 §2.2——`state` 只作优先级不作排除依据，正确性由失败上报 + 熔断兜底）。单节点部署 = 1 成员集群（members 配一条），寻址/选址同一管线，无特殊分支。
3. **不可用更换其它节点**：连接失败上报熔断——同一节点**连续失败达阈值（可配置，默认 3）**→ 熔断该地址（select 跳过）且**立即重新前置寻址刷新节点列表**（连续失败常意味着列表过期：节点已挂或拓扑变更，换址前先取最新列表收敛更快）；换址连接其它节点；**全部熔断 → 全量随机**（熔断是降权不是清零，可用性优先）；成功上报清零恢复。

**寻址重试退避**：立即重寻址若失败，持续重试按**指数退避 + 抖动**（默认 base 2s → max 60s，×2 增长），寻址成功后退避计数归零、回到正常周期刷新节奏（默认 5min）——避免前置寻址高频空转；退避期间新的熔断事件仍可触发一次立即寻址（打断退避、不重置计数）。节点连接一直失败但寻址可达时，由心跳重连自身的指数退避（`reconnect.*`，1s→30s）+ 全熔断随机兜底承接，不重复建设。

**兼容口径**：v0.2 SDK 要求 v0.2 服务端（`/cluster/nodes` 端点 + members external 端点）；v0.1 服务端不在支持范围（v0.x 内部演进，数据面 proto 契约冻结前不承诺跨版本兼容）。gRPC `NodeStatusService.GetNodeStatus`（phase-3 交付）保留为观测/管理面，SDK 发现统一走 HTTP。

### 熔断与恢复

- 失败上报入口：心跳断流（onError / ack 看门狗 / 开流失败）与 unary 瞬态失败（`UNAVAILABLE`/`DEADLINE_EXCEEDED`）→ `markFailed(address)`——同一地址**连续**失败 ≥ 阈值（默认 3）→ 熔断至 `now + circuitOpenMs`（默认 30s），select 跳过；窗口到期惰性恢复（select 时判定，非定时任务）。
- 成功上报入口：心跳 ack（服务端确认可达）与 unary 成功 → `markSucceeded(address)` 清零计数并解除熔断。
- 换址排除：重连与 TTL 轮换选址时排除当前地址（`select(exclude)`）；exclude 为唯一候选时仍返回之——同址重建 + 开流全量心跳上报仍完成对当前节点的对账（upsert 幂等），不破坏语义。

### 连接 TTL 轮换（ADR-006：客户端轮换即分布式对账）

- 每次心跳流建立时生成轮换 deadline：`now + ttlMs × [0.8, 1.2)`（默认 `ttlMs` 10min，抖动 ±20% 固定不配置化，防集群同步轮换雪崩）；`ttlMs = 0` 禁用。
- 心跳周期 tick 检查 deadline：到期 → 主动废弃当前流与 channel（作废旧代数）→ 立即重连（**无退避、不计失败、不进熔断**——健康换址）→ 开流即全量上报，修复当前节点的丢失事件残留。
- 与断流重连共用重建路径（`resetChannel` + `select(排除当前)`），调度单线程串行无竞态。

### 接口签名（internal，实现细节 ④ 定）

```java
interface ServerAddressProvider {
    /** 选址：exclude 非空且有其他候选时排除之（重连换址/TTL 轮换传当前地址；null = 冷启动）。 */
    String select(String exclude);
    /** 传输失败上报（连续计数达阈值熔断）；成功上报（清零 + 解除熔断）。 */
    void markFailed(String address);
    void markSucceeded(String address);
}

// NodeListFetcher：前置寻址传输端口——fetch() 依次试配置 url 调 GET /cluster/nodes，
// 返回 (gRPC endpoint, state) 列表；生产实现 HttpNodeListFetcher（HttpURLConnection + Basic auth
// + GetNodeStatusResponse.parseFrom，Java 8 零新增依赖），周期刷新由 DefaultArtemisClient 调度。
```

### 服务端发现端点（新增）

外部 HTTP 辅助端口 `GET /cluster/nodes`：Basic 认证生效（不在白名单），响应体 = `GetNodeStatusResponse` protobuf bytes（`Content-Type: application/x-protobuf`），复用 phase-3 应用层 `nodeStatus()/clusterNodes()` 组装——gRPC 观测 API 与 HTTP 发现端点同源同构。**readiness 门控（⑨ 验证定案）**：未就绪返回 503——未就绪节点不作寻址应答者，SDK 端零改动（`fetch` 已具备「多 url 依次重试、单 url 整体退避重试原址」语义）；gRPC 观测 API 的 readiness 豁免属观测面（供 peer 探测/观察 STARTING），寻址数据面不豁免。架构 §2.2/§4.1 已同步。

### 配置键（`serverAddresses` 改名归组 + 新增 4 个动态键）

SDK 兼具注册（registry 集群）与发现（discovery 集群，v0.3 交付）两个功能面，两集群独立部署、地址不同——配置键按面分组：本 phase 全部归 `artemis.client.registry.*`，v0.3 发现侧平行 `artemis.client.discovery.*`（discovery 无服务端存活列表，容灾参数集不同：配置地址 + 历史成功连接缓存 + 熔断）。

| 键 | 默认 | 值域 | 语义 |
|---|---|---|---|
| `artemis.client.registry.addresses`（改名自 `serverAddresses`） | 必配 | http(s) url，逗号分隔 | registry 集群 HTTP 前置寻址地址（生产通常一个域名）；原 host:port gRPC 直连语义废弃——SDK 未发布无兼容负担 |
| `artemis.client.registry.address.refreshIntervalMs` | 300000 | [1000, 3600000] | 节点列表拉取周期（成功后的稳态节奏） |
| `artemis.client.registry.address.retryBaseMs` | 2000 | [100, 60000] | 寻址失败重试退避基准（×2 指数 + 抖动） |
| `artemis.client.registry.address.retryMaxMs` | 60000 | [1000, 600000] | 寻址失败重试退避上限 |
| `artemis.client.registry.address.circuitThreshold` | 3 | [1, 100] | 同一节点连续连接失败熔断阈值（触发熔断 + 立即重寻址） |
| `artemis.client.registry.address.circuitOpenMs` | 30000 | [1000, 600000] | 熔断窗口 |
| `artemis.client.registry.connection.ttlMs` | 600000 | [0, 86400000] | 连接轮换 TTL 基准（0 = 禁用） |

现有共享键（`artemis.client.auth.*`、`heartbeat.*`、`reconnect.*`、`operation.*`）不随本次归组迁移：auth 为双面共享凭证（统一账户 v0.4 接管后再评估拆分），heartbeat/reconnect/operation 本就是注册侧专属但语义自明，避免无谓 churn。

### 指标（Micrometer，tag `client` 沿用现有惯例）

| 指标 | 类型 | tag | 语义 |
|---|---|---|---|
| `artemis.client.address.circuit.opened` | Counter | address | 熔断触发 |
| `artemis.client.nodelist.refresh` | Counter | outcome=success\|empty\|failure | 列表拉取三态 |
| `artemis.client.rotation` | Counter | — | TTL 轮换执行 |

### proto 生成矩阵变更

`artemis-client` includes 增加 `replication/v1/*.proto`（protoc 3.25.x 同现有矩阵；只消费 `GetNodeStatusResponse` 消息类——HTTP 端点响应解析，不消费 gRPC stub）。契约文件零变更，矩阵同步落点：`docs/dev/tech-stack-java.md` §2.2 表 client 行与 §4.2 说明。

## 实现结果（⑦ 补）

实现要点：

- client internal 新增 `FailoverAddressProvider`（选址池/优先级/熔断/exclude）、`HttpNodeListFetcher`（HttpURLConnection + Basic auth + proto bytes 解析，Java 8 零新增依赖）、`NodeListRefresher`（周期/立即/退避三态调度，独立 lazy 单线程）、`NodeEndpoint`/`NoAddressException`；`GrpcRegistryClient`（provider 注入、地址追踪、`channel(forcedExclude)` 强制换址——不复用活 channel、被替换 channel 关闭）、`HeartbeatChannel`（TTL deadline [0.8,1.2)×ttl、到期主动换址——先作废旧代数再优雅半关旧流）、`DefaultArtemisClient` 装配（熔断→重寻址闭环经 `setCircuitListener` 后注入）。
- 服务端新增 `ClusterNodesController`（外部 HTTP `GET /cluster/nodes`，application/x-protobuf，Basic 认证生效）。
- starter/测试迁移：`serverAddresses` → `registry.addresses` 全引用面更新；ClientSdkIT 改 1 成员集群 + DEFINED_PORT；TestAwait 补超时重载（服务端侧）。

code-review 修复（8 findings 全处理）：

1. rotate 泄漏被替换 channel 与旧流（release-blocker）→ `channel(forcedExclude)` 锁外 shutdown 被替换 channel；rotate 先 `generation++` 再 `onCompleted()` 优雅半关旧流（关闭回调不误触发 markBroken）。
2. url 校验 trim 与 fetch 不一致 → `fetchFrom(url.trim())` 对齐。
3. unary 熔断归因竞态（ops 线程与轮换并发错归节点）→ **熔断计数仅由心跳引擎上报**（单线程归因无竞态），设计文档 §熔断同步（v1.5）。
4. 指标偏离设计表 → `circuit.opened` 补 `address` tag；`nodelist.refresh` 补 `outcome=empty`（空 members 与拉取失败区分观测）。
5. `isNonBlank` 双副本 → 集中 `ClientProperties.isNonBlank`（认证头语义单一实现）。
6. 熔断表随拓扑变更无界增长 → `updateNodes` 时 `retainAll` 清理离开列表地址。
7. url 带 query/fragment 时路径拼接变形 → `validateAddress` 拒绝。
8. SdkFailoverIT 前置寻址 setProperty 移出节点循环（纯位置修正）。

⑨ 验证回退增量（用户裁决）：

- `/cluster/nodes` 补 readiness 门控（未就绪 503），`ClusterNodesController` 注入 `ReadinessGate`；SDK 端零改动（多 url 重试其它地址、单 url 退避重试原址既有语义）。
- 补「节点活但开流 UNAVAILABLE 拒绝 → SDK 熔断换址收敛到就绪节点」用例（`FakeRegistryServer` 增 `rejectHeartbeatOpen` 开流拒绝模式）。

回退重入 code-review 修复（9 findings 全处理）：

1. `NodeListRefresher.refreshNow` 在 refresh 执行中破坏单链（周期链永久翻倍）→ 锁内互斥 + `immediateRefreshRequested` 让位标记：运行中 refresh 尾部不再自续，补单链保持用例。
2. `markBroken` 可被 watchdog 与迟到 onError 双调（单故障重复计数、熔断提前）→ `markBroken` 内 `generation++` 作废在途回调。
3. `markFailed` 阈值动态变更时 opened 无指标/不触发重寻址 → 单次读阈值 + 「未开→开」事件判定（`wasCircuited`），窗口续期不重复触发。
4. `transportOutcome` javadoc 与「熔断仅由心跳上报」设计矛盾 → 注释修正。
5. TTL 轮换双跳（`openStreamAndSend` 尾部 send + tick 再 send）→ `maybeRotate` 返回已轮换，tick 跳过本跳。
6. 非法 url 报「must be set」误导（SCF filter 丢值后与缺失同形）→ 错误消息覆盖 missing/invalid 并指向 WARN。
7. `NamedOpsThreadFactory` 与共享 `NamedDaemonThreadFactory` 双副本 → 替换删除。
8. `RotationStressTest` 泄漏 `GrpcRegistryClient`（transport 线程）→ 字段化 + tearDown close。
9. `SdkFailoverIT` 客户端 setProperty 在节点循环内 → 移出循环（客户端属性一次设置）。

## 文档同步（⑩ 填）

- `docs/arch/artemis-next-architecture.md`（v1.31，④ 期同步）：§2.2 v0.2 端口落点补 `GET /cluster/nodes`（readiness 门控 503，与观测面豁免对照）；§4.1 not ready 清单补寻址端点 503。
- `docs/api/sdk-java.md`（v1.2）：地址键 `registry.addresses`（http url 前置寻址）；新增「地址与容灾」节（三级管线 + TTL 轮换）；配置表补 `registry.*` 六键；指标补 rotation/circuit/nodelist。
- `docs/api/node-status-api.md`（v1.1）：新增 §1.2 HTTP `GET /cluster/nodes`（protobuf bytes、Basic 认证、readiness 不豁免 503）。
- `docs/product/artemis.md`（v1.13）：v0.2 phase-5 交付节（三级容灾/TTL 轮换/配置与指标/工程形态）；phase-7 容灾骨架描述更新。
- CLAUDE.md：无需更新（构建命令与模块结构无变化）。
- 本 phase 无新 ADR（TTL 轮换承接 ADR-006；寻址门控为 §2.2/§4.1 行为口径补充，非新决策面）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.8 | 2026-10-23 | ⑩ 文档同步清单填记（架构 v1.31/sdk-java v1.2/node-status-api v1.1/产品 v1.13） |
| 1.7 | 2026-10-23 | 回退重入 code-review 9 findings 全修复（单链保持/markBroken 幂等/熔断 opened 事件语义/轮换双跳等） |
| 1.6 | 2026-10-23 | ⑨ 验证回退：`/cluster/nodes` readiness 门控（未就绪 503，SDK 重试其它/原址）；补 UNAVAILABLE 拒绝 → 熔断换址收敛用例 |
| 1.5 | 2026-10-22 | code-review 修复同步：熔断计数仅由心跳引擎上报（unary 归因竞态）；rotate 优雅半关旧流 + 被替换 channel 关闭 |
| 1.4 | 2026-10-22 | review 修订：熔断触发（连续失败达阈值）立即重新前置寻址刷新列表；寻址持续失败按指数退避 + 抖动（2s→60s，成功归零），新增 `address.retryBaseMs/retryMaxMs` 键 |
| 1.3 | 2026-10-22 | review 修订：配置键按 SDK 功能面分组——`serverAddresses` 改名 `artemis.client.registry.addresses`，本 phase 新增键归 `registry.*` 组，v0.3 发现侧平行 `discovery.*` |
| 1.2 | 2026-10-22 | review 修订（对齐原产品形态）：配置地址 = http url 前置寻址（生产配域名），服务端外部 HTTP 端口新增 `GET /cluster/nodes`（proto bytes）发现节点与端口；删 v0.1 服务端兼容与「配置地址即选址池」回退；单节点 = 1 成员集群 |
| 1.1 | 2026-10-22 | review 修订：三级语义重述为管线（配置地址 = 前置寻址入口取节点列表，非降级模式；列表为选址池，空/UNIMPLEMENTED 时配置地址即选址池） |
| 1.0 | 2026-10-22 | 设计定稿：三级降级语义（勘误 §8-5 修正）、熔断降权不清零、TTL 轮换无退避、配置键/指标、proto 矩阵扩组 |
| 0.1 | 2026-10-22 | 开工骨架 |
