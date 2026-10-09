# 运维管控与审计 · 功能规格

版本: 1.1    更新时间: 2026-10-09

> 调研对象：原仓库 `~/Projects/mydotey/artemis`（version 2.0.2，git HEAD 9727bb5）。
> 定位：以需求语言规格化原产品「实例 / 服务器 / zone / 组四级摘除、审计、状态 API、节点运维开关」行为，作为新产品需求设计输入。NodeManager 状态机与 force 开关的机制细节见 [replication-cluster-spec.md](replication-cluster-spec.md) FR-RC-11；本域只管其**运维操作语义**。
> 证据引用约定同 [registry-lease-spec.md](registry-lease-spec.md)；标 **⚠ legacy** 为原产品特有行为或包袱；缺陷见同域 logic.md §7。

## 1. 域定位

为运维提供**不删数据的服务上下线控制**：摘除 = 写一条可叠加、带操作者上下文的「下线操作记录」，恢复 = 删记录（**操作记录即状态**）；判定 = 四级级联 OR。摘除只影响发现投影（过滤 + 合成事件），**不触碰注册表租约**——被摘实例的心跳照常续约。另提供全量审计与运行时状态查询面。

角色：**运维**（摘除 / 恢复 / 审计查询）；**平台 SRE**（force 开关、状态 API 排障）。

## 2. 概念与术语

| 概念 | 定义 |
|---|---|
| 下线操作记录 | DB 行（业务键 + operation + operatorId / token / 时间戳），存在即摘除 |
| 四级级联 | instance → server → zone → group 的 OR 判定，任一命中即 down |
| ServerKey | regionId + 物理机 IP（serverId = IP） |
| ZoneKey | regionId + serviceId + zoneId（**服务粒度**的 zone 摘除） |
| 合成事件 | 管理面据操作记录变化向发现订阅者伪造的 DELETE / NEW / RELOAD 事件 |
| force 开关 | 本机 IP 粒度的 6 个节点强制上 / 下线配置键 |

## 3. 功能需求

### FR-OA-01 摘除模型（操作记录即状态）

**陈述**：摘除应表现为写「下线操作记录」（可叠加多条、各带 operation 标签与操作者上下文），恢复 = 删除记录；记录存在即摘除，**operation 字符串不参与判定**（纯审计标签，任意非空值均视为摘除）。

**规则**：
- 同业务键同 operation 重复提交为 upsert（覆盖 operator / token）；不同 operation 可叠加多条——**全部删除后才恢复**。
- 写前要求本节点 status = UP（checkCurrentNode）；写后固定等待对端刷新（sleep 2s）再返回。
- 变更双写审计 log 表（complete=false 记摘除、true 记恢复）。

**⚠ legacy**：恢复按（业务键 + operation）删**单条**——叠加多条时须逐条按原 operation 字符串恢复，传错既删不掉也不报错。

**证据**：原仓库 `artemis-management/.../ManagementServiceImpl.java:108-150`、`ManagementRepository.java:183-209`、`dao/InstanceDao.java`（delete SQL 按 operation 精确匹配）。

### FR-OA-02 四级级联判定

**陈述**：实例是否摘除应由四级短路 OR 判定，任一级命中即 down，无优先级仲裁、无相互抵消（恢复低层级不解除高层级）。

| 级 | 键 | 匹配 | 影响范围 |
|---|---|---|---|
| 1 instance | InstanceKey（regionId.serviceId.instanceId，小写） | 精确 | 单实例 |
| 2 server | ServerKey（regionId + IP） | 实例 ip 精确匹配 | **同 region 该物理机上全部服务的全部实例**（跨 serviceId） |
| 3 zone | ZoneKey（regionId + serviceId + zoneId） | 精确 | 该服务在该 zone 的实例（**按服务摘 zone**，无全 zone 形态） |
| 4 group | 五级 groupKey 前缀（SearchTree 逐级精确下钻，无通配） | 组 key 是实例 key 的精确前缀 | 组内实例（实际即「组名匹配 instance.groupId」一种粒度） |

**证据**：原仓库 `ManagementRepository.java:155-171`、`zone/ZoneKey.java:28-33`、`GroupRepository.java:126-128,372-382`、`util/{SearchTree,ServiceGroupKeys}.java`。

