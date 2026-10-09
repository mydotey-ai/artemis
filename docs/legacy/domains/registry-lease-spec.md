# 实例注册与租约生命周期 · 功能规格

版本: 1.1    更新时间: 2026-10-09

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：以需求语言规格化原产品在「实例注册与租约生命周期」域的行为，作为新产品的需求设计输入。规格忠实于原产品实际行为；标 **⚠ legacy** 的条目为原产品特有行为或兼容包袱，新产品须显式决策继承或替换；缺陷性行为不进规格，见同域 logic.md §7。
> 证据引用：前缀「原仓库」= 相对原仓库根路径；路径中 `...` = `src/main/java/org/mydotey/artemis` 下的包路径缩写；`基线 §x` = [legacy-product-analysis.md](../legacy-product-analysis.md)，`features §x` = [features.md](../features.md)。行号为 2.0.2 HEAD 快照。

## 1. 域定位

本域维护「服务实例在线状态」这一核心事实：实例注册、持续续约、失联剔除、显式注销，并向发现域输出实例生命周期变更事件（NEW / DELETE）。本域是注册中心数据面的写入源头，正确性目标为：**实例真实在线 ⇒ 租约存在且可被发现；实例真实离线 ⇒ 在有界时间内从注册表消失**。

角色：

- **服务提供方**（SDK 嵌入业务进程）：注册 / 注销自己的实例，维持心跳。
- **注册中心服务端**（对等集群）：接收注册 / 心跳 / 注销，维护租约，执行剔除与自我保护。

## 2. 概念与术语

| 概念 | 定义 |
|---|---|
| 本地实例集 | 客户端进程内持有的已注册实例全量集合，是注册状态的**唯一事实源** |
| 心跳 | 客户端周期性将本地实例集**全量**上报服务端的动作，兼有注册与续约双重语义（「心跳即注册」） |
| 实例标识（InstanceKey） | `regionId.serviceId.instanceId` 三元组，**大小写不敏感**比较（原仓库 `artemis-common/.../InstanceKey.java:72-94`） |
| 租约（Lease） | 服务端为每实例维护的在线凭证：creationTime / renewalTime / evictionTime / ttl |
| 续约（renew） | 心跳到达时刷新租约 renewalTime |
| 过期剔除 | 租约超 TTL 未续约，由服务端清理线程移除实例 |
| 注销（unregister） | 客户端显式声明实例下线，立即生效语义（绕过自我保护） |
| 自我保护 | 服务端检测续约量骤降时暂停过期剔除的降级模式 |
| legacy 实例 | `metadata.java_registry` 非空的老代客户端实例（⚠ 兼容概念，见 FR-RL-12） |

Instance 其余字段（ip/port/protocol/url/healthCheckUrl/metadata 等 13 字段）为透传数据，见 features §1.1；`status` 字段语义见 FR-RL-14。

## 3. 功能需求

### FR-RL-01 注册事实源与注册动作

**陈述**：客户端应以本地实例集为注册状态的唯一事实源；注册 / 注销动作只修改本地实例集，注册动作本身**不直接发送注册请求**。

**规则**：
- `register(instances)` 应：先向服务端发送批量注销（清除旧租约，语义见 FR-RL-08），再将实例并入本地实例集；**注销失败（网络异常或失败响应）不得阻塞或回滚**本地并入——本地事实源优先，远端状态由对账机制收敛（FR-RL-04）。
- `unregister(instances)` 应：发送批量注销并将实例从本地实例集移除，失败处理同上。
- 前置注销的作用（可证实部分）：显式 evict 旧租约使其**绕过自我保护**被立即清理，且注销会复制到全集群（新租约 creationTime 全新，避免旧租约的清理保护误判，见 logic.md D2/D7）。
- 实例注册前应经客户端本地预检：serviceId / instanceId / url 非空白。

