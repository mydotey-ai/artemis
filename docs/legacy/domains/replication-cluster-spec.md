# 复制与集群一致性 · 功能规格

版本: 1.0    更新时间: 2026-10-08

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：以需求语言规格化原产品「对等复制、集群成员、节点状态与就绪、冷启动、管理面同步」行为，作为新产品需求设计输入。租约与剔除语义见 [registry-lease-spec.md](registry-lease-spec.md)；客户端侧寻址见 [client-sdk-spec.md](client-sdk-spec.md)。
> 证据引用约定同 [registry-lease-spec.md](registry-lease-spec.md)；标 **⚠ legacy** 为原产品特有行为或包袱；缺陷见同域 logic.md §7。

## 1. 域定位

region 内全部对等节点各自持有全量注册表并最终一致。本域维护：写操作的全对全异步复制、集群成员与节点状态、节点就绪门控（readiness）、冷启动重建、管理面元数据的全网生效。一致性模型：**AP 最终一致**——无 quorum、无 leader、无确认闭环；收敛依赖「客户端心跳全量对账」这一上游事实（每实例每 5s 重述状态），复制丢失可被天然补齐。

角色：**集群运维**（成员配置、force 开关）；复制为节点间内部行为，对外不可见。

## 2. 概念与术语

| 概念 | 定义 |
|---|---|
| 复制任务（task） | Register / Unregister / Heartbeat 三种消息，payload = Instance |
| 批量 / 单条通道 | 双 TaskDispatcher：心跳默认批量（凑批发送）、注册 / 注销默认单条；batching-enabled 可热切换 |
| taskId | 任务类型 + InstanceKey + 目标 peer——去重合并的键 |
| 任务 TTL | 任务有效期（默认 5s，自首次提交起算），过期出队即丢 |
| 广播 / 定向 | serviceUrl 为空 = 广播全部可服务 peer；携带具体 serviceUrl = 定向（重试路径） |
| readiness 门控 | 节点未完成初始同步前拒绝对外服务（canServiceRegistry / canServiceDiscovery） |
| force 开关 | 人工强制节点 / 平面上下线的 6 个配置键 |

## 3. 功能需求

### FR-RC-01 复制协议总则

**陈述**：本节点每次成功的客户端写操作应异步复制到全部可服务 peer；复制入队即返回，客户端写延迟与 peer 状态解耦。

**规则**：仅 3 种消息（register / unregister / heartbeat）；region 内全对全复制（zone 不是数据局部性单位）；跨 region 零同步。

**证据**：原仓库 `artemis-service/.../registry/RegistryServiceImpl.java:45-97`、`registry/replication/RegistryReplicationTool.java:158-163`；基线 §2.7。

### FR-RC-02 通道与去重合并

**陈述**：复制任务应按（任务类型 × 实例 × 目标 peer）去重合并：同键新任务覆盖旧任务并继承原提交时间（不延长成批等待）；重试任务与在途新任务同键时新任务胜出（重试丢弃）。

**规则**：心跳默认批量通道（凑批 250 条 / 最大延迟 2s），注册 / 注销默认单条通道（可热切换）；两通道并发执行、无跨通道顺序保证——乱序容错依赖接收端注册幂等覆盖 + 新租约清理保护（registry-lease D7）。

**证据**：原仓库 `artemis-common/.../taskdispatcher/TaskAcceptor.java:211-244`、`registry/replication/{RegisterTask,UnregisterTask,HeartbeatTask}.java:14-15`。

### FR-RC-03 尽力送达语义

**陈述**：复制为 best-effort：可重试失败**无重试次数上限**，任务 TTL（默认 5s，自首次提交起算、跨重试不刷新）是唯一界限，过期出队即丢；丢弃的语义正确性由「客户端下一轮心跳全量重述」兜底。

**验收标准**：杀掉单 peer 数秒后恢复，其注册表与各节点一致（恢复期间丢失的复制由客户端心跳 + 接收端补注册收敛，无需人工干预）。

**证据**：原仓库 `taskdispatcher/TaskExecutor.java:110-116`、`TaskAcceptor.java:181-183`、`registry/replication/RegistryReplicationTool.java:112-115`。⚠ 实现存在批量重试截断缺陷（一批仅第一个可重试任务被重试），见 logic.md §7.1。

### FR-RC-04 失败分类

**陈述**：复制失败应按响应错误码分类：可重试（rate-limited / unknown / 网络异常 / data-not-found / partial_fail）进入重试；不可重试（bad-request / no-permission / internal-service-error / service-unavailable）直接丢弃并告警。

**规则**：广播任务失败时退化为**定向重试**——仅向失败 peer 生成携带其 serviceUrl 的重试任务，成功 peer 不重发。