### FR-OA-03 摘除生效链路

**陈述**：摘除应经两个机制生效：① 发现过滤——lookup / service / 全量缓存的 instances 与 logicInstances 中剔除 down 实例；② 合成事件——操作记录新增 → 对注册表现存实例合成 DELETE 推送订阅方；记录消失（恢复）→ 合成 NEW。

**规则**：
- 生效时序：各节点缓存刷新（instance/server 级默认 1s，group/zone 5s）内先换缓存引用（过滤即刻生效），后推合成事件；**过滤生效不依赖合成事件**。
- 该服务同时有逻辑实例变化时聚合为一条 RELOAD（跳过逐实例事件）；实例已不在注册表则不合成。
- 推送过滤：down 实例的 NEW 类推送被压掉（DELETE / RELOAD 恒放行）。
- 端到端：API 返回（含 sleep 2s）后约 1–3s 全网生效。

**⚠ legacy**：server 级合成事件只按 IP 匹配（不比 regionId）——跨 region IP 复用时会对未摘除实例推虚假 DELETE，且推送过滤器实时重查判定（region 不符 → 非 down）会放行该虚假事件。

**验收标准**：摘除单实例后 ≤5s，全部节点的发现结果与订阅方视图均不含该实例；实例租约不受影响（心跳照常）。

**证据**：原仓库 `ManagementRepository.java:325-374,436-509`、`ManagementDiscoveryFilter.java:41-46`、`ManagementNotificationFilter.java:9-16`。

### FR-OA-04 zone 摘除

**陈述**：zone 摘除应按（regionId, serviceId, zoneId）记录操作，语义同「记录即状态」。

**⚠ legacy**：
- **推送盲区**：变化检测只在 serviceId 集合对称差层面——同服务已有 zone 摘除记录时，再摘 / 恢复另一 zone **不触发任何推送**，仅靠缓存刷新后的发现过滤静默生效（与 instance / group 路径行为不一致）。
- zone 写路径**无 waitForPeerSync**（instance / server / group / canary 有）。

**证据**：原仓库 `ZoneRepository.java:101-151`、`ManagementZoneController.java:48-53`。

### FR-OA-05 管理视角查询

**陈述**：应提供管理视角的服务查询：全量服务 + 每实例 up/down 判定 + 租约时间注入（creationTime / renewalTime / ttl 进 metadata）+ 实例 status 按 down 判定**覆写**（down → down，否则 up；逻辑实例恒 up）；单服务查询另附分组信息。

**证据**：原仓库 `ManagementRepository.java:298-319`、`GroupRepository.java:540-567`（status 覆写写点）。

### FR-OA-06 审计日志

**陈述**：全部管理面变更应双写 log 表并可查询；group 系日志记录操作时实体快照 + 操作者四元组（operatorId / token / operation / reason）。

**⚠ legacy（实际能力与宣称差距）**：
- 快照为**单快照**（delete 记删前值、insert / update 记写后值），非 before/after 双值；instance / server 操作日志**无实体快照、无 reason**（表结构无此列）；zone 日志 reason 模型有但 insert SQL 无该列（不落库）。
- 查询过滤字段 = 业务键 + operation + operatorId + complete（group-logs 另有 name / appId）——**token / reason / 时间段均不可作过滤条件**。
- ⚠ 查询无分页无模糊搜索（10 万级不可用）。

**证据**：原仓库 `ManagementLogServiceImpl.java`、`group/dao/*LogDao.java`、`dao/InstanceLogDao.java:40-60`；基线 §2.6 原表述待勘误。

### FR-OA-07 状态 API

**陈述**：应提供 7 个运行时状态端点（排障面，不登机器）：

| 端点 | 内容 |
|---|---|
| node.json | 本节点自声明状态（status + 双平面可服务位 + 双 allow 位） |
| cluster.json | 全节点状态视图（探测缓存，不可达 = unknown） |
| leases / legacy-leases.json | 租约明细 + 自我保护统计（maxCount / 窗口计数 / isSafe / 开关 / leaseCount / 逐实例时间三元组），支持 serviceIds 过滤 |
| config.json | 全部配置项 + 来源（排障利器） |
| deployment.json | 部署身份快照 |
| websocket/connection.json | 双通道活跃 WS 连接数 |