**验收标准**：
1. 调用 register 后无任何 HTTP 注册请求发出，实例在下一次心跳 + 对账后可被发现（首次注册延迟 ≈ 1 个心跳间隔）。
2. register 时服务端完全不可达，调用正常返回且实例保留在本地实例集；服务端恢复后实例自动恢复注册，无人工干预。

**证据**：原仓库 `artemis-client/.../registry/InstanceRepository.java:111-126`、`ArtemisRegistryHttpClient.java:50-68`（异常全吞仅 log）、`common/Conditions.java:14-19`、`RegistryClientImpl.java:32-35`。

**⚠ legacy**：重复注册同标识实例时，本地集合合并不替换旧数据（Set 语义），心跳载荷仍为首次注册的数据——见 logic.md §7.3。

### FR-RL-02 心跳通道与全量上报

**陈述**：客户端应经 WebSocket 心跳通道以固定间隔（默认 5s）上报本地实例集**全量**；一条心跳消息即完整注册状态快照。

**规则**：
- 心跳检查线程（默认 1s 周期）应按双阈值调度：距上次心跳 ≥ heartbeat-interval（默认 5s）→ 发送；≥ instance-ttl（默认 20s）→ 判连接不可用，强制重建连接（可能换节点）。
- 心跳消息载荷 = 经注册过滤器链（RegistryFilter SPI）过滤后的全量实例。
- **本地实例集为空时应不发送心跳**（且不触发连接重建）：空集语义 = 客户端无在线实例，全部实例由服务端按 FR-RL-09 过期剔除收敛。
- 心跳通道应有会话防漂移机制：服务端强制关闭超过会话 TTL（默认 6min）的连接，客户端重建后续传心跳，自动完成重注册。

**验收标准**：
1. 单实例进程的注册稳态流量 = 每 5s 一条 WS 消息（N 实例摊薄为 1 条消息）。
2. 本地实例全部 unregister 后不再有心跳消息，服务端在 TTL + 清理周期内移除全部实例并发出 DELETE 事件。

**证据**：原仓库 `artemis-client/.../registry/InstanceRepository.java:83-95`（空集 return null）、`InstanceRegistry.java:140-168`；服务端会话治理 `artemis-server/.../websocket/ArtemisWsHandler.java:38-43,114-135`。

### FR-RL-03 续约与心跳响应处理

**陈述**：服务端应为心跳中的每个实例续约租约；客户端应根据心跳响应驱动容错决策。

**规则**：
- 服务端对心跳逐实例处理：租约存在且续约成功 → 正常；租约缺失或续约失败 → 该实例计入响应 `failedInstances`（errorCode = data-not-found）。
- 客户端收到心跳响应后：
  - 响应状态为 serviceDown（internal-service-error / service-unavailable）→ 熔断当前节点并重建连接；
  - `failedInstances` 中 errorCode ∈ {data-not-found, unknown} 的实例 → 触发 HTTP 补注册（FR-RL-04）；其余错误码不触发。

**⚠ legacy**：心跳消息解析异常时，服务端应答固定 success 消息（不含 failedInstances）——错误被静默吞掉，客户端无从感知（原仓库 `artemis-server/.../websocket/HeartbeatWsHandler.java:32-53`）。

**证据**：原仓库 `artemis-service/.../registry/RegistryServiceImpl.java:70-75`、`artemis-client/.../registry/InstanceRegistry.java:176-183`、`ErrorCodes.java:12-20`。

### FR-RL-04 注册对账（自动补注册）

**陈述**：系统应提供幂等对账机制：任何原因（断网、重启、服务端数据丢失、池迁移）导致的「客户端认为已注册、服务端无租约」状态，都应在无需人工干预的情况下自动收敛。

**规则**：
- 客户端面：心跳返回 data-not-found → 对失配实例发 HTTP 批量注册（`/api/registry/register.json`），补注册同样经过滤器链。
- 复制面：节点收到 peer 复制的心跳而本地无该租约时，应**同步就地补注册**（与客户端面的异步补不同，见 FR-RL-10）。
- 对账不依赖显式重注册状态机：心跳全量上报天然携带完整期望状态，失配即修复。

