# Phase 5 测试：SDK 三级地址容灾与连接 TTL 轮换

版本: 2.4    更新时间: 2026-10-23

## 测试方案

执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见工作流 ⑤ 节），本 phase 无新模式扩展：

- **单测**（`artemis-client`，surefire，JDK 8 toolchain）：地址供给纯逻辑（选址池/熔断/排除/优先级）直测；TTL 抖动边界直测；`HttpNodeListFetcher` 用 JDK 内置 `HttpServer` 起本地端点验证 proto bytes 解析/Basic 头/失败语义；SDK 换址/轮换/经寻址发现节点经 `NodeListFetcher` fake + `FakeRegistryServer` 多实例驱动。
- **集群语义 IT**（`artemis-registry-server`，failsafe，`SdkFailoverIT` + `ClusterNodesHttpIT`）：进程内 3 节点真实集群（`SpringApplicationBuilder` 随机端口组——external gRPC + peer + 外部 HTTP 三组端口，同 `NodeStatusApiIT` 设施扩展）+ 真实 SDK（`ClientSdkIT` 模式：JDK 25 JVM 加载 release=8 SDK；心跳间隔/TTL 经系统属性压短，服务端 TTL 测试装配压短），SDK 前置寻址配真实 `http://127.0.0.1:<http-port>` url 驱动端到端容灾与对账；HTTP 发现端点单独验证认证与响应可解析。

覆盖面对应验收方式：熔断阈值/换址/恢复、TTL 轮换抖动（单测）；杀节点无感换址并续约、TTL 轮换修复节点数据、HTTP 前置寻址发现（IT）。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_selectFromNodeList_when_fetched | 前置寻址取到节点列表后选址池 = 列表 | 单测（FailoverAddressProviderTest） | pass |
| should_retryBootstrap_when_nodeListEmpty | 列表不可得时寻址退避重试、无选址；就绪后自动恢复（冷启动自愈） | 单测（DefaultArtemisClientTest.should_becomeAvailable_when_bootstrapPending） | pass |
| should_keepStaleNodeList_when_refreshFails | 列表拉取失败不清空已生效列表（勘误 §8-5） | 单测（NodeListRefresherTest） | pass |
| should_preferUpState_when_nodeListMixed | UP/UNKNOWN/DOWN 混合时 UP 组优先、DOWN 最后、无 UP 落次优组 | 单测（FailoverAddressProviderTest） | pass |
| should_openCircuit_when_consecutiveFailuresReachThreshold | 同址连续失败达阈值（默认 3）熔断并触发重寻址回调 | 单测（FailoverAddressProviderTest） | pass |
| should_refreshNodeListImmediately_when_circuitOpened | 熔断触发立即重寻址（打断稳态周期） | 单测（NodeListRefresherTest） | pass |
| should_backoffRetries_when_bootstrapKeepsFailing | 寻址持续失败按指数退避重试，成功后退避归零回到稳态周期 | 单测（NodeListRefresherTest） | pass |
| should_keepSingleChain_when_refreshNowDuringSlowRefresh | 慢寻址执行中触发 refreshNow（熔断风暴）：运行中 refresh 尾部让位，不产生第二条周期链 | 单测（NodeListRefresherTest） | pass |
| should_skipCircuitedAddress_inSelect | 熔断中地址被 select 跳过，窗口到期后惰性恢复可选 | 单测（FailoverAddressProviderTest） | pass |
| should_selectFromAll_when_allCircuited | 全部候选熔断时仍全量随机（降权不清零） | 单测（FailoverAddressProviderTest） | pass |
| should_recoverCircuit_when_succeeded | markSucceeded 清零计数并解除熔断 | 单测（FailoverAddressProviderTest） | pass |
| should_excludeCurrentAddress_when_alternativesExist | select(exclude) 排除当前地址；唯一候选时返回同址（对账语义保留） | 单测（FailoverAddressProviderTest） | pass |
| should_rotateConnection_when_ttlReached | TTL 到期主动换址重建（无退避），心跳流在节点间轮转 | 单测（HeartbeatChannelTest；抖动边界由轮转节奏间接覆盖） | pass |
| should_keepHeartbeatsFlowing_when_rotatingRepeatedly | 3 fake 节点 12s 高频轮换（~30 次）：全部节点被覆盖且心跳持续增长（无累积性死链） | 单测（RotationStressTest） | pass |
| should_parseNodeListAndSendAuth_when_httpBootstrapServed | JDK HttpServer 返回 proto bytes → 解析端点与优先级、Basic 头、非法 external 成员跳过 | 单测（HttpNodeListFetcherTest） | pass |
| should_tryNextUrl_when_firstFails | 前置寻址 url 依次重试（第一个失败用第二个） | 单测（HttpNodeListFetcherTest） | pass |
| should_failFast_when_addressesMissingOrInvalid | 寻址 url 必配缺失/非法格式构造期 fail-fast；路径拼接保留 url path 前缀 | 单测（HttpNodeListFetcherTest） | pass |
| should_failoverToOtherNode_when_currentNodeDies | 双 fake 节点：当前连接节点停止 → 换到列表中另一节点继续上报 | 单测（DefaultArtemisClientTest） | pass |
| should_failoverToReadyNode_when_currentNodeRefuses | 节点活但开流即拒（UNAVAILABLE，未就绪语义）：熔断 + 重寻址后换址收敛到就绪节点 | 单测（DefaultArtemisClientTest） | pass |
| should_tryNextUrl_when_serverNotReady | 寻址 url 返回 503（未就绪拒绝）→ 依次重试下一 url 成功 | 单测（HttpNodeListFetcherTest） | pass |
| should_refuseWith503_when_notReady | `/cluster/nodes` 未就绪返回 503（不作寻址应答者） | 单测（ClusterNodesControllerTest） | pass |
| should_serveNodeListViaHttp_when_clusterNodesRequested | `GET /cluster/nodes`：无认证 401；Basic 认证返回可解析 proto bytes（self + 3 成员端点/观测态） | 集群语义 IT（ClusterNodesHttpIT） | pass |
| should_failoverAndKeepLeaseAlive_when_connectedNodeKilled | 3 节点 + SDK 经 HTTP 前置寻址注册可见 → 关闭当前连接节点 → 换址续约，存活节点实例不过期 | 集群语义 IT（SdkFailoverIT） | pass |
| should_reconcileNodeData_when_ttlRotation | TTL 轮换换址有效（心跳到达 ≥2 节点）且各节点注册表一致（轮换即对账正确性；全节点覆盖由 RotationStressTest 低负载承担） | 集群语义 IT（SdkFailoverIT） | pass |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑 | all | 全 reactor | 失败→修复 4 轮：IT 服务装配（web-application-type/DEFINED_PORT/@Primary TestProperties members）、NodeListRefresher 测试参数、SDK select 竞态（rotate 强制换址：`channel(forcedExclude)` 不复用活 channel）、starter relaxed 变体用例修正；末轮 BUILD SUCCESS |
| ⑧ 回归 | all | 全 reactor（code-review 8 修复后） | BUILD SUCCESS |
| ⑤ 回退重入 | all | 全 reactor（⑨ 裁决增量：/cluster/nodes 503 门控 + 3 新用例） | BUILD SUCCESS（12 模块，DefaultArtemisClientTest 13 / HttpNodeListFetcherTest 5 / ClusterNodesControllerTest 1 全过） |
| ⑧ 回归（回退重入） | all | 全 reactor（code-review 9 修复 + 单链用例后） | 失败→修复 2 项：starter 断言对齐新错误消息（F6）；ReplicationFanoutIT 副本可见性超时（单跑 6/6 过 + 末轮 all 过，判 flaky 非增量引入）；末轮 BUILD SUCCESS |