**证据**：原仓库 `replication/ReplicationTool.java:16-30`、`RegistryReplicationTool.java:136-196`。

### FR-RC-05 背压与缓冲

**陈述**：复制子系统应有界：任务缓冲（默认 1 万）满时丢弃最老整批；TrafficShaper 支持按错误码配置发送退避（⚠ 默认无配置即无退避延迟——基线「默认 10ms」有误，见 logic.md §7.2）。

**证据**：原仓库 `taskdispatcher/TaskAcceptor.java`、`TrafficShaper.java:44-77,108-115`。

### FR-RC-06 复制接收端

**陈述**：peer 收到复制请求应：豁免 readiness 门控与 zone 校验（**region 强校验不豁免**，违反按不可重试丢弃）、统一限流（默认 1M QPS，按操作分桶，超限整体 rate-limited）、注册无条件覆盖入库、心跳遇缺失租约**同步就地补注册**。

**规则**：getServices（peer 全量拉取端点）同样无 readiness 门，socket timeout 2s；复制心跳 socket timeout 200ms 快速失败。

**证据**：原仓库 `registry/replication/RegistryReplicationServiceImpl.java:52-139`、`RegistryReplicationServiceClient.java:28-37`、`RegistryTool.java:125-143`。

### FR-RC-07 广播扇出与状态门控

**陈述**：广播复制应只发给「探测状态可服务注册」的 peer：按节点状态表逐个检查，不可服务（含不可达 = UNKNOWN）即跳过且**不生成失败任务**——被跳过 peer 的数据缺口靠该 peer 恢复后接收客户端心跳（复制心跳补注册）收敛。

**规则**：定向重试任务不查状态表，直接发送（移出集群的节点仍可能收到残留重试）。

**证据**：原仓库 `RegistryReplicationTool.java:158-163`、`cluster/ClusterManager.java:198-224`。

### FR-RC-08 集群成员管理

**陈述**：集群成员应来自静态配置（`cluster.nodes`，zoneId→urls）驱动，无自动成员发现。⚠ 发布形态下配置变更需重启（NFR-35 / [config-reference](config-reference.md) §0）。

**规则**：成员变更即时影响广播扇出（出批时读最新视图）；被移除节点在下一轮探测后从状态表消失（呈现 UNKNOWN）。
- ⚠ legacy：本机识别 = 「URL 包含本机 ip:port」子串匹配；成员视图在**新列表为空**时不替换（清空场景旧成员残留）。

**证据**：原仓库 `artemis-common/.../cluster/ServiceCluster.java:65-87`、`cluster/ClusterManager.java:148-192`；基线 §6.10。

### FR-RC-09 节点状态探测

**陈述**：节点应周期（默认 5s，fixed-delay）**串行**探测每个 peer 的自声明状态（`/api/status/node.json`，3 次重试、host 不可达立即中断），结果整体重建状态表。

**规则**：探测语义 = 拉取 peer **自声明状态**而非存活探测；不可达呈现为 UNKNOWN（非 DOWN）；状态表供扇出门控与 cluster.json / up-nodes 使用。
- ⚠ legacy：串行探测在节点多时 5s 周期可能跑不完；探测调用无显式超时。

**证据**：原仓库 `ClusterManager.java:198-241`、`StatusServiceClient.java:42-52`。

### FR-RC-10 节点就绪门控（readiness）

**陈述**：节点应在外满足两个初始同步目标后才对外服务：REGISTRY（从任一 UP peer 全量拉取注册表并重建租约）+ DISCOVERY（管理面缓存首轮刷新成功）；未就绪节点对客户端返回 service-unavailable（复制入口不受门控）。

**规则**：
- REGISTRY 成功标准 = 拉取到**非空**注册表（拒绝以空数据宣告就绪）；每 1s 重试直至成功。
- force-up 可越过门控直接就绪（⚠ 同时跳过全部初始同步，见 FR-RC-11 与 logic.md §7.4）。
- ⚠ legacy：空集群（全部节点无数据）无法自举到 UP，必须 force-up 引导（logic.md §7.3）。

**证据**：原仓库 `cluster/NodeManager.java:101-190`、`RegistryReplicationInitializer.java:92-112`；基线 §5.9。

### FR-RC-11 节点状态机与 force 开关

**陈述**：节点状态机应为 STARTING → UP（双目标达成）即终态；DOWN 仅由 force-down 声明。6 个 force 开关（整体 / registry / discovery × up / down）配置驱动。