**验收标准**：
1. 断网 T > TTL 后恢复：实例在一个心跳间隔内自动恢复注册。
2. 服务端节点重启（注册表清空）：恢复心跳后实例自动重注册，其中经复制通道触达该节点的实例无需等客户端心跳。
3. 对账操作幂等：重复补注册不产生错误，注册表状态不变（覆盖语义，FR-RL-13）。

**证据**：原仓库 `artemis-client/.../registry/InstanceRegistry.java:170-187`、`InstanceRepository.java:97-109`、`artemis-service/.../registry/replication/RegistryReplicationServiceImpl.java:89-93`；基线 §2.2。

### FR-RL-05 批量写 API 契约

**陈述**：注册 / 心跳 / 注销应提供批量 HTTP API，逐实例独立执行、部分失败聚合返回，无跨实例事务。

**规则**：
- 端点：`/api/registry/register.json`、`/api/registry/heartbeat.json`、`/api/registry/unregister.json`，入参 `instances[]`。
- 响应统一携带 `responseStatus{status, errorCode, message}` + `failedInstances[]{instance, errorCode, errorMessage}`；全部成功 = success，存在失败 = partial_fail。
- 服务端处理管线对 WS 心跳与 HTTP 心跳一致（同一服务入口，仅传输层不同）。

**⚠ legacy**：HTTP 心跳端点在原产品客户端内**无调用方**（客户端心跳只走 WS）；历史用途已结项不再追溯（[product-overview](../product-overview.md) §7.C；原仓库内 grep 仅死代码 `RegistryServiceClient.java:55-67` 引用）。

**证据**：原仓库 `artemis-server/.../rest/controller/RegistryController.java:35-40`、`artemis-service/.../registry/RegistryServiceImpl.java:45-97`；features §4.1。

### FR-RL-06 实例校验

**陈述**：写请求应对实例做最小必填校验，超集校验（格式 / 范围 / 取值）不在本层。

**规则**：
- 请求级：request 与 instances 非空，否则 bad-request（整批拒绝）。
- 实例级（register / heartbeat / unregister 均执行）：instance 非空，且 serviceId / instanceId / url 非空白。**无** ip 格式、port 范围、protocol / metadata / status 取值校验（port 为裸 int）。

**证据**：原仓库 `artemis-service/.../registry/RegistryTool.java:115-123`、`artemis-common/.../util/InstanceChecker.java:15-20`。

### FR-RL-07 准入控制

**陈述**：写请求应按有序门槛准入：限流 → 节点就绪 → region 一致 → zone 一致；每个门槛对应确定性错误码。

**规则**（按判定顺序，先命中先返回）：

| 顺序 | 门槛 | 失败错误码 | 豁免 |
|---|---|---|---|
| 1 | 限流（registry 通道，默认 100k QPS） | rate-limited（整批） | — |
| 2 | 请求 / 实例校验（FR-RL-06） | bad-request（整批） | — |
| 3 | 节点就绪（canServiceRegistry） | service-unavailable（整批） | 复制请求豁免 |
| 4 | region 与节点一致（大小写不敏感；空视为不匹配） | no-permission（逐实例） | **复制请求也不豁免** |
| 5 | zone 与节点一致，或节点放开 allow-from-other-zone | no-permission（逐实例） | 复制请求豁免 |
| 6 | 业务执行异常 | internal-service-error（逐实例） | — |
| 7 | 心跳遇租约缺失 / 续约失败 | data-not-found（逐实例） | — |

**⚠ legacy**：`allow-from-other-zone` 代码默认 false，但发布配置设为 true（原仓库 `artemis.properties:33`）——默认部署形态实际放开了跨 zone 注册。