**规则**：除 node.json（peer 探测用，不限流）外共用限流（默认 30 / 10s 窗口）。

**证据**：原仓库 `artemis-service/.../status/StatusServiceImpl.java:62-237`、`websocket/WsStatusController.java:27-31`；基线 §5.12。

### FR-OA-08 节点运维开关（force）

**陈述**：应提供本机 IP 粒度 6 个配置开关（整体 / registry / discovery × up / down）人工干预节点服务状态，语义与优先级见 FR-RC-11。

**⚠ legacy**：force-up 会**跳过全部初始同步**直接对外服务（危险开关，无护栏）；UP 后平面级 force-down 被压制、DOWN 粘性（见 replication-cluster logic §7）。

**证据**：原仓库 `cluster/NodeManager.java:29-50,129-163`。

### FR-OA-09 废弃数据清理

**陈述**：⚠ legacy `destroyServers`（按 ServerKey 物理删除 server / instance 操作记录）为**死代码**：无 REST 端点、无调用方；且 instance 侧删除条件为 `instance_id = serverId`（与实例键实际形态不符，即使调用也清不到正常记录）；不写审计。新产品不应将其视为既有能力。

**证据**：原仓库 `ManagementRepository.java:211-214`、`dao/ServerDao.java`（grep 全仓库无调用方）。

### FR-OA-10 访问控制现状

**陈述**：⚠ legacy 开源形态下全部管理 API **无认证、无鉴权**（摘实例、改路由权重均裸奔）；唯一屏障为 WS 握手 IP 黑名单（默认启用、名单默认空）与 zone / region 软隔离；OperationContext.token 只存不验（instance / server 路径连非空都不要求）。新产品必须重立安全边界。

**证据**：原仓库全仓库无 Security / 拦截器配置；`WsIPBlackList.java:24-49`；基线 §3.6、§6.19。

## 4. 与其他域的接口

| 域 | 关系 |
|---|---|
| discovery | 摘除经发现过滤 + 推送过滤 + 合成事件三路作用于发现视图 |
| registry-lease | 摘除不触碰租约（心跳照常；实例真实下线后由租约到期天然 DELETE 幂等叠加） |
| traffic-governance | 组级摘除共用五级 groupKey / SearchTree；过滤器顺序 Group → Management |
| replication-cluster | NodeManager 状态机、状态端点数据源、checkCurrentNode 语义 |

## 5. 配置项总表（本域）

| 配置键 | 默认 | 关联 FR |
|---|---|---|
| 管理面缓存刷新（instance/server 级） | 1000ms（200ms–60s） | FR-OA-03 |
| 管理面缓存刷新（group/zone 级） | 5000ms（10ms–60s） | FR-OA-02/04 |
| `artemis.management.db-sync.wait-time` | 2000ms | FR-OA-01 |
| `artemis.service.status`（限流） | 30 / 10s 窗口 | FR-OA-07 |
| 6 个 force 键 | false | FR-OA-08 |
| `artemis.service.{id}.ws-ip.black-list[.enabled]` | 空名单 / true | FR-OA-10 |

## 6. 完整性对照

- 端点：`/api/management/` 10 个 + `/api/management/log/` 9 个 + `/api/management/zone/` 5 个 + `/api/status/` 7 个全覆盖 ✓（group 32 个属 traffic-governance，canary 1 个属 traffic-governance）
- 判定链：四级级联各级键 / 匹配 / 影响范围全覆盖（FR-OA-02）✓
- 历史类遗留已结项（2026-10-09，作者确认生产实绩，[product-overview](../product-overview.md) §7.C）：`destroyServers` 的历史调用方不再追溯；10 万实例下管理查询可用性为生产实际运行事实（无分页为既定代码事实）
- 未入本域：group 族 CRUD（traffic-governance）、NodeManager 机制（replication-cluster）

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-09 | §6 历史类遗留结项（destroyServers 考古不再追溯、管理查询可用性为生产事实，product-overview §7.C） |
| 1.0 | 2026-10-08 | 初版 |