## 验证记录（⑨ 填）

自动化验证（工作流执行，回退重入后 2026-10-23 重验）：

| # | 命令 | 结果 |
|---|---|---|
| 1 | `bash java/scripts/dev-test.sh all`（⑧ 末轮） | BUILD SUCCESS（12 模块全绿，03:54；SdkFailoverIT 2 / ClusterNodesHttpIT 1 / ReplicationFanoutIT 6，Failures/Errors 全 0） |
| 2 | `grep "connection ttl reached, rotating"` 于 SdkFailoverIT failsafe 报告 | 命中 1 次：TTL 轮换在 3 节点集群真实发生 |
| 3 | `git grep serverAddresses -- java/` | 无匹配（无生产配置键残留） |

人工验证（用户执行）：待用户对制品 / 产品功能自行验证并记录结论（首验已裁决两条变更：寻址端点 readiness 门控、UNAVAILABLE 拒绝收敛用例——均已实现）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 2.4 | 2026-10-23 | 回退重入 ⑧ 回归全绿（9 修复 + 单链用例）；⑨ 自动化验证重验；ReplicationFanoutIT flaky 记录 |
| 2.3 | 2026-10-23 | ⑨ 回退增量用例：UNAVAILABLE 拒绝 → 熔断换址收敛、寻址 503 换 url、/cluster/nodes 未就绪 503 |
| 2.2 | 2026-10-23 | ⑨ 自动化验证由工作流执行并记录（all 全绿 + TTL 轮换取证）；人工验证改为用户自行决定，不设清单 |
| 2.1 | 2026-10-22 | ⑧ 回归全绿 + ⑨ 验证清单就绪 |
| 2.0 | 2026-10-22 | ⑤ 全绿：用例结果与形态落点对齐最终实现（含 RotationStressTest 补充），回归记录首跑多轮修复 |
| 1.3 | 2026-10-22 | review 修订：增「熔断触发立即重寻址」「寻址持续失败指数退避」两用例 |
| 1.2 | 2026-10-22 | review 修订（http 前置寻址）：删「配置地址即选址池」用例、增寻址重试与 HttpNodeListFetcher（JDK HttpServer）用例、IT 增 HTTP 发现端点用例（3 节点端口组扩外部 HTTP） |
| 1.1 | 2026-10-22 | review 修订：用例名对齐三级管线语义（配置地址 = 前置寻址，列表 = 选址池） |
| 1.0 | 2026-10-22 | 测试设计定稿：10 单测 + 3 SDK 单测 + 2 集群语义 IT |
| 0.1 | 2026-10-22 | 开工骨架 |