**证据**：原仓库 `artemis-service/.../registry/RegistryTool.java:78-101,115-154`、`util/SameRegionChecker.java:29-34`、`util/SameZoneChecker.java:29-34`、`cluster/NodeManager.java:56-60`。

### FR-RL-08 显式注销

**陈述**：注销应具有立即生效语义：标记租约 evict 后由清理线程（默认 1s 周期）摘除，**不受自我保护约束**，并向全集群复制。

**规则**：
- evict 标记幂等（首写胜出）；标记后租约不可再续约。
- 注销复制到全部 peer，各节点独立摘除并各自向自己的发现订阅者发 DELETE 事件。
- 客户端注销失败不回滚本地移除（FR-RL-01），远端由过期剔除兜底收敛。

**验收标准**：调用注销后 ≤ TTL 内（实际 ≈ 清理周期 1s + 推送）实例从发现结果消失，即使此刻集群处于自我保护态。

**证据**：原仓库 `artemis-common/.../lease/Lease.java:81-86`、`LeaseManager.java:133`（evicted 跳过保护检查）、`artemis-service/.../registry/RegistryServiceImpl.java:87-94`。

### FR-RL-09 租约 TTL 与过期剔除

**陈述**：服务端应为每个实例维护租约，租约超 TTL 未续约即过期；过期租约由清理线程剔除并发出 DELETE 变更事件。

**规则**：
- 过期判定：`now > renewalTime + ttl`（或已 evict）。默认 TTL 20s；过期状态一经判定即粘滞（此后拒绝续约）。
- 清理线程（默认 2 线程 × 1s 周期）全表扫描逐租约处理，剔除决策见 logic.md D2。
- 实例全部剔除后，服务条目应一并从注册表移除（不留空 Service 壳）。
- 剔除受自我保护约束（FR-RL-11）；保护期内过期租约**保留不摘**，等待保护解除。

**验收标准**：客户端停止心跳后，实例在 ≤ TTL + 清理周期 + 推送延迟 内从发现结果消失（默认参数 ≈ 21s 量级；自我保护激活时例外）。

**证据**：原仓库 `artemis-common/.../lease/Lease.java:53-59`、`LeaseManager.java:98-100,126-164`、`artemis-service/.../registry/RegistryRepository.java:277-306`。

### FR-RL-10 剔除的集群语义：本地化收敛

**陈述**：过期剔除应是**纯本地**行为，不产生跨节点消息；集群一致性由「心跳续约复制到每个节点」+「各节点独立过期」共同达成。

**规则**：
- 客户端心跳只送达一个节点；该节点成功续约后复制心跳任务到全部 peer（peer 各自续约）。复制心跳遇本地租约缺失 → 同步补注册（FR-RL-04 复制面）。
- 客户端停止心跳 ⇒ 所有节点同步失去续约来源 ⇒ 各自独立过期、各自向自己的订阅者发 DELETE——无需也不存在「剔除复制」。

**证据**：原仓库 `artemis-service/.../registry/RegistryRepository.java`（全文件无 Replication 引用）、`RegistryServiceImpl.java:70-75`、`replication/RegistryReplicationServiceImpl.java:89-93`；logic.md §5.3。

### FR-RL-11 自我保护

**陈述**：服务端应检测续约量骤降（网络故障特征），进入保护态暂停全部「仅过期」剔除，防止大面积误摘；显式注销不受影响。

**规则**：
- 每个租约池独立统计：滑动窗口（默认 10s）内成功续约次数，每秒评估。
- 判定（logic.md D4）：历史峰值 maxCount ≥ 50（启用门槛）且 当前窗口计数 < maxCount × 85% → 保护态。
- maxCount 超过 10min 未刷新则回落为当前窗口计数（防止历史高峰永久抬高阈值）。
- 保护态暴露于状态 API（leases.json 的 isSafe / maxCount / 窗口计数），供运维观测。

**验收标准**：模拟 50% 以上客户端心跳同时中断（网络分区），实例不被批量摘除；心跳恢复后保护态自动解除，无残留影响。