**规则**：
- force 优先级（后判覆盖）：force-up → force-down；各平面 force-down → force-up。force-up 直接置 UP + 双平面可服务。
- canService 判定口径：UP 恒可服务、DOWN 恒不可、STARTING/UNKNOWN 看平面标志。
- ⚠ legacy：UP 后**无任何回退路径**（管理面 DB 故障不降级）；DOWN 粘性（撤销 force-down 不自动恢复，需 force-up 或重启）；UP 状态下平面级 force-down 被压制（无法单摘一个平面）。

**证据**：原仓库 `NodeManager.java:101-190`、`util/ServiceNodeUtil.java:37-65`。

### FR-RC-12 冷启动全量重建

**陈述**：节点启动应优先从本 zone 其他节点、失败再其他 zone 节点，拉取全量注册表逐服务按复制语义重建租约（不再扇出）。

**规则**：拉取端点不做 zone 过滤（zone 参数不生效），返回全量；重建的每个注册会发 NEW 事件（⚠ 事件缓冲按实例折叠 + 容量上限，大规模重建的事件流被截断，订阅方靠客户端兜底收敛）。

**证据**：原仓库 `RegistryReplicationInitializer.java:72-112`、`RegistryReplicationServiceImpl.java:138-139`。

### FR-RC-13 管理面数据全网生效

**陈述**：管理面元数据应以共享 DB 为事实源、各节点独立定时全量拉取（Management 仓库默认 1s、Group/Zone 5s）；写操作后仅做固定等待（默认 sleep 2s）即返回——**写成功 ≠ 全网生效**，无跨节点确认机制。

**证据**：原仓库 `artemis-management/.../ManagementRepository.java:90-91,121-131,321-323`、`GroupRepository.java:83-84,110-124,344-346`；基线 §6.8。

### FR-RC-14 up-nodes 端点

**陈述**：集群应向客户端提供可用节点列表端点（up-registry-nodes / up-discovery-nodes）：按「可服务 + zone 匹配（或目标节点放开）」**过滤**（无排序），空列表返回 data-not-found；独立限流（默认 10k / 10s 窗口）。

**证据**：原仓库 `cluster/ClusterServiceImpl.java:38-109`。

## 4. 与其他域的接口

| 域 | 关系 |
|---|---|
| registry-lease | 写成功触发复制；复制心跳补注册是对账闭环的一路；冷启动重建租约 |
| operations-audit | force 开关的操作语义、状态端点内容；NodeManager 状态机共用 |
| client-sdk | up-nodes 是地址候选来源；探测端点被 5s 轮询使用 |
| traffic-governance | 管理面元数据生效走 FR-RC-13 管线 |

## 5. 配置项总表（本域）

| 配置键 | 默认 | 关联 FR |
|---|---|---|
| `{register,unregister,heartbeat}.replication.task-ttl` | 5000ms（2s–30s） | FR-RC-03 |
| `*.batching-enabled`（三任务独立） | heartbeat true / 其余 false | FR-RC-02 |
| `task-acceptor.max-buffer-size / write-complete-wait` | 10000 / 5ms | FR-RC-05 |
| `batching.task-acceptor.max-batching-size / max-batching-delay` | 250 / 2000ms | FR-RC-02 |
| `task-executor.thread-count`（每通道） | 20 | — |
| `traffic-shaper.fail-delay`（错误码→ms map） | 空（无退避） | FR-RC-05 |
| 复制接收限流 `artemis.service.registry.replication` | 1000000（1k–10M） | FR-RC-06 |
| 复制 HTTP `heartbeat.socket-timeout / get-services.socket-timeout` | 200ms / 2000ms | FR-RC-06 |
| `cluster.nodes`（multimap）/ `status-update.interval / fail-retry-times` | — / 5000ms / 3 | FR-RC-08/09 |
| `cluster.node.init.sync-interval` | 1000ms | FR-RC-10 |
| 6 个 force 键 + 2 个 allow-from-other-zone | false | FR-RC-11、registry-lease FR-RL-07 |
| `cluster` 限流（up-nodes） | 10000（100–100k） | FR-RC-14 |
| `management.db-sync.wait-time` | 2000ms（0–60s） | FR-RC-13 |
| 管理面缓存刷新 run-interval | Management 1s / Group、Zone 5s | FR-RC-13 |

## 6. 完整性对照

- 端点：`/api/replication/registry/{register,heartbeat,unregister,services}.json` + `/api/cluster/up-{registry,discovery}-nodes.json` 全覆盖 ✓
- 错误码：rate-limited（限流）/ no-permission（region 不符，不可重试）/ data-not-found（up-nodes 空）✓
- 组件：taskdispatcher 全家（TaskAcceptor / Batching / SingleItem / TaskExecutor / TrafficShaper / TaskErrorCode）✓
- 未入本域：LeaseManager 清理保护（registry-lease L7）、NodeManager 状态端点内容（operations-audit）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