**证据**：原仓库 `artemis-common/.../lease/LeaseUpdateSafeChecker.java:90-112,147-185`、`LeaseManager.java:133-135`；基线 §2.3、§5.10。

### FR-RL-12 ⚠ legacy 双租约池

**陈述**：系统应为老代客户端（心跳周期长）保留独立租约池：`metadata.java_registry` 非空白的实例使用 legacy 池（发布配置 TTL 90s），其余使用普通池（TTL 20s）。

**规则**：
- 池归属按**当次请求**的 metadata 判定，非注册时固化；两池独立统计自我保护、独立清理，但共享同一注册表视图。
- 实例 metadata 中途变化导致池迁移时：新池心跳缺失 → data-not-found → 客户端补注册到新池；旧池租约过期清理时被「更新租约保护」拦截（logic.md D7），静默消失、不发 DELETE。

**证据**：原仓库 `artemis-service/.../registry/RegistryRepository.java:66-68,318-323`、`artemis.properties:16,22`；基线 §2.2。

### FR-RL-13 实例数据覆盖语义

**陈述**：对已存在实例的注册应无条件覆盖：生成全新租约（creationTime = 当前时间）替换旧租约，不报错、不比较数据差异。

**规则**：
- 重复注册对发现订阅者表现为又一次 NEW 变更事件，并向 peer 复制注册任务。
- 实例身份按 InstanceKey（大小写不敏感）判定，同标识即覆盖。

**⚠ legacy**：覆盖仅作用于租约层；注册表二级索引以**原始大小写**字符串为键，混大小写重复注册会在发现视图产生残留条目——已知缺陷，见 logic.md §7.1。

**证据**：原仓库 `artemis-service/.../registry/RegistryRepository.java:103-119`、`artemis-common/.../lease/LeaseManager.java:69-75`、`Lease.java:24-31`。

### FR-RL-14 ⚠ legacy 实例 status 字段

**陈述**：`Instance.status`（starting / up / down / unhealthy / unknown 取值）在注册与租约生命周期中应为**纯透传字段**：客户端可自由设置，服务端注册链路不读、不写、不校验。

**规则**：
- 注册 / 心跳 / 剔除 / 推送过滤均不依据 status 行为。
- 发现响应中的 status 由管理面在构建响应时按「四级摘除判定」**重新推导覆写**（摘除 → down，否则 up；逻辑实例恒 up）——该行为属于运维管控域（operations-audit），非本域。
- 摘除实例从发现结果的移除是**整只剔除**（按操作记录判定），非按 status 过滤。

**证据**：原仓库 `artemis-management/.../ManagementRepository.java:304-315`、`GroupRepository.java:562`、`ManagementDiscoveryFilter.java:48-62`；补证调查 2026-10-08（status 全仓库 main 代码零读点）。

### FR-RL-15 生命周期变更事件

**陈述**：实例生命周期变化应产生变更事件供发现域消费：注册 → NEW，实际移除 → DELETE。

**规则**：
- 事件仅在实例真正进入 / 离开注册表时产生；「更新租约保护」确保覆盖注册与并发清理不误发 DELETE（logic.md D7）。
- 管理面摘除可合成 DELETE / NEW 事件（operations-audit 域），复用同一事件通道。
- 事件缓冲有界（默认 1 万条，超限丢最老）：极端情况下通知不完整，由发现域全量兜底轮询收敛（discovery 域）。

**证据**：原仓库 `artemis-service/.../registry/RegistryRepository.java:58-60,74-75,263-275`。

### FR-RL-16 断线与故障恢复（端到端自愈）

**陈述**：心跳通道任何层级的故障（连接断、会话被服务端关闭、节点宕机、节点重启）都应自动恢复并重新达成注册一致，全程无人工干预。

**规则**：
- 连接健康检查（默认 1s）：ping/pong 超时（默认 1s）或会话超客户端 TTL（默认 5min）→ 重建；重建受重连限流（默认 5 次 / 20s）。
- 重建后心跳照常发送；服务端租约已过期则触发 FR-RL-04 对账。
- 客户端重连 / 换节点的寻址与熔断机制属于 client-sdk 域。

**验收标准**：kill -9 服务端节点后，已注册实例在心跳通道恢复 + 1 个心跳间隔内重新达成注册，期间无注册数据丢失。

**证据**：原仓库 `artemis-client/.../websocket/WebSocketSessionContext.java:94-161,219-235`、`InstanceRegistry.java:158-187`。

## 4. 与其他域的接口

| 域 | 关系 |
|---|---|
| discovery | 消费本域 NEW / DELETE 事件驱动推送；lookup / 兜底轮询直读注册表 |
| replication-cluster | 本域成功写后产生复制任务（Register / Heartbeat / UnregisterTask）；冷启动全量重建租约；复制心跳补注册是本域 FR-RL-04 的一部分 |
| operations-audit | 管理摘除经操作记录 → 合成 DELETE / NEW 事件 + 发现过滤介入；不触碰本域租约 |
| client-sdk | 心跳 / 注册的传输设施（地址容灾、HTTP 执行器、WS 生命周期） |

## 5. 配置项总表（本域）

| 配置键 | 默认 | 范围 | 关联 FR |
|---|---|---|---|
| 服务端 `artemis.service.registry`（限流） | 100000 QPS | 1k–1M | FR-RL-07 |
| `artemis.service.registry.allow-from-other-zone` | false（发布配置 true） | — | FR-RL-07 |
| `artemis.service.registry.data.init-capacity` | 10000 | 1k–100k | — |
| `artemis.service.registry.data.instance-change.max-buffer-size` | 10000 | 1k–1M | FR-RL-15 |
| `artemis.service.registry.data.instance-change.poll-wait` | 20ms | 1–30000ms | — |
| 每池 `{instance,legacy-instance}.lease-manager.lease.ttl` | 20000ms（legacy 发布配置 90000） | 10s–7d | FR-RL-09/12 |
| 每池 `.lease-manager.clean-task.thread-count / run-interval` | 2 / 1000ms | 1–10 / 100–5000ms | FR-RL-09 |
| 每池 `.lease-manager.lease-update-safe-checker.*`（enabled / time-window / percentage-threshold / max-count-threshold / max-count-reset-interval） | true / 10s / 85 / 50 / 10min | — | FR-RL-11 |
| `artemis.service.websocket.session.ttl`（心跳 WS 会话） | 6min | 5min–5h | FR-RL-02 |
| 客户端 `.instance-registry.heartbeat-interval` | 5000ms | 500ms–5min | FR-RL-02 |
| 客户端 `.instance-registry.instance-ttl` | 20000ms | 5s–24h | FR-RL-02 |
| 客户端 `.instance-registry.heartbeat-checker` | 1000ms | 500ms–90s | FR-RL-02 |

## 6. 完整性对照

- 端点：`/api/registry/{register,heartbeat,unregister}.json` + `/websocket/registry/heartbeat`（本域全部端点，FR-RL-02/05）✓
- 错误码：success / partial_fail / bad-request / rate-limited / no-permission / data-not-found / internal-service-error / service-unavailable / unknown 在本域的语义全覆盖（FR-RL-03/05/07）✓
- 配置：features §1.3 双池 10 键、§2.1 registry data 3 键、§5.7 客户端心跳 3 键、WS 会话 TTL——均已入 §5 表 ✓
- 未入本域（归属他域）：leases.json 状态端点（operations-audit）、复制通道配置（replication-cluster）、HTTP 执行器 / 地址容灾配置（client-sdk）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-09 | FR-RL-05 legacy 注随 product-overview §7.C 结项同步 |
| 1.0 | 2026-10-08 | 初版 |
